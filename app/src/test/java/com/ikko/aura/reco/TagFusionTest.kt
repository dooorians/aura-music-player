package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagFusionTest {

    @Test fun `one style written many ways is one genre`() {
        for (raw in listOf("Hip Hop", "hip-hop", "Rap", "rap/hip-hop", "HIPHOP")) assertEquals(raw, "hip-hop", TagFusion.canonical(raw))
        assertEquals("drum & bass", TagFusion.canonical("Drum n Bass"))
        assertEquals("drum & bass", TagFusion.canonical("dnb"))
        assertEquals("french rap", TagFusion.canonical("Rap Français"))
        assertEquals("afrobeats", TagFusion.canonical("afrobeat"))
    }

    @Test fun `what is about the listener or the file is no style`() {
        for (noise in listOf("seen live", "favorites", "female vocalists", "2010s", "", "  ", "Unknown", "(12)")) assertNull(noise, TagFusion.canonical(noise))
    }

    @Test fun `sources agree into one vector, the listener's own tag weighing most`() {
        val v = TagFusion.fuse(listOf(listOf("Hip-Hop") to TagFusion.W_FILE, listOf("rap", "hip hop", "trap", "seen live") to TagFusion.W_ARTIST))
        assertEquals(setOf("hip-hop", "trap"), v.weights.keys)
        assertTrue(v.weights.getValue("hip-hop") > v.weights.getValue("trap"))
        assertEquals(1.0, v.weights.values.sumOf { it * it }, 1e-9)
        assertEquals(1.0, v.confidence, 1e-9)
    }

    @Test fun `the first tags of a list say more than the last`() {
        val v = TagFusion.fuse(listOf(listOf("shoegaze", "dream pop", "noise rock", "post-rock") to 1.0))
        val w = v.weights
        assertTrue(w.getValue("shoegaze") > w.getValue("dream pop") && w.getValue("dream pop") > w.getValue("post-rock"))
    }

    @Test fun `similarity - identical, same family, neighbours, worlds apart`() {
        val kpop = TagFusion.uniform(listOf("K-Pop"))
        val synth = TagFusion.uniform(listOf("Synthpop"))
        val metal = TagFusion.uniform(listOf("Death Metal"))
        val rnb = TagFusion.uniform(listOf("R&B"))
        val hiphop = TagFusion.uniform(listOf("Hip-Hop"))
        assertEquals(1.0, TagFusion.similarity(kpop, kpop), 1e-9)
        val family = TagFusion.similarity(kpop, synth)
        val neighbours = TagFusion.similarity(rnb, hiphop)
        assertTrue("family $family", family in 0.3..0.8)
        assertTrue("neighbours $neighbours", neighbours in 0.1..family)
        assertEquals(0.0, TagFusion.similarity(kpop, metal), 1e-9)
        assertEquals(0.0, TagFusion.similarity(kpop, TagFusion.GenreVector.Empty), 0.0)
    }

    @Test fun `two songs tagged only with a broad genre are a weak tie`() {
        val pop = TagFusion.uniform(listOf("Pop"))
        assertTrue(pop.confidence < 0.5)
        val s = TagFusion.similarity(pop, pop)
        assertTrue("pop vs pop $s", s in 0.2..0.5)
        // A specific genre next to it lifts the confidence.
        assertTrue(TagFusion.uniform(listOf("Pop", "Synthpop")).confidence > pop.confidence)
    }

    @Test fun `bad weights and empty lists give the empty vector`() {
        assertTrue(TagFusion.fuse(emptyList()).isEmpty)
        assertTrue(TagFusion.fuse(listOf(listOf("rock") to Double.NaN, listOf("jazz") to -1.0, emptyList<String>() to 1.0)).isEmpty)
    }

    @Test fun `similarity is symmetric and bounded on real tag lists`() {
        val lists = listOf(
            listOf("indie rock", "alternative", "rock"), listOf("k-pop", "dance-pop", "pop"), listOf("jazz", "bossa nova"),
            listOf("trap", "hip-hop", "rap"), listOf("ambient", "electronic", "idm"), listOf("chanson", "french pop"),
        ).map { TagFusion.uniform(it) }
        for (a in lists) for (b in lists) {
            val ab = TagFusion.similarity(a, b)
            assertTrue(ab in 0.0..1.0)
            assertEquals(ab, TagFusion.similarity(b, a), 1e-9)
        }
    }
}
