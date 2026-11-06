package com.ikko.aura.ui.components

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Ce que l'on touche devient l'en-tête de la page suivante": the cover, the title and the line under it travel from
 * where they were touched to where the next page draws them, and back. One floating copy is drawn over everything for
 * the length of the trip; the real elements at both ends stay hidden while it stands in for them, and the hand-over at
 * each end happens in the same frame the copy lands (no copy left over the real thing, no gap).
 *
 * The boards' choreography, not their geometry: the places are those of this app's own layout, measured.
 *
 * - out: the cover goes in 620 ms, the texts in 540, a card's play button fades in 180 (linear); the rest of the list
 *   steps back to 96 % and clears in 200 ms (accelerating), the nearer to the cover the earlier (up to 110 ms); what the
 *   page brings rises and appears, 420 ms, 50 ms apart, from 240 ms; the page's background arrives in 460 ms.
 * - back: the cover and the texts return in 520 ms, the play button comes back from 320 ms; what the page brought leaves
 *   in 140 ms; the list comes back from the touched item outwards (260 ms + up to 170, 360 ms each).
 * - from the player: its own controls leave in 140 ms and come back 380 ms after the return has begun.
 *
 * A text whose words are not the same at both ends (a song's title becoming its album's) is not switched: the two
 * cross-fade on the way, each in its own layout.
 */
enum class HeroPhase { Idle, Opening, Settled, Closing }

internal val OPEN_CLIP = Rect(-10000f, -10000f, 100000f, 100000f)

/**
 * True unclipped geometry of [this] in root coordinates, unaffected by parent scroll/carousel clip bounds.
 * Unlike [boundsInRoot], this preserves the element's full dimensions even when partially or fully scrolled outside
 * a container viewport.
 */
internal fun LayoutCoordinates.fullRectInRoot(): Rect? {
    if (!isAttached) return null
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    if (w <= 0f || h <= 0f) return null
    val tl = localToRoot(Offset.Zero)
    if (!tl.x.isFinite() || !tl.y.isFinite()) return null
    return Rect(tl.x, tl.y, tl.x + w, tl.y + h)
}

/**
 * The active container clip planes in root coordinates that currently cut [this] (or [OPEN_CLIP] on edges that are
 * not clipped). When [this] is completely outside its clipping container ([boundsInRoot] is empty), walks up to the
 * nearest ancestor with non-empty [boundsInRoot] so the exact container clip edge is still known.
 */
internal fun LayoutCoordinates.containerClipInRoot(full: Rect? = fullRectInRoot()): Rect {
    if (!isAttached || full == null) return OPEN_CLIP
    val b = boundsInRoot()
    val ref = if (!b.isEmpty) {
        b
    } else {
        var p = parentLayoutCoordinates
        var found: Rect? = null
        while (p != null) {
            if (p.isAttached) {
                val pb = p.boundsInRoot()
                if (!pb.isEmpty) {
                    found = pb
                    break
                }
            }
            p = p.parentLayoutCoordinates
        }
        found
    } ?: return OPEN_CLIP
    return Rect(
        left = if (ref.left > full.left + 0.5f) ref.left else OPEN_CLIP.left,
        top = if (ref.top > full.top + 0.5f) ref.top else OPEN_CLIP.top,
        right = if (ref.right < full.right - 0.5f) ref.right else OPEN_CLIP.right,
        bottom = if (ref.bottom < full.bottom - 0.5f) ref.bottom else OPEN_CLIP.bottom,
    )
}

/**
 * Interpolates the container clip planes between source (`p = 0`) and target (`p = 1`).
 * Any stationary container edge that already contains the other end's full rect stays fixed in root space so the
 * full-sized element slides cleanly out from (and back behind) its container boundary without squishing or popping.
 */
internal fun lerpContainerClip(
    srcFull: Rect,
    srcClip: Rect,
    tgtFull: Rect,
    tgtClip: Rect,
    p: Float,
): Rect {
    val sLeft = lerpF(srcClip.left, min(srcClip.left, tgtFull.left), p)
    val sTop = lerpF(srcClip.top, min(srcClip.top, tgtFull.top), p)
    val sRight = lerpF(srcClip.right, max(srcClip.right, tgtFull.right), p)
    val sBottom = lerpF(srcClip.bottom, max(srcClip.bottom, tgtFull.bottom), p)

    val tLeft = lerpF(min(tgtClip.left, srcFull.left), tgtClip.left, p)
    val tTop = lerpF(min(tgtClip.top, srcFull.top), tgtClip.top, p)
    val tRight = lerpF(max(tgtClip.right, srcFull.right), tgtClip.right, p)
    val tBottom = lerpF(max(tgtClip.bottom, srcFull.bottom), tgtClip.bottom, p)

    val l = max(sLeft, tLeft)
    val t = max(sTop, tTop)
    val r = max(l, min(sRight, tRight))
    val b = max(t, min(sBottom, tBottom))
    return Rect(l, t, r, b)
}

/** One end of a text's trip: what it says, where it is, and how it is laid out there. */
@Stable
class HeroText(
    val text: String,
    val rect: Rect,
    val style: TextStyle,
    val color: Color,
    val maxLines: Int = 1,
    val align: TextAlign = TextAlign.Start,
    val clip: Rect = OPEN_CLIP,
    /**
     * The width the element lays its text out in (its constraint), when that differs from the bounds it draws: a title
     * that wraps owns its whole column but only paints the widest of its lines, and a copy laid out on the painted width
     * would keep on one line the words the page wraps onto a second.
     */
    val widthPx: Int = 0,
) {
    fun at(r: Rect, c: Rect = clip) = HeroText(text, r, style, color, maxLines, align, c, widthPx)
}

/** A place the hero can start from, kept up to date by [heroAnchor]. */
@Stable
class HeroAnchor {
    var coords: LayoutCoordinates? = null
    /** What the element says now, for a source whose words change by themselves (the line being sung). */
    var label: String? = null
    fun rect(): Rect? = coords?.fullRectInRoot()
    fun clip(): Rect = coords?.containerClipInRoot() ?: OPEN_CLIP
}

private val Morph = CubicBezierEasing(0.2f, 0.9f, 0.2f, 1f)
private val EaseOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
private val EaseIn = CubicBezierEasing(0.4f, 0f, 1f, 1f)

private class SavedHeroTrip(
    val key: String?,
    val uri: Uri?,
    val cacheKey: String?,
    val coverSizeDp: Dp?,
    val srcPicture: ImageBitmap?,
    val srcCover: Rect?,
    val srcClip: Rect,
    val srcRadiusPx: Float,
    val srcPlayButton: Boolean,
    val srcTitle: HeroText?,
    val srcSub: HeroText?,
    val srcCoverAnchor: HeroAnchor?,
    val srcTitleAnchor: HeroAnchor?,
    val srcSubAnchor: HeroAnchor?,
    val tgtCover: Rect?,
    val tgtClip: Rect,
    val tgtRadiusPx: Float,
    val tgtTitle: HeroText?,
    val tgtSub: HeroText?,
    val tgtCoverCoords: LayoutCoordinates?,
    val tgtTitleCoords: LayoutCoordinates?,
    val tgtSubCoords: LayoutCoordinates?,
    val drawCover: Boolean,
    val coverFades: Boolean,
    val lyricsMode: Boolean,
    val fromPlayer: Boolean,
    val anyPageOfKind: Boolean,
    val coverAlbumId: Long?,
    val coverAlbumTitle: String?,
    val tripId: Int,
)

@Stable
class HeroState {
    var key by mutableStateOf<String?>(null)
        private set
    var phase by mutableStateOf(HeroPhase.Idle)
        private set

    /** 0 at the source, 1 at the target; the cover and the texts have their own, as their durations differ. */
    val coverP = Animatable(0f)
    val textP = Animatable(0f)
    /** The card's play button, drawn on the copy while it leaves (1 = there). */
    val buttonAlpha = Animatable(1f)
    /** Milliseconds since the list started to clear (out) or to come back (back). */
    val clearClock = Animatable(0f)
    val returnClock = Animatable(0f)
    /** 0 while what the page brought is in place, 1 once it has gone (140 ms on the way back). */
    val contentOut = Animatable(0f)
    /**
     * The copy of the words, faded out while the page the trip goes to has not yet said where they go (a page that comes from a source takes a
     * moment): left whole on the card, they stayed there, the title and the artist, until the page came. 0 shown, 1 gone.
     */
    val waitFade = Animatable(0f)
    /** The player's own controls, during a trip from the player. */
    internal val uiAlpha = Animatable(1f)

    var uri by mutableStateOf<Uri?>(null)
        private set
    var cacheKey: String? = null
        private set
    /** The size the source drew the cover at, so that the copy finds the same decoded picture and is there at once. */
    var coverSizeDp: Dp? = null
        private set
    /** The player's ground (the cover already melted into the aurora): what the copy is at the player's end. */
    var srcPicture: ImageBitmap? by mutableStateOf(null)
        private set

