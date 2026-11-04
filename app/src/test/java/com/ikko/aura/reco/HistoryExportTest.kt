package com.ikko.aura.reco

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HistoryExportTest {
    private fun listen(id: String, artist: String, outcome: Outcome, at: Long, origin: PlayOrigin = PlayOrigin.QUEUE, played: Long = 200_000L) =
        ExportListen(id, "Title $id", artist, "Album", 200_000L, played, outcome.name, at, origin.name)

    private val export = HistoryExport(
        exportedAtMs = 10L,
        library = listOf(ExportTrack("1", "Title 1", "Charli XCX", "Brat", listOf("Electropop")), ExportTrack("2", "Title 2", "Queen", "Jazz")),
        listens = listOf(
            listen("2", "Queen", Outcome.SKIPPED_EARLY, at = 300L, played = 4_000L),
            listen("1", "Charli XCX", Outcome.COMPLETED, at = 100L, origin = PlayOrigin.MANUAL),
            // A stream no longer in the library.
            listen("ytm:z", "Someone", Outcome.INTERRUPTED, at = 200L, origin = PlayOrigin.AUTOPLAY, played = 30_000L),
        ),
    )

    @Test fun `the file reads back as it was written`() {
        assertEquals(export, HistoryCodec.decode(HistoryCodec.encode(export)))
    }

    @Test fun `a file written by a newer app with more fields still reads`() {
        val text = HistoryCodec.encode(export).replace("\"version\":1", "\"version\":1,\"somethingNew\":[1,2,3]")
        assertEquals(export, HistoryCodec.decode(text))
    }

    @Test fun `listens come back in the order they happened with their outcome and who chose them`() {
        val (library, events) = HistoryCodec.toReplay(export)
        assertEquals(listOf("1", "ytm:z", "2"), events.map { it.trackId })
        assertEquals(listOf(Transition.AUTO, Transition.QUEUE_REPLACED, Transition.USER_SKIP), events.map { it.transition })
        assertTrue(events[0].manual && !events[0].autoplay)
        assertTrue(events[1].autoplay && !events[1].manual)
        // The stream is not in the library file but is in the replay's.
        assertTrue(library.any { it.id == "ytm:z" })
        assertEquals(setOf("1", "2", "ytm:z"), library.map { it.id }.toSet())
        assertEquals(setOf("Electropop"), library.first { it.id == "1" }.genres)
    }

    @Test fun `an empty history replays to nothing`() {
        val (library, events) = HistoryCodec.toReplay(HistoryExport(exportedAtMs = 0L, library = emptyList(), listens = emptyList()))
        assertTrue(library.isEmpty() && events.isEmpty())
        assertEquals(0, ReplayEvaluator.run(library, events).steps)
    }

    /**
     * Judges the ranker on a real export: set AURA_HISTORY to the path of the file the app wrote (Réglages, Recommandations, Données) and run
     * this test; it prints the report. Skipped when the variable is not set.
     */
    @Test fun `report on a real exported history`() {
        val path = System.getenv("AURA_HISTORY")
        assumeTrue("AURA_HISTORY is not set", !path.isNullOrBlank() && File(path).isFile)
        val (library, events) = HistoryCodec.toReplay(HistoryCodec.decode(File(path!!).readText()))
        val report = ReplayEvaluator.run(library, events)
        println("HISTORY listens=${events.size} library=${library.size}")
        println("HISTORY $report lift=${report.liftAt10}")
        assertFalse(report.steps < 0)
    }
}
