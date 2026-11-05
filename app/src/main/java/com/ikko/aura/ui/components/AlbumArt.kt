package com.ikko.aura.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.scale
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.util.AbsentCovers
import com.ikko.aura.util.AlbumCovers
import com.ikko.aura.util.SingleFlight
import com.ikko.aura.util.reductionSteps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

object AlbumArtCache {
    /** Kept out of band from anything a content URI or an album id can contain. */
    private const val SIZE_SEPARATOR = "\u0000"

    /**
     * Used whenever a caller doesn't specify a target size (large hero art, palette extraction).
     */
    const val DEFAULT_SIZE_PX = 512

    /** What the palette, the theme and the blurred backdrop decode at (their own constants); see [load]. */
    private const val SOURCE_PICTURE_PX = 192

    /** The size the files of an album are sampled at to find its cover: only its look is wanted. */
    private const val SAMPLE_PX = 64

    /**
     * [LruCache] measures entries through [sizeOf], which defaults to 1 per entry — so without
     * this override the "an eighth of the heap, in KB" budget below was really a cap of ~30000
     * *bitmaps*, letting the cache grow to hundreds of MB and pay for it in GC pauses mid-scroll.
     * Reporting each bitmap's actual footprint makes the budget mean what it says.
     */
    private val cache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024

        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: Bitmap,
            newValue: Bitmap?,
        ) {
            if (newValue != null) return
            val cacheKey = key.substringBeforeLast(SIZE_SEPARATOR)
            val size = key.substringAfterLast(SIZE_SEPARATOR).toIntOrNull() ?: return
            heldSizes[cacheKey]?.let { sizes ->
                sizes.remove(size)
                if (sizes.isEmpty()) heldSizes.remove(cacheKey)
            }
        }
    }

    /**
     * A track's `artworkUri` is the audio file's own content URI, so decoding is per *track* —
     * every song on an album re-extracts the same embedded cover, at ~11ms a piece (measured via
     * `ImageDecoder#decodeBitmap` in an atrace capture of a library scroll). Callers that know
     * several items share one cover pass its album id as [cacheKey] so it is decoded once.
     */
    private fun key(cacheKey: String, targetSizePx: Int) = "$cacheKey$SIZE_SEPARATOR$targetSizePx"

    /**
     * Which sizes are currently held for a given cover, so a request can find a bigger copy of
     * the same picture without walking the whole cache.
     *
     * Kept in step with the cache from `entryRemoved`, which LruCache calls for both an eviction
     * and a replacement — so an entry dropped under memory pressure stops being advertised here
     * on the same call that drops it, and a stale size can never be handed back.
     */
    private val heldSizes = ConcurrentHashMap<String, ConcurrentSkipListSet<Int>>()

    /**
     * Decoding is CPU-bound, and [Dispatchers.IO] grows to 64 threads — a fast fling could put a
     * decode on every core at once and leave the UI thread fighting for one, which is exactly
     * when it can least afford to miss its 11ms budget. Two at a time keeps thumbnails arriving
     * promptly without starving the frame that has to draw them.
     */
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(2)

    /**
     * The same two slots again, for the web only. A cover on the web can take as long as its timeouts (10 s to connect,
     * 15 s to read), and two of them slow held both [decodeDispatcher] slots: every local cover on the screen, the
     * pill's included, stayed empty until one answered (8 s with two slow ones, 15 s with two that did not answer).
     * Like [decodeDispatcher] this is a view of the shared IO pool, not a pool of its own: no thread is kept for it,
     * at most two are borrowed while a cover is being fetched.
     */
    private val networkDispatcher = Dispatchers.IO.limitedParallelism(2)

    private fun isRemote(uri: Uri) = uri.scheme == "https" || uri.scheme == "http"

    /** One decode at a time per cover and size: the rows of one album all ask for it together. */
    private val flights = SingleFlight<String, Bitmap>()

    /**
     * Synchronous cache lookup, for reading an already-decoded bitmap during composition instead
     * of waiting a frame for a coroutine to hand back something already in memory.
     */
    fun peek(cacheKey: String?, targetSizePx: Int, derive: Boolean = false): Bitmap? {
        if (cacheKey == null) return null
        cache.get(key(cacheKey, targetSizePx))?.let { return it }
        // A cover already decoded at a bigger size is scaled down on the spot: a millisecond or two,
        // against a frame of placeholder followed by the picture appearing.
        return if (derive) downscaledFromLarger(cacheKey, targetSizePx)?.let { store(cacheKey, targetSizePx, it) } else null
    }

    suspend fun load(
        context: Context,
        uri: Uri?,
        targetSizePx: Int = DEFAULT_SIZE_PX,
        cacheKey: String? = uri?.toString(),
        shrink: Boolean = false,
    ): Bitmap? {
        if (uri == null || cacheKey == null) return null
        val size = targetSizePx
        // One answer per (cover, size) whoever asks, since they share a cache entry: the size the palette, the
        // theme and the blurred backdrop decode at is never shrunk, they are tuned on the picture as it comes.
        val reduce = shrink && size != SOURCE_PICTURE_PX
        val fullKey = key(cacheKey, size)
        cache.get(fullKey)?.let { return it }
        downscaledFromLarger(cacheKey, size)?.let { return store(cacheKey, size, it) }
        // After the cache, not before: a file with no picture of its own still shows its album's, when another
        // track of it has put one there.
        val file = uri.toString()
        // A device album's cover is one choice for the whole album (see AlbumCovers), not the picture of whichever track
        // asks first; for anything else the key is the file and what is asked for is what is decoded.
        val candidates = AlbumCovers.candidates(cacheKey)
        if (candidates == null && file in AbsentCovers) return null
        return flights.run(fullKey) {
            withContext(if (isRemote(uri)) networkDispatcher else decodeDispatcher) {
                // Checked once more on the decode thread: between the miss above and getting here a
                // concurrent request for a bigger rung of the same cover may well have landed, and a
                // track change asks for four sizes of one picture within a frame or two of each other.
                cache.get(fullKey)
                    ?: downscaledFromLarger(cacheKey, size)?.let { store(cacheKey, size, it) }
                    ?: if (candidates == null && file in AbsentCovers) null else decodeAndStore(context, uri, file, cacheKey, size, reduce, candidates)
            }
        }
    }

    private suspend fun decodeAndStore(
        context: Context,
        uri: Uri,
        file: String,
        cacheKey: String,
        size: Int,
        reduce: Boolean,
        candidates: List<String>?,
    ): Bitmap? {
        // The files whose picture may be this cover, best first. A file with no picture lets the next one stand in; a
        // request that merely failed (the provider) does not: a later file answering for it would fix the cover to the
        // wrong picture for good, so it is left to be tried again.
        for (candidate in if (candidates == null) listOf(file) else bestFirst(context, cacheKey, candidates)) {
            if (candidate in AbsentCovers) continue
            val target = if (candidate == file) uri else Uri.parse(candidate)
            val decoded = try {
                decode(context, target, size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Decoded.FAILED
            }
            // Cancelled while it was decoding: the result is for nobody and says nothing about the cover.
            currentCoroutineContext().ensureActive()
            decoded.bitmap?.let { return store(cacheKey, size, if (reduce) reducedTo(it, size) else it) }
            // Only a local file that was read and has no picture; a failed request (the network, the provider) is
            // not "no cover" and is tried again.
            if (decoded.absent) AbsentCovers.add(candidate) else return null
        }
        return null
    }

    /**
     * [candidates] with the file that carries the album's own cover first. Three files are sampled (the album's first,
     * middle and last, small and cheap) and the picture most of them share wins, the earliest on a tie; the rest follow
     * in the album's order as the ones to fall back on. Albums of one or two files are not sampled. The choice is kept
     * for the session (reset by each scan); it only depends on the files, so it is the same every time.
     */
    private suspend fun bestFirst(context: Context, cacheKey: String, candidates: List<String>): List<String> {
        val live = candidates.filter { it !in AbsentCovers }
        if (live.size <= 2) return live
        // Already decided for this scan (another size of the same cover): no need to sample again.
        AlbumCovers.chosenFor(cacheKey)?.takeIf { it in live }?.let { known -> return listOf(known) + live.filter { it != known } }
        val sample = listOf(live.first(), live[live.size / 2], live.last()).distinct()
        val looks = sample.map { file ->
            val decoded = try {
                decode(context, Uri.parse(file), SAMPLE_PX)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Decoded.FAILED
            }
            currentCoroutineContext().ensureActive()
            if (decoded.absent) AbsentCovers.add(file)
            file to decoded.bitmap?.let { look(it) }
        }
        val best = AlbumCovers.vote(looks) ?: return live
        AlbumCovers.choose(cacheKey, best)
        return listOf(best) + live.filter { it != best }
    }

    /** A 64-bit average hash of the picture (8 x 8 greys, one bit each for lighter than the mean); the bitmap is released. */
    private fun look(bitmap: Bitmap): Long {
        val small = runCatching { Bitmap.createScaledBitmap(bitmap, 8, 8, true) }.getOrNull()
        if (small !== bitmap) bitmap.recycle()
        small ?: return 0L
        val pixels = IntArray(64)
        small.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        small.recycle()
        val grey = IntArray(64) { i ->
            val c = pixels[i]
            ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        val mean = grey.average()
        var hash = 0L
        for (i in 0 until 64) if (grey[i] > mean) hash = hash or (1L shl i)
        return hash
    }

    /**
     * [source] reduced so that its SHORTER side is [targetPx], on the decode thread.
     *
     * [android.content.ContentResolver.loadThumbnail] takes the size as a hint and hands back more: 130 px asked for
     * came back 140 to 166, 375 came back 700, and the cache then held (and the GPU uploaded) the bigger picture
     * for a box that cannot show it. A picture is only ever made smaller, never larger, and one that already fits
     * is returned as it is. The ratio is kept and so is the crop, which the GPU still does when it is drawn
     * (`ContentScale.Crop` fills the square box with the picture's shorter side, which is why that is the side
     * brought to the target: see [reductionSteps]). The pictures made on the way, and the original once it is no
     * longer needed, are released at once rather than left to the collector.
     */
    private fun reducedTo(source: Bitmap, targetPx: Int): Bitmap {
        var current = source
        for ((width, height) in reductionSteps(source.width, source.height, targetPx)) {
            val smaller = runCatching { current.scale(width, height) }.getOrNull() ?: break
            if (current !== source) current.recycle()
            current = smaller
        }
        if (current !== source) source.recycle()
        return current
    }

    /** What a decode found: the picture, or nothing because there is none ([absent]) or because it did not work. */
    private class Decoded(val bitmap: Bitmap?, val absent: Boolean = false) {
        companion object {
            val FAILED = Decoded(null)
            val ABSENT = Decoded(null, absent = true)
        }
    }

    private fun store(cacheKey: String, size: Int, bitmap: Bitmap): Bitmap {
        cache.put(key(cacheKey, size), bitmap)
        heldSizes.getOrPut(cacheKey) { ConcurrentSkipListSet() }.add(size)
        return bitmap
    }

    /**
     * The same cover, already decoded at a larger size, scaled down instead of read and decoded
     * again.
     *
     * Opening a track in the player asks for four sizes of one picture at once — the hero art, the blurred backdrop, the palette and the
     * pill — and an atrace of six track changes showed twelve `decodeBitmap` slices averaging
     * 45ms each, all of them re-reading covers the app had just finished decoding. Scaling a
     * bitmap already in memory is a millisecond or two, so the first one to arrive pays and the
     * rest are free.
     *
     * An entry is only usable if the bitmap it holds really is bigger than what is being asked
     * for: a small embedded cover decoded at a large target comes back at its own size, and
     * stretching that up would be the one case where this cost quality rather than saving time.
     *
     * Sizes are kept exactly as asked for rather than rounded to a ladder of common values.
     * Rounding up did collapse the four requests into one entry, but it also handed every list
     * and grid a bitmap wider than the box drawing it — and the extra texture showed, taking the
     * album grid's 90th-percentile GPU time from 3ms to 5ms for a picture that looked the same.
     */
    private fun downscaledFromLarger(cacheKey: String, targetSizePx: Int): Bitmap? {
        val sizes = heldSizes[cacheKey] ?: return null
        val larger = sizes.ceiling(targetSizePx) ?: return null
        val source = cache.get(key(cacheKey, larger)) ?: return null
        val longest = maxOf(source.width, source.height)
        if (longest <= targetSizePx) return null
        val scale = targetSizePx.toFloat() / longest
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        return runCatching { source.scale(width, height) }.getOrNull()
    }

    /**
     * A cover on the web (a streaming source's), sampled down to the size it will be drawn at. Kept on disk, so a list
     * scrolled again does not fetch every picture again; the folder is trimmed to [WEB_COVER_BUDGET_BYTES].
     */
    private suspend fun fetchRemote(context: Context, uri: Uri, targetSizePx: Int): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            // The request is blocking, so it is the connection that is closed when the cover is no longer wanted (its
            // row left the screen): the read then fails at once and the slot is free, instead of held until the
            // server answers or the timeout runs out.
            val open = java.util.concurrent.atomic.AtomicReference<java.net.HttpURLConnection?>()
            continuation.invokeOnCancellation { open.get()?.disconnect() }
            val result = runCatching { fetchRemoteBlocking(context, uri, targetSizePx, open) { continuation.isCancelled } }
            if (continuation.isActive) continuation.resumeWith(result)
        }

    private fun fetchRemoteBlocking(
        context: Context,
        uri: Uri,
        targetSizePx: Int,
        open: java.util.concurrent.atomic.AtomicReference<java.net.HttpURLConnection?>,
        cancelled: () -> Boolean,
    ): Bitmap? {
        val dir = java.io.File(context.cacheDir, "web_covers").apply { mkdirs() }
        val file = java.io.File(dir, java.security.MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) })
        val bytes = if (file.exists() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            file.readBytes()
        } else {
            // The address as given, then — for a video's thumbnail, whose best size is not made for every video — the
            // smaller ones, the first that answers. What is kept is kept under the address that was asked for.
            val addresses = listOf(uri.toString()) + com.ikko.aura.source.ytm.YtmImages.fallbacks(uri.toString())
            var fetched: ByteArray? = null
            var failure: Throwable? = null
            for (address in addresses) {
                val connection = java.net.URL(address).openConnection() as java.net.HttpURLConnection
                open.set(connection)
                // Cancelled before the connection was published: the callback had nothing to close.
                if (cancelled()) {
                    connection.disconnect()
                    throw java.io.IOException("cancelled")
                }
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    fetched = connection.inputStream.use { it.readBytes() }
                    break
                } catch (e: java.io.IOException) {
                    failure = e
                    if (cancelled()) throw e
                } finally {
                    connection.disconnect()
                }
            }
            val got = fetched ?: throw (failure ?: java.io.IOException("no cover"))
            runCatching { file.writeBytes(got); trimWebCovers(dir) }
            got
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetSizePx && bounds.outHeight / (sample * 2) >= targetSizePx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun trimWebCovers(dir: java.io.File) {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= WEB_COVER_BUDGET_BYTES) return
        for (old in files.sortedBy { it.lastModified() }) {
            total -= old.length()
            old.delete()
            if (total <= WEB_COVER_BUDGET_BYTES * 3 / 4) break
        }
    }

    private const val WEB_COVER_BUDGET_BYTES = 120L * 1024 * 1024

    /**
     * [loadThumbnail] is a blocking system-provider call with no coroutine awareness of its own —
     * during a fast scroll, a [Composable] can leave composition (and its [LaunchedEffect]
     * coroutine get cancelled) while its decode is still in flight, but a plain blocking call
     * keeps running to completion regardless, burning IPC/CPU on a result nobody will ever see.
     * [CancellationSignal] is the real, documented way to interrupt that call server-side the
     * moment the coroutine that requested it is cancelled, via [suspendCancellableCoroutine]'s
     * `invokeOnCancellation` hook — rather than just letting it finish and discarding the result.
     */
    private suspend fun decode(context: Context, uri: Uri, targetSizePx: Int): Decoded {
        if (isRemote(uri)) return Decoded(fetchRemote(context, uri, targetSizePx))
        // A picture the app keeps in its own storage (an artist's photo): read at the size it is shown at.
        if (uri.scheme == "file") return withContext(Dispatchers.IO) {
            val path = uri.path ?: return@withContext Decoded.ABSENT
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetSizePx && bounds.outHeight / (sample * 2) >= targetSizePx) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.let { Decoded(it) } ?: Decoded.ABSENT
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val signal = CancellationSignal()
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { signal.cancel() }
                val result = try {
                    Decoded(context.contentResolver.loadThumbnail(uri, Size(targetSizePx, targetSizePx), signal))
                } catch (e: OperationCanceledException) {
                    Decoded.FAILED
                } catch (e: java.io.IOException) {
                    // What the provider throws for a file it read and found no picture in.
                    Decoded.ABSENT
                } catch (e: Exception) {
                    Decoded.FAILED
                }
                if (continuation.isActive) continuation.resumeWith(Result.success(result))
            }
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val bytes = retriever.embeddedPicture
                if (bytes == null) Decoded.ABSENT else Decoded(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            } finally {
                retriever.release()
            }
        }
    }
}