    var srcCover: Rect? by mutableStateOf(null)
        private set
    var srcClip: Rect by mutableStateOf(OPEN_CLIP)
        private set
    var srcRadiusPx = 0f
        private set
    var srcPlayButton = false
        private set
    var srcTitle: HeroText? by mutableStateOf(null)
        private set
    var srcSub: HeroText? by mutableStateOf(null)
        private set

    private var srcCoverAnchor: HeroAnchor? = null
    private var srcTitleAnchor: HeroAnchor? = null
    private var srcSubAnchor: HeroAnchor? = null

    var tgtCover: Rect? by mutableStateOf(null)
        private set
    var tgtClip: Rect by mutableStateOf(OPEN_CLIP)
        private set
    var tgtRadiusPx = 0f
        private set
    var tgtTitle: HeroText? by mutableStateOf(null)
        private set
    var tgtSub: HeroText? by mutableStateOf(null)
        private set

    private var tgtCoverCoords: LayoutCoordinates? = null
    private var tgtTitleCoords: LayoutCoordinates? = null
    private var tgtSubCoords: LayoutCoordinates? = null

    private val savedTrips = ArrayDeque<SavedHeroTrip>()
    private var tripCounter = 0

    /** Whether a copy of the cover travels at all (a playlist's or an artist's tile has no picture to carry). */
    var drawCover = true
        private set
    /** The destination has no place for the cover: it shrinks towards the name and clears on the way. */
    var coverFades = false
        private set
    /** Not a trip of the cover but its going: it blurs, darkens, grows a little and clears, as the lyrics come. */
    var lyricsMode = false
        private set
    /** The trip leaves from (and returns to) the player, whose controls step aside meanwhile. */
    var fromPlayer = false
        private set
    /** True when closing from a scrolled Album/Artist page whose target header cover is no longer visible in the viewport. */
    var scrolledFallbackClose by mutableStateOf(false)
        private set

    /** True when the target cover is currently attached and visible in the viewport (or when in lyricsMode). */
    val isTargetCoverVisible: Boolean
        get() {
            if (lyricsMode) return true
            // Where the covers do not fly (see COVERS_FLY), [drawCover] is always off: the page's cover is still what says whether the way
            // back has somewhere to land, or the words (which do travel) were sent off with a fade every time.
            if (!drawCover && COVERS_FLY) return false
            val c = tgtCover ?: return false
            val coords = tgtCoverCoords
            if (coords != null && !coords.isAttached) return false
            val visible = coords?.boundsInRoot() ?: c
            return !visible.isEmpty && visible.bottom > 110f && visible.height >= c.height * 0.35f
        }
    /**
     * The page the trip goes to is known only by its kind (an album, an artist), not by its id: a song streamed from a
     * source has its own album ids, so the next page of that kind is the destination, whatever its id.
     */
    var anyPageOfKind = false
        private set

    /** Whether [k] names this trip's destination element (exactly, or by kind when [anyPageOfKind] is true). */
    fun matchesTarget(k: String): Boolean {
        val mine = key ?: return false
        if (mine == k) return true
        if (mine.startsWith("album:") && (k == "album:null" || k == "album:" || k == "album:unknown")) return true
        return anyPageOfKind && mine.substringBefore(':') == k.substringBefore(':')
    }

    /** Whether [k] names this trip's source element (strictly the same element, never by kind across different items). */
    fun matchesSource(k: String): Boolean {
        val mine = key ?: return false
        if (mine == k) return true
        if (fromPlayer && mine.substringBefore(':') == k.substringBefore(':')) return true
        return false
    }

    /** Whether [k] names this trip's element. Defaults to [matchesSource] to avoid cross-element match bleed. */
    fun matches(k: String): Boolean = matchesSource(k)

    /** The trip key a navigation route and its arguments stand for, null for a route no trip goes to. */
    private fun entryKey(route: String?, args: android.os.Bundle?): String? {
        if (route == null) return null
        fun argStr(name: String): String? =
            args?.getString(name) ?: @Suppress("DEPRECATION") args?.get(name)?.toString()
        return when {
            route.startsWith("album/") -> argStr("albumId")?.let { "album:$it" } ?: "album:"
            route.startsWith("playlist/") -> argStr("playlistId")?.let { "playlist:$it" } ?: "playlist:"
            route.startsWith("artist/") -> argStr("artistId")?.let { "artist:$it" } ?: "artist:"
            // A source's pages have ids of their own: they are matched by their kind alone.
            route.startsWith("ytm_album/") || route.startsWith("remote_album/") -> "album:"
            route.startsWith("ytm_artist/") || route.startsWith("remote_artist/") -> "artist:"
            route == "lyrics" -> "lyrics"
            route == "queue" -> "queue"
            // The settings' pages: the hub, the pages it opens, the pages those open. Matched whole, never by kind.
            com.ikko.aura.navigation.isSettingsPageRoute(route) ->
                com.ikko.aura.navigation.settingsTripKey(com.ikko.aura.navigation.settingsTripPath(route) { argStr(it) })
            else -> null
        }
    }

    /** Whether the current trip targets the given navigation route and arguments. */
    fun matchesEntry(route: String?, args: android.os.Bundle?): Boolean {
        val k = entryKey(route, args) ?: return false
        val mine = key ?: return false
        if (mine == k) return true
        return (anyPageOfKind || k.endsWith(':')) && mine.substringBefore(':') == k.substringBefore(':')
    }

    /**
     * The page of the trip that waits under the one now returning is being left (back pressed twice in a row, the second before
     * the first return ended): the first return is cut short, the trip under it put back, and its own return starts at once. It
     * used to be refused, and the trip stayed open for good with its list's item hidden.
     */
    fun closeParentAfterReturn(route: String?, args: android.os.Bundle?) {
        if (phase != HeroPhase.Closing) return
        val parent = savedTrips.lastOrNull() ?: return
        val k = entryKey(route, args) ?: return
        if (parent.key == k || ((parent.anyPageOfKind || k.endsWith(':')) && parent.key?.substringBefore(':') == k.substringBefore(':'))) {
            reset()
            startClosing()
        }
    }

    /** The album whose card, among an artist's, the cover lands on: set for a trip from the player to an artist. */
    var coverAlbumId: Long? = null
        private set
    /** The same album by its name: a source's artist page lists its own albums, with ids of its own. */
    var coverAlbumTitle: String? = null
        private set

    /** Whether the card of [id] / [title] among an artist's albums is the one the player's cover lands on. */
    fun landsOn(id: Long, title: String): Boolean =
        coverAlbumId == id || (coverAlbumTitle != null && coverAlbumTitle.equals(title.trim(), ignoreCase = true))

    /** A trip is starting: the pages use it to pick a transition that leaves the travelling elements alone. */
    val armed: Boolean get() = phase == HeroPhase.Opening
    val canClose: Boolean get() = phase == HeroPhase.Opening || phase == HeroPhase.Settled

    /**
     * Whether this trip's own animation has started. Until it has, the progress values still hold the last trip's, and
     * everything reads as "at the source" instead.
     */
    var running by mutableStateOf(false)
        internal set

    internal fun cp(): Float = if (running) coverP.value else 0f
    /**
     * The list's clocks, likewise: until this trip has set them back to zero they hold the last trip's end, and an item
     * read against that for a frame would blink out (going) or in (coming back).
     */
    internal var clearRunning by mutableStateOf(false)
    /** Counts the trips: an item knows which trip it was composed during, if any. */
    internal var tripId = 0
        private set
    internal fun parentTripId(): Int = savedTrips.lastOrNull()?.tripId ?: -1
    /** When the current trip started: a page that comes with it times its parts from here, not from its own composition. */
    internal var tripStartNanos = 0L
        private set

    /**
     * The line of a card that is neither its cover nor its title nor its subtitle (a count, a "Favori"): it goes with the
     * first moments of the trip, and comes back only as the cover lands, so it is never left alone in the list.
     */
    fun sideLineAlpha(key: String): Float {
        if (!matches(key)) return 1f
        return when (phase) {
            HeroPhase.Opening, HeroPhase.Settled -> 1f - (clearMs() / 170f).coerceIn(0f, 1f)
            HeroPhase.Closing -> if (running) ((0.5f - cp()) / 0.5f).coerceIn(0f, 1f) else 0f
            HeroPhase.Idle -> 1f
        }
    }

    /** The copy of the cover where it is drawn now. */
    internal fun coverNow(): Rect? {
        val s = srcCover ?: return null
        val g = tgtCover ?: return null
        val p = drawn(cp())
        return Rect(lerpF(s.left, g.left, p), lerpF(s.top, g.top, p), lerpF(s.right, g.right, p), lerpF(s.bottom, g.bottom, p))
    }

    /** Whether the texts of this trip ride on the cover's frame (they sit under it at both ends) instead of crossing it. */
    internal val textsFollowCover: Boolean
        get() = false
    internal var returnRunning by mutableStateOf(false)
    private fun clearMs(): Float = if (clearRunning) clearClock.value else 0f
    private fun returnMs(): Float = if (returnRunning) returnClock.value else 0f
    internal fun tp(): Float = if (running) textP.value else 0f
    internal fun bp(): Float = if (clearRunning || running) buttonAlpha.value else 1f

