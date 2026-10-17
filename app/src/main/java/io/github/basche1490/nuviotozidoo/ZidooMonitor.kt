package io.github.basche1490.nuviotozidoo

import kotlinx.coroutines.delay

/** One position reading. Times are milliseconds. */
data class ZidooSample(
    val positionMs: Long,
    val durationMs: Long,
    val paused: Boolean,
)

sealed interface ZidooMonitorOutcome {
    /** The Zidoo player never reported a loaded video inside the startup window. */
    data object PlayerDidNotStart : ZidooMonitorOutcome

    /** Playback ended. [lastPositionMs] is the last real position seen, 0 if none was. */
    data class Finished(val lastPositionMs: Long, val durationMs: Long) : ZidooMonitorOutcome
}

/**
 * Follows one Zidoo playback from launch to the end: wait for the player to
 * report a loaded video, then read its status every [POLL_INTERVAL_MS] until it
 * stops answering. Ported from the Silo Zidoo fork, which follows the
 * ZidooPlexMod add-on for ZDMC.
 *
 * - A pause is a pause, not the end: only [MAX_STOPPED_READINGS] stopped readings
 *   or [MAX_MISSED_POLLS] failed reads in a row end the playback (or the caller
 *   saying the player closed).
 * - Zero positions are not reported. The player can read 0 for a moment after it
 *   starts, and passing that on would overwrite a resume position.
 * - Skips only happen while the player is actually playing.
 * - A playback resuming at [startPositionMs] is held to that point while it gets
 *   going ([ResumeGuard]). The Zidoo player does not always start there: with a
 *   large file it can start from the beginning instead.
 */
class ZidooPlaybackMonitor(
    private val api: ZidooPlayerApi,
    private val planner: SkipPlanner,
    /** Where playback was asked to start, in milliseconds; 0 for the start of the file. */
    private val startPositionMs: Long = 0L,
    private val startupTimeoutMs: Long = 60_000L,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * Runs until playback ends or the coroutine is cancelled.
     *
     * [onStarted] runs once, when the player first reports a loaded video.
     * [onSample] runs for each reading with a real position.
     * [onSkip] runs after each successful jump.
     * [giveUpStarting] is asked after each startup poll that finds no video; true
     * ends the wait early, for when the caller knows the player already closed.
     * [playerClosed] is asked after each failed read once playback runs; true ends
     * it right away instead of after [MAX_MISSED_POLLS] failures.
     */
    suspend fun run(
        onStarted: suspend () -> Unit,
        onSample: (ZidooSample) -> Unit,
        onSkip: (SkipAction) -> Unit = {},
        giveUpStarting: () -> Boolean = { false },
        playerClosed: () -> Boolean = { false },
    ): ZidooMonitorOutcome {
        var status: ZidooPlayStatus = awaitPlayerStart(giveUpStarting)
            ?: return ZidooMonitorOutcome.PlayerDidNotStart
        onStarted()

        var lastPositionMs = 0L
        var durationMs = status.durationMs
        var stoppedReadings = 0
        val resume = ResumeGuard(startPositionMs, durationMs)
        while (true) {
            if (status.durationMs > 0L && status.state != ZidooPlayState.STOPPED) {
                stoppedReadings = 0
                durationMs = status.durationMs
                val shortOfResumePoint = resume.isShort(status)
                if (resume.shouldSeek()) api.seekTo(resume.resumeAtMs)
                if (!shortOfResumePoint && status.positionMs > 0L) {
                    lastPositionMs = status.positionMs
                    onSample(ZidooSample(lastPositionMs, durationMs, status.state == ZidooPlayState.PAUSED))
                    if (status.state == ZidooPlayState.PLAYING) skipIfDue(status, onSkip)
                }
            } else {
                // A stopped reading can be a moment of loading or buffering; only a run of them is the end.
                stoppedReadings++
                if (stoppedReadings >= MAX_STOPPED_READINGS || playerClosed()) {
                    return ZidooMonitorOutcome.Finished(lastPositionMs, durationMs)
                }
            }
            status = nextReading(playerClosed) ?: return ZidooMonitorOutcome.Finished(lastPositionMs, durationMs)
        }
    }

    private suspend fun awaitPlayerStart(giveUp: () -> Boolean): ZidooPlayStatus? {
        var waitedMs = 0L
        while (true) {
            pause(POLL_INTERVAL_MS)
            waitedMs += POLL_INTERVAL_MS
            val reading = api.status()
            if (reading != null && reading.durationMs > 0L) return reading
            if (giveUp() || waitedMs >= startupTimeoutMs) return null
        }
    }

    /** The next answer from the player; null after [MAX_MISSED_POLLS] failed reads in a row. */
    private suspend fun nextReading(playerClosed: () -> Boolean): ZidooPlayStatus? {
        repeat(MAX_MISSED_POLLS) {
            pause(POLL_INTERVAL_MS)
            api.status()?.let { return it }
            if (playerClosed()) return null
        }
        return null
    }

    private suspend fun skipIfDue(status: ZidooPlayStatus, onSkip: (SkipAction) -> Unit) {
        val action = planner.nextAction(status.positionMs, status.durationMs) ?: return
        if (api.seekTo(action.targetMs)) onSkip(action) else planner.release(action)
    }

    companion object {
        const val POLL_INTERVAL_MS = 1_000L
        const val MAX_MISSED_POLLS = 3
        const val MAX_STOPPED_READINGS = 5
    }
}

