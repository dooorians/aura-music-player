package com.ikko.aura

import android.app.Application
import com.ikko.aura.catalog.CatalogDao
import com.ikko.aura.source.jiosaavn.JioSaavnClient
import com.ikko.aura.source.ytm.YtmClient
import com.ikko.aura.source.ytm.YtmLibrary
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class AuraApplication : Application() {
    @Inject lateinit var ytm: Lazy<YtmClient>
    @Inject lateinit var jioSaavn: Lazy<JioSaavnClient>
    @Inject lateinit var catalog: Lazy<CatalogDao>

    override fun onCreate() {
        super.onCreate()
        // The first search of a session paid for everything at once: the extractor's set-up, the DNS lookups and the TLS
        // handshakes. Done here, off the main thread while the first screen is drawn, the first real search finds the
        // connection open and the set-up done.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch { runCatching { ytm.get().warmUp() } }
        scope.launch { runCatching { jioSaavn.get().warmUp() } }
        scope.launch { runCatching { resyncAlbumArtistsOnce() } }
    }

    /**
     * Albums read before the artist was taken from their songs stored the name of the channel that hosted them ("le
     * rappeur Damso", "Zaho Officiel"), so their artist link asks for an artist that does not exist. Once, in the
     * background, each album is asked who really interprets it and its rows are rewritten - their place in the library
     * and their downloads left alone, and the screens' own flows pick the change up.
     */
    private suspend fun resyncAlbumArtistsOnce() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ARTISTS_RESYNCED, false)) return
        val client = ytm.get()
        // With the source off there is nobody to ask: tried again at the next launch rather than given up on.
        if (!client.isEnabled()) return
        val dao = catalog.get()
        for (ref in dao.albumRefs()) {
            runCatching {
                client.album(ref).songs.forEach { song ->
                    if (song.artist.isNotBlank()) dao.setArtist(YtmLibrary.keyOf(song.videoId), song.artist)
                }
            }
        }
        prefs.edit().putBoolean(KEY_ARTISTS_RESYNCED, true).apply()
    }

    private companion object {
        const val PREFS = "aura"
        const val KEY_ARTISTS_RESYNCED = "album-artists-resynced"
    }
}