    /** The copy has reached the page (Opening), or come home (Closing). Read in draw: it is what hands over. */
    private val arrived: Boolean get() = cp() >= 1f && tp() >= 1f
    private val landedBack: Boolean get() = cp() <= 0f && tp() <= 0f
    internal val copyVisible: Boolean
        get() = when (phase) {
            HeroPhase.Opening -> !arrived
            HeroPhase.Closing -> !landedBack && !scrolledFallbackClose
            else -> false
        }

    /**
     * Starts a trip from a card. Returns false when a trip is already starting (a second tap on the way): the caller
     * then does nothing, so a page is never opened twice.
     */
    fun begin(
        key: String,
        uri: Uri?,
        cacheKey: String?,
        cover: HeroAnchor,
        radiusPx: Float,
        title: Pair<HeroAnchor, HeroText>?,
        sub: Pair<HeroAnchor, HeroText>?,
        playButton: Boolean = false,
        drawCover: Boolean = true,
        sizeDp: Dp? = null,
        /**
         * The bottom, in root pixels, of the band at the top of the page that its content fades out under (the header's): a
         * card scrolled up into it is there only faded, and its copy is faded the same way while it is at the card's place -
         * so that it takes the card's place and gives it back with no jump - and less and less as it travels.
         */
        visibleFromY: Float? = null,
        /**
         * The page's own key is not known beforehand (an album of a source: its id is made when the page loads): it answers to the
         * trip by its kind, `album:` whatever follows, as a page does when the trip comes from the player.
         */
        anyOfKind: Boolean = false,
    ): Boolean {
        if (phase == HeroPhase.Opening) return false
        val rect = cover.rect()
        val clip = cover.clip()
        start(key, uri, cacheKey, rect, clip, radiusPx, cover, title, sub)
        anyPageOfKind = anyOfKind
        srcVisibleFromY = visibleFromY
        srcPlayButton = playButton
        // The cover does not fly (a test of the playlists' way for every list: the names travel, the page fades in round them and
        // the cover comes with it; the way back is the soft fade-out). [COVERS_FLY] puts the flight back.
        this.drawCover = drawCover && COVERS_FLY && rect != null
        coverSizeDp = sizeDp
        return true
    }

    /** A trip from the player: its cover is a picture of the ground, not a composable of its own. */
    fun beginFromPlayer(
        key: String,
        uri: Uri?,
        cacheKey: String?,
        cover: Rect,
        picture: ImageBitmap?,
        title: Pair<HeroAnchor, HeroText>?,
        sub: Pair<HeroAnchor, HeroText>?,
        coverAlbumId: Long? = null,
        coverAlbumTitle: String? = null,
        lyrics: Boolean = false,
        radiusPx: Float = if (lyrics) 0f else 72f,
    ): Boolean {
        if (phase == HeroPhase.Opening) return false
        savedTrips.clear()
        start(key, uri, cacheKey, cover, OPEN_CLIP, radiusPx, null, title, sub, pushCurrentIfSettled = false)
        srcVisibleFromY = null
        srcPicture = picture
        fromPlayer = true
        anyPageOfKind = true
        this.coverAlbumId = coverAlbumId
        this.coverAlbumTitle = coverAlbumTitle?.trim()?.takeIf { it.isNotEmpty() }
        lyricsMode = lyrics
        this.drawCover = COVERS_FLY && !lyrics
        if (lyrics) {
            // The cover does not go anywhere: it goes out of focus where it is.
            tgtCover = cover
            tgtClip = OPEN_CLIP
            tgtRadiusPx = 0f
        }
        return true
    }

    private fun start(
        key: String,
        uri: Uri?,
        cacheKey: String?,
        cover: Rect?,
        clip: Rect,
        radiusPx: Float,
        coverAnchor: HeroAnchor?,
        title: Pair<HeroAnchor, HeroText>?,
        sub: Pair<HeroAnchor, HeroText>?,
        pushCurrentIfSettled: Boolean = true,
    ) {
        if (pushCurrentIfSettled && phase == HeroPhase.Settled && this.key != null) {
            savedTrips.addLast(
                SavedHeroTrip(
                    key = this.key,
                    uri = this.uri,
                    cacheKey = this.cacheKey,
                    coverSizeDp = this.coverSizeDp,
                    srcPicture = this.srcPicture,
                    srcCover = this.srcCover,
                    srcClip = this.srcClip,
                    srcRadiusPx = this.srcRadiusPx,
                    srcPlayButton = this.srcPlayButton,
                    srcTitle = this.srcTitle,
                    srcSub = this.srcSub,
                    srcCoverAnchor = this.srcCoverAnchor,
                    srcTitleAnchor = this.srcTitleAnchor,
                    srcSubAnchor = this.srcSubAnchor,
                    tgtCover = this.tgtCover,
                    tgtClip = this.tgtClip,
                    tgtRadiusPx = this.tgtRadiusPx,
                    tgtTitle = this.tgtTitle,
                    tgtSub = this.tgtSub,
                    tgtCoverCoords = this.tgtCoverCoords,
                    tgtTitleCoords = this.tgtTitleCoords,
                    tgtSubCoords = this.tgtSubCoords,
                    drawCover = this.drawCover,
                    coverFades = this.coverFades,
                    lyricsMode = this.lyricsMode,
                    fromPlayer = this.fromPlayer,
                    anyPageOfKind = this.anyPageOfKind,
                    coverAlbumId = this.coverAlbumId,
                    coverAlbumTitle = this.coverAlbumTitle,
                    tripId = this.tripId,
                )
            )
        }
        this.key = key
        this.uri = uri
        this.cacheKey = cacheKey
        srcCover = cover
        srcClip = clip
        srcRadiusPx = radiusPx
        srcPicture = null
        srcPlayButton = false
        drawCover = cover != null
        coverFades = false
        coverSizeDp = null
        lyricsMode = false
        fromPlayer = false
        scrolledFallbackClose = false
        anyPageOfKind = false
        coverAlbumId = null
        coverAlbumTitle = null
        srcCoverAnchor = coverAnchor
        srcTitleAnchor = title?.first
        srcSubAnchor = sub?.first
        srcTitle = title?.let { (a, t) -> a.rect()?.let { t.at(it, a.clip()) } }
        srcSub = sub?.let { (a, t) -> a.rect()?.let { t.at(it, a.clip()) } }
        tgtCover = null
        tgtClip = OPEN_CLIP
        tgtTitle = null
        tgtSub = null
        tgtCoverCoords = null
        tgtTitleCoords = null
        tgtSubCoords = null
        tripCounter++
        tripId = tripCounter
        tripStartNanos = System.nanoTime()
        running = false
        clearRunning = false
        returnRunning = false
        phase = HeroPhase.Opening
    }

    internal fun reportTarget(
        key: String,
        cover: Rect?,
        coverClip: Rect = OPEN_CLIP,
        radiusPx: Float,
        title: HeroText?,
        sub: HeroText?,
        coords: LayoutCoordinates? = null,
    ) {
        // Frozen once the return has begun: the page may slide or fade, the copy leaves from where it was.
        if (!matchesTarget(key) || phase == HeroPhase.Idle || phase == HeroPhase.Closing) return
        var dy = 0f
        if (cover != null && cover.width > 1f && cover.height > 1f) {
            val prev = tgtCover
            if (prev != null) dy = cover.top - prev.top
            tgtCover = cover
            tgtClip = coverClip
            tgtRadiusPx = radiusPx
            if (coords != null) tgtCoverCoords = coords
        }
        if (title != null && title.rect.width > 1f && title.rect.height > 1f) {
            val prev = tgtTitle
            if (prev != null && dy == 0f) dy = title.rect.top - prev.rect.top
            tgtTitle = title
            if (coords != null) tgtTitleCoords = coords
        }
        if (sub != null && sub.rect.width > 1f && sub.rect.height > 1f) {
            val prev = tgtSub
            if (prev != null && dy == 0f) dy = sub.rect.top - prev.rect.top
            tgtSub = sub
            if (coords != null) tgtSubCoords = coords
        }
        if (dy != 0f) {
            val shift = Offset(0f, dy)
            if (cover == null && tgtCover != null && tgtCoverCoords?.isAttached == false) {
                tgtCover = tgtCover!!.translate(shift)
            }
            if (title == null && tgtTitle != null && tgtTitleCoords?.isAttached == false) {
                tgtTitle = tgtTitle!!.at(tgtTitle!!.rect.translate(shift), tgtTitle!!.clip)
            }
            if (sub == null && tgtSub != null && tgtSubCoords?.isAttached == false) {
                tgtSub = tgtSub!!.at(tgtSub!!.rect.translate(shift), tgtSub!!.clip)
            }
        }
    }

    /**
     * The source element re-appeared (the list came back after the page was left): where it is now is where the
     * trip back must land, which is not always where it was.
     */
    /** The bottom of the band the card's page fades its content out under (see [begin]'s visibleFromY); null outside one. */
    var srcVisibleFromY: Float? = null
        private set

