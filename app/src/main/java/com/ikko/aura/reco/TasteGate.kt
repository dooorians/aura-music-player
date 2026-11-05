package com.ikko.aura.reco

import java.text.Normalizer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** What an outside catalogue (Last.fm) knows about artists. Null from any call: could not tell (no network, no key). */
interface ArtistInsight {
    /** The artist's tags, most typical first; empty when the catalogue does not know the artist. */
    suspend fun tags(name: String): List<String>?

    /** Artists the catalogue lists as close to [name], closest first; empty when it does not know. */
    suspend fun similar(name: String): List<String>?

    /** The listener's own most played artists on the catalogue, most played first; empty when not connected. */
    suspend fun listenerTopArtists(): List<String>

    /** The artist's MusicBrainz ID as reported by Last.fm (artist.getInfo / artist.getCorrection). */
    suspend fun mbid(name: String): String? = null
}

/**
 * Keeps out of the candidates what has nothing to do with the listener's taste, which names alone cannot tell: an artist
 * called nearly like a loved one ("Angele" for "Angèle"), a catalogue's regional music the listener never plays.
 *
 * A candidate's artist is removed only for a clear reason: a regional music the listener never plays, a name that is another artist's
 * with the accents changed. Where the name only differs by accents from a known artist, it is taken for another artist.
 * When the catalogue cannot be asked, nothing is removed (but an ambiguous name, which is not worth the risk).
 */
class TasteGate(private val insight: ArtistInsight, private val profile: TasteProfile, private val blockedNames: List<String> = emptyList()) {

    private var tasteTags: Map<String, Double> = emptyMap()
    private var similarKeys: Set<String> = emptySet()
    /** Artist key → how many of the listener's top artists Last.fm lists it as close to. */
    private var similarCount: Map<String, Int> = emptyMap()
    /** Artist key → how many blocked artists Last.fm lists it as close to: what resembles what the listener refused. */
    private var blockedCount: Map<String, Int> = emptyMap()
    private val cache = HashMap<String, List<String>?>()

    private suspend fun tagsOf(name: String): List<String>? {
        // By the name as written: "Angele" and "Angèle" are two entries here.
        val k = fold(name)
        if (cache.containsKey(k)) return cache[k]
        val t = withTimeoutOrNull(6_000L) { insight.tags(name) }
        return t.also { cache[k] = it }
    }

    suspend fun apply(pool: List<Candidate>): List<Candidate> = coroutineScope {
        val top = profile.topArtists(10)
        if (top.isEmpty()) return@coroutineScope pool
        val gate = Semaphore(5)
        val weights = HashMap<String, Double>()
        top.map { key ->
            async { gate.withPermit { profile.nameOf(key).let { n -> key to tagsOf(n) } } }
        }.awaitAll().forEach { (key, tags) ->
            tags?.let { weightedTags(it).forEach { (t, w) -> weights.merge(t, w * profile.affinity(key), Double::plus) } }
        }
        // Without the listener's own tags, only what resembles the blocked can still be told.
        if (weights.isEmpty() && blockedNames.isEmpty()) return@coroutineScope pool
        tasteTags = weights
        val lists = top.take(8).map { k ->
            async { gate.withPermit { withTimeoutOrNull(6_000L) { insight.similar(profile.nameOf(k)) }.orEmpty().map { ArtistNames.key(it) }.toSet() } }
        }.awaitAll()
        similarKeys = lists.flatten().toHashSet()
        similarCount = lists.flatten().groupingBy { it }.eachCount()

        // What the listener blocked tells what not to propose: whoever Last.fm gives as close to blocked artists goes with them.
        blockedCount = blockedNames.take(MAX_BLOCKED_LOOKED_UP).map { n ->
            async { gate.withPermit { withTimeoutOrNull(6_000L) { insight.similar(n) }.orEmpty().take(25).map { ArtistNames.key(it) }.toSet() } }
        }.awaitAll().flatten().groupingBy { it }.eachCount()

        // The artists to judge: the primary one of each candidate, but not the listener's loved ones (unless the name is ambiguous).
        val judged = pool.map { it.primary() }.distinctBy { fold(it.name) }.filter { it.key.isNotEmpty() }
            .filter { profile.affinity(it.key) < LOVED || ambiguous(it) }
            .sortedByDescending { profile.affinity(it.key) }.take(MAX_JUDGED)
        val verdict = judged.map { a -> async { gate.withPermit { fold(a.name) to allows(a) } } }.awaitAll().toMap()
        val kept = pool.filter { verdict[fold(it.primary().name)] != false }
        closeToSeed(kept, gate)
    }

