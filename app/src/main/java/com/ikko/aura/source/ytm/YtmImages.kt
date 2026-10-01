package com.ikko.aura.source.ytm

/**
 * YouTube Music hands out covers at 60 or 120 px, but the server behind those addresses will give the same picture at
 * any size asked for in the address itself (`=w120-h120-l90-rj`). Left as listed, a cover looked soft in a list and
 * blurry full-screen in the player.
 */
object YtmImages {
    private val SIZED = Regex("=w\\d+-h\\d+")
    private val SQUARE = Regex("=s\\d+(?=-|$)")

    /** [url] asking for a [px] square, when it is one that can be resized; any other address is returned as it is. */
    fun sized(url: String?, px: Int = COVER_PX): String? {
        if (url.isNullOrEmpty()) return url
        if ("googleusercontent.com" !in url && "ggpht.com" !in url) return url
        return when {
            SIZED.containsMatchIn(url) -> url.replace(SIZED, "=w$px-h$px")
            SQUARE.containsMatchIn(url) -> url.replace(SQUARE, "=s$px")
            else -> url
        }
    }

    /** Big enough for the player's cover on this screen (about 850 px wide) and its blurred backdrop. */
    const val COVER_PX = 800
}
