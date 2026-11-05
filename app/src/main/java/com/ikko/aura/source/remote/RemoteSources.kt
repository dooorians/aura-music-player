package com.ikko.aura.source.remote

import com.ikko.aura.data.model.Track
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** A source that is on, with a way to talk to it. */
class RemoteHandle(val config: SourceConfigEntity, val client: RemoteClient)

/**
 * The sources the user added. The list lives in Room (the password sealed by [SecretBox]); a client is built from a
 * source's row the first time it is needed and kept until the row changes.
 */
@Singleton
class RemoteSources @Inject constructor(
    private val dao: SourceConfigDao,
    private val secrets: SecretBox,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = ConcurrentHashMap<String, Pair<SourceConfigEntity, RemoteClient>>()

    @Volatile private var snapshot: Map<String, SourceConfigEntity>? = null

    init {
        scope.launch { dao.observeAll().collect { rows -> snapshot = rows.associateBy { it.id } } }
    }

    val configs: Flow<List<SourceConfigEntity>> = dao.observeAll()

    /** The ids of the sources that are switched on. */
    val enabledIds: Flow<Set<String>> = dao.observeAll().map { rows -> rows.filter { it.enabled }.mapTo(HashSet()) { it.id } }

    private fun current(): Map<String, SourceConfigEntity> =
        snapshot ?: runBlocking { dao.all().associateBy { it.id } }.also { snapshot = it }

    fun configOf(id: String): SourceConfigEntity? = current()[id]

    fun isRemoteId(sourceId: String): Boolean = current().containsKey(sourceId)

    fun nameOf(id: String): String = configOf(id)?.name ?: id

    /** A client for the source [id], or null if there is none or it is off. */
    fun client(id: String): RemoteClient? {
        val config = configOf(id)?.takeIf { it.enabled } ?: return null
        clients[id]?.takeIf { it.first == config }?.let { return it.second }
        val built = build(config, secrets.open(config.secret))
        clients[id] = config to built
        return built
    }

    /** Every source that is on. */
    fun active(): List<RemoteHandle> = current().values.filter { it.enabled }.sortedBy { it.createdAt }
        .mapNotNull { config -> client(config.id)?.let { RemoteHandle(config, it) } }

    /** The address and the file type (when known) a song can be fetched from, for the downloads. */
    fun streamFor(key: String): String {
        val sourceId = key.substringBefore(':')
        val client = client(sourceId) ?: throw IOException("source $sourceId is off")
        return client.streamUrl(key.substringAfter(':'))
    }

    private fun build(config: SourceConfigEntity, password: String): RemoteClient = when (RemoteKind.valueOf(config.kind)) {
        RemoteKind.SUBSONIC -> SubsonicClient(config.baseUrl, config.username, password, config.salt)
    }

    /** Tries the credentials without saving anything: null if they work, the reason if they do not. */
    suspend fun test(kind: RemoteKind, baseUrl: String, username: String, password: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                when (kind) {
                    RemoteKind.SUBSONIC -> SubsonicClient(normalise(baseUrl), username, password, "test").ping()
                }
            }.exceptionOrNull()?.let { it.message?.takeIf(String::isNotBlank) ?: it::class.simpleName ?: "erreur" }
        }

    suspend fun add(kind: RemoteKind, name: String, baseUrl: String, username: String, password: String): String {
        val id = "sub" + UUID.randomUUID().toString().replace("-", "").take(6)
        dao.upsert(
            SourceConfigEntity(
                id = id,
                kind = kind.name,
                name = name.trim().ifEmpty { kind.label },
                baseUrl = normalise(baseUrl),
                username = username.trim(),
                secret = secrets.seal(password),
                salt = UUID.randomUUID().toString().replace("-", "").take(12),
                enabled = true,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return id
    }

    /** Changes a source; an empty [password] leaves the stored one as it is. */
    suspend fun update(id: String, name: String, baseUrl: String, username: String, password: String) {
        val old = dao.all().firstOrNull { it.id == id } ?: return
        dao.upsert(
            old.copy(
                name = name.trim().ifEmpty { old.name },
                baseUrl = normalise(baseUrl),
                username = username.trim(),
                secret = if (password.isEmpty()) old.secret else secrets.seal(password),
            ),
        )
        clients.remove(id)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun remove(id: String) {
        dao.delete(id)
        clients.remove(id)
    }

    private fun normalise(url: String): String = normaliseBaseUrl(url)
}

/**
 * The server's address, from whatever was pasted: with or without `http://`, with a trailing slash, or copied from
 * the browser's bar on the server's own page (`…/app/#/albums`, `…/rest`, `…/rest/ping.view?u=…`). The address of the
 * server is what is kept; a path of its own (a reverse proxy at `/navidrome`) stays.
 */
fun normaliseBaseUrl(raw: String): String {
    var url = raw.trim()
    if (url.isEmpty()) return url
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) url = "http://$url"
    url = url.substringBefore('#').substringBefore('?').trimEnd('/')
    val lower = url.lowercase()
    for (tail in listOf("/rest/ping.view", "/rest/ping", "/rest", "/app", "/index.html", "/manifest.json")) {
        if (lower.endsWith(tail)) { url = url.dropLast(tail.length); break }
    }
    return url.trimEnd('/')
}

/** What a remote song is to the lists and the player. */
object RemoteTracks {
    fun uriOf(sourceId: String, itemId: String): android.net.Uri =
        android.net.Uri.Builder().scheme("remote").authority(sourceId).appendPath(itemId).build()

    /** The track for a song [key]ed `<source>:<item>`; [albumRef] is `<source>:<albumId>` when the album is known. */
    fun trackOf(
        key: String,
        title: String,
        artist: String,
        durationMs: Long,
        artworkUrl: String?,
        albumRef: String?,
        albumTitle: String?,
        trackNumber: Int = 0,
        year: Int = 0,
    ): Track {
        val sourceId = key.substringBefore(':')
        return Track(
            id = key,
            title = title,
            artist = artist,
            album = albumTitle?.ifEmpty { null } ?: sourceId,
            artistId = 0L,
            // One id for an album's songs, so they share a page and a cover.
            albumId = com.ikko.aura.source.ytm.YtmLibrary.pseudoAlbumId(albumRef ?: key),
            artworkUri = artworkUrl?.takeIf { it.isNotEmpty() }?.let(android.net.Uri::parse),
            contentUri = uriOf(sourceId, key.substringAfter(':')),
            durationMs = durationMs,
            trackNumber = trackNumber,
            year = year,
            remoteAlbumId = albumRef,
        )
    }

    fun trackOf(sourceId: String, song: RemoteSong, client: RemoteClient): Track = trackOf(
        key = "$sourceId:${song.id}",
        title = song.title,
        artist = song.artist,
        durationMs = song.durationMs,
        artworkUrl = client.coverUrl(song.coverId, COVER_PX),
        albumRef = song.albumId?.let { "$sourceId:$it" },
        albumTitle = song.album,
        trackNumber = song.trackNumber,
        year = song.year,
    )

    const val COVER_PX = 800
}
