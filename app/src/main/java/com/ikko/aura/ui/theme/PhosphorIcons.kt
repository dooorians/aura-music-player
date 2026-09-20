package com.ikko.aura.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.ikko.aura.R

/** Phosphor (phosphoricons.com) Regular-weight vector drawables, used for every icon in Aura's UI. */
object PhosphorIcons {
    val Library: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_books)
    val ChevronLeft: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_caret_left)
    val ChevronDown: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_caret_down)
    val MoreVertical: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_dots_three_vertical)
    val Play: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_play)
    val Pause: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_pause)
    val SkipNext: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_skip_forward)
    val SkipPrevious: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_skip_back)
    val Shuffle: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_shuffle)
    val Repeat: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_repeat)
    val Star: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_star)
    val Heart: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_heart)
    val Queue: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_queue)
    val Headphones: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_headphones)
    val Sort: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_sort_ascending)
    val Add: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_plus)
    val Delete: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_trash)
    val Close: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_x)
    val Refresh: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_arrow_clockwise)
    val Person: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_user)
    val MusicNote: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_music_note)
    val DragHandle: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_dots_six)
    val Check: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_check)
    val AutoAwesome: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_sparkle)
    val Album: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_vinyl_record)
    val PlaylistMusic: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_playlist)
    val PlaylistAdd: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_ph_list_plus)
}
