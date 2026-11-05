package com.ikko.aura.data.home

import com.ikko.aura.data.model.Album
import com.ikko.aura.reco.ReasonCode
import com.ikko.aura.reco.authority.CompilationTitles
import com.ikko.aura.reco.RecoItem

/**
 * An album offered on Home: one of the library ([album]) or one of an external source ([ref] is its page's reference, a
 * YouTube Music playlist id or `<source>:<id>`, and [artworkUrl] its cover).
 */
@androidx.compose.runtime.Immutable
data class SectionAlbum(
    val title: String,
    val artist: String,
    val ref: String? = null,
    val artworkUrl: String = "",
    /** The album's own cover (resolved from its reference), when known; else the song's artwork is used. */
    val albumCoverUrl: String? = null,
    val album: Album? = null,
    /** The songs of an external album that has no page of its own to open (they are played instead). */
    val items: List<RecoItem> = emptyList(),
    /** A tile that plays its [items] (a radio, a mood) instead of opening an album page. */
    val playsItems: Boolean = false,
    /** A tile of an artist (round, opening the artist's page) rather than of an album. */
    val isArtist: Boolean = false,
    /** A radio of one artist: the name that a long press offers to stop recommending. Null for a radio of several, and for albums. */
    val blockableArtist: String? = null,
) {
    // An album with neither a page reference nor songs (a chart's) is told apart by its name and its artist's.
    val key: String get() = album?.let { "local:${it.id}" } ?: ref?.let { "ref:$it" } ?: "songs:${items.firstOrNull()?.trackId ?: sameAs}"
    val sameAs: String get() = title.trim().lowercase() + "\u0000" + artist.trim().lowercase()

    /** The album's own cover when known; else the song's artwork the tile was built from. */
    fun effectiveCover(): String? = albumCoverUrl ?: artworkUrl.takeIf { it.isNotBlank() }
}

/**
 * A row of Home under "Mixes pour toi": [kicker] ("Plus de", "Plus de contenu similaire à") over [title], and its albums.
 * The first album's cover stands at the head of the row.
 */
@androidx.compose.runtime.Immutable
data class HomeSection(
    val id: String,
    val kicker: String?,
    val title: String,
    val albums: List<SectionAlbum>,
    val subtitle: String? = null,
    /** Albums that fit this row as well as its own and were left out for lack of room: what takes the place of one that goes. */
    val spare: List<SectionAlbum> = emptyList(),
)

/**
 * The rows of Home, made from what is played and from what the recommendation engine found for it on the external sources:
 * the new releases of the artists played, more by an artist played (the library's albums of theirs first, then what the
 * sources have), albums close to one played, what is returned to often, and styles of the library. What is already listened
 * to is offered again: a row mixes the two. An album shows once, and a row has at least [MIN_ALBUMS]. Pure, so it can be
 * checked on its own.
 */
object HomeSections {
    /** Rows of three, as the library's grid: a row has one line of three albums, or two. */
    const val PER_ROW = 3
    const val MIN_ALBUMS = PER_ROW
    /** One line: a row of Home (but the mixes) never holds more than three albums. */
    const val MAX_ALBUMS = PER_ROW
    const val MAX_SECTIONS = 6
    private const val EXTERNAL_PER_ARTIST = 3
    private const val SPARE = 6

    private fun realAlbum(album: String): String =
        if (album.equals("YouTube Music", ignoreCase = true) || album.equals("JioSaavn", ignoreCase = true)) "" else album.trim()

    fun isExcludedAlbum(title: String): Boolean =
        CompilationTitles.matches(title)

    private fun validReason(r: RecoItem): Boolean =
        r.reason != null && !(r.reason.code == ReasonCode.EXPLORATION && r.sourceId == "jiosaavn") && !isExcludedAlbum(r.album) && !isExcludedAlbum(r.title)

    /**
     * Every album the sources found, one tile each (the first song of a group speaks for it), with its songs for those that have
     * no page to open. The rows of themes are drawn from these.
     */
    fun externalAlbums(items: Map<String, List<RecoItem>>): List<SectionAlbum> {
        val groups = items.values.flatten().filter { it.artworkUrl.isNotBlank() && validReason(it) && !isExcludedAlbum(it.album) && !isExcludedAlbum(it.title) }
            .groupBy { r ->
                val alb = realAlbum(r.album)
                when {
                    r.albumRef.isNotBlank() -> "ref:${r.albumRef}"
                    alb.isNotBlank() -> "name:" + alb.lowercase() + "\u0000" + r.artist.trim().lowercase()
                    else -> "song:${r.trackId}"
                }
            }.values.map { it.distinctBy { s -> s.trackId } }
        return groups.map { g ->
            val r = g.first()
            val alb = realAlbum(r.album)
            SectionAlbum(alb.ifBlank { r.title }, r.artist, ref = r.albumRef.takeIf { it.isNotBlank() }, artworkUrl = r.artworkUrl, items = g)
        }.filter { !isExcludedAlbum(it.title) }
    }

