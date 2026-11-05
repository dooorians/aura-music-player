package com.ikko.aura

import android.app.Application
import com.ikko.aura.source.jiosaavn.JioSaavnClient
import com.ikko.aura.source.ytm.YtmClient
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
    @Inject lateinit var network: com.ikko.aura.net.NetworkMonitor
    @Inject lateinit var uriAccessGuard: Lazy<com.ikko.aura.data.UriAccessGuard>

    override fun onCreate() {
        super.onCreate()
        // Before anything asks the network: from here every metadata request knows whether there is one, and keeps its answers.
        network.start()
        // The first search of a session paid for everything at once: the extractor's set-up, the DNS lookups and the TLS
        // handshakes. Done here, off the main thread while the first screen is drawn, the first real search finds the
        // connection open and the set-up done.
        // Not offline: that would only wake the radio to fail (and the process is often started for playback alone, from a
        // headset button or the car, where a search is not coming).
        if (!network.state.value.online) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch { runCatching { ytm.get().warmUp() } }
        scope.launch { runCatching { jioSaavn.get().warmUp() } }
        // Pictures picked through the system's file picker whose grant has since gone (see UriAccessGuard).
        scope.launch { runCatching { uriAccessGuard.get().pruneLostGrants() } }
    }
}
