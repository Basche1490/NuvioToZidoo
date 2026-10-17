package io.github.basche1490.nuviotozidoo

import android.content.Context
import android.content.SharedPreferences

/** Which Zidoo player app plays the video. */
enum class PlayerChoice { AUTO, NEW_PLAYER, CLASSIC_PLAYER }

/** What happens when an episode reaches its end credits. */
enum class CreditsAction { UP_NEXT, KEEP_PLAYING }

/** The app's settings, stored in SharedPreferences. */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var skipIntro: Boolean
        get() = prefs.getBoolean(KEY_SKIP_INTRO, true)
        set(value) = prefs.edit().putBoolean(KEY_SKIP_INTRO, value).apply()

    var skipRecap: Boolean
        get() = prefs.getBoolean(KEY_SKIP_RECAP, true)
        set(value) = prefs.edit().putBoolean(KEY_SKIP_RECAP, value).apply()

    /** Films only: episodes follow [episodeCredits]. Off by default: it jumps past any scene after the credits. */
    var skipFilmCredits: Boolean
        get() = prefs.getBoolean(KEY_SKIP_FILM_CREDITS, false)
        set(value) = prefs.edit().putBoolean(KEY_SKIP_FILM_CREDITS, value).apply()

    var episodeCredits: CreditsAction
        get() = enumOr(prefs.getString(KEY_EPISODE_CREDITS, null), CreditsAction.UP_NEXT)
        set(value) = prefs.edit().putString(KEY_EPISODE_CREDITS, value.name).apply()

    /** Seconds the Up Next countdown runs before the next episode plays; 0 plays it straight away. */
    var countdownSeconds: Int
        get() = prefs.getInt(KEY_COUNTDOWN, 10).coerceIn(0, 60)
        set(value) = prefs.edit().putInt(KEY_COUNTDOWN, value).apply()

    var rememberPositions: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER, true)
        set(value) = prefs.edit().putBoolean(KEY_REMEMBER, value).apply()

    var player: PlayerChoice
        get() = enumOr(prefs.getString(KEY_PLAYER, null), PlayerChoice.AUTO)
        set(value) = prefs.edit().putString(KEY_PLAYER, value.name).apply()

    /** Delays the first start so Nuvio's own Zidoo tracking gives up first. See README. */
    var holdForNuvio: Boolean
        get() = prefs.getBoolean(KEY_HOLD_FOR_NUVIO, false)
        set(value) = prefs.edit().putBoolean(KEY_HOLD_FOR_NUVIO, value).apply()

    fun skipSettings(isEpisode: Boolean): SkipSettings = SkipSettings(
        skipIntro = skipIntro,
        skipRecap = skipRecap,
        // An episode's credits are handled by Up Next instead of a jump.
        skipCredits = !isEpisode && skipFilmCredits,
    )

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    companion object {
        val COUNTDOWN_CHOICES = listOf(0, 5, 10, 15, 20, 30)

        private const val KEY_SKIP_INTRO = "skip_intro"
        private const val KEY_SKIP_RECAP = "skip_recap"
        private const val KEY_SKIP_FILM_CREDITS = "skip_film_credits"
        private const val KEY_EPISODE_CREDITS = "episode_credits"
        private const val KEY_COUNTDOWN = "countdown_seconds"
        private const val KEY_REMEMBER = "remember_positions"
        private const val KEY_PLAYER = "player"
        private const val KEY_HOLD_FOR_NUVIO = "hold_for_nuvio"
    }
}
