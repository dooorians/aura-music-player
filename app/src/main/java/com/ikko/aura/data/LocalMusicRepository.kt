package com.ikko.aura.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

@Singleton
class LocalMusicRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : MusicRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    override val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    override val albums: StateFlow<List<Album>> = _tracks
        .map(::groupAlbums)
        .stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

    override val artists: StateFlow<List<Artist>> = _tracks
        .map(::groupArtists)
        .stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var hasScanned = false

    override fun forget(trackIds: Set<String>) {
        if (trackIds.isEmpty()) return
        _tracks.value = _tracks.value.filterNot { it.id in trackIds }
    }

    override suspend fun refresh(forceRescan: Boolean) {
        if (_isScanning.value || (hasScanned && !forceRescan)) return
        hasScanned = true
        _isScanning.value = true
        try {
            val initial = canonicaliseAlbums(queryMediaStore())
            _tracks.value = initial
            enrichAudioProperties(initial)
        } finally {
            _isScanning.value = false
        }
    }

    private suspend fun queryMediaStore(): List<Track> = withContext(Dispatchers.IO) {
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
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val results = mutableListOf<Track>()
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

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val contentUri = ContentUris.withAppendedId(collection, id)
                val rawTrackNumber = cursor.getInt(trackNumberCol)

                results += Track(
                    id = id.toString(),
                    title = cursor.getString(titleCol) ?: "Titre inconnu",
                    artist = cursor.getString(artistCol) ?: "Artiste inconnu",
                    album = cursor.getString(albumCol) ?: "Album inconnu",
                    artistId = cursor.getLong(artistIdCol),
                    albumId = cursor.getLong(albumIdCol),
                    artworkUri = contentUri,
                    contentUri = contentUri,
                    durationMs = cursor.getLong(durationCol),
                    trackNumber = if (rawTrackNumber >= 1000) rawTrackNumber % 1000 else rawTrackNumber,
                    year = cursor.getInt(yearCol),
                    format = mimeToFormat(cursor.getString(mimeCol)),
                )
            }
        }
        results
    }

    private suspend fun enrichAudioProperties(initial: List<Track>) = withContext(Dispatchers.IO) {
        if (initial.isEmpty()) return@withContext
        val enriched = initial.toMutableList()
        var lastEmitAt = System.currentTimeMillis()
        initial.forEachIndexed { index, track ->
            val properties = MetadataExtractor.extract(context, track.contentUri, null)
            enriched[index] = track.copy(
                format = properties.format,
                sampleRateHz = properties.sampleRateHz,
                bitDepth = properties.bitDepth,
                bitrateKbps = properties.bitrateKbps,
            )
            val now = System.currentTimeMillis()
            if (now - lastEmitAt >= ENRICHMENT_EMIT_INTERVAL_MS) {
                _tracks.value = enriched.toList()
                lastEmitAt = now
            }
            // Yield between files so thumbnail decoding for on-screen items isn't
            // starved of IO/binder time by this background scan on low-end SoCs.
            delay(ENRICHMENT_STEP_DELAY_MS)
        }
        _tracks.value = enriched.toList()
    }

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

        private const val ENRICHMENT_EMIT_INTERVAL_MS = 500L
        private const val ENRICHMENT_STEP_DELAY_MS = 4L
    }
}
