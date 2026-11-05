package com.ikko.aura.ui.components

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ikko.aura.data.model.Track
import com.ikko.aura.data.playlist.PlaylistSummary
import com.ikko.aura.ui.theme.CupertinoIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val playlistCustomArtCache = LruCache<String, Bitmap>(8)

/**
 * Loads and displays a custom image chosen by the user for a personal playlist.
 */
@Composable
fun CustomPlaylistArt(uriString: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = playlistCustomArtCache.get(uriString), key1 = uriString) {
        val cached = playlistCustomArtCache.get(uriString)
        if (cached != null) {
            value = cached
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(uriString)
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > 512) {
                        val scale = 512f / longest
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1),
                        )
                    }
                }.also { playlistCustomArtCache.put(uriString, it) }
            }.getOrNull()
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize(),
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.26f)),
            contentAlignment = Alignment.Center,
        ) {
            val u = designUnit()
            Icon(CupertinoIcons.PlaylistMusic, null, tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(u * 96f))
        }
    }
}

/**
 * Renders the square cover of a playlist:
 * 1. User custom photo if set.
 * 2. Animated mix video (or still poster) if it's a recognized mix category ("fav", "disc", "energy", "chill", "night").
 * 3. 2x2 album art mosaic if the playlist has 4+ distinct albums, or 1x1 full cover if 1-3.
 * 4. Sober gray placeholder if empty or no artwork available.
 */
@Composable
fun PlaylistSquareCover(
    playlist: PlaylistSummary,
    tracksById: Map<String, Track>,
    customArtworkUri: String?,
    modifier: Modifier = Modifier,
    playing: Boolean = true,
) {
    val u = designUnit()

    if (!customArtworkUri.isNullOrBlank()) {
        CustomPlaylistArt(uriString = customArtworkUri, modifier = modifier)
        return
    }

    val mixArtwork = remember(playlist.id, playlist.name) {
        mixArtworkForPlaylist(playlist.id, playlist.name)
    }
    if (mixArtwork != null) {
        MixArtworkView(artwork = mixArtwork, modifier = modifier.fillMaxSize(), playing = playing)
        return
    }

    if (playlist.trackCount > 0) {
        val effectiveUris = remember(playlist.coverUris, playlist.trackIds, tracksById) {
            if (playlist.coverUris.isNotEmpty()) playlist.coverUris
            else {
                val seen = mutableSetOf<String>()
                playlist.trackIds.mapNotNull { tracksById[it]?.artworkUri?.toString() }.filter { seen.add(it) }.take(4)
            }
        }

        when {
            effectiveUris.size >= 4 -> {
                // 2x2 mosaic
                val zeroShape = RoundedCornerShape(0.dp)
                val u0 = remember(effectiveUris[0]) { Uri.parse(effectiveUris[0]) }
                val u1 = remember(effectiveUris[1]) { Uri.parse(effectiveUris[1]) }
                val u2 = remember(effectiveUris[2]) { Uri.parse(effectiveUris[2]) }
                val u3 = remember(effectiveUris[3]) { Uri.parse(effectiveUris[3]) }
                Column(modifier = modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            AlbumArt(
                                uri = u0,
                                contentDescription = null,
                                shape = zeroShape,
                                modifier = Modifier.fillMaxSize(),
                                targetSizeDp = 48.dp,
                                cacheKey = effectiveUris[0],
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            AlbumArt(
                                uri = u1,
                                contentDescription = null,
                                shape = zeroShape,
                                modifier = Modifier.fillMaxSize(),
                                targetSizeDp = 48.dp,
                                cacheKey = effectiveUris[1],
                            )
                        }
                    }
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            AlbumArt(
                                uri = u2,
                                contentDescription = null,
                                shape = zeroShape,
                                modifier = Modifier.fillMaxSize(),
                                targetSizeDp = 48.dp,
                                cacheKey = effectiveUris[2],
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            AlbumArt(
                                uri = u3,
                                contentDescription = null,
                                shape = zeroShape,
                                modifier = Modifier.fillMaxSize(),
                                targetSizeDp = 48.dp,
                                cacheKey = effectiveUris[3],
                            )
                        }
                    }
                }
                return
            }
            effectiveUris.isNotEmpty() -> {
                // Single full cover
                val u0 = remember(effectiveUris[0]) { Uri.parse(effectiveUris[0]) }
                AlbumArt(
                    uri = u0,
                    contentDescription = null,
                    shape = RoundedCornerShape(0.dp),
                    modifier = modifier.fillMaxSize(),
                    targetSizeDp = 120.dp,
                    cacheKey = effectiveUris[0],
                )
                return
            }
        }
    }

    // Sober empty state: gray tile with icon
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.26f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            CupertinoIcons.PlaylistMusic,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.size(u * 96f),
        )
    }
}