    fun updateSource(
        key: String,
        role: Int,
        rect: Rect,
        text: String? = null,
        clip: Rect = OPEN_CLIP,
        anchor: HeroAnchor? = null,
        isFromPlayer: Boolean = false,
    ) {
        if (isFromPlayer != fromPlayer) return
        if (!matches(key) || (phase != HeroPhase.Settled && phase != HeroPhase.Closing)) return
        if (rect.width <= 1f || rect.height <= 1f) return

        val bound = when (role) {
            ROLE_TITLE -> srcTitleAnchor
            ROLE_SUB -> srcSubAnchor
            else -> srcCoverAnchor
        }
        if (bound != null && anchor != null && bound !== anchor) {
            if (bound.coords?.isAttached == true) {
                return
            }
            if (rect.right <= 0f || rect.left >= 10000f) return
            when (role) {
                ROLE_TITLE -> srcTitleAnchor = anchor
                ROLE_SUB -> srcSubAnchor = anchor
                else -> srcCoverAnchor = anchor
            }
        }

        val known = when (role) { ROLE_TITLE -> srcTitle?.rect; ROLE_SUB -> srcSub?.rect; else -> srcCover }
        val knownText = when (role) { ROLE_TITLE -> srcTitle?.text; ROLE_SUB -> srcSub?.text; else -> null }
        val newWords = text != null && knownText != null && text != knownText
        if (known != null && rect.width < known.width * 0.6f && !newWords) return
        if (fromPlayer && role != ROLE_COVER) {
            val now = if (role == ROLE_TITLE) srcTitle else srcSub
            if (now == null || text == null || text == now.text) return
        }
        when (role) {
            ROLE_COVER -> if (srcCover != null && srcPicture == null) {
                srcCover = rect
                srcClip = clip
            }
            ROLE_TITLE -> srcTitle = srcTitle?.let { HeroText(text ?: it.text, rect, it.style, it.color, it.maxLines, it.align, clip) }
            ROLE_SUB -> srcSub = srcSub?.let { HeroText(text ?: it.text, rect, it.style, it.color, it.maxLines, it.align, clip) }
        }
    }

    /** The player's ground now (another song may be playing): the copy goes back as that picture. */
    fun updatePlayerPicture(picture: ImageBitmap?, uri: Uri?, cacheKey: String?) {
        if (!fromPlayer || phase == HeroPhase.Idle || phase == HeroPhase.Opening) return
        if (picture != null) srcPicture = picture
        if (uri != null && uri != this.uri) {
            this.uri = uri
            this.cacheKey = cacheKey
        }
    }

    internal fun settle() { if (phase == HeroPhase.Opening) phase = HeroPhase.Settled }

    /** The page has no place for the cover after all (the album is not among the artist's): it fades towards the name. */
    internal fun coverHasNoPlace() {
        if (phase != HeroPhase.Opening || tgtCover != null) return
        val name = tgtTitle ?: return
        val h = name.rect.height
        tgtCover = Rect(name.rect.left, name.rect.top, name.rect.left + h, name.rect.top + h)
        tgtClip = OPEN_CLIP
        tgtRadiusPx = h / 2f
        coverFades = true
    }

    /** Back: from wherever the trip is (still on its way out included), everything returns. */
    fun startClosing() {
        if (!canClose) return
        // If the target header cover was scrolled off-screen, disable the floating shared-element cover/text return
        // so we use a clean, smooth slide/fade transition without a detached cover flying over the Player or list.
        if (fromPlayer || (!lyricsMode && !isTargetCoverVisible)) {
            scrolledFallbackClose = true
            drawCover = false
            srcTitle = null
            srcSub = null
            tgtTitle = null
            tgtSub = null
        } else {
            scrolledFallbackClose = false
        }
        returnRunning = false
        phase = HeroPhase.Closing
    }

    fun reset() {
        val prev = savedTrips.removeLastOrNull()
        if (prev != null) {
            key = prev.key
            uri = prev.uri
            cacheKey = prev.cacheKey
            coverSizeDp = prev.coverSizeDp
            srcPicture = prev.srcPicture
            srcCover = prev.srcCover
            srcClip = prev.srcClip
            srcRadiusPx = prev.srcRadiusPx
            srcPlayButton = prev.srcPlayButton
            srcTitle = prev.srcTitle
            srcSub = prev.srcSub
            srcCoverAnchor = prev.srcCoverAnchor
            srcTitleAnchor = prev.srcTitleAnchor
            srcSubAnchor = prev.srcSubAnchor
            tgtCover = prev.tgtCover
            tgtClip = prev.tgtClip
            tgtRadiusPx = prev.tgtRadiusPx
            tgtTitle = prev.tgtTitle
            tgtSub = prev.tgtSub
            tgtCoverCoords = prev.tgtCoverCoords
            tgtTitleCoords = prev.tgtTitleCoords
            tgtSubCoords = prev.tgtSubCoords
            drawCover = prev.drawCover
            coverFades = prev.coverFades
            lyricsMode = prev.lyricsMode
            fromPlayer = prev.fromPlayer
            scrolledFallbackClose = false
            anyPageOfKind = prev.anyPageOfKind
            coverAlbumId = prev.coverAlbumId
            coverAlbumTitle = prev.coverAlbumTitle
            tripId = prev.tripId
            running = true
            clearRunning = false
            returnRunning = false
            phase = HeroPhase.Settled
            return
        }
        phase = HeroPhase.Idle
        key = null
        scrolledFallbackClose = false
        srcPicture = null
        srcCoverAnchor = null
        srcTitleAnchor = null
        srcSubAnchor = null
        tgtCover = null
        tgtClip = OPEN_CLIP
        tgtTitle = null
        tgtSub = null
        tgtCoverCoords = null
        tgtTitleCoords = null
        tgtSubCoords = null
    }

    internal fun handingOver(p: Float): Boolean = fromPlayer && phase == HeroPhase.Closing && p < HANDOVER

    internal fun drawn(p: Float): Float =
        if (fromPlayer && phase == HeroPhase.Closing) ((p - HANDOVER) / (1f - HANDOVER)).coerceIn(0f, 1f) else p
    internal fun handoverAlpha(p: Float): Float = if (handingOver(p)) 0f else 1f

    /** True while the real element for [key] must stay out of sight: the floating copy is standing in for it. */
    fun hidesSource(
        key: String,
        role: Int = ROLE_COVER,
        anchor: HeroAnchor? = null,
        isFromPlayer: Boolean = false,
    ): Boolean {
        if (scrolledFallbackClose && phase == HeroPhase.Closing) return false
        if (isFromPlayer != fromPlayer) return false
        if (!matches(key)) return false
        if (role == ROLE_COVER && !drawCover) return false
        if (role == ROLE_TITLE && srcTitle == null) return false
        if (role == ROLE_SUB && srcSub == null) return false
        val bound = when (role) {
            ROLE_TITLE -> srcTitleAnchor
            ROLE_SUB -> srcSubAnchor
            else -> srcCoverAnchor
        }
        if (bound != null && anchor != null && bound !== anchor && bound.coords?.isAttached == true) {
            return false
        }
        return when (phase) {
            HeroPhase.Idle -> false
            HeroPhase.Closing -> !landedBack && !handingOver(if (role == ROLE_COVER) cp() else tp())
            else -> true
        }
    }

    /** True while the page's own element for [key] must stay out of sight: the copy is standing in for it. */
    fun hidesTarget(key: String): Boolean {
        if (lyricsMode) return false
        if (scrolledFallbackClose && phase == HeroPhase.Closing) return false
        return matchesTarget(key) && when (phase) {
            HeroPhase.Opening -> !arrived
            HeroPhase.Closing -> true
            else -> false
        }
    }

    /**
     * The page's cover while the trip carries no copy of it (see [COVERS_FLY]): it comes in with the page's words instead of
     * waiting hidden for a copy to land, and is simply there on the way back. Null when the cover does travel (or no trip).
     */
    fun coverWithoutFlightAlpha(key: String): Float? {
        if (lyricsMode || drawCover || !matchesTarget(key)) return null
        return if (phase == HeroPhase.Opening) smooth(tp(), 0.3f, 0.85f) else null
    }

    /**
     * The page's line that the trip has no copy of (the artist under an album's title, when the item left only showed the
     * title): it comes and goes with the title's flight, instead of waiting for the landing and appearing at the end.
     * Null when the trip does carry that line (or none is under way): the line then follows [hidesTarget].
     */
    fun lineWithoutSourceAlpha(key: String): Float? {
        if (lyricsMode || srcSub != null || srcTitle == null || !matchesTarget(key)) return null
        return when (phase) {
            HeroPhase.Opening, HeroPhase.Closing -> smooth(tp(), 0.3f, 0.8f)
            else -> null
        }
    }

    /** The player's ground under a trip: it gives way to the copy (which is a piece of it) and comes back as it lands. */
    fun playerGroundAlpha(): Float {
        if (!fromPlayer || !drawCover || scrolledFallbackClose) return 1f
        return when (phase) {
            // Going: the ground carries the player's cover, and is gone within the first fifth of the way (the copy, which
            // is that very picture, has taken over). Coming back it returns with the copy, not early: the page the trip
            // left shows through a ground that is not there yet.
            HeroPhase.Opening -> 1f - smooth((cp() / 0.2f).coerceIn(0f, 1f), 0f, 1f)
            HeroPhase.Settled -> 0f
            HeroPhase.Closing -> if (landedBack || handingOver(cp())) 1f else 1f - drawn(cp())
            else -> 1f
        }
    }

