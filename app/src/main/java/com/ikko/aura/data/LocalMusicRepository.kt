package com.ikko.aura.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.ikko.aura.catalog.Availability
import com.ikko.aura.catalog.CatalogDao
import com.ikko.aura.catalog.CatalogTrackEntity
import com.ikko.aura.catalog.LocalBinding
import com.ikko.aura.catalog.LocalReconciler
import com.ikko.aura.catalog.ScannedFile
import com.ikko.aura.catalog.TrackBindingEntity
import com.ikko.aura.source.SourceId
import com.ikko.aura.data.model.Album
import com.ikko.aura.data.model.Artist
import com.ikko.aura.data.model.AudioFormat
import com.ikko.aura.data.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

@Singleton
class LocalMusicRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val catalog: CatalogDao,
    private val ytmLibrary: com.ikko.aura.source.ytm.YtmLibrary,
) : MusicRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    /** Songs saved from streaming sources: in the library like any other title, but not part of any album or artist. */
    private val _saved = MutableStateFlow<List<Track>>(emptyList())

    /** Held back until the first scan has landed, so that "the library has loaded" still means the device's files. */
    private val _scanned = MutableStateFlow(false)

    override val tracks: StateFlow<List<Track>> = combine(_tracks, _saved, _scanned) { local, saved, scanned ->
        if (scanned) local + saved else emptyList()
    }.stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

    init {
        repositoryScope.launch {
            runCatching { ytmLibrary.prune() }
            ytmLibrary.observeKept().collect { _saved.value = it }
        }
    }

    override val albums: StateFlow<List<Album>> = _tracks
        .map(::groupAlbums)
        .stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

    override val artists: StateFlow<List<Artist>> = _tracks
        .map(::groupArtists)
        .stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var hasScanned = false

    /**
     * Tracks deleted on purpose in this session. A scan or the enrichment that was already running holds a list from
     * before the deletion and publishes it whole: without this, a deleted track came back in the library.
     */
    private val forgotten: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    override fun forget(trackIds: Set<String>) {
        if (trackIds.isEmpty()) return
        forgotten += trackIds
        _tracks.value = _tracks.value.filterNot { it.id in trackIds }
        // Deleted on purpose, so not "unavailable": nothing is left to list or re-link.
        repositoryScope.launch { catalog.forgetKeys(trackIds) }
    }

    override suspend fun refresh(forceRescan: Boolean) {
        if (_isScanning.value || (hasScanned && !forceRescan)) return
        _isScanning.value = true
        try {
            val scan = withContext(Dispatchers.IO) { keyed(queryMediaStore()) }
            val cached = withContext(Dispatchers.IO) { propertiesCache.read() }
            // Everything already measured goes in straight away, so the library is complete from its
            // first emission and only new or changed files are left for the extractor.
            val initial = canonicaliseAlbums(scan.tracks).filterNot { it.id in forgotten }.map { track ->
                cached[track.id]?.takeIf { it.stamp == scan.stamps[track.id] }
                    ?.let { track.withProperties(it.properties) }
                    ?: track
            }
            _tracks.value = initial
            _scanned.value = true
            // Only now: a scan that failed (no permission yet, a provider error) leaves the library to be tried again.
            hasScanned = true
            // The covers found missing are looked for again: a file may have been given one since.
            com.ikko.aura.util.AbsentCovers.clear()
            enrichAudioProperties(initial, scan.stamps, cached)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Callers launch this in a viewModelScope: letting it out takes the app down. The library stays as it was.
            android.util.Log.w("LocalMusicRepository", "Scan failed", e)
        } finally {
            _isScanning.value = false
        }
    }

    private val propertiesCache by lazy { AudioPropertiesCache(java.io.File(context.filesDir, "audio_properties.tsv")) }

    /** What MediaStore listed, and each file's modification stamp for [AudioPropertiesCache]. */
    private class Scan(
        val tracks: List<Track>,
        val stamps: Map<String, String>,
        val files: List<ScannedFile> = emptyList(),
    )

    /**
     * Gives every scanned file the key the catalogue knows it by, and records the scan. A file MediaStore has
     * re-indexed under a new id comes back under its old key, so what is keyed on that key follows it. A failure
     * of the catalogue must not cost the library: the scan then goes through under its MediaStore ids, as before.
     */
    private suspend fun keyed(scan: Scan): Scan = try {
        val previous = catalog.bindingsOf(SOURCE).map {
            LocalBinding(it.itemId, it.trackKey, it.fingerprint, Availability.valueOf(it.availability), it.lastSeenAt)
        }
        val result = LocalReconciler.reconcile(previous, scan.files, System.currentTimeMillis())
        if (scan.files.isNotEmpty()) {
            catalog.replaceBindings(
                SOURCE,
                result.bindings.map {
                    TrackBindingEntity(SOURCE, it.itemId, it.trackKey, it.fingerprint, it.availability.name, it.lastSeenAt)
                },
                result.tracks.map { CatalogTrackEntity(it.key, it.title, it.artist, it.album, it.durationMs) },
            )
        }
        val key = result.keyByItem
        Scan(
            tracks = scan.tracks.map { t -> key[t.id]?.takeIf { it != t.id }?.let { t.copy(id = it) } ?: t },
            stamps = scan.stamps.mapKeys { (id, _) -> key[id] ?: id },
            files = scan.files,
        )
    } catch (e: Exception) {
        scan
    }

    private suspend fun queryMediaStore(): Scan = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        // Not only what the scanner flagged as music: a file it left unflagged (a mix, a set, a flac
        // with no tags) is still audio, and those flags are NULL rather than 0 when unknown. Ringtones, notifications and
        // alarms stay out.
        val selection = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.MIME_TYPE} LIKE 'audio/%') AND " +
            "COALESCE(${MediaStore.Audio.Media.IS_RINGTONE}, 0) = 0 AND " +
            "COALESCE(${MediaStore.Audio.Media.IS_NOTIFICATION}, 0) = 0 AND " +
            "COALESCE(${MediaStore.Audio.Media.IS_ALARM}, 0) = 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val results = mutableListOf<Track>()
        val stamps = HashMap<String, String>()
        val files = ArrayList<ScannedFile>()
        context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val artistIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackNumberCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                stamps[id.toString()] = "${cursor.getLong(modifiedCol)}:${cursor.getLong(sizeCol)}"
                val contentUri = ContentUris.withAppendedId(collection, id)
                val rawTrackNumber = cursor.getInt(trackNumberCol)

                val track = Track(
                    id = id.toString(),
                    title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                        ?: cursor.getString(nameCol)?.substringBeforeLast('.')
                        ?: "Titre inconnu",
                    // MediaStore does not return null for a missing tag: it returns the literal "<unknown>".
                    artist = cursor.getString(artistCol).knownOr("Artiste inconnu"),
                    album = cursor.getString(albumCol).knownOr("Album inconnu"),
                    artistId = cursor.getLong(artistIdCol),
                    albumId = cursor.getLong(albumIdCol),
                    artworkUri = contentUri,
                    contentUri = contentUri,
                    durationMs = cursor.getLong(durationCol),
                    trackNumber = if (rawTrackNumber >= 1000) rawTrackNumber % 1000 else rawTrackNumber,
                    year = cursor.getInt(yearCol),
                    format = mimeToFormat(cursor.getString(mimeCol)),
                )
                results += track
                // What survives a re-index that gives the file a new id: its name, size and length.
                val fingerprint = "${cursor.getString(nameCol)}|${cursor.getLong(sizeCol)}|${track.durationMs / 1000}"
                files += ScannedFile(track.id, fingerprint, track.title, track.artist, track.album, track.durationMs)
            }
        }
        Scan(results, stamps, files)
    }

    /**
     * Opens only the files [AudioPropertiesCache] has no current measurement for, then writes the
     * cache back if anything changed (a new or modified file, or one that has gone).
     *
     * Opening every file on every launch cost ~41% of a core for ~18 s after each cold start (437
     * files, measured on the device), re-emitting the whole library twice a second while it went.
     * The answer is the same as long as the file is, so it is kept, stamped with the file's
     * modification date and size.
     */
    private suspend fun enrichAudioProperties(
        initial: List<Track>,
        stamps: Map<String, String>,
        cached: Map<String, AudioPropertiesCache.Entry>,
    ) = withContext(Dispatchers.IO) {
        val fresh = HashMap<String, AudioPropertiesCache.Entry>(initial.size)
        val toMeasure = ArrayList<Int>()
        initial.forEachIndexed { index, track ->
            val stamp = stamps[track.id] ?: return@forEachIndexed
            val known = cached[track.id]?.takeIf { it.stamp == stamp }
            if (known != null) fresh[track.id] = known else toMeasure += index
        }
        if (toMeasure.isNotEmpty()) {
            val enriched = initial.toMutableList()
            var lastEmitAt = System.currentTimeMillis()
            for (index in toMeasure) {
                val track = initial[index]
                val properties = MetadataExtractor.extract(context, track.contentUri, null)
                enriched[index] = track.withProperties(properties)
                stamps[track.id]?.let { fresh[track.id] = AudioPropertiesCache.Entry(it, properties) }
                val now = System.currentTimeMillis()
                if (now - lastEmitAt >= ENRICHMENT_EMIT_INTERVAL_MS) {
                    _tracks.value = enriched.filterNot { it.id in forgotten }
                    lastEmitAt = now
                }
                // Yield between files so thumbnail decoding for on-screen items isn't
                // starved of IO/binder time by this background scan on low-end SoCs.
                delay(ENRICHMENT_STEP_DELAY_MS)
            }
            _tracks.value = enriched.filterNot { it.id in forgotten }
        }
        if (toMeasure.isNotEmpty() || fresh.size != cached.size) propertiesCache.write(fresh)
    }

    /** Exactly what a fresh measurement puts on the track, whether it was just made or read back. */
    private fun Track.withProperties(properties: TrackAudioProperties) = copy(
        // The probe cannot name some codecs (MP3, WAV, Vorbis, WMA, AIFF come back UNKNOWN): the format MediaStore
        // gave the file is kept then, instead of replacing it with "unknown" — and caching that.
        format = preferKnownFormat(properties.format, format),
        sampleRateHz = properties.sampleRateHz,
        bitDepth = properties.bitDepth,
        bitrateKbps = properties.bitrateKbps,
    )

    /**
     * Puts every track of an album under one album id, whatever MediaStore thinks.
     *
     * MediaStore mints a new album row whenever the tags it keys on differ at all — a different
     * album-artist on one file, a stray space, a cover embedded in some files and not others. The
     * result on a real library is the same record appearing two or three times, each holding a
     * few of its tracks, with the odd single carrying the album's artwork off on its own. That is
     * what the duplicated cards on Home were: not a carousel drawing an item twice, but two album
     * rows that should have been one.
     *
     * Grouped instead on the album's name and its artist, folded to ignore case, accents and
     * spacing. The canonical id is the smallest MediaStore id in the group — a real id, so
     * anything already keyed on it (favourites, play counts, artwork cache entries) still
     * resolves, and stable across rescans as long as the files are.
     *
     * Deliberately not stripping "(Deluxe)" or "- Single": those name genuinely different
     * releases, and merging them would be the same mistake in the other direction.
     */
    private fun canonicaliseAlbums(tracks: List<Track>): List<Track> {
        val canonicalIds = tracks
            .groupBy { albumKey(it) }
            .mapValues { (_, group) -> group.minOf { it.albumId } }
        return tracks.map { track ->
            val canonical = canonicalIds[albumKey(track)] ?: track.albumId
            if (canonical == track.albumId) track else track.copy(albumId = canonical)
        }
    }

    private fun albumKey(track: Track): String = "${track.album.foldForMatching()}\u0000${track.artist.foldForMatching()}"

    private fun groupAlbums(tracks: List<Track>): List<Album> = tracks
        .groupBy { it.albumId }
        .map { (albumId, groupTracks) ->
            // The opening track's artwork, not whichever file happened to come first out of the
            // cursor: a compilation where one track carries a different cover would otherwise
            // give the album a sleeve none of the others have.
            val cover = groupTracks.minByOrNull { it.trackNumber.takeIf { n -> n > 0 } ?: Int.MAX_VALUE }
                ?: groupTracks.first()
            // The most common spelling wins the label, since the group exists precisely because
            // the files disagree about it.
            val title = groupTracks.groupingBy { it.album }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
            val artist = groupTracks.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
            Album(
                id = albumId,
                title = title,
                artist = artist,
                artistId = cover.artistId,
                artworkUri = cover.artworkUri,
                trackCount = groupTracks.size,
                year = groupTracks.maxOf { it.year },
            )
        }
        .sortedBy { it.title.lowercase() }

    private fun groupArtists(tracks: List<Track>): List<Artist> = tracks
        .groupBy { it.artistId }
        .map { (artistId, groupTracks) ->
            Artist(
                id = artistId,
                name = groupTracks.first().artist,
                trackCount = groupTracks.size,
                albumCount = groupTracks.map { it.albumId }.distinct().size,
            )
        }
        .sortedBy { it.name.lowercase() }

    private fun mimeToFormat(mimeType: String?): AudioFormat = when (mimeType) {
        "audio/mpeg" -> AudioFormat.MP3
        "audio/mp4", "audio/m4a", "audio/mp4a-latm" -> AudioFormat.AAC
        "audio/ogg" -> AudioFormat.OGG
        "audio/opus" -> AudioFormat.OPUS
        "audio/x-ms-wma" -> AudioFormat.WMA
        "audio/flac", "audio/x-flac" -> AudioFormat.FLAC
        "audio/x-wav", "audio/wav", "audio/vnd.wave" -> AudioFormat.WAV
        "audio/x-aiff", "audio/aiff" -> AudioFormat.AIFF
        else -> AudioFormat.UNKNOWN
    }

    companion object {
        private val DIACRITICS = Regex("\\p{Mn}+")
        private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")

        /**
         * Case, accents, punctuation and spacing removed — everything two taggings of the same
         * record are liable to disagree about, and nothing that distinguishes two records.
         */
        private fun String.foldForMatching(): String =
            java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD)
                .replace(DIACRITICS, "")
                .lowercase()
                .replace(NON_ALPHANUMERIC, " ")
                .trim()

        private val SOURCE = SourceId.LOCAL.value
        private const val ENRICHMENT_EMIT_INTERVAL_MS = 500L
        private const val ENRICHMENT_STEP_DELAY_MS = 4L
    }
}

private fun String?.knownOr(fallback: String): String =
    if (this == null || isBlank() || this == MediaStore.UNKNOWN_STRING) fallback else this

/** What the probe made of a file, unless it could not name the codec: then what MediaStore said it was. */
internal fun preferKnownFormat(probed: AudioFormat, fromMediaStore: AudioFormat): AudioFormat =
    probed.takeIf { it != AudioFormat.UNKNOWN } ?: fromMediaStore
