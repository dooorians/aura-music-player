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
    private val previews: PreviewAnalyzer,
) : SongFeatureSource {

    override suspend fun featuresOf(songs: List<Candidate>, budgetMs: Long): Map<String, SongFeatures> {
        val out = ConcurrentHashMap<String, SongFeatures>()
        val wanted = songs.distinctBy { it.identity }
        val now = System.currentTimeMillis()
        val kept = wanted.map { it.identity }.chunked(400).flatMap { runCatching { dao.songFeatures(it) }.getOrDefault(emptyList()) }.associateBy { it.identity }
        val todo = ArrayList<Candidate>()
        for (c in wanted) {
            val row = kept[c.identity]
            // A song found before the previews were listened to is looked up again, to hear its preview.
            val fresh = row != null && now - row.fetchedAt < (if (row.found) FOUND_TTL_MS else MISSING_TTL_MS) && !(row.found && row.probed == 0 && row.felt == 0)
            if (!fresh) todo += c else if (row!!.found) out[c.identity] = row.toFeatures()
        }
        val learned = Collections.synchronizedList(ArrayList<SongFeaturesEntity>())
        withTimeoutOrNull(budgetMs) {
            val gate = Semaphore(4)
            // Listening to a preview is the heavy part (download, decode, analysis): two at a time.
            val listening = Semaphore(2)
            coroutineScope {
                todo.map { c ->
                    async {
                        gate.withPermit {
                            var failed = false
                            val d = try { deezer.trackFeatures(c.title, c.artist) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { failed = true; null }
                            val tags = try { lastFm.trackTags(c.title, c.artist) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }.orEmpty()
                            val url = d?.previewUrl
                            val heard = if (url != null) listening.withPermit { try { previews.listen(url) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null } } else null
                            val probe = heard?.probe
                            val feel = heard?.feel
                            val f = SongFeatures(bpm = d?.bpm?.takeIf { it > 0.0 } ?: c.bpm, gainDb = d?.gainDb, trackTags = tags, probe = probe, feel = feel)
                            val found = d != null || tags.isNotEmpty()
                            if (found) out[c.identity] = f
                            // A network failure is not an answer: it is not kept as one.
                            if (!failed) learned += SongFeaturesEntity(
                                c.identity, f.bpm ?: 0.0, f.gainDb, tags.joinToString("\u0000"), found, System.currentTimeMillis(),
                                probeBpm = probe?.bpm ?: 0.0, probeConf = probe?.beatConfidence ?: 0.0, probeRmsDb = probe?.rmsDb ?: 0.0,
                                probeLow = probe?.lowShare ?: 0.0, probeVar = probe?.variability ?: 0.0, probed = if (probe != null) 1 else 2,
                                feelEnergy = feel?.energy ?: 0.0, feelAcoustic = feel?.acousticness ?: 0.0, feelValence = feel?.valence ?: 0.0,
                                feelDance = feel?.danceability ?: 0.0, feelLoud = feel?.loudness ?: 0.0, feelTempo = feel?.tempo ?: 0.0, felt = if (feel != null) 1 else 2,
                            )
                        }
                    }
                }.awaitAll()
            }
        }
        if (learned.isNotEmpty()) runCatching { dao.putSongFeatures(learned.toList()) }
        return out
    }

    private fun SongFeaturesEntity.toFeatures() =
        SongFeatures(
            bpm.takeIf { it > 0.0 }, gainDb, trackTags.split('\u0000').filter { it.isNotEmpty() },
            probe = if (probed == 1) AudioProbe(probeBpm, probeConf, probeRmsDb, probeLow, probeVar) else null,
            feel = if (felt == 1) ReccoFeatures(feelEnergy, feelAcoustic, feelValence, feelDance, feelLoud, feelTempo) else null,
        )

    private companion object {
        const val FOUND_TTL_MS = 120L * 86_400_000L
        const val MISSING_TTL_MS = 14L * 86_400_000L
    }
}
