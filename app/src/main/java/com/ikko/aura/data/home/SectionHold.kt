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

    fun reconcile(held: List<HomeSection>, live: List<HomeSection>, blocked: Set<String>, refused: Set<String>): List<HomeSection> {
        if (blocked.isEmpty() && refused.isEmpty()) return held
        val taken = HashSet<String>()
        held.forEach { s -> s.albums.forEach { if (!gone(it, blocked, refused)) taken += it.key } }
        return held.mapNotNull { s ->
            if (s.albums.none { gone(it, blocked, refused) }) return@mapNotNull s
            val kept = s.albums.filterNot { gone(it, blocked, refused) }
            // What takes its place: the same row in the arrangement of the moment, an album not yet shown anywhere.
            val more = (live.firstOrNull { it.id == s.id }?.let { it.albums + it.spare }.orEmpty() + s.spare)
                .distinctBy { it.key }
                .filter { !gone(it, blocked, refused) && it.key !in taken && kept.none { k -> k.key == it.key } }
            val row = (kept + more).take(HomeSections.PER_ROW)
            if (row.size < HomeSections.PER_ROW) null
            else s.copy(albums = row, spare = s.spare.filter { sp -> row.none { it.key == sp.key } }).also { r -> r.albums.forEach { taken += it.key } }
        }
    }
}
