package com.ikko.aura.data.home

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    PLAYLIST("playlist", "Playlist", true, WidgetSize.MEDIUM),
    /** A picture of the user's own: [HomeWidget.arg] is the path of the GIF copied into the app's storage. */
    GIF("gif", "GIF", true, WidgetSize.MEDIUM);

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

/** The layout a fresh install starts with: the reference's arrangement, built from what the library really knows. */
fun defaultHomeLayout(): List<HomeWidget> = listOf(
    HomeWidget("favorites", WidgetKind.FAVORITE_ALBUMS, size = WidgetSize.MEDIUM),
    HomeWidget("hires", WidgetKind.CATEGORY, HomeCategory.HI_RES, WidgetSize.COMPACT),
    HomeWidget("daily", WidgetKind.DAILY_MIX, size = WidgetSize.MEDIUM),
    HomeWidget("rediscover", WidgetKind.REDISCOVER, size = WidgetSize.COMPACT),
    HomeWidget("recent", WidgetKind.RECENT_TRACKS, size = WidgetSize.COMPACT),
    HomeWidget("for_you", WidgetKind.FOR_YOU, size = WidgetSize.COMPACT),
    HomeWidget("resume", WidgetKind.RESUME, size = WidgetSize.WIDE),
    HomeWidget("top", WidgetKind.TOP_PLAYED, size = WidgetSize.COMPACT, hidden = true),
)

@Serializable
internal data class HomeWidgetDto(
    val id: String,
    val kind: String,
    val arg: String? = null,
    val size: String,
    val hidden: Boolean = false,
)

/**
 * The saved form of the layout: a JSON list. Read defensively — an entry whose kind this version does not know, one
 * that needs an argument and has none, or a repeated id is dropped rather than failing the whole layout, so a layout
 * written by a newer build never leaves Home empty.
 */
object HomeLayoutCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(widgets: List<HomeWidget>): String = json.encodeToString(
        widgets.map { HomeWidgetDto(it.id, it.kind.key, it.arg, it.size.name, it.hidden) },
    )

    /** Null when there is nothing usable saved (nothing, or something unreadable): the caller starts from the default. */
    fun decode(raw: String?): List<HomeWidget>? {
        if (raw.isNullOrBlank()) return null
        val entries = runCatching { json.decodeFromString<List<HomeWidgetDto>>(raw) }.getOrNull() ?: return null
        val seen = HashSet<String>()
        return entries.mapNotNull { dto ->
            val kind = WidgetKind.fromKey(dto.kind) ?: return@mapNotNull null
            if (dto.id.isBlank() || !seen.add(dto.id)) return@mapNotNull null
            if (kind.takesArgument && dto.arg.isNullOrBlank()) return@mapNotNull null
            HomeWidget(
                id = dto.id,
                kind = kind,
                arg = dto.arg.takeIf { kind.takesArgument },
                size = WidgetSize.fromName(dto.size) ?: kind.defaultSize,
                hidden = dto.hidden,
            )
        }
    }
}

/** Every edit to the layout, as a function from a list to a list: no state here, so each one can be checked alone. */
object HomeLayoutOps {
    private inline fun List<HomeWidget>.mapWidget(id: String, change: (HomeWidget) -> HomeWidget) =
        map { if (it.id == id) change(it) else it }

    fun resize(widgets: List<HomeWidget>, id: String, size: WidgetSize) = widgets.mapWidget(id) { it.copy(size = size) }

    fun setHidden(widgets: List<HomeWidget>, id: String, hidden: Boolean) =
        widgets.mapWidget(id) { it.copy(hidden = hidden) }

    fun remove(widgets: List<HomeWidget>, id: String) = widgets.filterNot { it.id == id }

    /** Points [id] at something else; its size stays, since that is the user's choice of shape, not of content. */
    fun replaceContent(widgets: List<HomeWidget>, id: String, kind: WidgetKind, arg: String?) =
        widgets.mapWidget(id) { it.copy(kind = kind, arg = arg.takeIf { _ -> kind.takesArgument }) }

    /** A new widget at the end of the page, in the kind's own size, under an id no other widget has. */
    fun add(widgets: List<HomeWidget>, kind: WidgetKind, arg: String?): List<HomeWidget> {
        var n = widgets.size + 1
        while (widgets.any { it.id == "w$n" }) n++
        return widgets + HomeWidget("w$n", kind, arg.takeIf { kind.takesArgument }, kind.defaultSize)
    }

    /** [id] takes the place of [targetId] in the order, the widgets between them shifting by one: what a drag does. */
    fun moveTo(widgets: List<HomeWidget>, id: String, targetId: String): List<HomeWidget> {
        val from = widgets.indexOfFirst { it.id == id }
        val to = widgets.indexOfFirst { it.id == targetId }
        if (from < 0 || to < 0 || from == to) return widgets
        return widgets.toMutableList().also { it.add(to, it.removeAt(from)) }
    }

    /** One step earlier ([delta] < 0) or later among the widgets that are shown; hidden ones keep their places. */
    fun shift(widgets: List<HomeWidget>, id: String, delta: Int): List<HomeWidget> {
        val shown = widgets.filterNot { it.hidden }
        val at = shown.indexOfFirst { it.id == id }
        if (at < 0) return widgets
        val target = shown.getOrNull(at + delta) ?: return widgets
        return moveTo(widgets, id, target.id)
    }
}
