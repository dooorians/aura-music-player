package com.ikko.aura.taste

import com.ikko.aura.data.LibraryNormalizer
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.lastfm.LastFm
import com.ikko.aura.reco.ArtistNames
import com.ikko.aura.reco.RecommendationEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Where the picker's artists come from, and where its choice goes. */
@Singleton
class TasteCatalog @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    private val lastFm: LastFm,
    private val library: MusicRepository,
    private val recommendations: RecommendationEngine,
    private val images: com.ikko.aura.data.ArtistImages,
) {
    private val prefs = context.getSharedPreferences("taste_picker", android.content.Context.MODE_PRIVATE)

    /** Keys of the artists already offered in an earlier test (picked or not): not offered again. */
    fun alreadyShown(): Set<String> = prefs.getStringSet("shown", emptySet()).orEmpty()

    /** Remembers what was on the grid, so that the next test brings others. Bounded: the oldest are forgotten first. */
    fun markShown(keys: Collection<String>) {
        val all = (alreadyShown() + keys).toList().takeLast(MAX_REMEMBERED).toSet()
        prefs.edit().putStringSet("shown", all).apply()
    }

    /** Keys of the artists the listener already said they like (here or in Réglages). */
    suspend fun alreadyPicked(): Set<String> = pickedNames().mapTo(HashSet()) { ArtistNames.key(it) }

    suspend fun pickedNames(): List<String> = recommendations.seedArtists.first()

    /** Artists close to those already picked: what a second test refines with once the first names have all been seen. */
    suspend fun nextFrom(picked: List<String>, count: Int): List<String> {
        val out = LinkedHashSet<String>()
        for (name in picked.shuffled().take(20)) {
            out += similar(name).take(14)
            if (out.size >= count * 3) break
        }
        return out.toList()
    }

    /**
     * The first bubbles: what the account plays most on Last.fm, then the artists of the library with the most songs, then a
     * handful of well-known names so that a new install is not empty.
     */
    suspend fun starters(count: Int): List<String> = withContext(Dispatchers.Default) {
        val fromLastFm = runCatching { lastFm.listenerTopArtists() }.getOrDefault(emptyList()).take(count)
        val tracks = library.tracks.value
        val standalone = LibraryNormalizer.standaloneArtists(tracks.map { it.artist })
        val fromLibrary = tracks.groupingBy { LibraryNormalizer.primaryOf(it.artist, standalone) }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }.filter { !it.startsWith("Artiste inconnu") }.take(count)
        val chart = runCatching { images.chart() }.getOrDefault(emptyList()).shuffled()
        (fromLastFm + fromLibrary + WELL_KNOWN + chart).distinctBy { ArtistNames.key(it) }.take(count)
    }

    /** Artists Last.fm lists as close to [name]; empty when it does not know or cannot be reached. */
    suspend fun similar(name: String): List<String> = kotlinx.coroutines.coroutineScope {
        // Last.fm's list and Deezer's, asked together and woven: neither one alone always has enough.
        val lf = async { runCatching { lastFm.similar(name) }.getOrNull().orEmpty() }
        val dz = async { images.related(name) }
        val a = lf.await(); val b = dz.await()
        buildList { for (i in 0 until maxOf(a.size, b.size)) { a.getOrNull(i)?.let(::add); b.getOrNull(i)?.let(::add) } }
            .distinctBy { ArtistNames.key(it) }
    }

    /** Artists matching what was typed, from Deezer's catalogue (their photos come with it). */
    suspend fun search(query: String): List<String> = images.search(query)

    suspend fun block(name: String) = recommendations.blockArtist(name)

    suspend fun unblock(key: String) = recommendations.unblockArtist(key)

    val consent: kotlinx.coroutines.flow.Flow<Boolean> get() = recommendations.consent

    suspend fun blockedKeys(): Set<String> = recommendations.blockedArtists.first().mapTo(HashSet()) { it.key }

    /** Keeps the choice as artists the listener likes (added to what was typed in), counted strongly, and renews the playlists. */
    suspend fun save(names: List<String>) {
        recommendations.artistsPicked(names)
        // The playlists are made again in the background: the screen does not wait for the sources.
        if (recommendations.consent.first()) recommendations.refreshInBackground()
    }

    private companion object {
        const val MAX_REMEMBERED = 600
        val WELL_KNOWN = listOf(
            "Taylor Swift", "The Weeknd", "Dua Lipa", "Billie Eilish", "Ariana Grande", "Bad Bunny", "Daft Punk", "Stromae",
            "Angèle", "BTS", "BLACKPINK", "Rihanna", "Beyoncé", "Ed Sheeran", "Coldplay", "Kendrick Lamar", "Drake", "Lana Del Rey",
            "Arctic Monkeys", "Radiohead", "Charli XCX", "Olivia Rodrigo",
        )
    }
}
