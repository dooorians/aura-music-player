package com.ikko.aura.reco.authority

import com.ikko.aura.data.deezer.DeezerClient
import com.ikko.aura.data.musicbrainz.MusicBrainzArtist
import com.ikko.aura.data.musicbrainz.MusicBrainzClient
import com.ikko.aura.data.deezer.DeezerArtistProfile
import com.ikko.aura.reco.ArtistNames
import com.ikko.aura.reco.TrackIdentity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class DisambiguatedArtist(
    val canonicalKey: String,
    val canonicalName: String,
    val mbid: String?,
    val deezerId: Long?,
    val country: String?,
    val primaryTags: Set<String>,
    val disambiguation: String? = null,
)

@Singleton
class ArtistAuthority(
    private val mbClient: MusicBrainzClient,
    private val deezerClient: DeezerClient,
    private val lastFmTagsProvider: (suspend (String) -> List<String>)? = null,
    private val lastFmMbidProvider: (suspend (String) -> String?)? = null,
    /** The titles of an artist's most played songs on Last.fm: what tells two artists of the same name apart. */
    private val lastFmTopTracksProvider: (suspend (String) -> List<String>)? = null,
    /** The identities settled when the listener chose their artists: asked first, before anything is guessed by name. */
    private val identities: ArtistIdentityStore? = null,
) {
    @Inject
    constructor(
        mbClient: MusicBrainzClient,
        deezerClient: DeezerClient,
        lastFm: com.ikko.aura.lastfm.LastFm,
        identities: ArtistIdentityPrefs,
    ) : this(
        mbClient = mbClient,
        deezerClient = deezerClient,
        lastFmTagsProvider = { lastFm.tags(it).orEmpty() },
        lastFmMbidProvider = { lastFm.mbid(it) },
        lastFmTopTracksProvider = { lastFm.topTracks(it).orEmpty() },
        identities = identities,
    )
    private val cache = ConcurrentHashMap<String, DisambiguatedArtist>()

    suspend fun resolve(
        name: String,
        listenerContextTags: Set<String> = emptySet(),
    ): DisambiguatedArtist? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val key = ArtistNames.key(clean)
        if (key.isEmpty()) return null

        cache[key]?.let { return it }
        // The artist the listener chose, settled then: not guessed again.
        identities?.get(key)?.let { cache[key] = it; return it }

        // 1. Gather external signals: Last.fm MBID and top tags
        val lastFmMbid = runCatching { lastFmMbidProvider?.invoke(clean) }.getOrNull()
        val lastFmTags = runCatching { lastFmTagsProvider?.invoke(clean) }.getOrNull().orEmpty()
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

        val allContextTags = (lastFmTags + listenerContextTags.map { it.trim().lowercase() }).toSet()

        // 2. Query MusicBrainz candidates
        val mbCandidates = runCatching { mbClient.searchArtist(clean) }.getOrDefault(emptyList())

        // Score MusicBrainz candidates
        val bestMb = mbCandidates.maxByOrNull { cand ->
            scoreMbCandidate(cand, clean, key, lastFmMbid, allContextTags)
        }

        // 3. Deezer: the artist of that name, among the namesakes
        val bestDeezer = deezerArtist(clean)

        val canonicalName = bestMb?.name ?: bestDeezer?.name ?: clean
        val combinedTags = LinkedHashSet<String>()
        if (bestMb != null) {
            combinedTags += bestMb.tags.sortedByDescending { it.second }.map { it.first.lowercase() }.take(8)
        }
        combinedTags += lastFmTags

        val resolved = DisambiguatedArtist(
            canonicalKey = ArtistNames.key(canonicalName),
            canonicalName = canonicalName,
            mbid = bestMb?.mbid ?: lastFmMbid,
            deezerId = bestDeezer?.id,
            country = bestMb?.country,
            primaryTags = combinedTags,
            disambiguation = bestMb?.disambiguation,
        )

        cache[key] = resolved
        return resolved
    }

    /**
     * Settles who [name] is, once and for all: looked up afresh with every signal there is (Last.fm, MusicBrainz, Deezer), then kept, so that
     * nothing that follows guesses by name again. Called when the listener chooses an artist. Null (nothing kept) when no catalogue knows them.
     */
    suspend fun pin(name: String, listenerContextTags: Set<String> = emptySet()): DisambiguatedArtist? {
        val key = ArtistNames.key(name.trim())
        if (key.isEmpty()) return null
        identities?.remove(key)
        cache.remove(key)
        deezerCache.remove(key)
        val resolved = resolve(name, listenerContextTags) ?: return null
        if (resolved.deezerId == null && resolved.mbid.isNullOrBlank()) return null
        identities?.put(key, resolved)
        return resolved
    }

    /** Whether [name] is an artist the listener chose and whose identity is settled. */
    fun isPinned(name: String): Boolean = identities?.get(ArtistNames.key(name.trim())) != null

    private class DeezerPick(val artist: DeezerArtistProfile?)
    private val deezerCache = ConcurrentHashMap<String, DeezerPick>()

    /**
     * The Deezer artist of [name]. Deezer lists namesakes side by side (two "F5ve": a rapper and a pop group, "Yena" the singer and a
     * band); the first answer, or the most followed, is not proof. Among those that carry exactly the name, the one whose most played
     * songs are the ones Last.fm gives for the name wins; one with no songs at all (a dead profile) comes after one with songs; the
     * fans only break a tie. One artist alone is taken as it is.
     */
    suspend fun deezerArtist(name: String): DeezerArtistProfile? {
        val clean = name.trim()
        val key = ArtistNames.key(clean)
        if (key.isEmpty()) return null
        deezerCache[key]?.let { return it.artist }
        identities?.get(key)?.deezerId?.let { id ->
            val pinned = DeezerArtistProfile(id = id, name = identities.get(key)?.canonicalName ?: clean)
            deezerCache[key] = DeezerPick(pinned)
            return pinned
        }
        val found = runCatching { deezerClient.searchArtist(clean, limit = 8) }.getOrDefault(emptyList())
        val matching = found.filter {
            val candKey = ArtistNames.key(it.name)
            candKey == key || ArtistNames.compatible(candKey, key)
        }
        val pool = matching.ifEmpty { found }
        // Prefer exact accent and exact case/name matches if available
        val exactAccents = pool.filter { it.name.trim().equals(clean, ignoreCase = true) && !hasAccentMismatch(clean, it.name.trim()) }
        val candidates = exactAccents.ifEmpty { pool }
        val sameName = candidates.filter { ArtistNames.key(it.name) == key }
        val best = if (sameName.size > 1) {
            val wanted = runCatching { lastFmTopTracksProvider?.invoke(clean) }.getOrNull().orEmpty().mapTo(HashSet()) { TrackIdentity.baseKey(it) }
            sameName.map { cand ->
                val top = runCatching { deezerClient.getArtistTopTracks(cand.id, limit = 25) }.getOrDefault(emptyList())
                val titles = top.mapTo(HashSet()) { TrackIdentity.baseKey(it.title) }
                val overlap = titles.count { it in wanted }
                cand to (overlap * 1000.0 + (if (top.isNotEmpty()) 200.0 else 0.0) + Math.log(cand.nbFan + 1.0) * 10.0)
            }.maxByOrNull { it.second }?.first
        } else candidates.maxByOrNull { it.nbFan }
        // An answer that did not come (no network, a quota) is not kept: the next ask tries again.
        if (found.isNotEmpty()) deezerCache[key] = DeezerPick(best)
        return best
    }

    private fun scoreMbCandidate(
        cand: MusicBrainzArtist,
        rawName: String,
        key: String,
        lastFmMbid: String?,
        contextTags: Set<String>,
    ): Int {
        var score = cand.score // base score 0..100 from MusicBrainz search

        // Exact MBID match from Last.fm gives overwhelming priority
        if (!lastFmMbid.isNullOrEmpty() && cand.mbid.equals(lastFmMbid, ignoreCase = true)) {
            score += 500
        }

        // Name match
        val candKey = ArtistNames.key(cand.name)
        if (candKey == key) {
            score += 50
        } else if (cand.aliases.any { ArtistNames.key(it) == key }) {
            score += 40
        } else if (ArtistNames.compatible(candKey, key)) {
            score += 25
        }

        // Tag overlap with Last.fm tags or listener context tags
        val candTagNames = cand.tags.map { it.first.lowercase() }.toSet()
        val tagOverlap = candTagNames.intersect(contextTags).size
        score += tagOverlap * 30

        // Disambiguation hint bonus
        if (!cand.disambiguation.isNullOrBlank()) {
            val disLower = cand.disambiguation.lowercase()
            if (contextTags.any { t -> disLower.contains(t) }) {
                score += 25
            }
        }

        // Country correlation
        if (cand.country == "KR" && (contextTags.contains("k-pop") || contextTags.contains("korean"))) {
            score += 40
        } else if (cand.country == "JP" && (contextTags.contains("j-pop") || contextTags.contains("japanese") || contextTags.contains("anime"))) {
            score += 40
        } else if (cand.country == "FR" && (contextTags.contains("french") || contextTags.contains("chanson francaise"))) {
            score += 40
        }

        return score
    }

    companion object {
        fun hasAccentMismatch(s1: String, s2: String): Boolean {
            val n1 = java.text.Normalizer.normalize(s1.trim(), java.text.Normalizer.Form.NFD)
            val n2 = java.text.Normalizer.normalize(s2.trim(), java.text.Normalizer.Form.NFD)
            val accents1 = n1.filter { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            val accents2 = n2.filter { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            return accents1 != accents2
        }
    }
}
