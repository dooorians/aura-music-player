package com.ikko.aura

import android.app.Application
import com.ikko.aura.source.jiosaavn.JioSaavnClient
import com.ikko.aura.source.ytm.YtmClient
import com.ikko.aura.startup.FirstFrame
import com.ikko.aura.startup.atBackgroundPriority
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class AuraApplication : Application() {
    // Lazy: nothing of the sources is built while the process starts, only when the warm-up below runs.
    @Inject lateinit var ytm: Lazy<YtmClient>
    @Inject lateinit var jioSaavn: Lazy<JioSaavnClient>
    @Inject lateinit var network: com.ikko.aura.net.NetworkMonitor
    @Inject lateinit var uriAccessGuard: Lazy<com.ikko.aura.data.UriAccessGuard>

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Before anything asks the network: from here every metadata request knows whether there is one, and keeps its answers.
        network.start()
        // The first search of a session paid for everything at once: the extractor's set-up, the DNS lookups and the TLS
        // handshakes. Done ahead of it, so the first real search finds the connection open and the set-up done — but
        // only once the first frame is on screen: the extractor's set-up and its first page are seconds of CPU on the
        // little cores, taken from the frame the user is waiting for if they run beside it.
        appScope.launch {
            FirstFrame.await()
            // Pictures picked through the system's file picker whose grant has since gone (see UriAccessGuard).
            launch { runCatching { uriAccessGuard.get().pruneLostGrants() } }
            // Not offline: that would only wake the radio to fail (and the process is often started for playback alone,
            // from a headset button or the car, where a search is not coming).
            if (!network.state.value.online) return@launch
            // Blocking, so it stays on its thread: the priority is put back on the same thread it was lowered on.
            launch { runCatching { atBackgroundPriority { ytm.get().warmUp() } } }
            launch { runCatching { jioSaavn.get().warmUp() } }
        }
    }
}
