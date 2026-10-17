package io.github.basche1490.nuviotozidoo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class SkipKind { INTRO, RECAP, CREDITS }

/** A part of the video to jump over. Times are milliseconds from the start. */
data class SkipSegment(val kind: SkipKind, val startMs: Long, val endMs: Long)

data class SkipSettings(
    val skipIntro: Boolean,
    val skipRecap: Boolean,
    val skipCredits: Boolean,
)

data class SkipAction(val segmentIndex: Int, val kind: SkipKind, val targetMs: Long)

/**
 * Reads the `skip_segments` extra Nuvio puts on its external-player intent when
 * Settings → Playback → "Send skip segments" is on:
 * `[{"type":"intro","start":12.0,"end":95.5}, ...]`, times in seconds.
 *
 * Nuvio's types come from IntroDB, AniSkip and its own lookups: `intro`, `op`,
 * `mixed-op`, `recap`, `outro`, `ed`, `mixed-ed`, `end-credits` and others.
 * Unknown types (for example `post-credits`, a scene worth watching) are ignored.
 */
object SkipSegments {

    fun parse(json: String?): List<SkipSegment> {
        if (json.isNullOrBlank()) return emptyList()
        val array = try {
            Json.parseToJsonElement(json) as? JsonArray
        } catch (_: IllegalArgumentException) {
            null
        } ?: return emptyList()

        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val type = (item["type"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val kind = kindOf(type) ?: return@mapNotNull null
            val start = (item["start"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val end = (item["end"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            if (!start.isFinite() || !end.isFinite() || start < 0.0 || end <= start) return@mapNotNull null
            SkipSegment(kind, (start * 1000.0).toLong(), ceil(end * 1000.0).toLong())
        }.sortedBy { it.startMs }
    }

    fun kindOf(type: String): SkipKind? = when (type.trim().lowercase()) {
        "intro", "op", "opening", "mixed-op", "new intro" -> SkipKind.INTRO
        "recap", "previously", "summary" -> SkipKind.RECAP
        "outro", "ed", "ending", "mixed-ed", "credits", "end-credits", "new credits", "mixed credits" ->
            SkipKind.CREDITS
        else -> null
    }

    /**
     * Where the end credits start, if a credits segment sits in the last 40% of
     * the video. Used to count a stop during the credits as finishing the title.
     */
    fun creditsStartMs(segments: List<SkipSegment>, durationMs: Long): Long? {
        if (durationMs <= 0L) return null
        val earliestAllowed = (durationMs * 0.6).toLong()
        return segments
            .filter { it.kind == SkipKind.CREDITS && it.startMs >= earliestAllowed && it.startMs < durationMs }
            .minOfOrNull { it.startMs }
    }
}

/**
 * Decides when the Zidoo player should jump over an intro, a recap or the credits.
 *
 * Rules (the same as the ZidooPlexMod add-on and the Silo Zidoo fork):
 * - A segment triggers while the position is inside it, and the jump goes to its end.
 * - A jump never lands closer than [FINAL_MARGIN_MS] to the end of the file, so the
 *   player still reaches its natural end rather than parking on the last frame.
 * - Each segment triggers once per playback. Seeking back into it plays it.
 */
class SkipPlanner(
    private val segments: List<SkipSegment>,
    private val settings: SkipSettings,
) {
    private val done = mutableSetOf<Int>()

    /** The jump to make for [positionMs], or null when nothing should be skipped. */
    fun nextAction(positionMs: Long, durationMs: Long): SkipAction? {
        if (positionMs <= 0L || durationMs <= 0L) return null
        segments.forEachIndexed { index, segment ->
            if (index in done || !isEnabled(segment.kind)) return@forEachIndexed
            val start = max(0L, segment.startMs)
            val end = min(segment.endMs, durationMs - FINAL_MARGIN_MS)
            if (end <= start) return@forEachIndexed
            if (positionMs >= start && positionMs < end) {
                done.add(index)
                return SkipAction(index, segment.kind, end)
            }
        }
        return null
    }

    /** Lets a segment trigger again; used when the seek [nextAction] asked for failed. */
    fun release(action: SkipAction) {
        done.remove(action.segmentIndex)
    }

    private fun isEnabled(kind: SkipKind): Boolean = when (kind) {
        SkipKind.INTRO -> settings.skipIntro
        SkipKind.RECAP -> settings.skipRecap
        SkipKind.CREDITS -> settings.skipCredits
    }

    companion object {
        const val FINAL_MARGIN_MS = 1_000L
    }
}