    /** The player's own controls during a trip from it; outside one they are simply there. */
    fun playerUiAlpha(): Float =
        if (fromPlayer && phase != HeroPhase.Idle && !(scrolledFallbackClose && phase == HeroPhase.Closing)) {
            uiAlpha.value.coerceAtLeast(0.004f)
        } else {
            1f
        }

    /** The player's controls settle into place as they come back (12 dp), rather than appearing where they are. */
    fun playerUiRise(): Float = 0f

    companion object {
        /** Whether the cover of a card flies to its page (as on an album's) or not (as on a playlist's): see [begin]. */
        const val COVERS_FLY = false

        const val ROLE_COVER = 0
        const val ROLE_TITLE = 1
        const val ROLE_SUB = 2
        private const val HANDOVER = 0.04f
    }

    /** How much of an item of the list that is clearing (or coming back) is still there, and how big. */
    internal fun rippleOf(center: Offset, own: Boolean, unitPx: Float, fast: Boolean = false): Pair<Float, Float> {
        if (fromPlayer) return 1f to 1f
        val c = srcCover?.center ?: srcTitle?.rect?.center ?: return 1f to 1f
        val dx = center.x - c.x
        val dy = center.y - c.y
        val dist = sqrt(dx * dx + dy * dy * 0.6f) / unitPx
        return when (phase) {
            HeroPhase.Opening, HeroPhase.Settled -> {
                // The touched item goes at once (it is where the trip starts); its cover and texts are already out of
                // sight, this takes what is left of it (a count, a date) away too.
                val delay = if (own || fast) 0f else min(40f, dist / 22f)
                val t = ((clearMs() - delay) / (if (fast) 110f else 150f)).coerceIn(0f, 1f)
                val e = EaseIn.transform(t)
                (1f - e) to (1f - 0.04f * e)
            }
            HeroPhase.Closing -> {
                if (own) {
                    val t = ((returnMs() - 200f) / 220f).coerceIn(0f, 1f)
                    return EaseOut.transform(t) to 1f
                }
                if (fast) return EaseOut.transform(((returnMs() - 90f) / 240f).coerceIn(0f, 1f)) to 1f
                val delay = 110f + min(100f, dist / 9f)
                val t = ((returnMs() - delay) / 300f).coerceIn(0f, 1f)
                val e = EaseOut.transform(t)
                e to (0.96f + 0.04f * e)
            }
            HeroPhase.Idle -> 1f to 1f
        }
    }
}

val LocalHero = staticCompositionLocalOf { HeroState() }

/** Marks a source element: reports its place, and hides it while the hero stands in for it. */
@Composable
fun Modifier.heroAnchor(
    key: String,
    anchor: HeroAnchor,
    role: Int = HeroState.ROLE_COVER,
    hero: HeroState = LocalHero.current,
    text: String? = null,
    fromPlayer: Boolean = false,
): Modifier =
    this
        .onGloballyPositioned {
            anchor.coords = it
            if (hero.phase != HeroPhase.Settled && hero.phase != HeroPhase.Closing) return@onGloballyPositioned
            val full = it.fullRectInRoot()
            if (full != null) {
                hero.updateSource(key, role, full, text, it.containerClipInRoot(full), anchor, fromPlayer)
            }
        }
        .graphicsLayer { if (hero.hidesSource(key, role, anchor, fromPlayer)) alpha = 0f }

/**
 * Marks an item of a list the hero leaves behind: it steps back to 96 % and clears (200 ms, the nearer to the cover the
 * earlier), and comes back the same way from the touched item outwards. [key] is the item's own trip key; [pageKey] is
 * the enclosing hero destination page's key (if this list lives inside a hero page like `ArtistScreen`).
 */
@Composable
fun Modifier.heroRipple(
    key: String? = null,
    hero: HeroState = LocalHero.current,
    pageKey: String? = null,
    fast: Boolean = false,
    // For something that blends with what is behind it (the header's translucent title): the trip's fade is folded into
    // this one layer, with its own alpha and shift, since a layer above it would isolate it and blank it for the trip.
    blend: androidx.compose.ui.graphics.BlendMode? = null,
    baseAlpha: (() -> Float)? = null,
    shiftX: (() -> Float)? = null,
): Modifier {
    val center = remember { floatArrayOf(Float.NaN, Float.NaN) }
    val unitPx = with(LocalDensity.current) { designUnit().toPx() }
    return this
        .onGloballyPositioned {
            if (hero.phase == HeroPhase.Idle) return@onGloballyPositioned
            val full = it.fullRectInRoot()
            if (full != null) {
                val c = full.center
                center[0] = c.x
                center[1] = c.y
            }
        }
        .graphicsLayer {
            if (blend != null) blendMode = blend
            val base = baseAlpha?.invoke() ?: 1f
            if (shiftX != null) translationX = shiftX()
            alpha = base
            if (hero.phase == HeroPhase.Idle) return@graphicsLayer
            if (pageKey != null && hero.matchesTarget(pageKey)) return@graphicsLayer
            // Not placed yet (composed during the trip): treated as being at the touched item, so it never shows at full
            // strength for a frame, and this block still reads the trip's clocks, so it is redrawn with them.
            val at = if (center[0].isNaN()) hero.srcCover?.center ?: Offset.Zero else Offset(center[0], center[1])
            val (a, s) = hero.rippleOf(at, key != null && hero.matchesSource(key), unitPx, fast)
            alpha = a * base
            scaleX = s
            scaleY = s
        }
}

/** The third line of a card of a list the hero leaves (see [HeroState.sideLineAlpha]). */
@Composable
fun Modifier.heroSideLine(key: String, hero: HeroState = LocalHero.current): Modifier =
    graphicsLayer { alpha = hero.sideLineAlpha(key) }

/** Marks the next page's own cover or text: reports its place, and stays hidden until the copy lands on it. */
@Composable
fun Modifier.heroTarget(
    key: String,
    hero: HeroState = LocalHero.current,
    radiusPx: Float = 0f,
    text: HeroText? = null,
    isSub: Boolean = false,
): Modifier {
    val coordsRef = remember { arrayOfNulls<LayoutCoordinates>(1) }
    SideEffect {
        val c = coordsRef[0]
        if (c != null && c.isAttached && text != null) {
            val full = c.fullRectInRoot()
            if (full != null) {
                val t = text.at(full, c.containerClipInRoot(full))
                hero.reportTarget(key, null, OPEN_CLIP, 0f, if (isSub) null else t, if (isSub) t else null, c)
            }
        }
    }
    return this
        .onGloballyPositioned { c ->
            coordsRef[0] = c
            val full = c.fullRectInRoot() ?: return@onGloballyPositioned
            val clip = c.containerClipInRoot(full)
            if (text == null) {
                hero.reportTarget(key, full, clip, radiusPx, null, null, c)
            } else {
                val t = text.at(full, clip)
                hero.reportTarget(key, null, OPEN_CLIP, 0f, if (isSub) null else t, if (isSub) t else null, c)
            }
        }
        .graphicsLayer {
            val own = when {
                text == null -> hero.coverWithoutFlightAlpha(key)
                isSub -> hero.lineWithoutSourceAlpha(key)
                else -> null
            }
            if (own != null) {
                alpha = own
            } else if (text == null && !hero.drawCover) {
                // When covers do not fly, the page cover stays visible and fades out with the page (via heroPageOut)
                alpha = 1f
            } else if (hero.hidesTarget(key)) {
                alpha = 0f
            }
        }
}

/**
 * What a page brings with it (the line under the title, the actions, the rows): when it comes with a trip it rises
 * 28 design px and appears, 280 ms for the header and 300 ms for the rows, [index] steps of 18/12 ms from 90/170 ms; it
 * leaves in 140 ms, 16 px down, on the way back. Outside a trip it is simply there.
 */
