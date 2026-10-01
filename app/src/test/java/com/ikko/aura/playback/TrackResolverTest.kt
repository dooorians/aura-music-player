package com.ikko.aura.playback

import com.ikko.aura.source.PlayableSource
import com.ikko.aura.source.SourceId
import com.ikko.aura.source.SourceProvider
import com.ikko.aura.source.SourceTrackRef
import com.ikko.aura.source.TrackBindings
import com.ikko.aura.source.TrackKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackResolverTest {

    private val local = SourceId.LOCAL
    private val remote = SourceId("ytm")

    private class FakeSource(
        override val id: SourceId,
        private val answers: Map<String, PlayableSource?>,
    ) : SourceProvider {
        override suspend fun playable(itemId: String): PlayableSource? = answers[itemId]
    }

    private fun playable(source: SourceId, item: String, mime: String? = null) =
        PlayableSource(SourceTrackRef(source, item), "uri://${source.value}/$item", mime, randomAccess = false)

    private fun bindings(vararg refs: SourceTrackRef) = TrackBindings { refs.toList() }

    @Test
    fun `one binding gives one candidate`() = runTest {
        val resolver = TrackResolver(
            bindings(SourceTrackRef(local, "1")),
            mapOf(local to FakeSource(local, mapOf("1" to playable(local, "1")))),
        )
        assertEquals(listOf("uri://local/1"), resolver.candidates(TrackKey("1")).map { it.uri })
    }

    @Test
    fun `a source that has just failed is left out`() = runTest {
        val a = SourceTrackRef(local, "1")
        val b = SourceTrackRef(remote, "x")
        val resolver = TrackResolver(
            bindings(a, b),
            mapOf(
                local to FakeSource(local, mapOf("1" to playable(local, "1"))),
                remote to FakeSource(remote, mapOf("x" to playable(remote, "x"))),
            ),
        )
        assertEquals(listOf(b), resolver.candidates(TrackKey("k"), exclude = setOf(a)).map { it.ref })
    }

    @Test
    fun `a source with no provider, or none that can play the item, is skipped`() = runTest {
        val resolver = TrackResolver(
            bindings(SourceTrackRef(remote, "gone"), SourceTrackRef(SourceId("nobody"), "z"), SourceTrackRef(local, "1")),
            mapOf(
                remote to FakeSource(remote, mapOf("gone" to null)),
                local to FakeSource(local, mapOf("1" to playable(local, "1"))),
            ),
        )
        assertEquals(listOf(SourceTrackRef(local, "1")), resolver.candidates(TrackKey("k")).map { it.ref })
    }

    @Test
    fun `the ranking is applied`() = runTest {
        val a = SourceTrackRef(remote, "x")
        val b = SourceTrackRef(local, "1")
        val providers = mapOf(
            remote to FakeSource(remote, mapOf("x" to playable(remote, "x"))),
            local to FakeSource(local, mapOf("1" to playable(local, "1"))),
        )
        val localFirst = TrackResolver(bindings(a, b), providers, order = { refs -> refs.sortedBy { it.source != local } })
        assertEquals(listOf(b, a), localFirst.candidates(TrackKey("k")).map { it.ref })
    }

    @Test
    fun `a candidate that is known not to be audio is dropped, an audio or unknown one is kept`() = runTest {
        val video = SourceTrackRef(remote, "v")
        val audio = SourceTrackRef(remote, "a")
        val unknown = SourceTrackRef(remote, "u")
        val resolver = TrackResolver(
            bindings(video, audio, unknown),
            mapOf(
                remote to FakeSource(
                    remote,
                    mapOf(
                        "v" to playable(remote, "v", "video/mp4"),
                        "a" to playable(remote, "a", "audio/webm"),
                        "u" to playable(remote, "u", null),
                    ),
                ),
            ),
        )
        assertEquals(listOf(audio, unknown), resolver.candidates(TrackKey("k")).map { it.ref })
    }

    @Test
    fun `nothing playable gives an empty list, not an error`() = runTest {
        val resolver = TrackResolver(bindings(SourceTrackRef(local, "1")), emptyMap())
        assertTrue(resolver.candidates(TrackKey("1")).isEmpty())
    }
}
