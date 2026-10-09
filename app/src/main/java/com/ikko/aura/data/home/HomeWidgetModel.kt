package com.ikko.aura.data.home

import androidx.compose.runtime.Immutable

/**
 * The formats a Home widget can take, as cells of a two-column grid whose rows are half a column wide: a COMPACT is a
 * half-height tile, a MEDIUM a square, a WIDE a full-width bar, a HERO a full-width banner. Changing a widget's size is
 * changing this one value; the grid re-packs and the card picks the layout that suits its new shape.
 */
enum class WidgetSize(val cols: Int, val rows: Int, val label: String) {
    COMPACT(cols = 1, rows = 1, label = "Compact"),
    MEDIUM(cols = 1, rows = 2, label = "Moyen"),
    WIDE(cols = 2, rows = 1, label = "Large"),
    HERO(cols = 2, rows = 3, label = "Hero");

    companion object {
        const val GRID_COLUMNS = 2

        fun fromName(name: String?): WidgetSize? = entries.firstOrNull { it.name == name }
    }
}

/**
 * What a widget shows. One kind per way of choosing a list of music; [takesArgument] kinds are pointed at one thing
 * (a category, an album, a playlist) by [HomeWidget.arg]. The kind decides the content, never the size: any kind can be
 * given any [WidgetSize].
 */
enum class WidgetKind(
    val key: String,
    val title: String,
    val takesArgument: Boolean,
    val defaultSize: WidgetSize,
) {
    FAVORITE_ALBUMS("favorite_albums", "Mix Favoris", false, WidgetSize.MEDIUM),
    RECENT_TRACKS("recent_tracks", "Récents", false, WidgetSize.COMPACT),
    RESUME("resume", "Reprendre", false, WidgetSize.WIDE),
    REDISCOVER("rediscover", "Découvertes du jour", false, WidgetSize.COMPACT),
    TOP_PLAYED("top_played", "Les plus écoutés", false, WidgetSize.COMPACT),
    DAILY_MIX("daily_mix", "Sélection du moment", false, WidgetSize.MEDIUM),
    FOR_YOU("for_you", "Pour toi", false, WidgetSize.COMPACT),
    CATEGORY("category", "Catégorie", true, WidgetSize.COMPACT),
    ALBUM("album", "Album", true, WidgetSize.MEDIUM),
    PLAYLIST("playlist", "Playlist", true, WidgetSize.MEDIUM);

    companion object {
        fun fromKey(key: String?): WidgetKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One slot of the Home grid, and everything that is saved about it. Its place in the list is its place on the page,
 * [hidden] keeps it out of the grid without forgetting its settings, and [arg] is the album id, the playlist id or the
 * category (see [HomeCategory]) a [WidgetKind.takesArgument] kind points at.
 */
@Immutable
data class HomeWidget(
    val id: String,
    val kind: WidgetKind,
    val arg: String? = null,
    val size: WidgetSize = kind.defaultSize,
    val hidden: Boolean = false,
)

/** What a [WidgetKind.CATEGORY] widget's argument can say, kept in one place so the picker and the resolver agree. */
object HomeCategory {
    const val HI_RES = "hires"
    const val LOSSLESS = "lossless"
    private const val DECADE_PREFIX = "decade:"

    fun decade(startYear: Int) = "$DECADE_PREFIX$startYear"

    /** The first year of the decade [arg] names, or null if it is not a decade. */
    fun decadeOf(arg: String?): Int? =
        arg?.takeIf { it.startsWith(DECADE_PREFIX) }?.removePrefix(DECADE_PREFIX)?.toIntOrNull()

    fun title(arg: String?): String = when {
        arg == HI_RES -> "Hi-Res"
        arg == LOSSLESS -> "Lossless"
        decadeOf(arg) != null -> "Années ${decadeOf(arg)}"
        else -> "Catégorie"
    }

    fun matches(arg: String?, isHiRes: Boolean, isLossless: Boolean, year: Int): Boolean = when {
        arg == HI_RES -> isHiRes
        arg == LOSSLESS -> isLossless
        decadeOf(arg) != null -> year in decadeOf(arg)!!..(decadeOf(arg)!! + 9)
        else -> false
    }
}

/** The six colour moods a "Pour toi" card can wear (see ForYouCard). */
enum class CardAmbience { FAVORIS, DECOUVERTE, NOUVEAUTES, ENERGIE, CHILL, NUIT }
