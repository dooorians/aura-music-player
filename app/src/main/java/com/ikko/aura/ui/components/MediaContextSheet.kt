package com.ikko.aura.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.layout
import com.ikko.aura.data.model.Album
import com.ikko.aura.data.model.Track
import com.ikko.aura.source.ytm.DownloadEntry
import com.ikko.aura.source.ytm.DownloadState
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.theme.CupertinoIcons
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What a long press landed on. The menu's contents follow from it. */
sealed interface LibraryContextTarget {
    data class TrackTarget(val track: Track) : LibraryContextTarget
    data class AlbumTarget(val album: Album, val tracks: List<Track>) : LibraryContextTarget

    /** An album of an external source, not in the library: its songs are fetched when an action needs them. */
    data class RemoteAlbumTarget(
        val ref: String?, val title: String, val artist: String, val artworkUrl: String?,
        /** The songs, when the source gave them with the album and it has no page to fetch them from. */
        val tracks: List<Track> = emptyList(),
    ) : LibraryContextTarget
}

/**
 * Everything you can do to a track or an album without leaving the list, as BitChord lays it out: the screen behind goes dark
 * and soft, a card shows what was pressed (a song's cover and names, or an album's large cover), and under it a panel of
 * actions in groups. A song's panel begins with three quick answers (J'aime, Je n'aime pas, Partager). The panel is the
 * three-dots menu's own - its colour taken from the page, its rim and light edge - and scrolls, fading at its ends, when it
 * is taller than the screen allows.
 *
 * Deletion is last and red, and it is the only entry that leads anywhere destructive: it hands off to the system's own
 * confirmation, which is both required on modern Android for media the app did not write and the right place for that
 * decision to be made.
 */