private const val DERIVE_FROM_PX = 200
private const val REVEAL_MS = 130

@Composable
fun AlbumArt(
    uri: Uri?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = AuraShapes.AlbumCover,
    // Decoding every thumbnail at the library-wide default (512x512, 1MB as ARGB_8888)
    // regardless of how small it's actually displayed was the real cause of catastrophic Library
    // scroll jank: a 2-column grid of 52dp thumbnails was decoding ~16x more pixels per image
    // than it could ever show, and paying to upload each oversized bitmap as a GPU texture on
    // every newly-composed row. Passing the real display size here lets small list/grid
    // thumbnails decode at the size they'll actually render at; leave null for large hero art
    // (Album/NowPlaying covers, palette extraction) where the full-size decode is the point.
    targetSizeDp: Dp? = null,
    // Items that share one cover (every track on an album) should share one cache entry, rather
    // than each decoding the same embedded artwork out of its own audio file.
    cacheKey: String? = null,
    // A carousel flung past its cards composes and drops them within a few frames: a decode that is cancelled with
    // its card is thrown away, and the card is found empty on the way back. With this on, the decode finishes into
    // the cache whatever happens to the card. Off for dense grids, where wasted decodes would cost the scroll.
    keepLoading: Boolean = false,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val targetSizePx = targetSizeDp
        ?.let { with(density) { it.toPx().toInt() }.coerceAtLeast(1) }
        ?: AlbumArtCache.DEFAULT_SIZE_PX
    val effectiveKey = cacheKey ?: uri?.toString()

    // Seeded from the cache synchronously: during a fling most thumbnails scrolling back into
    // view are already decoded, and going through LaunchedEffect for those meant every item
    // drew the placeholder icon first and then recomposed a frame later with the real image —
    // twice the work per item, and a visible flicker, for a bitmap that was already in memory.
    // Big covers (the hero on an album page, a carousel card) are derived from a larger cached copy
    // right here if need be; small thumbnails in a scrolling grid are not, so a fling never pays for it.
    val derive = targetSizePx >= DERIVE_FROM_PX
    val seeded = remember(effectiveKey, targetSizePx) { AlbumArtCache.peek(effectiveKey, targetSizePx, derive) }
    var bitmap by remember(effectiveKey, targetSizePx) { mutableStateOf(seeded) }
    var settled by remember(effectiveKey, targetSizePx) { mutableStateOf(seeded != null) }
    // A picture that arrives after the first frame fades in over the plain ground rather than
    // popping in; one that was there from the start is simply there.
    val reveal = remember(effectiveKey, targetSizePx) { Animatable(if (seeded != null) 1f else 0f) }

    LaunchedEffect(effectiveKey, targetSizePx) {
        if (bitmap == null) {
            bitmap = if (keepLoading) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    AlbumArtCache.load(context, uri, targetSizePx, effectiveKey, shrink = true)
                }
            } else {
                AlbumArtCache.load(context, uri, targetSizePx, effectiveKey, shrink = true)
            }
            settled = true
        }
        if (bitmap != null) reveal.animateTo(1f, tween(REVEAL_MS))
    }

    Box(
        modifier = modifier
            .clip(shape)
            .groundFill(androidx.compose.ui.graphics.SolidColor(AuraColors.SurfaceVariantDark), shape),
        contentAlignment = Alignment.Center,
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            // Wrapped once per bitmap rather than once per recomposition. `asImageBitmap` alone
            // allocates a new wrapper each call, and BitmapPainter another on top of it, so the
            // list rows that recompose while a fling is in flight were minting two throwaway
            // objects a frame apiece for a picture that had not changed.
            val painter = remember(currentBitmap) { BitmapPainter(currentBitmap.asImageBitmap()) }
            Image(
                painter = painter,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = reveal.value },
                contentScale = ContentScale.Crop,
            )
        } else if (settled) {
            // Only once loading has finished with nothing to show: while it is still going the
            // ground stays plain, so there is no icon to swap for the picture.
            Icon(
                imageVector = CupertinoIcons.MusicNote,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxWidth(fraction = 0.35f),
                tint = AuraColors.TextTertiary,
            )
        }
    }
}
