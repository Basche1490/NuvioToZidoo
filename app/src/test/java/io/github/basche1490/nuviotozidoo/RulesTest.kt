package io.github.basche1490.nuviotozidoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {

    private fun request(position: Long, fromStart: Boolean = false, title: String = "Show - S01E05") =
        LaunchRequest(
            url = "https://host/d/ABC/Show.S01E05.mkv",
            title = title,
            requestedPositionMs = position,
            fromStart = fromStart,
            skipSegments = emptyList(),
        )

    private val now = 1_800_000_000_000L

    @Test
    fun episodeTags() {
        assertEquals(EpisodeTag("Show", 2, 5), EpisodeTag.parse("Show - S02E05"))
        assertEquals(EpisodeTag("Show", 2, 5), EpisodeTag.parse("Show - S02E05 - The One"))
        assertEquals(EpisodeTag("Star Wars - Andor", 1, 12), EpisodeTag.parse("Star Wars - Andor - S01E12"))
        assertEquals("S02E05", EpisodeTag.parse("Show - S02E05")!!.code)
        assertNull(EpisodeTag.parse("Dune: Part Two"))
        assertNull(EpisodeTag.parse(null))
    }

    @Test
    fun startFromBeginningWins() {
        val record = ResumeRecord(900_000L, 2_000_000L, completed = false, savedAtMs = now)
        assertEquals(StartDecision(0L, StartSource.BEGINNING), ResumeRules.decide(request(500_000L, fromStart = true), record, now, true))
    }

    @Test
    fun ownRecordFurtherAlongWins() {
        val record = ResumeRecord(900_000L, 2_000_000L, completed = false, savedAtMs = now - 60_000L)
        assertEquals(StartDecision(900_000L, StartSource.OWN_RECORD), ResumeRules.decide(request(300_000L), record, now, true))
    }

    @Test
    fun nuvioWinsWhenItIsFurtherOrRecordIsUnusable() {
        val behind = ResumeRecord(200_000L, 2_000_000L, false, now)
        assertEquals(StartDecision(300_000L, StartSource.NUVIO), ResumeRules.decide(request(300_000L), behind, now, true))
        val barelyAhead = ResumeRecord(310_000L, 2_000_000L, false, now)
        assertEquals(StartSource.NUVIO, ResumeRules.decide(request(300_000L), barelyAhead, now, true).source)
        val finished = ResumeRecord(2_000_000L, 2_000_000L, true, now)
        assertEquals(StartSource.NUVIO, ResumeRules.decide(request(300_000L), finished, now, true).source)
        val old = ResumeRecord(900_000L, 2_000_000L, false, now - ResumeRules.MAX_AGE_MS - 1)
        assertEquals(StartSource.NUVIO, ResumeRules.decide(request(300_000L), old, now, true).source)
        val good = ResumeRecord(900_000L, 2_000_000L, false, now)
        assertEquals(StartSource.NUVIO, ResumeRules.decide(request(300_000L), good, now, useOwnRecords = false).source)
    }

    @Test
    fun noNuvioPositionStartsAtZero() {
        // Nuvio sends no position for a title it has as unwatched or finished: respect that.
        val record = ResumeRecord(900_000L, 2_000_000L, false, now)
        assertEquals(StartDecision(0L, StartSource.NONE), ResumeRules.decide(request(0L), record, now, true))
    }

    @Test
    fun keys() {
        assertEquals("show - s01e05", ResumeRules.key("Show - S01E05", "https://a/b/c.mkv?x=1"))
        assertEquals("dune|dune.2021.2160p.mkv", ResumeRules.key("Dune", "https://a/d/XYZ/Dune.2021.2160p.mkv?token=1"))
        assertEquals("a+b.mkv", ResumeRules.fileNameOf("https://h/p/a+b.mkv"))
        assertEquals("a b.mkv", ResumeRules.fileNameOf("https://h/p/a%20b.mkv"))
    }

    @Test
    fun nextEpisodeDetection() {
        val mark = CompletionMark("Show - S01E05", now - 30_000L)
        assertTrue(UpNextRules.isNextEpisode(mark, "Show - S01E06", now))
        assertTrue(UpNextRules.isNextEpisode(mark, "show - S02E01", now))
        assertFalse(UpNextRules.isNextEpisode(mark, "Show - S01E05", now)) // same episode again
        assertFalse(UpNextRules.isNextEpisode(mark, "Show - S01E04", now))
        assertFalse(UpNextRules.isNextEpisode(mark, "Other - S01E06", now))
        assertFalse(UpNextRules.isNextEpisode(mark, "Show - S01E06", now + UpNextRules.WINDOW_MS))
        assertFalse(UpNextRules.isNextEpisode(null, "Show - S01E06", now))
        assertFalse(UpNextRules.isNextEpisode(CompletionMark("Some Film", now), "Show - S01E01", now))
        assertTrue(UpNextRules.isNextEpisode(CompletionMark("One Piece", now - 1_000L), "One Piece", now))
    }

    @Test
    fun settling() {
        val dur = 2_640_000L
        assertNull(PlaybackResults.settle(0L, dur, null))
        assertEquals(PlaybackResult(1_000_000L, dur, EndReason.STOPPED), PlaybackResults.settle(1_000_000L, dur, null))
        assertEquals(PlaybackResult(dur, dur, EndReason.COMPLETED), PlaybackResults.settle(dur - 20_000L, dur, null))
        assertEquals(PlaybackResult(dur, dur, EndReason.COMPLETED), PlaybackResults.settle(2_550_000L, dur, 2_500_000L))
        assertEquals(PlaybackResult(2_450_000L, dur, EndReason.STOPPED), PlaybackResults.settle(2_450_000L, dur, 2_500_000L))
        assertEquals(PlaybackResult(5_000L, 0L, EndReason.STOPPED), PlaybackResults.settle(5_000L, 0L, null))
        // A short clip: the end window shrinks to a tenth of its length.
        assertEquals(EndReason.STOPPED, PlaybackResults.settle(40_000L, 60_000L, null)!!.reason)
    }
}
