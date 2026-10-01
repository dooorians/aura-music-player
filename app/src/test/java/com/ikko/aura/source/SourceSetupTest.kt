package com.ikko.aura.source

import com.ikko.aura.source.remote.normaliseBaseUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceSetupTest {
    @Test fun bareHostGetsAScheme() = assertEquals("http://192.168.1.20:4533", normaliseBaseUrl("192.168.1.20:4533"))

    @Test fun trailingSlashesGo() = assertEquals("https://music.example.org", normaliseBaseUrl("https://music.example.org///"))

    @Test fun apiPathsPastedFromTheBrowserAreCutOff() {
        assertEquals("http://nas:4533", normaliseBaseUrl("http://nas:4533/rest"))
        assertEquals("http://nas:4533", normaliseBaseUrl("http://nas:4533/rest/"))
        assertEquals("http://nas:4533", normaliseBaseUrl("http://nas:4533/rest/ping.view?u=a&t=b"))
        assertEquals("http://nas:4533", normaliseBaseUrl("http://nas:4533/app/#/albums"))
        assertEquals("http://nas:4533", normaliseBaseUrl("http://nas:4533/app"))
    }

    @Test fun aReverseProxyPathStays() {
        assertEquals("https://home.example.org/navidrome", normaliseBaseUrl("https://home.example.org/navidrome/"))
        assertEquals("https://home.example.org/navidrome", normaliseBaseUrl("https://home.example.org/navidrome/app/"))
    }

    @Test fun blankStaysBlank() = assertEquals("", normaliseBaseUrl("   "))

    @Test fun orderKeepsStoredThenAppendsNew() {
        val available = listOf("local", "ytm", "sub1", "sub2")
        assertEquals(listOf("sub2", "local", "ytm", "sub1"), SourceOrder.merge(listOf("sub2", "local"), available))
    }

    @Test fun orderDropsWhatIsGoneAndDuplicates() {
        assertEquals(listOf("ytm", "local"), SourceOrder.merge(listOf("ytm", "gone", "ytm"), listOf("local", "ytm")))
    }
}