@Composable
fun Modifier.heroRise(index: Int, hero: HeroState = LocalHero.current, rows: Boolean = false): Modifier {
    val fromTrip = remember { hero.phase == HeroPhase.Opening }
    val myTripId = remember {
        when (hero.phase) {
            HeroPhase.Opening, HeroPhase.Settled -> hero.tripId
            HeroPhase.Closing -> hero.parentTripId()
            HeroPhase.Idle -> -1
        }
    }
    // A list's rows (and the actions above them) only fade in, in place and all but together, once the words of the trip
    // have gone over where they stand: before, the flying title crossed them and neither could be read.
    val p = remember { Animatable(if (fromTrip) 0f else 1f) }
    if (fromTrip) {
        LaunchedEffect(Unit) {
            val elapsed = (System.nanoTime() - hero.tripStartNanos) / 1_000_000L
            // Tight, so the page reads as arriving in one gesture: the words, the actions and the rows are still a beat
            // apart (the wave), but no group waits its turn visibly.
            val wait = if (rows) 170L + index.coerceIn(0, 7) * 12L else 90L + index.coerceIn(0, 9) * 18L
            delay((wait - elapsed).coerceAtLeast(0L))
            p.animateTo(1f, tween(if (rows) 300 else 280, easing = if (rows) FastOutSlowInEasing else EaseOut))
        }
    }
    val unitPx = with(LocalDensity.current) { designUnit().toPx() }
    // Once the page has been sent away it stays away: the trip's end (Idle) must not bring it back for the frames the
    // navigation still keeps it composed.
    val left = remember { BooleanArray(1) }
    return this.graphicsLayer {
        // Only the trip this page came with is its own: a page opened without one (-1) is not closing when the album it opened is.
        val isClosingMine = !hero.fromPlayer && hero.phase == HeroPhase.Closing && myTripId != -1 && hero.tripId == myTripId
        if (isClosingMine) left[0] = true
        // A trip that is only restored (the one this page's own was opened from, put back as the return ends) does not bring a
        // page that has left back for the frames it is still kept: only a new trip does, or its own, resting again.
        else if (hero.phase == HeroPhase.Opening || (hero.phase == HeroPhase.Settled && hero.tripId == myTripId)) left[0] = false
        val out = if (isClosingMine || left[0]) EaseIn.transform(if (isClosingMine) hero.contentOut.value else 1f) else 0f
        alpha = p.value * (1f - out)
        // Rows rise less than the page's other parts (14 against 28 design px): enough to read as arriving, not enough to
        // cross the title in flight.
        translationY = (if (rows) 14f else 28f) * unitPx * (1f - p.value) + 16f * unitPx * out
    }
}

/**
 * A cover's own arrival, read off the trip's clock (the one the title fades on) rather than an animation of its own: it
 * therefore always moves, and at the very speed of the words beside it. [coverWithoutFlightAlpha] gives the fade;
 * the rise is the same progress. Null outside such a trip, where the cover is simply there.
 */
@Composable
fun Modifier.coverArrival(key: String, hero: HeroState = LocalHero.current): Modifier {
    val unitPx = with(LocalDensity.current) { designUnit().toPx() }
    return this.graphicsLayer {
        val fade = hero.coverWithoutFlightAlpha(key) ?: return@graphicsLayer
        translationY = 28f * unitPx * (1f - fade)
    }
}

/**
 * The body of a page a trip ends on (its list): on the way back it leaves as a whole in 140 ms, wherever it was scrolled
 * to.
 */
@Composable
fun Modifier.heroPageOut(hero: HeroState = LocalHero.current): Modifier {
    val myTripId = remember {
        when (hero.phase) {
            HeroPhase.Opening, HeroPhase.Settled -> hero.tripId
            HeroPhase.Closing -> hero.parentTripId()
            HeroPhase.Idle -> -1
        }
    }
    val left = remember { BooleanArray(1) }
    return this.graphicsLayer {
        if (!hero.fromPlayer && hero.phase == HeroPhase.Closing && myTripId != -1 && hero.tripId == myTripId) {
            left[0] = true
            alpha = 1f - EaseIn.transform(hero.contentOut.value)
        } else if (left[0]) {
            if (hero.phase == HeroPhase.Opening || (hero.phase == HeroPhase.Settled && hero.tripId == myTripId)) left[0] = false else alpha = 0f
        }
    }
}

/** A page's background: arrives in 460 ms after 100 when it comes with a trip, leaves in 380 ms after 140 on the way back. */
@Composable
fun rememberHeroGround(hero: HeroState = LocalHero.current): State<Float> {
    // From the player the page's ground is there from the start: the player fades over it, both ways, and a ground
    // still arriving (or already leaving) under it showed as a dark gap.
    val fromTrip = remember { hero.phase == HeroPhase.Opening && !hero.fromPlayer }
    val myTripId = remember {
        when (hero.phase) {
            HeroPhase.Opening, HeroPhase.Settled -> hero.tripId
            HeroPhase.Closing -> hero.parentTripId()
            HeroPhase.Idle -> -1
        }
    }
    val a = remember { Animatable(if (fromTrip) 0f else 1f) }
    val left = remember { BooleanArray(1) }
    LaunchedEffect(hero.phase, hero.tripId) {
        when (hero.phase) {
            // Not for a page that has left while the trip put back under it is only being restored: its ground would fade back in
            // for the frames the page is still kept, and the old page would show again.
            HeroPhase.Opening, HeroPhase.Settled -> if (a.value < 1f && !(left[0] && hero.tripId != myTripId)) {
                if (hero.phase == HeroPhase.Opening && hero.tripId == myTripId) delay(100)
                a.animateTo(1f, tween(460, easing = FastOutSlowInEasing))
            }
            HeroPhase.Closing -> if (!hero.fromPlayer && myTripId != -1 && hero.tripId == myTripId) {
                left[0] = true
                delay(30)
                a.animateTo(0f, tween(190, easing = EaseIn))
            }
            HeroPhase.Idle -> if (!left[0]) a.snapTo(1f) else a.snapTo(0f)
        }
    }
    return remember(a) { object : State<Float> { override val value: Float get() = a.value } }
}

