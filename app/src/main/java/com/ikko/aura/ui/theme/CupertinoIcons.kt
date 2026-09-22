package com.ikko.aura.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.ikko.aura.R

/**
 * Cupertino Icons (the `cupertino_icons` set behind Flutter's iOS widgets), converted glyph by
 * glyph from CupertinoIcons.ttf into vector drawables. The transport glyphs are the filled ones,
 * as in iOS; the toggles come as an outline / filled pair (see FillableIcon).
 */
object CupertinoIcons {
    val Play: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_play_fill)
    val Pause: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_pause_fill)
    val SkipNext: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_forward_fill)
    val SkipPrevious: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_backward_fill)
    val Heart: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_heart)
    val HeartFilled: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_heart_fill)
    val Star: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_star)
    val StarFilled: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_star_fill)
    val Shuffle: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_shuffle)
    val Repeat: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_repeat)
    val RepeatOne: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_repeat_1)
    val AutoAwesome: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_sparkles)
    val ChevronLeft: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_chevron_left)
    val ChevronRight: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_chevron_right)
    val Search: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_search)
    val ChevronDown: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_chevron_down)
    val MoreVertical: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_ellipsis_vertical)
    val Add: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_plus)
    val Delete: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_trash)
    val Close: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_xmark)
    val Refresh: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_arrow_clockwise)
    val Person: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_person)
    val MusicNote: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_music_note)
    val DragHandle: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_line_horizontal_3)
    val Check: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_checkmark)
    val AlbumFilled: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_music_albums_fill)
    val PersonFilled: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_person_fill)
    val Album: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_music_albums)
    val Library: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_music_note_list)
    val PlaylistMusic: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_music_note_list)
    val PlaylistAdd: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_text_badge_plus)
    val Queue: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_list_bullet)
    val Headphones: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_headphones)
    val Sort: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cu_arrow_up_arrow_down)
}
