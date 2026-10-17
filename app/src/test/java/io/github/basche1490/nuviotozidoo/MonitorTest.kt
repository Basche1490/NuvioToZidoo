package io.github.basche1490.nuviotozidoo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorTest {

    /** Plays back a script of readings; null entries are failed reads. */
    private class FakeApi(readings: List<ZidooPlayStatus?>) : ZidooPlayerApi {
        private val queue = ArrayDeque(readings)
        val seeks = mutableListOf<Long>()
        override suspend fun status(): ZidooPlayStatus? = if (queue.isEmpty()) null else queue.removeFirst()
        override suspend fun seekTo(positionMs: Long): Boolean {
            seeks += positionMs
            return true
        }
        override suspend fun stop(): Boolean = true
    }

    private fun playing(pos: Long, dur: Long = 3_000_000L) = ZidooPlayStatus(dur, pos, ZidooPlayState.PLAYING)
    private fun paused(pos: Long, dur: Long = 3_000_000L) = ZidooPlayStatus(dur, pos, ZidooPlayState.PAUSED)

    private fun monitor(api: ZidooPlayerApi, start: Long = 0L, settings: SkipSettings? = null, segments: List<SkipSegment> = emptyList()) =
        ZidooPlaybackMonitor(
            api = api,
            planner = SkipPlanner(segments, settings ?: SkipSettings(false, false, false)),
            startPositionMs = start,
            startupTimeoutMs = 5_000L,
            pause = {},
        )

    @Test
    fun longPauseDoesNotEndPlayback() = runBlocking {
        val readings = mutableListOf<ZidooPlayStatus?>(null, playing(10_000L))
        repeat(20) { readings += paused(11_000L) }
        readings += playing(12_000L)
        readings += playing(13_000L)
        val api = FakeApi(readings)
        val samples = mutableListOf<ZidooSample>()
        val outcome = monitor(api).run(onStarted = {}, onSample = { samples += it })
        assertEquals(ZidooMonitorOutcome.Finished(13_000L, 3_000_000L), outcome)
        assertTrue(samples.any { it.paused })
    }

    @Test
    fun neverStarts() = runBlocking {
        val outcome = monitor(FakeApi(emptyList())).run(onStarted = {}, onSample = {})
        assertEquals(ZidooMonitorOutcome.PlayerDidNotStart, outcome)
    }

    @Test
    fun giveUpStartingEndsEarly() = runBlocking {
        var polls = 0
        val api = object : ZidooPlayerApi {
            override suspend fun status(): ZidooPlayStatus? { polls++; return null }
            override suspend fun seekTo(positionMs: Long) = true
            override suspend fun stop() = true
        }
        val outcome = monitor(api).run(onStarted = {}, onSample = {}, giveUpStarting = { polls >= 2 })
        assertEquals(ZidooMonitorOutcome.PlayerDidNotStart, outcome)
        assertEquals(2, polls)
    }

    @Test
    fun playerClosedEndsOnFirstMissedRead() = runBlocking {
        val api = FakeApi(listOf(playing(50_000L), playing(51_000L), null, playing(99_000L)))
        val outcome = monitor(api).run(onStarted = {}, onSample = {}, playerClosed = { true })
        assertEquals(ZidooMonitorOutcome.Finished(51_000L, 3_000_000L), outcome)
    }

    @Test
    fun stoppedStateEnds() = runBlocking {
        val api = FakeApi(listOf(playing(50_000L), ZidooPlayStatus(3_000_000L, 0L, ZidooPlayState.STOPPED)))
        val outcome = monitor(api).run(onStarted = {}, onSample = {})
        assertEquals(ZidooMonitorOutcome.Finished(50_000L, 3_000_000L), outcome)
    }

    @Test
    fun playerStartingFromZeroIsSentToTheResumePoint() = runBlocking {
        val readings = (1..9L).map { playing(it * 1_000L) } + listOf(playing(600_000L), playing(601_000L))
        val api = FakeApi(readings)
        val samples = mutableListOf<Long>()
        val outcome = monitor(api, start = 600_000L).run(onStarted = {}, onSample = { samples += it.positionMs })
        assertEquals(listOf(600_000L, 600_000L, 600_000L), api.seeks)
        // Readings short of the resume point are never reported.
        assertEquals(listOf(600_000L, 601_000L), samples)
        assertEquals(ZidooMonitorOutcome.Finished(601_000L, 3_000_000L), outcome)
    }

    @Test
    fun skipsIntroWhilePlaying() = runBlocking {
        val api = FakeApi(listOf(playing(5_000L), paused(31_000L), playing(31_000L), playing(96_000L)))
        val skipped = mutableListOf<SkipAction>()
        monitor(
            api,
            settings = SkipSettings(skipIntro = true, skipRecap = false, skipCredits = false),
            segments = listOf(SkipSegment(SkipKind.INTRO, 30_000L, 95_000L)),
        ).run(onStarted = {}, onSample = {}, onSkip = { skipped += it })
        assertEquals(listOf(95_000L), api.seeks)
        assertEquals(1, skipped.size)
    }
}
