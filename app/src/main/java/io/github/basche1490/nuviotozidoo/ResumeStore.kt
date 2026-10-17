package io.github.basche1490.nuviotozidoo

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * This app's own resume records, one per title, plus the mark that lets the next
 * episode's launch show the Up Next countdown. Kept small: the oldest records go
 * once there are more than [MAX_RECORDS].
 */
class ResumeStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("resume", Context.MODE_PRIVATE)

    fun get(key: String): ResumeRecord? {
        val raw = prefs.getString(PREFIX + key, null) ?: return null
        return try {
            val json = JSONObject(raw)
            ResumeRecord(
                positionMs = json.getLong("p"),
                durationMs = json.optLong("d", 0L),
                completed = json.optBoolean("c", false),
                savedAtMs = json.optLong("t", 0L),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun put(key: String, record: ResumeRecord) {
        val json = JSONObject()
            .put("p", record.positionMs)
            .put("d", record.durationMs)
            .put("c", record.completed)
            .put("t", record.savedAtMs)
        prefs.edit().putString(PREFIX + key, json.toString()).apply()
        trimIfNeeded()
    }

    var lastCompletion: CompletionMark?
        get() {
            val title = prefs.getString(KEY_DONE_TITLE, null) ?: return null
            return CompletionMark(title, prefs.getLong(KEY_DONE_AT, 0L))
        }
        set(value) {
            val edit = prefs.edit()
            if (value == null) {
                edit.remove(KEY_DONE_TITLE).remove(KEY_DONE_AT)
            } else {
                edit.putString(KEY_DONE_TITLE, value.title).putLong(KEY_DONE_AT, value.atMs)
            }
            edit.apply()
        }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun trimIfNeeded() {
        val records = prefs.all.filterKeys { it.startsWith(PREFIX) }
        if (records.size <= MAX_RECORDS) return
        val oldestFirst = records.entries.sortedBy { (_, value) ->
            (value as? String)?.let { runCatching { JSONObject(it).optLong("t", 0L) }.getOrNull() } ?: 0L
        }
        val edit = prefs.edit()
        oldestFirst.take(records.size - MAX_RECORDS).forEach { edit.remove(it.key) }
        edit.apply()
    }

    private companion object {
        const val PREFIX = "r:"
        const val KEY_DONE_TITLE = "done_title"
        const val KEY_DONE_AT = "done_at"
        const val MAX_RECORDS = 400
    }
}

/**
 * The playback in progress, written to disk so the result can still be worked out
 * if Android stops this app while the Zidoo player plays and recreates it when
 * the player closes.
 */
data class ActiveSession(
    val key: String,
    val title: String?,
    val startPositionMs: Long,
    val creditsStartMs: Long?,
    val lastPositionMs: Long,
    val durationMs: Long,
)

class ActiveSessionStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("session", Context.MODE_PRIVATE)

    fun save(session: ActiveSession) {
        prefs.edit()
            .putString("key", session.key)
            .putString("title", session.title)
            .putLong("start", session.startPositionMs)
            .putLong("credits", session.creditsStartMs ?: -1L)
            .putLong("last", session.lastPositionMs)
            .putLong("duration", session.durationMs)
            .apply()
    }

    fun load(): ActiveSession? {
        val key = prefs.getString("key", null) ?: return null
        return ActiveSession(
            key = key,
            title = prefs.getString("title", null),
            startPositionMs = prefs.getLong("start", 0L),
            creditsStartMs = prefs.getLong("credits", -1L).takeIf { it >= 0L },
            lastPositionMs = prefs.getLong("last", 0L),
            durationMs = prefs.getLong("duration", 0L),
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
