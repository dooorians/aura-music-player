package com.ikko.aura.source.ytm

/**
 * YouTube Music hands out covers at 60 or 120 px, but the server behind those addresses will give the same picture at
 * any size asked for in the address itself (`=w120-h120-l90-rj`). Left as listed, a cover looked soft in a list and
 * blurry full-screen in the player.
 */
object YtmImages {
    private val SIZED = Regex("=w\\d+-h\\d+")
    private val SQUARE = Regex("=s\\d+(?=-|$)")

    /** A video's thumbnail: `i.ytimg.com/vi/<id>/<name>.jpg`, with or without the `_webp` variant and its query. */
    private val VIDEO_THUMB = Regex("^https?://(?:i\\d?\\.ytimg\\.com|img\\.youtube\\.com)/vi(?:_webp)?/([\\w-]{11})/[\\w]+\\.(?:jpg|webp)")

    /**
     * [url] asking for a [px] square, when it is one that can be resized; any other address is returned as it is.
     *
     * A song met through a video (the "up next" list) has that video's frame for cover, 336 px wide with its query: for an
     * official release that frame is the album's art, square, centred in a 16:9 picture with side bars. Its best version is
     * the 1280 x 720 one (the art then is 720 px a side, against 188 in the small one); the app crops the sides.
     */
    fun sized(url: String?, px: Int = COVER_PX): String? {
        if (url.isNullOrEmpty()) return url
        VIDEO_THUMB.find(url)?.let { return "https://i.ytimg.com/vi/${it.groupValues[1]}/maxresdefault.jpg" }
        if ("googleusercontent.com" !in url && "ggpht.com" !in url) return url
        return when {
            SIZED.containsMatchIn(url) -> url.replace(SIZED, "=w$px-h$px")
            SQUARE.containsMatchIn(url) -> url.replace(SQUARE, "=s$px")
            else -> url
        }
    }

    /** Big enough for the player's cover on this screen (about 850 px wide) and its blurred backdrop. */
    const val COVER_PX = 800

    /**
     * The same picture at lower sizes, to fall back on when the best one does not exist (the 1280 px frame is only made
     * for videos uploaded in HD). Empty for any address that has no such alternatives.
     */
    fun fallbacks(url: String): List<String> {
        val id = VIDEO_THUMB.find(url)?.groupValues?.get(1) ?: return emptyList()
        return listOf("sddefault", "hqdefault").map { "https://i.ytimg.com/vi/$id/$it.jpg" }
    }
}