@Composable
fun MediaContextSheet(
    target: LibraryContextTarget,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenArtist: () -> Unit,
    onDelete: () -> Unit,
    /** A song streamed from an external source: no file to delete, and its album is looked up when asked. */
    streamed: Boolean = false,
    keptInLibrary: Boolean = false,
    onToggleKeep: () -> Unit = {},
    streamedHasAlbum: Boolean = false,
    /** An album downloaded from another source: its menu deletes the downloads, not files of the system's. */
    streamedAlbum: Boolean = false,
    /** The streamed song's download, when it has one. */
    download: DownloadEntry? = null,
    onDownload: () -> Unit = {},
    onRemoveDownload: () -> Unit = {},
    /** For an album of the library: whether it is a favourite, and the way to change that. Null hides the line. */
    albumFavorite: Boolean? = null,
    onToggleAlbumFavorite: () -> Unit = {},
    /** A song, or an album of a source: liked (the star), and the answers that feed the recommendations. */
    liked: Boolean = false,
    onToggleLike: () -> Unit = {},
    onDislike: () -> Unit = {},
    onShare: () -> Unit = {},
    onPlay: () -> Unit = {},
    onShuffle: () -> Unit = {},
    onInfo: () -> Unit = {},
    onRadio: () -> Unit = {},
    /** Where the press landed, in window coordinates: the menu grows out of it. */
    origin: androidx.compose.ui.geometry.Offset? = null,
    onDownloadAll: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var boxPos by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var menuCenter by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(210)) }
    // The menu goes out the way it came before whatever was asked takes over.
    fun close(then: () -> Unit = {}) {
        scope.launch {
            progress.animateTo(0f, tween(140))
            onDismiss()
            then()
        }
    }
    /** An action that ends the menu. */
    fun act(run: () -> Unit): () -> Unit = { run(); close() }

    val base = menuBase()
    fun surfaceOf(shade: Float): Color {
        val c = base.toArgb()
        return Color(
            android.graphics.Color.rgb(
                (android.graphics.Color.red(c) * shade).roundToInt(),
                (android.graphics.Color.green(c) * shade).roundToInt(),
                (android.graphics.Color.blue(c) * shade).roundToInt(),
            ),
        )
    }
    val panelSurface = remember(base) { surfaceOf(0.30f) }
    val cardSurface = remember(base) { surfaceOf(0.22f) }
    val corner = RoundedCornerShape(22.4.dp)
    val track = (target as? LibraryContextTarget.TrackTarget)?.track
    val album = (target as? LibraryContextTarget.AlbumTarget)?.album
    val remote = target as? LibraryContextTarget.RemoteAlbumTarget
    val isAlbum = album != null || remote != null

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setWindowAnimations(0)
            window?.setDimAmount(0f)
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.58f * progress.value))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() }
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BoxWithConstraints(if (origin != null) Modifier.fillMaxSize().onGloballyPositioned { boxPos = it.positionInWindow() } else Modifier) {
                val panelMax = maxHeight - 0.dp
                Column(
                    Modifier
                        .then(
                            if (origin == null) Modifier
                            else Modifier.layout { m, c ->
                                // The menu sits where the press was: the cover over the item, the list under it, kept on screen.
                                val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
                                val x = (origin.x - boxPos.x - MenuWidth.toPx() / 2f).coerceIn(0f, (c.maxWidth - p.width).coerceAtLeast(0).toFloat())
                                val y = (origin.y - boxPos.y - 40.dp.toPx()).coerceIn(0f, (c.maxHeight - p.height).coerceAtLeast(0).toFloat())
                                layout(c.maxWidth, c.maxHeight) { p.place(x.roundToInt(), y.roundToInt()) }
                            },
                        )
                        .width(MenuWidth)
                        .onGloballyPositioned { menuCenter = it.positionInWindow() + androidx.compose.ui.geometry.Offset(it.size.width / 2f, it.size.height / 2f) }
                        .graphicsLayer {
                            val p = progress.value
                            val from = origin
                            if (from != null && menuCenter != androidx.compose.ui.geometry.Offset.Zero) {
                                // Out of the pressed item, to its place.
                                transformOrigin = TransformOrigin.Center
                                translationX = (from.x - menuCenter.x) * (1f - p)
                                translationY = (from.y - menuCenter.y) * (1f - p)
                                val sc = 0.35f + 0.65f * p
                                scaleX = sc
                                scaleY = sc
                            } else {
                                transformOrigin = TransformOrigin(0.1f, 0.5f)
                                val sc = 0.9f + 0.1f * p
                                scaleX = sc
                                scaleY = sc
                            }
                            alpha = (p / 0.55f).coerceIn(0f, 1f)
                        },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // The actions.
                    val scroll = rememberScrollState()
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = panelMax)
                            .clip(corner)
                            .background(panelSurface)
                            .border(BorderStroke(0.6.dp, Color.White.copy(alpha = 0.18f)), corner)
                            .drawBehind { drawRect(Color.White.copy(alpha = 0.12f), size = Size(size.width, 0.8.dp.toPx())) }
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                // The ends fade where there is more to scroll to.
                                val edge = 28.dp.toPx().coerceAtMost(size.height / 3f)
                                val top = (scroll.value / edge).coerceIn(0f, 1f)
                                val bottom = ((scroll.maxValue - scroll.value) / edge).coerceIn(0f, 1f)
                                if (top > 0f || bottom > 0f) {
                                    val f = edge / size.height
                                    drawRect(
                                        Brush.verticalGradient(
                                            0f to Color.Black.copy(alpha = 1f - top), f to Color.Black,
                                            1f - f to Color.Black, 1f to Color.Black.copy(alpha = 1f - bottom),
                                        ),
                                        blendMode = BlendMode.DstIn,
                                    )
                                }
                            }
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                            .verticalScroll(scroll)
                            .padding(4.8.dp),
                    ) {
                        if (track != null) {
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
                                QuickAction(if (liked) CupertinoIcons.StarFilled else CupertinoIcons.Star, "J'aime", Modifier.weight(1f), act(onToggleLike))
                                QuickAction(CupertinoIcons.Close, "Je n'aime pas", Modifier.weight(1f), act(onDislike))
                                QuickAction(CupertinoIcons.Share, "Partager", Modifier.weight(1f), act(onShare))
                            }
                            DesignMenuDivider()
                            DesignMenuRow("Ajouter à la playlist", onAddToPlaylist, CupertinoIcons.PlaylistAdd)
                            DesignMenuDivider()
                            DesignMenuRow("Lire ensuite", act(onPlayNext), CupertinoIcons.Play)
                            DesignMenuRow("Ajouter à la file d'attente", act(onAddToQueue), CupertinoIcons.Queue)
                            DesignMenuRow("Lancer la radio", act(onRadio), CupertinoIcons.Radio)
                            if (streamed) {
                                when (download?.state) {
                                    DownloadState.DONE -> Unit
                                    DownloadState.QUEUED, DownloadState.DOWNLOADING -> Unit
                                    else -> DesignMenuRow("Télécharger", act(onDownload), CupertinoIcons.ArrowDownCircle)
                                }
                            }
                            DesignMenuDivider()
                            if (!streamed || streamedHasAlbum) DesignMenuRow("Ouvrir l'album", act(onOpenAlbum), CupertinoIcons.Album, subtitle = track.album.ifBlank { null })
                            DesignMenuRow("Ouvrir l'artiste", act(onOpenArtist), CupertinoIcons.Person, subtitle = track.artist.ifBlank { null })
                            DesignMenuRow("Informations sur le titre", act(onInfo), CupertinoIcons.Info)
                            if (streamed) {
                                DesignMenuDivider()
                                val downloaded = download?.state == DownloadState.DONE
                                if (downloaded) {
                                    DesignMenuRow("Supprimer le titre et son téléchargement", act(onRemoveDownload), CupertinoIcons.Delete, danger = true)
                                } else if (keptInLibrary) {
                                    DesignMenuRow("Retirer de la bibliothèque", act(onToggleKeep), CupertinoIcons.Delete, danger = true)
                                } else {
                                    DesignMenuRow("Ajouter à la bibliothèque", act(onToggleKeep), CupertinoIcons.Add)
                                }
                            } else {
                                DesignMenuDivider()
                                DesignMenuRow("Supprimer le fichier", onDelete, CupertinoIcons.Delete, danger = true)
                            }
                        } else if (album != null) {
                            DesignMenuRow("Lecture", act(onPlay), CupertinoIcons.Play)
                            DesignMenuRow("Aléatoire", act(onShuffle), CupertinoIcons.Shuffle)
                            DesignMenuDivider()
                            DesignMenuRow("Lire ensuite", act(onPlayNext), CupertinoIcons.Queue)
                            DesignMenuRow("Ajouter à la file d'attente", act(onAddToQueue), CupertinoIcons.Queue)
                            DesignMenuRow("Ajouter à une playlist", onAddToPlaylist, CupertinoIcons.PlaylistAdd)
                            DesignMenuRow("Lancer la radio", act(onRadio), CupertinoIcons.Radio)
                            if (onDownloadAll != null) DesignMenuRow("Tout télécharger", act(onDownloadAll), CupertinoIcons.ArrowDownCircle)
                            DesignMenuDivider()
                            DesignMenuRow("Ouvrir l'album", act(onOpenAlbum), CupertinoIcons.Album)
                            DesignMenuRow("Ouvrir l'artiste", act(onOpenArtist), CupertinoIcons.Person, subtitle = album.artist.ifBlank { null })
                            if (albumFavorite != null) {
                                DesignMenuRow(
                                    if (albumFavorite) "Retirer des favoris" else "Ajouter aux favoris", act(onToggleAlbumFavorite),
                                    if (albumFavorite) CupertinoIcons.StarFilled else CupertinoIcons.Star,
                                )
                            }
                            DesignMenuRow("Partager", act(onShare), CupertinoIcons.Share)
                            DesignMenuDivider()
                            DesignMenuRow(
                                if (streamedAlbum) "Supprimer les téléchargements" else "Supprimer l'album",
                                onDelete, CupertinoIcons.Delete, danger = true,
                            )
                        } else if (remote != null) {
                            // Not in the library: what the listener thinks of it feeds the recommendations.
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
                                QuickAction(if (liked) CupertinoIcons.StarFilled else CupertinoIcons.Star, "J'aime", Modifier.weight(1f), act(onToggleLike))
                                QuickAction(CupertinoIcons.Close, "Je n'aime pas", Modifier.weight(1f), act(onDislike))
                                QuickAction(CupertinoIcons.Share, "Partager", Modifier.weight(1f), act(onShare))
                            }
                            DesignMenuDivider()
                            DesignMenuRow("Lecture", act(onPlay), CupertinoIcons.Play)
                            DesignMenuRow("Aléatoire", act(onShuffle), CupertinoIcons.Shuffle)
                            DesignMenuDivider()
                            DesignMenuRow("Lire ensuite", act(onPlayNext), CupertinoIcons.Queue)
                            DesignMenuRow("Ajouter à la file d'attente", act(onAddToQueue), CupertinoIcons.Queue)
                            DesignMenuRow("Ajouter à une playlist", onAddToPlaylist, CupertinoIcons.PlaylistAdd)
                            DesignMenuRow("Lancer la radio", act(onRadio), CupertinoIcons.Radio)
                            if (onDownloadAll != null) DesignMenuRow("Tout télécharger", act(onDownloadAll), CupertinoIcons.ArrowDownCircle)
                            DesignMenuDivider()
                            if (remote.ref != null) DesignMenuRow("Ouvrir l'album", act(onOpenAlbum), CupertinoIcons.Album)
                            DesignMenuRow("Ouvrir l'artiste", act(onOpenArtist), CupertinoIcons.Person, subtitle = remote.artist.ifBlank { null })
                        }
                    }
                }
            }
        }
    }
}

private val MenuWidth = 270.dp

@Composable
private fun PreviewText(title: String, subtitle: String) {
    Text(
        title, color = Color.White, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
    Text(
        subtitle, color = Color.White.copy(alpha = 0.62f), fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** One of the three quick answers over a song's panel: an icon above its name. */
@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(5.dp))
        Text(
            label, color = Color.White, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