/** Draws the floating copy and runs the trip. Over the pages, under the player's card and the search. */
@Composable
fun HeroOverlay(hero: HeroState = LocalHero.current, reduceMotion: Boolean = false) {
    val k = if (reduceMotion) 0.2f else 1f
    fun ms(v: Int) = (v * k).toInt().coerceAtLeast(1)
    val phase = hero.phase
    val context = LocalContext.current
    val localDensity = LocalDensity.current
    val sizePx = hero.coverSizeDp?.let { with(localDensity) { it.toPx().toInt() } } ?: AlbumArtCache.DEFAULT_SIZE_PX
    // The copy does not set off before its picture is in memory: a trip that left from a grey square read as a flash.
    var imageReadyKey by remember { mutableStateOf<String?>(null) }
    val pictureKey = "${hero.uri}|${hero.cacheKey}|$sizePx"
    LaunchedEffect(pictureKey, phase) {
        if (phase == HeroPhase.Opening && hero.uri != null && hero.drawCover) {
            AlbumArtCache.load(context, hero.uri, sizePx, hero.cacheKey ?: hero.uri.toString(), shrink = true)
        }
        imageReadyKey = pictureKey
    }
    val imageReady = !hero.drawCover || hero.uri == null || imageReadyKey == pictureKey
    val hasCoverTrip = hero.drawCover && hero.srcCover != null
    val hasTextTrip = hero.srcTitle != null || hero.srcSub != null
    val ready = imageReady && when {
        hasCoverTrip -> hero.tgtCover != null
        hasTextTrip -> hero.tgtTitle != null || hero.tgtSub != null
        else -> true
    }
    // A page that only has a name for the cover: the name goes, the cover fades towards it.
    val nameOnly = hasCoverTrip && hero.tgtCover == null && hero.tgtTitle != null
    // The texts' places are known (or will not be: a page may have no place for one of them).
    val textsReady = (hero.srcTitle == null || hero.tgtTitle != null) && (hero.srcSub == null || hero.tgtSub != null)
    var waitedForTexts by remember(phase, hero.key) { mutableStateOf(false) }

    // The list clears from the first frame of the trip, whether or not the page has said where things go yet.
    LaunchedEffect(phase, hero.tripId) {
        when (phase) {
            HeroPhase.Opening -> {
                hero.waitFade.snapTo(0f)
                hero.clearClock.snapTo(0f)
                hero.clearRunning = true
                hero.contentOut.snapTo(0f)
                coroutineScope {
                    launch { hero.clearClock.animateTo(310f, tween(ms(310), easing = LinearEasing)) }
                }
            }
            HeroPhase.Closing -> {
                hero.waitFade.snapTo(0f)
                hero.returnClock.snapTo(0f)
                hero.returnRunning = true
                if (hero.fromPlayer) {
                    hero.running = true
                    hero.coverP.snapTo(0f)
                    hero.textP.snapTo(0f)
                    hero.uiAlpha.snapTo(1f)
                    hero.buttonAlpha.snapTo(1f)
                    delay(ms(380).toLong())
                    hero.reset()
                    return@LaunchedEffect
                }
                if (hero.scrolledFallbackClose) {
                    hero.running = true
                    coroutineScope {
                        launch { hero.contentOut.animateTo(1f, tween(ms(260), easing = FastOutSlowInEasing)) }
                        launch { hero.coverP.animateTo(0f, tween(ms(340), easing = FastOutSlowInEasing)) }
                        launch { hero.textP.animateTo(0f, tween(ms(340), easing = FastOutSlowInEasing)) }
                        launch { hero.uiAlpha.animateTo(1f, tween(ms(340), easing = FastOutSlowInEasing)) }
                        launch { hero.buttonAlpha.animateTo(1f, tween(ms(240), easing = LinearEasing)) }
                        // The list it comes back to returns on its own clock, as after a normal return: without it the
                        // items stayed out until the trip's end and the screen was empty meanwhile.
                        if (!hero.fromPlayer) launch { hero.returnClock.animateTo(560f, tween(ms(560), easing = LinearEasing)) }
                    }
                    hero.uiAlpha.snapTo(1f)
                    hero.reset()
                    return@LaunchedEffect
                }
                if (!hero.running) {
                    hero.coverP.snapTo(0f)
                    hero.textP.snapTo(0f)
                    hero.buttonAlpha.snapTo(1f)
                    hero.running = true
                }
                val closeDur = if (hero.lyricsMode) 380 else 520
                val closeEasing = if (hero.lyricsMode) FastOutSlowInEasing else Morph
                coroutineScope {
                    launch { hero.contentOut.animateTo(1f, tween(ms(if (hero.lyricsMode) 260 else 140), easing = FastOutSlowInEasing)) }
                    launch { hero.coverP.animateTo(0f, tween(ms(closeDur), easing = closeEasing)) }
                    launch { hero.textP.animateTo(0f, tween(ms(closeDur), easing = closeEasing)) }
                    launch {
                        delay(((closeDur - 340).coerceAtLeast(60) * k).toLong())
                        hero.buttonAlpha.animateTo(1f, tween(ms(220), easing = LinearEasing))
                    }
                    // The player's controls come back while the cover is still settling, not after it: the player is usable
                    // at once and never looks empty.
                    if (hero.fromPlayer) launch {
                        delay((if (hero.lyricsMode) 20 else 40 * k).toLong())
                        hero.uiAlpha.animateTo(1f, tween(ms(if (hero.lyricsMode) 340 else 360), easing = FastOutSlowInEasing))
                    }
                    // A list is still coming back after the cover has landed: the trip is over with its last item. Back to
                    // the player there is no list: the trip is over when the cover is home and the controls are back.
                    if (!hero.fromPlayer) launch { hero.returnClock.animateTo(800f, tween(ms(800), easing = LinearEasing)) }
                }
                hero.uiAlpha.snapTo(1f)
                hero.reset()
            }
            HeroPhase.Idle -> {
                hero.uiAlpha.snapTo(1f)
                hero.buttonAlpha.snapTo(1f)
            }
            HeroPhase.Settled -> Unit
        }
    }
    var started by remember(phase, hero.key, hero.tripId) { mutableStateOf(false) }
    LaunchedEffect(phase, hero.tripId, ready, nameOnly, textsReady, waitedForTexts) {
        if (phase != HeroPhase.Opening) return@LaunchedEffect
        if (!started) {
            if (nameOnly) {
                // Give the page a moment to say where the cover goes; past it, the cover fades towards the name.
                delay(300)
                hero.coverHasNoPlace()
                return@LaunchedEffect
            }
            if (!ready) {
                // The page is built in a few frames, or a few seconds when it comes from a source: meanwhile the copy
                // stays where it left from. If it never says where things go, the trip is simply dropped (the page is
                // there, nothing is sent back to a place that is no longer on screen).
                delay(300)
                hero.waitFade.animateTo(1f, tween(ms(240), easing = FastOutSlowInEasing))
                delay(3500)
                if (hero.phase == HeroPhase.Opening && hero.tgtCover == null && hero.tgtTitle == null) hero.reset()
                return@LaunchedEffect
            }
            if (!textsReady && !waitedForTexts) {
                // The cover's place is known; the words' come a frame or two later. A text that has no place goes out.
                delay(300)
                waitedForTexts = true
                return@LaunchedEffect
            }
            hero.coverP.snapTo(0f)
            hero.textP.snapTo(0f)
            hero.buttonAlpha.snapTo(1f)
            hero.running = true
            started = true
        }
        // Restarted on the way (a place reported late): carries on from where it is.
        coroutineScope {
            launch { hero.coverP.animateTo(1f, tween(ms(if (hero.lyricsMode) 640 else 620), easing = Morph)) }
            launch { hero.textP.animateTo(1f, tween(ms(if (hero.lyricsMode) 560 else 540), easing = Morph)) }
            launch { hero.buttonAlpha.animateTo(0f, tween(ms(110), easing = LinearEasing)) }
            // The player's controls go when the copy leaves, not before: a page that is slow to say where things go
            // (an artist's albums) left the player stripped and still for half a second.
            if (hero.fromPlayer) launch { hero.uiAlpha.animateTo(0f, tween(ms(140), easing = EaseOut)) }
        }
        hero.settle()
    }

    if (phase != HeroPhase.Opening && phase != HeroPhase.Closing) return
    if (hero.scrolledFallbackClose) return
    val src = hero.srcCover
    // What the travelling copy draws with on each frame, made once for the trip instead of once per frame.
    val drawCache = remember { HeroDrawCache() }

    // While the cover is travelling nothing underneath is touchable: the finger that has just opened a page is still
    // on the screen where that page's rows are, and a second touch used to land on one of them (and play it).
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (hero.copyVisible) event.changes.forEach { it.consume() }
                    }
                }
            }
            .graphicsLayer { alpha = if (hero.copyVisible) 1f else 0f },
    ) {
        if (hero.drawCover && src != null) {
            val tgt = hero.tgtCover ?: src
            val baseW = max(src.width, tgt.width).coerceAtLeast(1f)
            val baseH = max(src.height, tgt.height).coerceAtLeast(1f)
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        val p = hero.drawn(hero.cp())
                        val to = hero.tgtCover ?: src
                        val toClip = if (hero.tgtCover != null) hero.tgtClip else hero.srcClip
                        val c = lerpContainerClip(src, hero.srcClip, to, toClip, p)
                        val fadeBottom = hero.srcVisibleFromY
                        // Full at the card's place and for the first half of the way, then it lets go: a copy that is half-faded
                        // on its way in showed as a pale ghost above the card, where the page shows nothing.
                        val s = ((1f - p) * 2f).coerceIn(0f, 1f)
                        if (fadeBottom != null && s > 0.01f) {
                            // At the card's place the copy is faded as the page fades the card (the same gradient), so that it
                            // takes its place and leaves it with no jump; the fade lets go as it travels.
                            drawIntoCanvas { canvas ->
                                canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height), drawCache.layerPaint)
                                clipRect(c.left, c.top, c.right, c.bottom) { this@drawWithContent.drawContent() }
                                // Kept with DstIn by a ramp from 1 - s (to 0.6) up to 1 is the same as taking away with
                                // DstOut a fixed ramp from 1 (to 0.6) down to 0 drawn at alpha s: 1 - s(1 - r) = (1 - s) + s r.
                                // The ramp depends on fadeBottom alone, so it is made once for the trip, not on every frame.
                                drawRect(
                                    drawCache.fadeFor(fadeBottom),
                                    size = androidx.compose.ui.geometry.Size(size.width, fadeBottom),
                                    alpha = s,
                                    blendMode = androidx.compose.ui.graphics.BlendMode.DstOut,
                                )
                                canvas.restore()
                            }
                        } else {
                            clipRect(c.left, c.top, c.right, c.bottom) {
                                this@drawWithContent.drawContent()
                            }
                        }
                    }
            ) {
                Box(
                    Modifier
                        .layout { m, c ->
                            val pl = m.measure(Constraints.fixed(baseW.roundToInt().coerceAtLeast(1), baseH.roundToInt().coerceAtLeast(1)))
                            layout(c.maxWidth, c.maxHeight) { pl.place(0, 0) }
                        }
                        .graphicsLayer {
                            val p = hero.drawn(hero.cp())
                            val to = hero.tgtCover ?: src
                            val curW = lerpF(src.width, to.width, p).coerceAtLeast(1f)
                            val curH = lerpF(src.height, to.height, p).coerceAtLeast(1f)
                            val curLeft = lerpF(src.left, to.left, p)
                            val curTop = lerpF(src.top, to.top, p)
                            val sx = curW / size.width.coerceAtLeast(1f)
                            val sy = curH / size.height.coerceAtLeast(1f)
                            val toR = if (hero.tgtCover != null) hero.tgtRadiusPx else hero.srcRadiusPx
                            // Interpolate cornerRadius continuously across the entire 0..1 transition in both directions
                            // so the cover never becomes square mid-flight.
                            val unscaledRadius = (lerpF(hero.srcRadiusPx, toR, smooth(p, 0f, 1f)) / sx.coerceAtLeast(0.01f)).coerceAtLeast(0f)
                            transformOrigin = TransformOrigin(0f, 0f)
                            shape = RoundedCornerShape(unscaledRadius)
                            clip = true
                            compositingStrategy = CompositingStrategy.Offscreen
                            alpha = (if (hero.coverFades) 1f - p else 1f) * hero.handoverAlpha(hero.cp())
                            if (hero.lyricsMode) {
                                // Out of focus, darker, a little larger, then gone (the board's 70 px blur, x1.15, gone by 520 ms).
                                // A twin of the ground left in place under it; on the way back it hands over to the player (names and
                                // controls included) as soon as it is sharp again (see drawn()).
                                alpha = (1f - EaseIn.transform((p / 0.81f).coerceIn(0f, 1f))) * hero.handoverAlpha(hero.cp())
                                val z = 1f + 0.15f * p
                                scaleX = sx * z
                                scaleY = sy * z
                                translationX = curLeft - curW * (z - 1f) * 0.5f
                                translationY = curTop - curH * (z - 1f) * 0.5f
                                val blurPx = 70f * p * curW / 1080f
                                renderEffect = if (blurPx >= 1f) {
                                    androidx.compose.ui.graphics.BlurEffect(blurPx, blurPx, androidx.compose.ui.graphics.TileMode.Decal)
                                } else null
                            } else {
                                scaleX = sx
                                scaleY = sy
                                translationX = curLeft
                                translationY = curTop
                                renderEffect = null
                            }
                        }
                        .drawWithContent {
                            drawContent()
                            if (hero.lyricsMode) {
                                drawRect(Color.Black.copy(alpha = 0.4f * hero.drawn(hero.cp())))
                            }
                            if (hero.srcPlayButton && hero.bp() > 0.001f) {
                                // The card's own play button, as the card draws it (bottom right: soft shadow, rounded glyph), going
                                // as the card leaves: it fades and shrinks a little where it is.
                                val a = hero.bp()
                                val r = size.width * (48f / 456f) * (0.8f + 0.2f * a)
                                val cx = size.width - size.width * (74f / 456f)
                                val cy = size.height - size.height * (84f / 456f)
                                drawCircle(Color(0xFFA9DCEF).copy(alpha = a), radius = r, center = Offset(cx, cy))
                                val tri = drawCache.triangle.apply {
                                    rewind()
                                    moveTo(cx - 0.26f * r, cy - 0.34f * r)
                                    lineTo(cx + 0.34f * r, cy)
                                    lineTo(cx - 0.26f * r, cy + 0.34f * r)
                                    close()
                                }
                                val glyph = Color(0xFF0E1A1F).copy(alpha = a)
                                drawPath(tri, glyph)
                                drawPath(tri, glyph, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.12f * r, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                            }
                        },
                ) {
                    if (hero.uri != null && !hero.lyricsMode) {
                        AlbumArt(
                            uri = hero.uri,
                            contentDescription = null,
                            shape = RoundedCornerShape(0.dp),
                            modifier = Modifier.fillMaxSize(),
                            targetSizeDp = hero.coverSizeDp,
                            cacheKey = hero.cacheKey,
                        )
                    }
                    val picture = hero.srcPicture
                    if (picture != null) {
                        // At the player's end the copy is the ground itself (the cover already melted into the aurora), so it
                        // takes off and lands without a seam; it turns into the sharp cover over the first half of the way.
                        Canvas(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = if (hero.lyricsMode) 1f else 1f - FastOutSlowInEasing.transform((hero.drawn(hero.cp()) / 0.55f).coerceIn(0f, 1f))
                                },
                        ) {
                            val s = size.width / src.width
                            drawImage(
                                picture,
                                dstOffset = IntOffset((-src.left * s).roundToInt(), (-src.top * s).roundToInt()),
                                dstSize = IntSize((picture.width * s).roundToInt(), (picture.height * s).roundToInt()),
                            )
                        }
                    }
                }
            }
        }
        HeroTextCopy(hero, hero.srcTitle, hero.tgtTitle)
        HeroTextCopy(hero, hero.srcSub, hero.tgtSub)
    }
}

