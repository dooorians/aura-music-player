package com.ikko.aura.reco

import com.ikko.aura.data.deezer.DeezerClient
import com.ikko.aura.lastfm.LastFm
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Where the pipeline asks what can be measured of the songs it may offer (see [MoodJudge]). */
interface SongFeatureSource {
    /** The features of [songs] by their identity, as many as could be found within [budgetMs]; songs nothing is known of are not in the answer. */
    suspend fun featuresOf(songs: List<Candidate>, budgetMs: Long): Map<String, SongFeatures>
}

/**
 * Asks Deezer for a song's tempo and loudness and Last.fm for the tags listeners gave it, and keeps the answer on the device: a song is looked up once
 * (for [FOUND_TTL_MS] when it was found, [MISSING_TTL_MS] when it was not), the next renewal only asks for the new ones.
 */
@Singleton
class SongFeatureService @Inject constructor(
    private val dao: TrackMetaDao,
    private val deezer: DeezerClient,
    private val lastFm: LastFm,
) : SongFeatureSource {

    override suspend fun featuresOf(songs: List<Candidate>, budgetMs: Long): Map<String, SongFeatures> {
        val out = ConcurrentHashMap<String, SongFeatures>()
        val wanted = songs.distinctBy { it.identity }
        val now = System.currentTimeMillis()
        val kept = wanted.map { it.identity }.chunked(400).flatMap { runCatching { dao.songFeatures(it) }.getOrDefault(emptyList()) }.associateBy { it.identity }
        val todo = ArrayList<Candidate>()
        for (c in wanted) {
            val row = kept[c.identity]
            val fresh = row != null && now - row.fetchedAt < if (row.found) FOUND_TTL_MS else MISSING_TTL_MS
            if (!fresh) todo += c else if (row!!.found) out[c.identity] = row.toFeatures()
        }
        val learned = Collections.synchronizedList(ArrayList<SongFeaturesEntity>())
        withTimeoutOrNull(budgetMs) {
            val gate = Semaphore(4)
            coroutineScope {
                todo.map { c ->
                    async {
                        gate.withPermit {
                            var failed = false
                            val d = try { deezer.trackFeatures(c.title, c.artist) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { failed = true; null }
                            val tags = try { lastFm.trackTags(c.title, c.artist) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }.orEmpty()
                            val f = SongFeatures(bpm = d?.bpm?.takeIf { it > 0.0 } ?: c.bpm, gainDb = d?.gainDb, trackTags = tags)
                            val found = d != null || tags.isNotEmpty()
                            if (found) out[c.identity] = f
                            // A network failure is not an answer: it is not kept as one.
                            if (!failed) learned += SongFeaturesEntity(c.identity, f.bpm ?: 0.0, f.gainDb, tags.joinToString("\u0000"), found, System.currentTimeMillis())
                        }
                    }
                }.awaitAll()
            }
        }
        if (learned.isNotEmpty()) runCatching { dao.putSongFeatures(learned.toList()) }
        return out
    }

    private fun SongFeaturesEntity.toFeatures() =
        SongFeatures(bpm.takeIf { it > 0.0 }, gainDb, trackTags.split('\u0000').filter { it.isNotEmpty() })

    private companion object {
        const val FOUND_TTL_MS = 120L * 86_400_000L
        const val MISSING_TTL_MS = 14L * 86_400_000L
    }
}
