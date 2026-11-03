package com.ikko.aura.data

import com.ikko.aura.data.model.Track
import com.ikko.aura.reco.ArtistNames

/** What a file's tag names as artist: the main one, and who is featured. */
data class ArtistCredit(val primary: String, val guests: List<String>)

/** What the device's media index knows about a file beyond [Track]: used only to group the library, never written back. */
data class FileExtras(val albumArtist: String? = null, val dir: String? = null, val disc: Int = 0)

/**
 * The library as the app groups it, whatever the files' tags say: one album when a record is spread over several media-index
 * albums (a featured artist on one song, "CD1" / "CD2" folders, spacing and case), and one artist when an artist field names
 * several. Only the app's own model is touched; no file is ever written.
 *
 * Pure, so it is tested without a device.
 */
object LibraryNormalizer {

    /** "feat." and its likes always name a guest. */
    private val STRONG = Regex(
        "\\s*(?:[(\\[]\\s*)?(?:\\bfeat\\.?|\\bfeaturing\\b|\\bft\\.?|\\bavec\\b|\\bwith\\b)\\s+|\\s*;\\s*",
        RegexOption.IGNORE_CASE,
    )

    /** "&", "x", "/", "," join a duo or a group as often as they list two artists: only split when the first is an artist alone elsewhere. */
    private val WEAK = Regex("\\s*(?:,|&|\\+|×|\\sx\\s|\\s/\\s|/)\\s*", RegexOption.IGNORE_CASE)

    private val DISC_SUFFIX = Regex(
        "\\s*[-–—:]?\\s*[(\\[]?\\s*(?:disc|disk|cd|dvd)\\s*\\.?\\s*(\\d{1,2})\\s*[)\\]]?\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val DISC_DIR = Regex("(?i)[/\\\\](?:cd|disc|disk)\\s*\\.?\\s*\\d{1,2}[/\\\\]?$")
    private val VARIOUS = Regex("(?i)^(?:various(?: artists)?|v\\.?a\\.?|compilation|divers|artistes? divers)$")

    /** The folded form of every artist field that names one artist alone: the names a joined one may be split at. */
    fun standaloneArtists(artistFields: Collection<String>): Set<String> =
        artistFields.filter { !STRONG.containsMatchIn(it) && !WEAK.containsMatchIn(it) }.mapTo(HashSet()) { ArtistNames.key(it) }

    fun parseArtist(raw: String, standalone: Set<String>): ArtistCredit {
        val text = raw.trim().trimEnd(')', ']')
        val strong = STRONG.split(text, limit = 2)
        var main = strong[0].trim().trim('(', '[', '-', ' ').ifEmpty { text }
        val guests = ArrayList<String>()
        if (strong.size > 1) guests += strong[1].split(WEAK).map { it.trim().trimEnd(')', ']') }.filter { it.isNotEmpty() }
        val tokens = main.split(WEAK).map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.size > 1 && ArtistNames.key(tokens[0]) in standalone) {
            guests.addAll(0, tokens.drop(1))
            main = tokens[0]
        }
        return ArtistCredit(main, guests.distinctBy { ArtistNames.key(it) })
    }

    /** The title without a disc marker ("Album (Disc 1)", "Album [CD2]", "Album - CD 1"), cased as written. */
    fun displayTitle(album: String): String = album.replace(DISC_SUFFIX, "").trim().ifEmpty { album.trim() }

