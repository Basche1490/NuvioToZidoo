package io.github.basche1490.nuviotozidoo

import java.util.Locale
import kotlin.math.min

/** What Nuvio asked for, read from its external-player intent. */
data class LaunchRequest(
    val url: String,
    val title: String?,
    /** Nuvio's saved resume point in milliseconds; 0 when it has none. */
    val requestedPositionMs: Long,
    /** Nuvio's "Start from beginning". */
    val fromStart: Boolean,
    val skipSegments: List<SkipSegment>,
) {
    val episode: EpisodeTag? get() = EpisodeTag.parse(title)
}

/**
 * The season and episode in a Nuvio player title. Nuvio titles episodes
 * "Show Name - S02E05" (optionally followed by " - Episode Title") and films by
 * their name alone.
 */
data class EpisodeTag(val show: String, val season: Int, val episode: Int) : Comparable<EpisodeTag> {

    val code: String
        get() = String.format(Locale.US, "S%02dE%02d", season, episode)

    fun sameShow(other: EpisodeTag): Boolean = show.trim().equals(other.show.trim(), ignoreCase = true)

    override fun compareTo(other: EpisodeTag): Int =
        compareValuesBy(this, other, EpisodeTag::season, EpisodeTag::episode)

    companion object {
        private val PATTERN = Regex("""^(.*\S)\s+-\s+S(\d{1,4})E(\d{1,5})(?:\s+-\s+.*)?$""", RegexOption.IGNORE_CASE)

        fun parse(title: String?): EpisodeTag? {
            if (title.isNullOrBlank()) return null
            val match = PATTERN.matchEntire(title.trim()) ?: return null
            val season = match.groupValues[2].toIntOrNull() ?: return null
            val episode = match.groupValues[3].toIntOrNull() ?: return null
            return EpisodeTag(match.groupValues[1], season, episode)
        }
    }
}

/** This app's own record of where a title stopped. */
data class ResumeRecord(
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val savedAtMs: Long,
)

enum class StartSource { BEGINNING, NUVIO, OWN_RECORD, NONE }

data class StartDecision(val positionMs: Long, val source: StartSource)

/**
 * Where to start playback.
 *
 * Nuvio passes its own resume point. Nuvio's Zidoo tracking can stop following
 * playback after a pause (it reads a paused Zidoo player as stopped) and then
 * keeps the pause point, not where you really stopped. This app saves the real
 * position every few seconds, so when its record is further along than Nuvio's
 * point it wins. Nuvio's point wins whenever it is further along (for example
 * after watching on another device), and "Start from beginning" always starts at 0.
 */
object ResumeRules {
    /** Our record must be at least this far past Nuvio's point to replace it. */
    const val MIN_GAIN_MS = 15_000L

    /** Records older than this are ignored. */
    const val MAX_AGE_MS = 45L * 24 * 60 * 60 * 1000

    fun decide(
        request: LaunchRequest,
        record: ResumeRecord?,
        nowMs: Long,
        useOwnRecords: Boolean,
    ): StartDecision {
        if (request.fromStart) return StartDecision(0L, StartSource.BEGINNING)
        val requested = request.requestedPositionMs.coerceAtLeast(0L)
        if (requested <= 0L) return StartDecision(0L, StartSource.NONE)
        val usable = record?.takeIf {
            useOwnRecords &&
                !it.completed &&
                nowMs - it.savedAtMs in 0..MAX_AGE_MS &&
                it.positionMs >= requested + MIN_GAIN_MS &&
                (it.durationMs <= 0L || it.positionMs < it.durationMs)
        }
        return if (usable != null) {
            StartDecision(usable.positionMs, StartSource.OWN_RECORD)
        } else {
            StartDecision(requested, StartSource.NUVIO)
        }
    }

    /**
     * The key a title's record is stored under. An episode's title is unique
     * ("Show - S01E05"); a film's or an absolute-numbered anime episode's title is
     * just the name, so the file name from the stream address is added.
     */
    fun key(title: String?, url: String): String {
        val name = title?.trim().orEmpty()
        if (EpisodeTag.parse(name) != null) return name.lowercase(Locale.ROOT)
        return (name + "|" + fileNameOf(url)).lowercase(Locale.ROOT)
    }

    internal fun fileNameOf(url: String): String {
        val path = url.substringBefore('#').substringBefore('?').trimEnd('/')
        val last = path.substringAfterLast('/')
        return try {
            java.net.URLDecoder.decode(last.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            last
        }
    }
}

/** When this app last handed a finished title back to Nuvio. */
data class CompletionMark(val title: String, val atMs: Long)

/**
 * Up Next: Nuvio auto-advances to the next episode by opening its stream in this
 * app straight after the previous one finished. That launch gets a countdown
 * first, so there is time to stop the chain.
 */
object UpNextRules {
    /** How long after a finished episode a launch still counts as the next one. */
    const val WINDOW_MS = 3L * 60 * 1000

    fun isNextEpisode(previous: CompletionMark?, title: String?, nowMs: Long): Boolean {
        if (previous == null || title.isNullOrBlank()) return false
        if (nowMs - previous.atMs !in 0..WINDOW_MS) return false
        val before = EpisodeTag.parse(previous.title)
        val now = EpisodeTag.parse(title)
        return when {
            before != null && now != null -> now.sameShow(before) && now > before
            // Absolute-numbered anime: Nuvio's title is the show name for every episode.
            before == null && now == null -> title.trim().equals(previous.title.trim(), ignoreCase = true)
            else -> false
        }
    }
}

enum class EndReason { COMPLETED, STOPPED }

/** What goes back to Nuvio when the Zidoo player closes. */
data class PlaybackResult(val positionMs: Long, val durationMs: Long, val reason: EndReason)

object PlaybackResults {
    /** Without a credits segment, how close to the end a stop counts as finishing. */
    const val END_WINDOW_MS = 30_000L

    /**
     * Turns the last position seen into the result for Nuvio, or null when there
     * is nothing to report. A stop in the credits or the last half minute counts
     * as finishing, which makes Nuvio mark the title watched and move on to the
     * next episode.
     */
    fun settle(lastPositionMs: Long, durationMs: Long, creditsStartMs: Long?): PlaybackResult? {
        if (lastPositionMs <= 0L) return null
        if (durationMs <= 0L) return PlaybackResult(lastPositionMs, 0L, EndReason.STOPPED)
        val position = min(lastPositionMs, durationMs)
        val inCredits = creditsStartMs != null && position >= creditsStartMs
        val nearEnd = durationMs - position <= min(END_WINDOW_MS, durationMs / 10)
        return if (inCredits || nearEnd) {
            PlaybackResult(durationMs, durationMs, EndReason.COMPLETED)
        } else {
            PlaybackResult(position, durationMs, EndReason.STOPPED)
        }
    }
}
