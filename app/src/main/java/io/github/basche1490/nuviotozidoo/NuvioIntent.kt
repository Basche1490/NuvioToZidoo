package io.github.basche1490.nuviotozidoo

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Nuvio's side of the external-player contract (NuvioTV
 * core/player/ExternalPlayerResultContract.kt).
 *
 * In: ACTION_VIEW with the stream as data, `title`, `position` (Int ms) and
 * `extra_position` (Long ms) for a resume, `from_start` for "Start from beginning",
 * `headers` (String[] "Name: value"), and `skip_segments` (JSON) when Nuvio's
 * "Send skip segments to external player" is on.
 *
 * Out: what MX Player returns, which Nuvio reads fully: `position` and
 * `duration` in milliseconds and `end_by` = `playback_completion` or `user`.
 * Nuvio counts `playback_completion` as finished, marks it watched and moves on
 * to the next episode when its auto-play setting is on.
 */
object NuvioIntent {

    fun parse(intent: Intent?): Parsed? {
        val url = intent?.dataString?.takeIf { it.isNotBlank() } ?: return null
        val extras = intent.extras
        val title = intent.getStringExtra("title")?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(Intent.EXTRA_TITLE)
        val position = longExtra(extras, "extra_position") ?: longExtra(extras, "position") ?: 0L
        return Parsed(
            request = LaunchRequest(
                url = url,
                title = title,
                requestedPositionMs = position.coerceAtLeast(0L),
                fromStart = intent.getBooleanExtra("from_start", false),
                skipSegments = SkipSegments.parse(intent.getStringExtra("skip_segments")),
            ),
            headers = HeaderRelay.parseHeaders(intent.getStringArrayExtra("headers")),
        )
    }

    data class Parsed(val request: LaunchRequest, val headers: Map<String, String>)

    fun resultIntent(result: PlaybackResult): Intent = Intent(MX_RESULT_ACTION).apply {
        val position = result.positionMs.coerceIn(0L, Int.MAX_VALUE.toLong())
        val duration = result.durationMs.coerceIn(0L, Int.MAX_VALUE.toLong())
        putExtra("position", position.toInt())
        putExtra("duration", duration.toInt())
        putExtra("extra_position", position)
        putExtra("extra_duration", duration)
        putExtra("end_by", if (result.reason == EndReason.COMPLETED) "playback_completion" else "user")
    }

    const val RESULT_OK = Activity.RESULT_OK

    private const val MX_RESULT_ACTION = "com.mxtech.intent.result.VIEW"

    @Suppress("DEPRECATION")
    private fun longExtra(extras: Bundle?, key: String): Long? = when (val value = extras?.get(key)) {
        is Long -> value
        is Int -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }?.takeIf { it > 0L }
}