    /** The disc a title or a folder names, 0 when none. */
    fun discOf(album: String, dir: String?): Int =
        DISC_SUFFIX.find(album)?.groupValues?.get(1)?.toIntOrNull()
            ?: dir?.let { Regex("(?i)(?:cd|disc|disk)\\s*\\.?\\s*(\\d{1,2})[/\\\\]?$").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0

    private fun folded(s: String) = ArtistNames.key(s).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun dirKey(dir: String?) = dir.orEmpty().replace(DISC_DIR, "/").lowercase()

    /** What grouping needs of a track: its artist field, album, and the media index's ids. */
    data class Row(val id: String, val artist: String, val album: String, val albumId: Long, val artistId: Long)

    /** The group a row belongs to: the album and artist ids it is known by now, and its disc. */
    data class Canon(val albumId: Long, val artistId: Long, val disc: Int)

    fun group(rows: List<Row>, extras: Map<String, FileExtras>): Map<String, Canon> {
        if (rows.isEmpty()) return emptyMap()
        val standalone = standaloneArtists(rows.map { it.artist })
        val primaryOf = rows.associate { it.id to ArtistNames.key(parseArtist(it.artist, standalone).primary) }

        // Compilations: one title, one folder, artists with nothing in common.
        val compilationKeys = HashSet<String>()
        rows.groupBy { folded(displayTitle(it.album)) + "\u0000" + dirKey(extras[it.id]?.dir) }.forEach { (key, group) ->
            val primaries = group.map { primaryOf.getValue(it.id) }
            val top = primaries.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
            if (group.size >= 4 && primaries.distinct().size >= 3 && top <= group.size * 0.6) compilationKeys += key
        }

        fun albumKey(t: Row): String {
            val e = extras[t.id]
            val title = folded(displayTitle(t.album))
            val groupKey = title + "\u0000" + dirKey(e?.dir)
            val albumArtist = e?.albumArtist?.takeIf { it.isNotBlank() && !it.equals("<unknown>", true) }
            val artist = when {
                groupKey in compilationKeys -> "various"
                albumArtist != null && VARIOUS.matches(albumArtist.trim()) -> "various"
                albumArtist != null -> ArtistNames.key(parseArtist(albumArtist, standalone).primary)
                else -> primaryOf.getValue(t.id)
            }
            return "$title\u0000$artist"
        }

        val albumIds = rows.groupBy(::albumKey).mapValues { (_, g) -> g.minOf { it.albumId } }
        val artistIds = rows.groupBy { primaryOf.getValue(it.id) }.mapValues { (_, g) -> g.minOf { it.artistId } }
        return rows.associate { t ->
            t.id to Canon(
                albumId = albumIds[albumKey(t)] ?: t.albumId,
                artistId = artistIds[primaryOf.getValue(t.id)] ?: t.artistId,
                disc = extras[t.id]?.disc?.takeIf { it > 0 } ?: discOf(t.album, extras[t.id]?.dir),
            )
        }
    }

    /**
     * Gives every track the album id and the artist id of its group (the smallest media-index id in it, a real one, so what is
     * keyed on it still resolves), and its disc number.
     */
    fun canonicalise(tracks: List<Track>, extras: Map<String, FileExtras>): List<Track> {
        val canon = group(tracks.map { Row(it.id, it.artist, it.album, it.albumId, it.artistId) }, extras)
        return tracks.map { t ->
            val c = canon[t.id] ?: return@map t
            t.copy(albumId = c.albumId, artistId = c.artistId, discNumber = t.discNumber.takeIf { it > 0 } ?: c.disc)
        }
    }

    /** The name an album goes by: the most common main artist of [artists], or "Artistes divers" for a compilation. */
    fun albumArtistOf(artists: List<String>): String {
        if (artists.isEmpty()) return ""
        val standalone = standaloneArtists(artists)
        val primaries = artists.map { parseArtist(it, standalone).primary }
        val counts = primaries.groupingBy { ArtistNames.key(it) }.eachCount()
        if (artists.size >= 4 && counts.size >= 3 && (counts.values.max()) <= artists.size * 0.6) return "Artistes divers"
        val best = counts.maxByOrNull { it.value }?.key
        return primaries.groupingBy { it }.eachCount().filterKeys { ArtistNames.key(it) == best }.maxByOrNull { it.value }?.key.orEmpty()
    }

    /** The main artist of one artist field, given the library's standalone names. */
    fun primaryOf(raw: String, standalone: Set<String>): String = parseArtist(raw, standalone).primary
}