    /**
     * A song found only because a source lists it "close to" one of the listener's (an artist's song) has to be close for
     * Last.fm too: its artist is listed as similar to the seed's, or shares its tags. Sources' lists are wide ("similar to REOL"
     * gave pop of any country); the ones that cannot be told apart from the seed's sound are not worth a row.
     */
    private suspend fun closeToSeed(pool: List<Candidate>, gate: Semaphore): List<Candidate> = coroutineScope {
        fun seedOf(c: Candidate): String? =
            c.provenance.takeIf { it.isNotEmpty() && it.all { p -> p.kind == ProvKind.RELATED_TRACK } }?.firstOrNull()?.seed
        val seeds = pool.mapNotNull { seedOf(it) }.distinct().take(MAX_SEEDS)
        if (seeds.isEmpty()) return@coroutineScope pool
        val similarOf = seeds.map { sd -> async { gate.withPermit { sd to withTimeoutOrNull(6_000L) { insight.similar(ArtistNames.split(sd).firstOrNull()?.let { profile.names[it] } ?: sd) } } } }
            .awaitAll().toMap()
        val seedTags = seeds.map { sd -> async { gate.withPermit { sd to tagsOf(profile.names[ArtistNames.split(sd).firstOrNull()].orEmpty().ifEmpty { sd }) } } }
            .awaitAll().toMap()
        val artists = pool.filter { seedOf(it) != null }.map { it.primary() }.distinctBy { fold(it.name) }.take(MAX_JUDGED)
        val verdict = artists.map { a -> async { gate.withPermit { fold(a.name) to tagsOf(a.name) } } }.awaitAll().toMap()
        pool.filter { c ->
            val sd = seedOf(c) ?: return@filter true
            val a = c.primary()
            if (a.key == ArtistNames.split(sd).firstOrNull()) return@filter true
            val similar = similarOf[sd] ?: return@filter true
            val theirs = seedTags[sd] ?: return@filter true
            if (similar.isEmpty() && theirs.isEmpty()) return@filter true
            val mine = verdict[fold(a.name)] ?: return@filter true
            if (mine.isEmpty()) return@filter true
            // Close to one artist is not enough: tags cannot tell Angèle's French neighbours from the listener's own taste (they share
            // "indie pop" and "electropop"). A stranger has to be close to several of the artists the listener plays most.
            if (profile.affinity(a.key) < 0.2 && similarCount.isNotEmpty()) {
                return@filter (similarCount[a.key] ?: 0) >= CONSENSUS
            }
            if (ArtistNames.key(a.name) in similar.map { ArtistNames.key(it) }) return@filter true
            // Only what says a style counts: "pop" is on nearly every artist, and made a Swedish singer close to Rina Sawayama.
            cosine(specific(mine), specific(theirs)) >= CLOSE
        }
    }

    private class Artist(val name: String) { val key: String = ArtistNames.key(name) }
    private fun Candidate.primary() = Artist(ArtistNames.primary(artist, beforeAmpersand = true))

    /** The name is the key of a known artist but is written differently: the accents were what told two artists apart. */
    private fun ambiguous(a: Artist): Boolean {
        val known = profile.names[a.key] ?: return false
        return fold(known) != fold(a.name) && profile.affinity(a.key) > 0.0
    }

    private fun fold(s: String) = Normalizer.normalize(s.trim(), Normalizer.Form.NFC).lowercase()

    private suspend fun allows(a: Artist): Boolean {
        // Close to what was blocked, and not closer to what is loved: it goes.
        val refused = blockedCount[a.key] ?: 0
        if (refused > 0 && refused >= (similarCount[a.key] ?: 0) && profile.affinity(a.key) < 0.2) return false
        if (a.key in similarKeys && !ambiguous(a)) return true
        val tags = tagsOf(a.name)
        // Last.fm itself merges the two spellings (it gives "Angele" the tags of "Angèle"), so tags cannot tell them apart:
        // a name that only differs by its accents from an artist the listener plays is taken for another one.
        if (ambiguous(a)) return false
        if (tags == null) return true
        if (tags.isEmpty()) return true
        val regional = tags.map { it.lowercase() }.take(6).any { it in REGIONAL } &&
            tasteTags.keys.none { it in REGIONAL }
        if (regional) return false
        // Anything else is kept: a style the listener has not met yet is what a discovery is for.
        return true
    }

    private fun weightedTags(tags: List<String>): Map<String, Double> =
        tags.map { it.lowercase().trim() }.filter { it.isNotEmpty() && it !in NOISE }.take(8)
            .mapIndexed { i, t -> t to 1.0 / (i + 1) }.toMap()

    /** The listener's taste as specific tags: their top artists' tags, weighted by affinity, without the generic ones. */
    private fun tasteVector(): Map<String, Double> =
        tasteTags.filterKeys { it !in NOISE && it !in GENERIC }

    private fun specific(tags: List<String>): Map<String, Double> =
        tags.map { it.lowercase().trim() }.filter { it.isNotEmpty() && it !in NOISE && it !in GENERIC }.take(6)
            .mapIndexed { i, t -> t to 1.0 / (i + 1) }.toMap()

    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dot = a.entries.sumOf { (t, w) -> w * (b[t] ?: 0.0) }
        val na = kotlin.math.sqrt(a.values.sumOf { it * it })
        val nb = kotlin.math.sqrt(b.values.sumOf { it * it })
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (na * nb)
    }

    companion object {
        const val CLOSE = 0.25
        /** How many of the listener's top artists a stranger has to be close to, to be offered as "similar". */
        const val CONSENSUS = 2
        const val MAX_BLOCKED_LOOKED_UP = 15
        const val MAX_SEEDS = 8
        const val LOVED = 0.5
        const val MAX_JUDGED = 80

        /** Tags that say nothing about a sound. */
        val NOISE = setOf("seen live", "favorites", "favourites", "favorite", "albums i own", "under 2000 listeners", "all", "male vocalists", "female vocalists")

        /** Tags too wide to say a sound. */
        val GENERIC = setOf("pop", "rock", "electronic", "alternative", "indie", "rap", "hip-hop", "hip hop", "rnb", "r&b", "soul", "dance", "experimental",
            // Where an artist is from says nothing of how it sounds: Clara Luciani is not like Angèle for being French too.
            "french", "belgian", "belgium", "japanese", "korean", "swedish", "norwegian", "german", "british", "american", "uk", "usa", "english", "spanish", "italian", "canadian", "australian", "scandinavian", "female vocalists")

        /** Music of a region or a language: not a style, and not something to offer to one who never plays it. */
        val REGIONAL = setOf(
            "bollywood", "indian", "hindi", "punjabi", "tamil", "telugu", "desi", "bhangra", "kollywood", "tollywood",
            "malayalam", "bengali", "india", "marathi", "gujarati", "pakistani", "indian music", "filmi",
        )
    }
}
