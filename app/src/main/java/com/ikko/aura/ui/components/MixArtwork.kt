package com.ikko.aura.ui.components

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ikko.aura.R

/**
 * The animated picture of a mix: one video for each of the five categories that have one, a loop that does not change with the
 * day. [poster] is a still of it (under the video until it plays, and where the page's colours are taken from).
 */
class MixArtwork(@RawRes val video: Int, @DrawableRes val poster: Int)

/** The artwork of the mix with this card id ("fav", "disc", "chill", "night", "energy"), or null: the others keep their gradient. */
fun mixArtworkOf(id: String?): MixArtwork? = when (id) {
    "fav" -> MixArtwork(R.raw.mix_art_favorites, R.drawable.mix_poster_favorites)
    "disc" -> MixArtwork(R.raw.mix_art_discovery, R.drawable.mix_poster_discovery)
    "chill" -> MixArtwork(R.raw.mix_art_chill, R.drawable.mix_poster_chill)
    "night" -> MixArtwork(R.raw.mix_art_night, R.drawable.mix_poster_night)
    "energy" -> MixArtwork(R.raw.mix_art_energy, R.drawable.mix_poster_energy)
    else -> null
}

/**
 * Returns the animated mix artwork for a playlist if it matches one of the 5 categories (by autoKey, id or name).
 */
fun mixArtworkForPlaylist(id: Long, name: String, autoKey: String? = null): MixArtwork? {
    if (autoKey != null) {
        mixArtworkOf(autoKey)?.let { return it }
    }
    if (id == -2L) return mixArtworkOf("fav")
    val lower = name.trim().lowercase()
    return when {
        lower.contains("favori") || lower.contains("préféré") || lower.contains("prefere") -> mixArtworkOf("fav")
        lower.contains("découverte") || lower.contains("decouverte") || lower.contains("discovery") || lower.contains("disc") -> mixArtworkOf("disc")
        lower.contains("bouge") || lower.contains("énerg") || lower.contains("energ") -> mixArtworkOf("energy")
        lower.contains("chill") || lower.contains("détente") || lower.contains("detente") -> mixArtworkOf("chill")
        lower.contains("nuit") || lower.contains("night") -> mixArtworkOf("night")
        else -> null
    }
}

/** Its video size, to fill a frame of any shape the way a cover does (cropped, centred). */
private const val VIDEO_RATIO = 310f / 414f

/**
 * [artwork] playing in a loop, silent, filling its frame (cropped, not stretched). The player only exists while the picture
 * is [playing] and the app is on screen: stopped, it is released and the poster shows.
 */
@Composable
fun MixArtworkView(artwork: MixArtwork, modifier: Modifier = Modifier, playing: Boolean = true) {
    val context = LocalContext.current
    // The app's own lifecycle, not the page's (its navigation entry): that one is only "resumed" once the page has finished
    // coming back, a fifth of a second after it is already in sight, and the artwork stood still for as long.
    var host: android.content.Context = context
    while (host is android.content.ContextWrapper && host !is androidx.lifecycle.LifecycleOwner) host = host.baseContext
    val lifecycle = ((host as? androidx.lifecycle.LifecycleOwner) ?: LocalLifecycleOwner.current).lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val holder = remember(artwork.video) { LoopHolder(context.applicationContext, artwork.video) }
    holder.wanted = playing && resumed
    DisposableEffect(holder) { onDispose { holder.release() } }

    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        Image(painterResource(artwork.poster), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        AndroidView(
            modifier = Modifier.layout { measurable, constraints ->
                val w = maxOf(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat() * VIDEO_RATIO)
                val h = w / VIDEO_RATIO
                val placeable = measurable.measure(
                    androidx.compose.ui.unit.Constraints.fixed(w.toInt(), h.toInt()),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val x = (constraints.maxWidth - placeable.width) / 2
                    val y = (constraints.maxHeight - placeable.height) / 2
                    placeable.place(x, y)
                }
            },
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) = holder.surfaceReady(Surface(st))
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            holder.surfaceGone(st)
                            return false
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
        )
    }
}

/**
 * The media player of one picture, from [MixPlayers]: it is not made again each time the page comes back (preparing a video
 * took about a second, during which the still showed), but kept prepared and paused while the page is away.
 */
private class LoopHolder(private val context: android.content.Context, @RawRes private val res: Int) {
    private var surface: Surface? = null
    private var attached: MediaPlayer? = null
    var wanted: Boolean = true
        set(value) { if (field != value) { field = value; refresh() } }

    fun surfaceReady(s: Surface) { surface = s; refresh() }
    fun surfaceGone(st: SurfaceTexture? = null) {
        val s = surface
        surface = null
        attached = null
        MixPlayers.detach(res, s, st)
    }
    fun release() = surfaceGone(null)

    private fun refresh() {
        val s = surface
        if (wanted && s != null) attach(s) else detach()
    }

    private fun attach(s: Surface) {
        if (attached != null) return
        attached = MixPlayers.attach(context, res, s)
    }

    private fun detach() {
        if (attached == null) return
        val s = surface
        attached = null
        MixPlayers.detach(res, s)
    }
}

/** The players of the pictures seen lately, prepared and paused when no page shows them, let go of after a while. */
private object MixPlayers {
    private class Entry(val player: MediaPlayer) { var prepared = false; var showing = false }
    private val entries = HashMap<Int, Entry>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val releasers = HashMap<Int, Runnable>()
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "MixPlayer-Worker").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private const val KEEP_MS = 60_000L

    fun attach(context: android.content.Context, res: Int, surface: Surface): MediaPlayer? {
        releasers.remove(res)?.let(handler::removeCallbacks)
        val entry = entries[res] ?: create(context, res)?.also { entries[res] = it } ?: return null
        entry.showing = true
        executor.execute {
            runCatching { entry.player.setSurface(surface) }
            if (entry.prepared && entry.showing) runCatching { entry.player.start() }
        }
        return entry.player
    }

    fun detach(res: Int, surfaceToRelease: Surface? = null, surfaceTextureToRelease: SurfaceTexture? = null) {
        val entry = entries[res] ?: run {
            if (surfaceToRelease != null || surfaceTextureToRelease != null) {
                executor.execute {
                    runCatching { surfaceToRelease?.release() }
                    runCatching { surfaceTextureToRelease?.release() }
                }
            }
            return
        }
        entry.showing = false
        executor.execute {
            runCatching { if (entry.prepared) entry.player.pause() }
            runCatching { entry.player.setSurface(null) }
            runCatching { surfaceToRelease?.release() }
            runCatching { surfaceTextureToRelease?.release() }
        }
        val letGo = Runnable {
            executor.execute {
                entries.remove(res)?.let { e -> runCatching { e.player.release() } }
                releasers.remove(res)
            }
        }
        releasers[res] = letGo
        handler.postDelayed(letGo, KEEP_MS)
    }

    private fun create(context: android.content.Context, res: Int): Entry? = runCatching {
        val afd = context.resources.openRawResourceFd(res)
        val mp = MediaPlayer()
        val entry = Entry(mp)
        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()
        mp.isLooping = true
        mp.setVolume(0f, 0f)
        mp.setOnPreparedListener { entry.prepared = true; if (entry.showing) runCatching { it.start() } }
        mp.setOnErrorListener { p, _, _ -> runCatching { p.release() }; entries.remove(res); true }
        mp.prepareAsync()
        entry
    }.getOrNull()
}
