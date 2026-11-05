package com.ikko.aura.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.ikko.aura.data.deezer.DeezerArtistProfile
import com.ikko.aura.data.deezer.DeezerClient
import com.ikko.aura.reco.ArtistNames
import com.ikko.aura.reco.TrackIdentity
import com.ikko.aura.reco.authority.ArtistAuthority
import com.ikko.aura.reco.authority.DiscographyVerifier
import com.ikko.aura.source.ytm.YtmClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * An artist's photo, by name: Deezer's (free, no key), else YouTube Music's. The picture itself is downloaded into the app's own
 * storage, so it shows at once and offline; what was not found is remembered for a few days. A photo that was found is looked
 * up again every week in the background: Deezer's addresses carry the picture's hash, so a different address is a new photo, which
 * is downloaded and takes the place of the old one.
 */
@Singleton
class ArtistImages(
    private val context: Context? = null,
    private val ytm: YtmClient? = null,
    private val repository: MusicRepository,
    private val authority: ArtistAuthority,
    private val verifier: DiscographyVerifier,
    private val deezerClient: DeezerClient,
    prefsOverride: android.content.SharedPreferences? = null,
    dirOverride: File? = null,
    /** The artist's best-known songs by Last.fm (titles): a proof of who a profile belongs to when the library has none of their songs. */
    private val lastFmTopTracks: (suspend (String) -> List<String>?)? = null,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        ytm: YtmClient,
        repository: MusicRepository,
        authority: ArtistAuthority,
        verifier: DiscographyVerifier,
        deezerClient: DeezerClient,
        lastFm: com.ikko.aura.lastfm.LastFm,
    ) : this(context, ytm, repository, authority, verifier, deezerClient, null, null, { lastFm.topTracks(it) })

    private val prefs = prefsOverride ?: context!!.getSharedPreferences("artist_images", Context.MODE_PRIVATE)
    private val dir = (dirOverride ?: File(context!!.filesDir, "artist_photos")).apply { mkdirs() }
    private val memo = ConcurrentHashMap<String, String>() // the file's address; "" = none
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Photos chosen by an older rule (a namesake's, picked by name alone) are not trusted: they are dropped and chosen again, one at a
        // time and when they are asked for, by the discography rule. A photo the current rule chose stays for good.
        if (prefs.getInt(RULES_KEY, 0) != PHOTO_RULES) {
            runCatching {
                dir.listFiles()?.forEach { it.delete() }
                prefs.edit().clear().putInt(RULES_KEY, PHOTO_RULES).apply()
            }
        }
    }

    /** One lookup at a time per artist, and only a few at once: a list of artists asks for dozens of photos together. */
    private val loading = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<String?>>()
    private val slots = kotlinx.coroutines.sync.Semaphore(3)

    private fun fileUri(file: File): String = "file://" + file.absolutePath

    private fun keyOf(name: String) = ArtistNames.primaryKey(name)

    /** What is already known, without any wait: null when the name has not been looked up (or has no photo). */
    fun peek(name: String): String? {
        val key = keyOf(name)
        if (key.isEmpty()) return null
        memo[key]?.let { return it.ifEmpty { null } }
        val parts = prefs.getString(key, null)?.split('\t')
        if (parts != null && parts.size == 3 && parts[2].isNotEmpty()) {
            val file = File(dir, parts[2])
            if (file.exists()) {
                val uriStr = fileUri(file)
                memo[key] = uriStr
                return uriStr
            }
        }
        return null
    }

    /** The photo's file, as an address; null when the artist has none. */
    suspend fun urlFor(name: String): String? = withContext(Dispatchers.IO) {
        val key = keyOf(name)
        if (key.isEmpty()) return@withContext null
        memo[key]?.let { return@withContext it.ifEmpty { null } }
        // "<time>\t<source address>\t<file name>"
        val parts = prefs.getString(key, null)?.split('\t')
        if (parts != null && parts.size == 3) {
            val age = System.currentTimeMillis() - (parts[0].toLongOrNull() ?: 0L)
            val file = File(dir, parts[2])
            if (parts[2].isEmpty()) {
                if (age < MISS_MS) { memo[key] = ""; return@withContext null }
            } else if (file.exists()) {
                memo[key] = fileUri(file)
                if (age > REFRESH_MS) refresh(key, name)
                return@withContext memo[key]
            }
        }
        // The same artist asked for by several places at once is looked up once.
        loading.computeIfAbsent(key) {
            scope.async { try { slots.withPermit { fetch(key, name) } } finally { loading.remove(key) } }
        }.await()
    }

    /** Looks the photo up and downloads it; the previous file is removed once the new one is there. */
    private suspend fun fetch(key: String, name: String): String? {
        val oldPart = prefs.getString(key, null)?.split('\t')
        val oldSource = oldPart?.getOrNull(1).orEmpty()
        val oldFile = oldPart?.getOrNull(2).orEmpty()
        val existingFile = if (oldFile.isNotEmpty()) File(dir, oldFile) else null
        val hasValidOld = existingFile != null && existingFile.exists() && existingFile.length() > 0

        val source = runCatching { resolveValidArtistImageUrl(name) }.getOrNull()
        if (source == null) {
            if (hasValidOld && existingFile != null) {
                // Preserve already validated image in database; do not overwrite with empty or null
                val uriStr = fileUri(existingFile)
                memo[key] = uriStr
                return uriStr
            } else {
                memo[key] = ""
                save(key, "", "")
                return null
            }
        }

        // If source URL is identical to existing record and file exists, reuse it
        if (hasValidOld && source == oldSource && existingFile != null) {
            val uriStr = fileUri(existingFile)
            memo[key] = uriStr
            save(key, source, oldFile)
            return uriStr
        }

        val file = File(dir, "${Integer.toHexString(key.hashCode())}-${Integer.toHexString(source.hashCode())}.jpg")
        if (!file.exists() && !download(source, file)) {
            if (hasValidOld && existingFile != null) {
                val uriStr = fileUri(existingFile)
                memo[key] = uriStr
                return uriStr
            }
            return memo[key]?.ifEmpty { null }
        }
        if (oldFile.isNotEmpty() && oldFile != file.name) {
            File(dir, oldFile).delete()
        }
        save(key, source, file.name)
        return fileUri(file).also { memo[key] = it }
    }

    private fun save(key: String, source: String, file: String) {
        prefs.edit().putString(key, "${System.currentTimeMillis()}\t$source\t$file").apply()
    }

    /** A photo already on disk is asked about again; if the artist changed it, the new one replaces it for next time. */
    private fun refresh(key: String, name: String) {
        if (!refreshing.add(key)) return
        scope.launch {
            try {
                fetch(key, name)
            } finally { refreshing.remove(key) }
        }
    }

    private fun download(source: String, into: File): Boolean = runCatching {
        val connection = URL(source).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        val partial = File(into.parentFile, into.name + ".part")
        try {
            connection.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
        } finally { connection.disconnect() }
        partial.renameTo(into)
    }.getOrDefault(false)

    /**
     * Resolves and validates an artist profile image URL.
     * Enforces:
     * 1. Cross-verification against local library discography (tracks and albums).
     * 2. Strict resolution via unique identifiers (MBID, Deezer ID, Last.fm tags).
     * 3. Disambiguation of homonyms by discography concordance and strict accent matching.
     * 4. Returns null if no discography concordance is confirmed with the local library.
     */
    internal suspend fun resolveValidArtistImageUrl(name: String): String? {
        val cleanName = ArtistNames.primary(name, beforeAmpersand = true)
        if (cleanName.isEmpty()) return null
        val key = keyOf(cleanName)
        if (key.isEmpty()) return null

        // 1. Collect local library discography for this artist
        val localTracks = repository.tracks.value.filter { track ->
            track.artist.equals(cleanName, ignoreCase = true) ||
                ArtistNames.split(track.artist).any { it.equals(cleanName, ignoreCase = true) } ||
                keyOf(track.artist) == key
        }
        val localAlbums = repository.albums.value.filter { album ->
            album.artist.equals(cleanName, ignoreCase = true) ||
                ArtistNames.split(album.artist).any { it.equals(cleanName, ignoreCase = true) } ||
                keyOf(album.artist) == key
        }
        val localTrackKeys = localTracks.map { TrackIdentity.baseKey(it.title) }.filter { it.isNotEmpty() }.toSet()
        val localAlbumKeys = localAlbums.map { TrackIdentity.baseKey(it.title) }.filter { it.isNotEmpty() }.toSet()
        val hasLocalMusic = localTrackKeys.isNotEmpty() || localAlbumKeys.isNotEmpty()

        // 2. Resolve via ArtistAuthority (Deezer ID, MBID MusicBrainz, Last.fm)
        val resolved = runCatching { authority.resolve(cleanName) }.getOrNull()

        // If authority gave a verified Deezer ID, check its discography and picture first
        val resolvedDeezerId = resolved?.deezerId
        if (resolvedDeezerId != null && resolvedDeezerId > 0) {
            val dArtist = runCatching { deezerClient.getArtist(resolvedDeezerId) }.getOrNull()
            val pic = dArtist?.pictureXl?.takeIf { it.isNotBlank() && !it.contains("/artist//") }
            if (pic != null && keyOf(dArtist.name) == key && !ArtistAuthority.hasAccentMismatch(cleanName, dArtist.name)) {
                val dTracks = runCatching { deezerClient.getArtistTopTracks(resolvedDeezerId, limit = 50) }.getOrDefault(emptyList())
                val dAlbums = runCatching { deezerClient.getArtistAlbums(resolvedDeezerId, limit = 50) }.getOrDefault(emptyList())
                val dTrackKeys = dTracks.map { TrackIdentity.baseKey(it.title) }.toSet()
                val dAlbumKeys = dAlbums.map { TrackIdentity.baseKey(it.title) }.toSet()

                val matches = localTrackKeys.any { it in dTrackKeys } || localAlbumKeys.any { it in dAlbumKeys }
                if (matches || !hasLocalMusic) {
                    return pic
                }
            }
        }

        // 3. Search Deezer candidates for homonym resolution and discography cross-check
        val candidates = runCatching { deezerClient.searchArtist(cleanName, limit = 10) }.getOrDefault(emptyList())
        if (candidates.isEmpty()) return null

        // The same name, and only that: "Angèle Dubeau" is not Angèle. A name that merely contains the artist's can be theirs (a
        // channel branded with a first name) only when their songs are in the library and the profile has them too.
        val sameName = candidates.filter { keyOf(it.name) == key }
        val compatibleCandidates = if (hasLocalMusic) {
            sameName + candidates.filter { cand -> cand !in sameName && ArtistNames.compatible(keyOf(cand.name), key) }
        } else sameName
        if (compatibleCandidates.isEmpty()) return null
        // What Last.fm calls the artist's best-known songs: a second proof, that needs no file of theirs in the library.
        val lastFmKeys = runCatching { lastFmTopTracks?.invoke(cleanName) }.getOrNull().orEmpty()
            .map { TrackIdentity.baseKey(it) }.filter { it.isNotEmpty() }.toSet()

        // In case of multiple candidates, filter by strict accents first if an exact match exists
        val exactAccentCandidates = compatibleCandidates.filter {
            !ArtistAuthority.hasAccentMismatch(cleanName, it.name.trim())
        }
        val pool = (if (exactAccentCandidates.isNotEmpty()) exactAccentCandidates else compatibleCandidates)
            .sortedWith(
                compareByDescending<DeezerArtistProfile> { it.id == resolvedDeezerId }
                    .thenByDescending { it.nbFan }
            ).take(4)

        var bestCandidate: DeezerArtistProfile? = null
        var bestScore = -1
        var bestHasDiscographyMatch = false

        for (cand in pool) {
            val candPic = cand.pictureXl?.takeIf { it.isNotBlank() && !it.contains("/artist//") } ?: continue

            val exactAccentMatch = cand.name.trim().equals(cleanName, ignoreCase = true) &&
                !ArtistAuthority.hasAccentMismatch(cleanName, cand.name.trim())
            val accentDiff = ArtistAuthority.hasAccentMismatch(cleanName, cand.name.trim())

            // Fetch candidate's discography from Deezer
            val candTopTracks = runCatching { deezerClient.getArtistTopTracks(cand.id, limit = 50) }.getOrDefault(emptyList())
            val candAlbums = runCatching { deezerClient.getArtistAlbums(cand.id, limit = 50) }.getOrDefault(emptyList())

            val candTrackKeys = candTopTracks.map { TrackIdentity.baseKey(it.title) }.toSet()
            val candAlbumKeys = candAlbums.map { TrackIdentity.baseKey(it.title) }.toSet()

            val trackMatches = localTrackKeys.intersect(candTrackKeys).size
            val albumMatches = localAlbumKeys.intersect(candAlbumKeys).size
            val lastFmMatches = lastFmKeys.intersect(candTrackKeys).size
            val totalMatches = trackMatches + (albumMatches * 2)

            // Proved by the library's songs, or by what Last.fm lists as the artist's own: either is enough.
            val hasDiscographyConcordance = totalMatches > 0 || lastFmMatches > 0

            // If the listener has tracks of this artist locally, lack of concordance strictly disqualifies!
            if (hasLocalMusic && !hasDiscographyConcordance) {
                continue
            }

            var score = 0
            if (hasDiscographyConcordance) {
                score += 1000 + (totalMatches * 100)
            }
            // Songs Last.fm lists as the artist's own are on this profile's list too: this is the right one of the namesakes.
            if (lastFmMatches > 0) {
                score += 800 + (lastFmMatches.coerceAtMost(5) * 50)
            }
            if (resolvedDeezerId == cand.id) {
                score += 500
            }
            if (exactAccentMatch) {
                score += 300
            } else if (accentDiff) {
                score -= 300
            }
            score += (cand.nbFan.coerceAtMost(10_000_000) / 200_000)

            if (score > bestScore) {
                bestScore = score
                bestCandidate = cand
                bestHasDiscographyMatch = hasDiscographyConcordance
            }
        }

        // If local tracks exist, we MUST have verified discography concordance
        if (hasLocalMusic && (!bestHasDiscographyMatch || bestCandidate == null)) {
            return null
        }

        return bestCandidate?.pictureXl
    }

    /** Artists whose name matches [query], best known first (Deezer, no key); empty when it does not answer. */
    suspend fun search(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val connection = URL("https://api.deezer.com/search/artist?q=$q&limit=18").openConnection() as HttpURLConnection
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            val body = try { connection.inputStream.bufferedReader().use { it.readText() } } finally { connection.disconnect() }
            val data = JSONObject(body).optJSONArray("data") ?: return@runCatching emptyList<String>()
            (0 until data.length()).map { data.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    @Volatile private var chartMemo: List<String> = emptyList()

    /** The artists of Deezer's chart (no key), a pool that does not run dry: used when the first names have all been seen. */
    suspend fun chart(): List<String> = withContext(Dispatchers.IO) {
        chartMemo.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        runCatching {
            val connection = URL("https://api.deezer.com/chart/0/artists?limit=100").openConnection() as HttpURLConnection
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            val body = try { connection.inputStream.bufferedReader().use { it.readText() } } finally { connection.disconnect() }
            val data = JSONObject(body).optJSONArray("data") ?: return@runCatching emptyList<String>()
            (0 until data.length()).map { data.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList()).also { chartMemo = it }
    }

    private val relatedMemo = ConcurrentHashMap<String, List<String>>()

    /** Artists Deezer lists as related to [name] (no key): a second opinion when Last.fm knows few or none. */
    suspend fun related(name: String): List<String> = withContext(Dispatchers.IO) {
        val key = keyOf(name)
        relatedMemo[key]?.let { return@withContext it }
        runCatching {
            fun get(path: String): JSONObject {
                val connection = URL("https://api.deezer.com/$path").openConnection() as HttpURLConnection
                connection.connectTimeout = 6_000
                connection.readTimeout = 6_000
                return try { JSONObject(connection.inputStream.bufferedReader().use { it.readText() }) } finally { connection.disconnect() }
            }
            val q = URLEncoder.encode(name.trim(), "UTF-8")
            val hits = get("search/artist?q=$q&limit=5").optJSONArray("data") ?: return@runCatching emptyList<String>()
            val id = (0 until hits.length()).map { hits.getJSONObject(it) }.firstOrNull { keyOf(it.optString("name")) == key }?.optLong("id") ?: return@runCatching emptyList<String>()
            val list = get("artist/$id/related?limit=25").optJSONArray("data") ?: return@runCatching emptyList<String>()
            (0 until list.length()).map { list.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) relatedMemo[key] = it }
    }

    companion object {
        const val REFRESH_MS = 7L * 24 * 3600 * 1000
        /** An artist with no photo is looked for again after this: short, as a failed lookup (no network) is not told apart from "none". */
        const val MISS_MS = 6L * 3600 * 1000
        /** Raised when the rule that chooses a photo changes: what an older rule chose is then dropped. */
        const val PHOTO_RULES = 3
        const val RULES_KEY = "photo_rules"
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface ArtistImagesEntryPoint {
    fun artistImages(): ArtistImages
}

@HiltViewModel
class ArtistImagesViewModel @Inject constructor(val images: ArtistImages) : ViewModel()

/** The photo of an artist, once it is known; null before that and for an artist without one. */
@Composable
fun rememberArtistImage(name: String): Uri? {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val images = androidx.compose.runtime.remember(context) {
        dagger.hilt.EntryPoints.get(context, ArtistImagesEntryPoint::class.java).artistImages()
    }
    val url by produceState(images.peek(name), name) { value = images.urlFor(name) }
    return url?.let(Uri::parse)
}
