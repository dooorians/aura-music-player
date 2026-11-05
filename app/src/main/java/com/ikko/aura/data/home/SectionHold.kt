package com.ikko.aura.data.home

import com.ikko.aura.reco.AlbumKeys
import com.ikko.aura.reco.ArtistNames

/**
 * The rows of Home as the listener first saw them, kept still: nothing in them moves because something was played, a style was
 * found or a chart came in. They change for two reasons only, and only where it applies: an artist the listener blocked, or an album
 * they refused, goes from every row, and the row is made up again with what the arrangement of the moment offers for the same row.
 */
object SectionHold {

    /** Whether a tile is of a blocked artist, or of an album refused as a whole. */
    fun gone(a: SectionAlbum, blocked: Set<String>, refused: Set<String>): Boolean {
        if (blocked.isNotEmpty()) {
            if (ArtistNames.split(a.artist).any { it in blocked }) return true
            if (a.isArtist && ArtistNames.key(a.title) in blocked) return true
            if (a.blockableArtist != null && ArtistNames.key(a.blockableArtist) in blocked) return true
        }
        if (refused.isNotEmpty() && !a.isArtist && !a.playsItems) {
            if (AlbumKeys.ofName(a.title, a.artist) in refused) return true
            if (a.ref != null && AlbumKeys.ofRef(a.ref) in refused) return true
        }
        return false
    }

    /**
     * The row "En boucle" brought up to date: [loop] (what is looped now) takes the place of the one held, without the albums that show in another
     * row, completed from its own reserve. Nothing changes when there is not a full line of it: a row held is better than a row cut short.
     * Called when Home is out of sight, so that the row never moves under the finger.
     */
    fun withLoop(held: List<HomeSection>, loop: HomeSection?): List<HomeSection> {
        if (loop == null) return held
        val shown = held.filter { it.id != loop.id }.flatMapTo(HashSet()) { s -> s.albums.map { it.key } }
        val albums = (loop.albums + loop.spare).filter { it.key !in shown }.distinctBy { it.key }.take(HomeSections.PER_ROW)
        if (albums.size < HomeSections.PER_ROW) return held
        val fresh = loop.copy(albums = albums, spare = loop.spare.filter { sp -> albums.none { it.key == sp.key } })
        return if (held.any { it.id == loop.id }) held.map { if (it.id == loop.id) fresh else it } else held
    }

    /** Whether a row is about an artist that is now blocked ("Plus de X", "similar to X"): it goes with them, whatever else it holds. */
    private fun aboutBlockedArtist(s: HomeSection, blocked: Set<String>): Boolean =
        blocked.isNotEmpty() && (s.id.startsWith("artist:") || s.id.startsWith("similar:")) && ArtistNames.key(s.title) in blocked

    /**
     * A row that loses albums is made up again, and **stays**: it is kept with what it still has even when nothing can take the place of
     * what went (a row short of one line is better than a row, and its title, vanishing for one refused album). It goes only when it is
     * about the artist that was blocked, or when nothing of it is left.
     */
    fun reconcile(
        held: List<HomeSection>,
        live: List<HomeSection>,
        blocked: Set<String>,
        refused: Set<String>,
        /** Everything that fits a row (by its id), asked only for a row that lost an album and has no reserve left. */
        poolOf: (String) -> List<SectionAlbum> = { emptyList() },
    ): List<HomeSection> {
        if (blocked.isEmpty() && refused.isEmpty()) return held
        val taken = HashSet<String>()
        held.forEach { s -> s.albums.forEach { if (!gone(it, blocked, refused)) taken += it.key } }
        return held.mapNotNull { s ->
            if (s.albums.none { gone(it, blocked, refused) }) return@mapNotNull s
            if (aboutBlockedArtist(s, blocked)) return@mapNotNull null
            val kept = s.albums.filterNot { gone(it, blocked, refused) }
            // What takes its place: the same row in the arrangement of the moment, an album not yet shown anywhere.
            fun candidates(from: List<SectionAlbum>) = from.distinctBy { it.key }
                .filter { !gone(it, blocked, refused) && it.key !in taken && kept.none { k -> k.key == it.key } }
            var more = candidates(live.firstOrNull { it.id == s.id }?.let { it.albums + it.spare }.orEmpty() + s.spare)
            // The reserve has not enough: the row's theme is asked again from the whole pool.
            if (kept.size + more.size < HomeSections.PER_ROW) more = candidates(more + poolOf(s.id))
            val row = (kept + more).take(HomeSections.PER_ROW)
            if (row.isEmpty()) null
            else s.copy(albums = row, spare = s.spare.filter { sp -> row.none { it.key == sp.key } }).also { r -> r.albums.forEach { taken += it.key } }
        }
    }
}
