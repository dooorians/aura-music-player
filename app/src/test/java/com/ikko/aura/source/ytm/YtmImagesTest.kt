package com.ikko.aura.source.ytm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtmImagesTest {

    @Test
    fun `nothing stays nothing`() {
        assertNull(YtmImages.sized(null))
        assertEquals("", YtmImages.sized(""))
    }

    @Test
    fun `a sized google cover is asked for at the size wanted`() {
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w800-h800-l90-rj",
            YtmImages.sized("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj"),
        )
        assertEquals("https://yt3.ggpht.com/abc=w300-h300", YtmImages.sized("https://yt3.ggpht.com/abc=w60-h60", 300))
    }

    @Test
    fun `a square google cover keeps its trailing options`() {
        assertEquals("https://yt3.ggpht.com/x=s800-c-k", YtmImages.sized("https://yt3.ggpht.com/x=s88-c-k"))
        assertEquals("https://yt3.ggpht.com/x=s800", YtmImages.sized("https://yt3.ggpht.com/x=s88"))
    }

    @Test
    fun `an address from anywhere else is left alone`() {
        val other = "https://example.com/cover=w120-h120.jpg"
        assertEquals(other, YtmImages.sized(other))
        val unsized = "https://lh3.googleusercontent.com/abc"
        assertEquals(unsized, YtmImages.sized(unsized))
    }

    @Test
    fun `a video frame is swapped for its largest version, with fallbacks`() {
        val thumb = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg?sqp=abc&rs=xyz"
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", YtmImages.sized(thumb))
        assertEquals(
            listOf("https://i.ytimg.com/vi/dQw4w9WgXcQ/sddefault.jpg", "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"),
            YtmImages.fallbacks(thumb),
        )
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", YtmImages.sized("https://i9.ytimg.com/vi_webp/dQw4w9WgXcQ/mqdefault.webp"))
    }

    @Test
    fun `no fallbacks for an address that is not a video frame`() {
        assertTrue(YtmImages.fallbacks("https://lh3.googleusercontent.com/abc=w120-h120").isEmpty())
        assertTrue(YtmImages.fallbacks("https://i.ytimg.com/vi/short/hq.jpg").isEmpty())
    }
}