    fun build(
        items: Map<String, List<RecoItem>>,
        library: List<Album>,
        recentAlbumIds: List<Long>,
        topPlayedAlbumIds: List<Long>,
        maxSections: Int = MAX_SECTIONS,
    ): List<HomeSection> {
        // The songs found, gathered by album (its page's reference when the source gives one, else its name and artist: a
        // source such as JioSaavn gives none), a song with no album standing for itself. The first of a group speaks for it.
        val groups = items.values.flatten().filter { it.artworkUrl.isNotBlank() && validReason(it) }
            .groupBy { r ->
                val alb = realAlbum(r.album)
                when {
                    r.albumRef.isNotBlank() -> "ref:${r.albumRef}"
                    alb.isNotBlank() -> "name:" + alb.lowercase() + "\u0000" + r.artist.trim().lowercase()
                    else -> "song:${r.trackId}"
                }
            }.values.map { it.distinctBy { s -> s.trackId } }
        val external = groups.map { it.first() }
        val songsOf = groups.associate { it.first().trackId to it }
        fun ext(r: RecoItem) = SectionAlbum(
            title = realAlbum(r.album).ifBlank { r.title }, artist = r.artist, ref = r.albumRef.takeIf { it.isNotBlank() },
            artworkUrl = r.artworkUrl, items = songsOf[r.trackId].orEmpty(),
        )
        fun loc(a: Album) = SectionAlbum(a.title, a.artist, album = a)
        val byId = library.associateBy { it.id }
        val ownedKeys = library.mapTo(HashSet()) { loc(it).sameAs }
        val used = HashSet<String>()
        val usedNames = HashSet<String>()
        val out = ArrayList<HomeSection>()

        // An external album the library already has is not offered as new.
        val fresh = external.filter { ext(it).sameAs !in ownedKeys }

        // A row holds only what its title says: "similar to X" the albums found as close to X, "Nouvelles sorties" what has just come
        // out. A row that has fewer than a line of them is not made, rather than completed with albums that have nothing to do with it.
        fun add(id: String, kicker: String?, title: String, from: List<SectionAlbum>) {
            if (out.size >= maxSections || title.isBlank()) return
            val found = from.filter { it.key !in used && it.sameAs !in usedNames }.distinctBy { it.sameAs }
            // Whole lines of three: a short last line would hang there.
            val albums = found.take(MAX_ALBUMS).let { it.take(it.size / PER_ROW * PER_ROW) }
            if (albums.size < MIN_ALBUMS) return
            albums.forEach { used += it.key; usedNames += it.sameAs }
            out += HomeSection(id, kicker, title, albums, spare = found.drop(albums.size).take(SPARE))
        }
        // 1. What has just come out, by the artists played or close to the taste.
        add(
            "new", "Nouvelles sorties", "Pour toi",
            fresh.filter { it.reason!!.code == ReasonCode.NEW_FROM_ARTIST || it.reason.code == ReasonCode.NEW_RELEASE }.map(::ext),
        )

        // 2. More of an artist that is played: the library's albums of theirs, then what the sources have.
        val recentArtists = recentAlbumIds.mapNotNull { byId[it]?.artist }.filter { it.isNotBlank() }
        val topArtists = topPlayedAlbumIds.mapNotNull { byId[it]?.artist }.filter { it.isNotBlank() }
        val knownArtistKeys = (recentArtists + topArtists + library.map { it.artist })
            .flatMap { com.ikko.aura.reco.ArtistNames.split(it) + com.ikko.aura.reco.ArtistNames.key(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        val reasonArtists = setOf(ReasonCode.LISTENED_ARTIST, ReasonCode.COLLAB, ReasonCode.LIBRARY_ARTIST, ReasonCode.NEW_FROM_ARTIST)
        val externalByArtist = fresh.filter { it.reason!!.code in reasonArtists && it.reason.subject.isNotBlank() && !isExcludedAlbum(it.album) && !isExcludedAlbum(it.title) }
            .groupBy { it.reason!!.subject.lowercase() }
        val artistOrder = (recentArtists + externalByArtist.values.sortedByDescending { it.size }.map { it.first().reason!!.subject })
            .filter { com.ikko.aura.reco.ArtistNames.key(it) in knownArtistKeys }
            .distinctBy { it.lowercase() }
        var artistRows = 0
        for (artist in artistOrder) {
            if (artistRows >= 2) break
            val own = library.filter { it.artist.equals(artist, ignoreCase = true) }.map(::loc)
            val more = externalByArtist[artist.lowercase()].orEmpty().take(EXTERNAL_PER_ARTIST).map(::ext).filter { !isExcludedAlbum(it.title) }
            val before = out.size
            add("artist:$artist", "Plus de", artist, own.take(MAX_ALBUMS - more.size) + more)
            if (out.size > before) artistRows++
        }

        // 3. Albums close to one that is played.
        // JioSaavn's "to explore" is a loose guess (it offered a Punjabi rapper as close to Charli XCX): only what a source lists as
        // close counts from it; the other sources' explorations are kept.
        fresh.filter {
            (it.reason!!.code == ReasonCode.RELATED_TO || (it.reason.code == ReasonCode.EXPLORATION && it.sourceId != "jiosaavn")) &&
                it.reason.subject.isNotBlank()
        }
            .groupBy { it.reason!!.subject }.entries.sortedByDescending { it.value.size }.take(2)
            .forEach { (seed, list) -> add("similar:$seed", "Plus de contenu similaire à", seed, list.map(::ext)) }

        // 4. What is returned to often.
        add("often", "Tu y reviens", "Souvent", topPlayedAlbumIds.mapNotNull { byId[it] }.map(::loc))

        // 5. A style of the library.
        fresh.filter { it.reason!!.code == ReasonCode.GENRE && it.reason.subject.isNotBlank() }
            .groupBy { it.reason!!.subject }.entries.sortedByDescending { it.value.size }
            .forEach { (genre, list) -> add("genre:$genre", null, genre.replaceFirstChar { it.uppercase() }, list.map(::ext)) }
        return out
    }
}
