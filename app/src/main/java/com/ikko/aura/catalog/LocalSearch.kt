package com.ikko.aura.catalog

import com.ikko.aura.data.model.Album
import com.ikko.aura.data.model.Artist
import com.ikko.aura.data.model.Track
import java.text.Normalizer
import java.util.Locale

/**
 * The library's search, without Android in it: the FTS query a typed text becomes, and what the index's hits
 * become on screen.
 *
 * The rule is the same on both sides: every word typed must begin a word of the field, case and accents aside.
 * "beat" finds "The Beatles", "edith piaf" finds "Édith Piaf", "AC/DC" finds "AC/DC".
 */
object LocalSearch {

    /** More words than this add nothing a listener would type, and only cost the index a longer intersection. */
    private const val MAX_TERMS = 8

    data class Results(val tracks: List<Track>, val albums: List<Album>, val artists: List<Artist>) {
        val isEmpty: Boolean get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty()
    }

    /**
     * The words of [raw], lowercased, split where unicode61 splits: on anything that is not a letter or a digit.
     * What is left is only letters and digits, so nothing of it can be read as FTS syntax (quotes, `*`, `-`, `:`,
     * parentheses), and lowercase keeps "OR", "AND", "NOT" and "NEAR" plain words.
     */
    fun terms(raw: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            if (Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) {
                word.appendCodePoint(cp)
            } else if (word.isNotEmpty()) {
                out += word.toString().lowercase(Locale.ROOT)
                word.setLength(0)
            }
            i += Character.charCount(cp)
        }
        if (word.isNotEmpty()) out += word.toString().lowercase(Locale.ROOT)
        return out.distinct().take(MAX_TERMS)
    }

    /** The MATCH expression for [raw]: each word as a prefix, all of them required. Null when there is no word. */
    fun matchExpression(raw: String): String? {
        val terms = terms(raw)
        if (terms.isEmpty()) return null
        return terms.joinToString(" ") { "$it*" }
    }

    /** Lowercase, without accents: what unicode61's remove_diacritics leaves of a Latin word. */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase(Locale.ROOT)

    private val DIACRITICS = Regex("\\p{Mn}+")

    /** Whether every one of [terms] begins a word of [text], case and accents aside. */
    fun startsWords(text: String, terms: List<String>): Boolean {
        if (terms.isEmpty()) return false
        val words = terms(fold(text))
        return terms.all { term -> val t = fold(term); words.any { it.startsWith(t) } }
    }

    /**
     * What the screen lists for [hits] from the index: tracks in the library's own order, then the albums and
     * artists of the matching tracks. Albums and artists are named on screen by their grouped spelling (an album
     * by its most common title, an artist by the main name of "A feat. B"), so each is checked against that name:
     * a song of A featuring B must not list A when B was searched.
     */
    fun fromHits(
        query: String,
        hits: List<SearchHit>,
        tracks: List<Track>,
        albums: List<Album>,
        artists: List<Artist>,
        limits: Limits,
    ): Results {
        val terms = terms(query)
        val trackKeys = HashSet<String>()
        val albumKeys = HashSet<String>()
        val artistKeys = HashSet<String>()
        for (hit in hits) {
            when (hit.field) {
                TrackSearchSql.TITLE -> trackKeys += hit.trackKey
                TrackSearchSql.ARTIST -> { trackKeys += hit.trackKey; albumKeys += hit.trackKey; artistKeys += hit.trackKey }
                TrackSearchSql.ALBUM -> albumKeys += hit.trackKey
            }
        }
        val foundTracks = if (limits.tracks == 0) emptyList() else tracks.asSequence().filter { it.id in trackKeys }.take(limits.tracks).toList()

        val foundAlbums = if (limits.albums == 0 || albumKeys.isEmpty()) emptyList() else {
            val ids = tracks.asSequence().filter { it.id in albumKeys }.mapTo(HashSet()) { it.albumId }
            albums.asSequence()
                .filter { it.id in ids && (startsWords(it.title, terms) || startsWords(it.artist, terms)) }
                .take(limits.albums).toList()
        }

        val foundArtists = if (limits.artists == 0 || artistKeys.isEmpty()) emptyList() else {
            val ids = tracks.asSequence().filter { it.id in artistKeys }.mapTo(HashSet()) { it.artistId }
            artists.asSequence().filter { it.id in ids && startsWords(it.name, terms) }.take(limits.artists).toList()
        }
        return Results(foundTracks, foundAlbums, foundArtists)
    }

    /**
     * The search without the index, for when it cannot answer or finds nothing: a part of a word ("eatles") is then
     * still found, as it always was, and now with accents and case aside too.
     */
    fun scan(query: String, tracks: List<Track>, albums: List<Album>, artists: List<Artist>, limits: Limits): Results {
        val needle = fold(query.trim())
        if (needle.isEmpty()) return Results(emptyList(), emptyList(), emptyList())
        fun has(text: String) = fold(text).contains(needle)
        return Results(
            tracks = if (limits.tracks == 0) emptyList() else tracks.asSequence().filter { has(it.title) || has(it.artist) }.take(limits.tracks).toList(),
            albums = if (limits.albums == 0) emptyList() else albums.asSequence().filter { has(it.title) || has(it.artist) }.take(limits.albums).toList(),
            artists = if (limits.artists == 0) emptyList() else artists.asSequence().filter { has(it.name) }.take(limits.artists).toList(),
        )
    }

    /** How many of each the screen shows; 0 for a kind the filter hides. */
    data class Limits(val tracks: Int, val albums: Int, val artists: Int)
}