/**
 * Holds a resuming playback to its resume point for its first
 * [WATCH_PLAYING_READINGS] readings of actual playback.
 *
 * A reading is short of the resume point when the player has not reached it yet,
 * or, once it has, when it is back within [RESTART_WINDOW_MS] of the start: a
 * player that reports the resume point and then starts the file over looks like
 * that. A viewer rewinding to anywhere later is left alone. Every
 * [SEEK_EVERY_PLAYING_READINGS]th reading that is playing and short asks the
 * player to go to the resume point, up to [MAX_SEEKS] times. Readings while the
 * player loads or is paused never ask: a jump asked for meanwhile is ignored.
 */
internal class ResumeGuard(startPositionMs: Long, durationMs: Long) {
    /** The resume point, or 0 when playback starts from the beginning or the file has no room for it. */
    val resumeAtMs: Long =
        startPositionMs.takeIf { it > TOLERANCE_MS && (durationMs <= 0L || it < durationMs - TOLERANCE_MS) } ?: 0L

    private val detectsRestart = resumeAtMs - TOLERANCE_MS > 2 * RESTART_WINDOW_MS
    private var playingReadingsLeft = if (resumeAtMs > 0L) WATCH_PLAYING_READINGS else 0
    private var reached = false
    private var playingShortReadings = 0
    private var seeks = 0
    private var seekDue = false

    /** Whether [status] is short of the resume point, so neither reported nor used for skips. */
    fun isShort(status: ZidooPlayStatus): Boolean {
        seekDue = false
        if (playingReadingsLeft <= 0) return false
        val playing = status.state == ZidooPlayState.PLAYING
        if (playing) playingReadingsLeft--
        val position = status.positionMs
        val short = if (!reached) {
            position < resumeAtMs - TOLERANCE_MS
        } else {
            detectsRestart && position < RESTART_WINDOW_MS
        }
        if (!short) {
            if (position >= resumeAtMs - TOLERANCE_MS) reached = true
            playingShortReadings = 0
        } else if (playing) {
            playingShortReadings++
            if (playingShortReadings % SEEK_EVERY_PLAYING_READINGS == 0 && seeks < MAX_SEEKS) {
                seeks++
                seekDue = true
            }
        }
        return short
    }

    /** True right after the [isShort] reading that calls for a jump to [resumeAtMs]. */
    fun shouldSeek(): Boolean = seekDue

    companion object {
        /** How far before the resume point the player may land and still count as there (it starts on a keyframe). */
        const val TOLERANCE_MS = 10_000L

        /** How close to the start a player that had reached the resume point counts as having started over. */
        const val RESTART_WINDOW_MS = 60_000L

        /** Readings of actual playback the resume point is held for. */
        const val WATCH_PLAYING_READINGS = 30

        /** Playing readings short of the resume point between each request to go there. */
        const val SEEK_EVERY_PLAYING_READINGS = 3

        const val MAX_SEEKS = 5
    }
}