/**
 * Decoupled text transition:
 * Texts (Title / Artist) are never clipped by or routed across the cover's geometric bounds.
 * Instead, the departure text fades out quickly at its origin with a subtle vertical drift,
 * and the arrival text fades in smoothly at its destination without any cover-frame clipping.
 */
@Composable
private fun HeroTextCopy(hero: HeroState, from: HeroText?, to: HeroText?) {
    if (from == null) return
    val end = to ?: from
    val dy = (end.rect.top - from.rect.top).coerceIn(-48f, 48f) * 0.25f

    Box(Modifier.fillMaxSize()) {
        HeroTextLayer(
            t = from,
            tintColor = { from.color },
            alpha = {
                val p = hero.drawn(hero.tp())
                val fadeOut = 1f - smooth(p, 0f, 0.28f)
                hero.handoverAlpha(hero.tp()) * fadeOut * (1f - hero.waitFade.value)
            },
        ) {
            val p = hero.drawn(hero.tp())
            val progressOut = smooth(p, 0f, 0.32f)
            TextPlacement(
                x = from.rect.left,
                y = from.rect.top + dy * progressOut,
                scaleX = 1f,
                scaleY = 1f,
            )
        }
        if (to != null) {
            HeroTextLayer(
                t = end,
                tintColor = { end.color },
                alpha = {
                    val p = hero.drawn(hero.tp())
                    val fadeIn = smooth(p, 0.48f, 0.92f)
                    hero.handoverAlpha(hero.tp()) * fadeIn
                },
            ) {
                val p = hero.drawn(hero.tp())
                val progressIn = 1f - smooth(p, 0.42f, 0.95f)
                TextPlacement(
                    x = end.rect.left,
                    y = end.rect.top - dy * progressIn,
                    scaleX = 1f,
                    scaleY = 1f,
                )
            }
        }
    }
}

private data class TextPlacement(val x: Float, val y: Float, val scaleX: Float, val scaleY: Float)

/**
 * The width a copy is laid out in: the element's own. An element that hugs its text measured it at a fractional width
 * that rounds down; one pixel over keeps the copy on the same lines as the element, no more (more would let it wrap
 * differently and jump when the real one takes over).
 */
private fun textWidth(t: HeroText): Int =
    if (t.widthPx > 0) t.widthPx else kotlin.math.ceil(t.rect.width).toInt().coerceAtLeast(1) + 1

@Composable
private fun HeroTextLayer(
    t: HeroText,
    tintColor: () -> Color,
    alpha: () -> Float,
    place: () -> TextPlacement,
) {
    Text(
        text = t.text,
        style = t.style.copy(textAlign = t.align),
        color = Color.White,
        maxLines = t.maxLines,
        softWrap = t.maxLines > 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .layout { m, c ->
                val w = textWidth(t)
                val pl = m.measure(Constraints(minWidth = if (t.align == TextAlign.Start) 0 else w, maxWidth = w))
                layout(c.maxWidth, c.maxHeight) { pl.place(0, 0) }
            }
            .graphicsLayer {
                val p = place()
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = p.scaleX
                scaleY = p.scaleY
                translationX = p.x
                translationY = p.y
                this.alpha = alpha()
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                drawRect(color = tintColor(), blendMode = BlendMode.SrcIn)
            },
    )
}

private fun smooth(p: Float, a: Float, b: Float): Float {
    val t = ((p - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * Keeps a press that starts inside the system back-gesture edge zone from rippling the list items under it (the system takes that
 * touch as a back swipe). Anywhere else the touch is the children's: a carousel's drag and a swipe to dismiss are left alone.
 */
fun Modifier.suppressEdgeBackRipple(): Modifier = this.pointerInput(Unit) {
    val edgeZonePx = 20.dp.toPx()
    awaitEachGesture {
        val down = awaitPointerEvent(PointerEventPass.Initial)
        val firstChange = down.changes.firstOrNull() ?: return@awaitEachGesture
        val startX = firstChange.position.x
        if (startX <= edgeZonePx || startX >= size.width - edgeZonePx) {
            down.changes.forEach { it.consume() }
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
        }
    }
}

/**
 * Applies a smooth vertical alpha gradient mask at the top and/or bottom of a scrollable list
 * so content fades cleanly before the status bar or navigation bar.
 */
fun Modifier.verticalFadingEdge(
    topFade: Dp = 28.dp,
    bottomFade: Dp = 36.dp,
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    // The mask is made once per size, not on every frame the list scrolls.
    .drawWithCache {
        val h = size.height.coerceAtLeast(1f)
        val topStop = (topFade.toPx() / h).coerceIn(0f, 0.25f)
        val bottomStop = (1f - (bottomFade.toPx() / h).coerceIn(0f, 0.25f)).coerceAtLeast(topStop)
        val mask = androidx.compose.ui.graphics.Brush.verticalGradient(
            0f to Color.Transparent,
            topStop to Color.Black,
            bottomStop to Color.Black,
            1f to Color.Transparent,
        )
        onDrawWithContent {
            drawContent()
            drawRect(brush = mask, blendMode = BlendMode.DstIn)
        }
    }

/** The travelling copy's drawing objects, reused frame after frame (see [HeroOverlay]). */
private class HeroDrawCache {
    val layerPaint = androidx.compose.ui.graphics.Paint()
    val triangle = Path()
    private var fadeBottom = Float.NaN
    private var fade: androidx.compose.ui.graphics.Brush? = null

    /** The ramp the copy is faded by under the card's own fade: 1 down to 0.6 of [bottom], then down to nothing at it. */
    fun fadeFor(bottom: Float): androidx.compose.ui.graphics.Brush {
        fade?.let { if (bottom == fadeBottom) return it }
        fadeBottom = bottom
        return androidx.compose.ui.graphics.Brush.verticalGradient(
            0f to Color.Black, 0.6f to Color.Black, 1f to Color.Transparent, startY = 0f, endY = bottom,
        ).also { fade = it }
    }
}
