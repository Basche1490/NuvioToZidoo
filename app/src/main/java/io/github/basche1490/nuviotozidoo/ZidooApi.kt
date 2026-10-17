package io.github.basche1490.nuviotozidoo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

enum class ZidooPlayState { PLAYING, PAUSED, STOPPED }

/** One reading of the Zidoo player. Times are milliseconds. */
data class ZidooPlayStatus(
    val durationMs: Long,
    val positionMs: Long,
    val state: ZidooPlayState,
)

/** The slice of the Zidoo player's local HTTP control API this app uses. */
interface ZidooPlayerApi {
    /** The player's current status, or null when nothing is loaded or the API does not answer. */
    suspend fun status(): ZidooPlayStatus?

    suspend fun seekTo(positionMs: Long): Boolean

    suspend fun stop(): Boolean
}

/**
 * Parses a `ZidooVideoPlay/getPlayStatus` body:
 * `{"status":200,"video":{"duration":<ms>,"currentPosition":<ms>,"status":<0|1|...>}}`.
 *
 * `video.status` 1 is playing and 0 is paused; anything else counts as stopped.
 * This is how the ZidooPlexMod add-on for ZDMC reads it, and how the Home
 * Assistant Zidoo integration does (it shows any loaded video that is not 1 as
 * paused). With nothing loaded the API answers a top-level `status` of 806,
 * which yields null here.
 */
internal fun parseZidooStatus(body: String): ZidooPlayStatus? {
    val root = parseObject(body) ?: return null
    if (root.number("status") != 200L) return null
    val video = root["video"] as? JsonObject ?: return null
    val duration = video.number("duration") ?: return null
    val position = video.number("currentPosition") ?: 0L
    val state = when (video.number("status")) {
        1L -> ZidooPlayState.PLAYING
        0L -> ZidooPlayState.PAUSED
        else -> ZidooPlayState.STOPPED
    }
    return ZidooPlayStatus(durationMs = duration, positionMs = position, state = state)
}

/** True for a body whose top-level `status` is 200. */
internal fun isZidooOk(body: String): Boolean = parseObject(body)?.number("status") == 200L

private fun parseObject(body: String): JsonObject? =
    try {
        Json.parseToJsonElement(body).jsonObject
    } catch (_: IllegalArgumentException) {
        // Not JSON, or JSON that is not an object.
        null
    }

private fun JsonObject.number(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
}

/**
 * Talks to the Zidoo player's HTTP API on the box itself (loopback, port 9529).
 *
 * `seekTo` takes its argument as `positon` (sic); that spelling is the API's.
 */
class HttpZidooPlayerApi : ZidooPlayerApi {

    override suspend fun status(): ZidooPlayStatus? =
        get("ZidooVideoPlay/getPlayStatus")?.let(::parseZidooStatus)

    override suspend fun seekTo(positionMs: Long): Boolean =
        get("ZidooVideoPlay/seekTo?positon=${positionMs.coerceAtLeast(0L)}")?.let(::isZidooOk) ?: false

    override suspend fun stop(): Boolean =
        get("ZidooControlCenter/RemoteControl/sendkey?key=Key.MediaStop")?.let(::isZidooOk) ?: false

    /** Presses Back on the remote, for a player that ignored [stop]. */
    suspend fun back(): Boolean =
        get("ZidooControlCenter/RemoteControl/sendkey?key=Key.Back")?.let(::isZidooOk) ?: false

    /** True when the API answers at all, whether or not anything is playing. */
    suspend fun isReachable(): Boolean = get("ZidooVideoPlay/getPlayStatus") != null

    private suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$BASE_URL/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
            }
            // Read the body whatever the HTTP status; the JSON's own `status` decides.
            val stream = if (connection.responseCode < HttpURLConnection.HTTP_BAD_REQUEST) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            stream?.bufferedReader()?.use { it.readText() }
        } catch (_: IOException) {
            // The player is not up yet, or has gone. Callers treat null as "no answer".
            null
        } catch (_: SecurityException) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val BASE_URL = "http://127.0.0.1:9529"
        const val TIMEOUT_MS = 2_000
    }
}
