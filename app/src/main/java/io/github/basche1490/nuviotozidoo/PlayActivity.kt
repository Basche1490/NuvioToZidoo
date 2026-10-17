package io.github.basche1490.nuviotozidoo

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/**
 * The "external player" Nuvio opens. It hands the stream to the Zidoo player,
 * follows playback through the Zidoo HTTP API, and reports back to Nuvio the way
 * MX Player does, so Nuvio saves progress, marks episodes watched and plays the
 * next one.
 *
 * - Resume: starts at Nuvio's point, or at this app's own record when that is
 *   further along, and holds the Zidoo player to it if it starts at 0:00.
 * - Saves the position every ten seconds, so stopping or powering off resumes there.
 * - Skips intros, recaps and (for films, if on) credits using Nuvio's segments.
 * - Up Next: at an episode's credits it ends the episode after a short warning,
 *   and when Nuvio opens the next episode it shows a countdown first.
 */
class PlayActivity : Activity() {

    private val scope = MainScope()
    private val api = HttpZidooPlayerApi()
    private lateinit var settings: Settings
    private lateinit var resumeStore: ResumeStore
    private lateinit var sessionStore: ActiveSessionStore

    private lateinit var headlineView: TextView
    private lateinit var titleView: TextView
    private lateinit var messageView: TextView
    private lateinit var ringView: CountdownRingView
    private lateinit var spinner: ProgressBar
    private lateinit var buttonRow: View
    private lateinit var primaryButton: Button
    private lateinit var secondaryButton: Button

    private var request: LaunchRequest? = null
    private var headers: Map<String, String> = emptyMap()
    private var key: String = ""
    private var title: String? = null
    private var startPositionMs = 0L
    private var isEpisode = false
    private var segments: List<SkipSegment> = emptyList()

    private var lastPositionMs = 0L
    private var durationMs = 0L
    private var lastPaused = false
    private var creditsStartMs: Long? = null
    private var lastRecordPositionMs = -1L
    private var lastSessionSaveAtMs = 0L

    private var relay: HeaderRelay? = null
    private var countdownJob: Job? = null
    private var monitorJob: Job? = null
    private var creditsJob: Job? = null
    private var creditsDeclined = false
    private var playerLaunched = false
    private var playerLaunchedAtMs = 0L
    private var playerClosed = false
    private var detached = false
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_play)
        headlineView = findViewById(R.id.headline)
        titleView = findViewById(R.id.title)
        messageView = findViewById(R.id.message)
        ringView = findViewById(R.id.ring)
        spinner = findViewById(R.id.spinner)
        buttonRow = findViewById(R.id.buttons)
        primaryButton = findViewById(R.id.primary_button)
        secondaryButton = findViewById(R.id.secondary_button)

        settings = Settings(this)
        resumeStore = ResumeStore(this)
        sessionStore = ActiveSessionStore(this)

        if (savedInstanceState?.getBoolean(STATE_PLAYER_LAUNCHED) == true) {
            // Android recreated this screen while the Zidoo player was open. Its result follows.
            restoreFromSession()
            return
        }

        val parsed = NuvioIntent.parse(intent)
        if (parsed == null) {
            showError(getString(R.string.error_nothing_to_play))
            return
        }
        begin(parsed)
    }

    private fun begin(parsed: NuvioIntent.Parsed) {
        val req = parsed.request
        request = req
        headers = parsed.headers
        title = req.title
        isEpisode = req.episode != null
        segments = req.skipSegments
        key = ResumeRules.key(req.title, req.url)

        val now = System.currentTimeMillis()
        val decision = ResumeRules.decide(req, resumeStore.get(key), now, settings.rememberPositions)
        startPositionMs = decision.positionMs
        Log.i(
            TAG,
            "Play \"$title\": start ${decision.positionMs} ms (${decision.source}), Nuvio asked " +
                "${req.requestedPositionMs} ms, fromStart=${req.fromStart}, segments=${segments.size}, headers=${headers.size}"
        )

        val component = ZidooPlayerLauncher.pick(this, settings.player)
        if (component == null) {
            showError(getString(R.string.error_no_zidoo_player))
            return
        }

        val isNext = UpNextRules.isNextEpisode(resumeStore.lastCompletion, req.title, now)
        val countdownMs = if (isNext) settings.countdownSeconds * 1_000L else 0L
        val holdMs = if (settings.holdForNuvio && ZidooPlayerLauncher.nuvioSeesZidoo()) HOLD_FOR_NUVIO_MS else 0L
        val waitMs = maxOf(countdownMs, holdMs)
        if (waitMs > 0L) {
            startCountdown(component, isNext, waitMs)
        } else {
            launchPlayer(component)
        }
    }

    // ---------------------------------------------------------------- countdown

    private fun startCountdown(component: ComponentName, isNext: Boolean, totalMs: Long) {
        headlineView.text = getString(if (isNext) R.string.up_next else R.string.starting)
        titleView.text = title.orEmpty()
        messageView.text = if (isNext) "" else getString(R.string.hold_explanation)
        messageView.visibility = if (isNext) View.GONE else View.VISIBLE
        spinner.visibility = View.GONE
        ringView.visibility = View.VISIBLE
        buttonRow.visibility = View.VISIBLE
        primaryButton.text = getString(R.string.play_now)
        primaryButton.setOnClickListener { launchPlayer(component) }
        secondaryButton.visibility = View.VISIBLE
        secondaryButton.text = getString(R.string.cancel)
        secondaryButton.setOnClickListener { cancelAndFinish() }
        primaryButton.requestFocus()

        val endAt = SystemClock.elapsedRealtime() + totalMs
        countdownJob = scope.launch {
            while (true) {
                val left = endAt - SystemClock.elapsedRealtime()
                if (left <= 0L) break
                ringView.fraction = left.toFloat() / totalMs
                ringView.seconds = ceil(left / 1000.0).toInt()
                delay(min(left, 100L))
            }
            launchPlayer(component)
        }
    }

    // ------------------------------------------------------------------ launch

    private fun launchPlayer(component: ComponentName) {
        if (playerLaunched || done) return
        val req = request ?: return
        countdownJob?.cancel()
        showStatus(getString(R.string.status_opening))

        val url = if (headers.isNotEmpty()) {
            try {
                HeaderRelay(req.url, headers).start().also { relay = it }.localUrl
            } catch (e: IOException) {
                Log.w(TAG, "Header relay failed to start; giving the Zidoo player the stream as it is", e)
                req.url
            }
        } else {
            req.url
        }

        // One countdown per finished episode.
        resumeStore.lastCompletion = null
        saveSession()
        MonitorService.start(this, title)
        val playerIntent = ZidooPlayerLauncher.buildIntent(component, url, title, startPositionMs)
        try {
            startActivityForResult(playerIntent, REQUEST_PLAYER)
        } catch (e: ActivityNotFoundException) {
            launchFailed(e)
            return
        } catch (e: SecurityException) {
            launchFailed(e)
            return
        }
        playerLaunched = true
        playerLaunchedAtMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "Opened ${component.flattenToShortString()} at $startPositionMs ms")
        startMonitor(segments, startPositionMs)
    }

    private fun launchFailed(e: Exception) {
        Log.w(TAG, "Could not open the Zidoo player", e)
        MonitorService.stop(this)
        relay?.close()
        relay = null
        sessionStore.clear()
        showError(getString(R.string.error_player_failed))
    }

    // ------------------------------------------------------------------ monitor

    private fun startMonitor(skipSegments: List<SkipSegment>, resumeAtMs: Long) {
        val planner = SkipPlanner(skipSegments, settings.skipSettings(isEpisode))
        val monitor = ZidooPlaybackMonitor(api, planner, resumeAtMs, startupTimeoutMs = STARTUP_TIMEOUT_MS)
        monitorJob = scope.launch {
            val outcome = monitor.run(
                onStarted = { Log.i(TAG, "The Zidoo player reports a loaded video") },
                onSample = ::onSample,
                onSkip = { Log.i(TAG, "Skipped ${it.kind} to ${it.targetMs} ms") },
                giveUpStarting = { playerClosed },
                playerClosed = { playerClosed },
            )
            Log.i(TAG, "Monitor finished: $outcome")
            if (detached) {
                // This player returned its result the moment it opened, so the end shows only here.
                finishPlayback(computeResult(zidooPositionMs = 0L))
            }
        }
    }

    private fun onSample(sample: ZidooSample) {
        lastPositionMs = sample.positionMs
        durationMs = sample.durationMs
        lastPaused = sample.paused
        if (creditsStartMs == null) creditsStartMs = SkipSegments.creditsStartMs(segments, durationMs)

        val now = SystemClock.elapsedRealtime()
        if (now - lastSessionSaveAtMs >= SESSION_SAVE_EVERY_MS) {
            saveSession()
            lastSessionSaveAtMs = now
        }
        val moved = abs(sample.positionMs - lastRecordPositionMs)
        if (moved >= RECORD_SAVE_EVERY_MS || (sample.paused && moved > 0L)) {
            saveRecord(sample.positionMs, completed = false)
        }
        checkCredits(sample)
        checkNearEnd(sample)
    }

    /** At an episode's end credits: warn, then end the episode so Nuvio moves on to the next one. */
    private fun checkCredits(sample: ZidooSample) {
        val credits = creditsStartMs ?: return
        if (!isEpisode || settings.episodeCredits != CreditsAction.UP_NEXT || creditsDeclined || done) return
        val inCredits = sample.positionMs >= credits
        if (creditsJob?.isActive == true) {
            if (!inCredits) {
                // Rewound out of the credits: keep watching this one to its end.
                creditsJob?.cancel()
                creditsDeclined = true
                toast(getString(R.string.toast_keep_watching))
            }
            return
        }
        if (!inCredits || sample.paused) return
        toast(getString(R.string.toast_credits_up_next, (CREDITS_GRACE_MS / 1000).toInt()))
        creditsJob = scope.launch {
            delay(CREDITS_GRACE_MS)
            if (lastPositionMs < credits || lastPaused) {
                creditsDeclined = true
                return@launch
            }
            Log.i(TAG, "Credits reached at $lastPositionMs ms: ending the episode for Up Next")
            finishPlayback(PlaybackResult(durationMs, durationMs, EndReason.COMPLETED))
            stopZidooPlayer()
        }
    }

    /**
     * In the last seconds, report the finish to Nuvio before the Zidoo player
     * closes, so Nuvio has it the instant it is back on screen. Skipped with the
     * header relay, which has to keep serving until the player closes.
     */
    private fun checkNearEnd(sample: ZidooSample) {
        if (relay != null || sample.paused || durationMs <= 0L || done) return
        if (durationMs - sample.positionMs in 0..NEAR_END_MS) {
            Log.i(TAG, "Last seconds of the video: reporting it finished")
            finishPlayback(PlaybackResult(durationMs, durationMs, EndReason.COMPLETED))
        }
    }

    // ------------------------------------------------------------------ results

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PLAYER) return
        val zidooPositionMs = data?.extras?.let(::positionExtra) ?: 0L
        Log.i(TAG, "Zidoo player returned: code=$resultCode position=$zidooPositionMs keys=${data?.extras?.keySet()}")
        if (done) return

        val sinceLaunch = SystemClock.elapsedRealtime() - playerLaunchedAtMs
        if (playerLaunchedAtMs > 0L && sinceLaunch < DETACHED_RETURN_MS &&
            resultCode == RESULT_CANCELED && data == null && monitorJob?.isActive == true
        ) {
            // Came back the moment it opened: this player runs in its own task and won't report back.
            Log.i(TAG, "The Zidoo player returned at once; following it through its API only")
            detached = true
            showStatus(getString(R.string.status_playing))
            return
        }
        playerClosed = true
        finishPlayback(computeResult(zidooPositionMs))
    }

    private fun computeResult(zidooPositionMs: Long): PlaybackResult? {
        var position = lastPositionMs
        if (zidooPositionMs > 0L) {
            val shortOfResume = startPositionMs > 0L && zidooPositionMs < startPositionMs - ResumeGuard.TOLERANCE_MS
            position = when {
                lastPositionMs <= 0L -> if (shortOfResume) 0L else zidooPositionMs
                zidooPositionMs >= lastPositionMs && zidooPositionMs - lastPositionMs <= MAX_RESULT_LEAD_MS -> zidooPositionMs
                else -> lastPositionMs
            }
        }
        return PlaybackResults.settle(position, durationMs, creditsStartMs)
    }

    private fun finishPlayback(result: PlaybackResult?) {
        if (done) return
        done = true
        countdownJob?.cancel()
        creditsJob?.cancel()
        monitorJob?.cancel()
        if (result != null) {
            val completed = result.reason == EndReason.COMPLETED
            saveRecord(result.positionMs, completed)
            val finishedTitle = title
            resumeStore.lastCompletion =
                if (completed && finishedTitle != null) CompletionMark(finishedTitle, System.currentTimeMillis()) else null
            setResult(RESULT_OK, NuvioIntent.resultIntent(result))
        } else {
            setResult(RESULT_CANCELED)
        }
        Log.i(TAG, "Back to Nuvio with $result")
        cleanUp()
        finish()
    }

    private fun cancelAndFinish() {
        if (done) return
        done = true
        countdownJob?.cancel()
        monitorJob?.cancel()
        // Cancelled Up Next: don't count down again if the episode is then picked by hand.
        resumeStore.lastCompletion = null
        setResult(RESULT_CANCELED)
        cleanUp()
        finish()
    }

    private fun cleanUp() {
        sessionStore.clear()
        relay?.close()
        relay = null
        MonitorService.stop(this)
    }

    /** Asks the Zidoo player to close; presses Back on it if Stop did not. Runs after this screen has gone. */
    private fun stopZidooPlayer() {
        appScope.launch {
            api.stop()
            delay(STOP_CHECK_DELAY_MS)
            if (api.status()?.state == ZidooPlayState.PLAYING) {
                Log.i(TAG, "Stop did not close the Zidoo player; pressing Back")
                api.back()
            }
        }
    }

    // ------------------------------------------------------------------ storage

    private fun saveRecord(positionMs: Long, completed: Boolean) {
        if (!settings.rememberPositions || key.isEmpty() || positionMs <= 0L) return
        resumeStore.put(key, ResumeRecord(positionMs, durationMs, completed, System.currentTimeMillis()))
        lastRecordPositionMs = positionMs
    }

    private fun saveSession() {
        if (key.isEmpty()) return
        sessionStore.save(ActiveSession(key, title, startPositionMs, creditsStartMs, lastPositionMs, durationMs))
    }

    private fun restoreFromSession() {
        playerLaunched = true
        val session = sessionStore.load()
        if (session != null) {
            key = session.key
            title = session.title
            isEpisode = EpisodeTag.parse(title) != null
            startPositionMs = session.startPositionMs
            creditsStartMs = session.creditsStartMs
            lastPositionMs = session.lastPositionMs
            durationMs = session.durationMs
        }
        showStatus(getString(R.string.status_playing))
        // Keep saving positions; the resume point was already reached before the restart.
        startMonitor(emptyList(), resumeAtMs = 0L)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PLAYER_LAUNCHED, playerLaunched && !done)
        if (playerLaunched && !done) saveSession()
    }

    // ---------------------------------------------------------------- UI bits

    private fun showStatus(headline: String) {
        headlineView.text = headline
        titleView.text = title.orEmpty()
        messageView.visibility = View.GONE
        ringView.visibility = View.GONE
        spinner.visibility = View.VISIBLE
        buttonRow.visibility = View.GONE
    }

    private fun showError(message: String) {
        headlineView.text = getString(R.string.error_headline)
        titleView.text = title.orEmpty()
        messageView.text = message
        messageView.visibility = View.VISIBLE
        ringView.visibility = View.GONE
        spinner.visibility = View.GONE
        buttonRow.visibility = View.VISIBLE
        primaryButton.text = getString(R.string.close)
        primaryButton.setOnClickListener { cancelAndFinish() }
        secondaryButton.visibility = View.GONE
        primaryButton.requestFocus()
    }

    private fun toast(text: String) {
        Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (playerLaunched && !done) {
            playerClosed = true
            finishPlayback(computeResult(zidooPositionMs = 0L))
        } else {
            cancelAndFinish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (isFinishing && !done) {
            relay?.close()
            MonitorService.stop(this)
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun positionExtra(extras: Bundle): Long? = when (val value = extras.get("position")) {
        is Int -> value.toLong()
        is Long -> value
        is String -> value.toLongOrNull()
        else -> null
    }?.takeIf { it > 0L }

    companion object {
        private const val TAG = "NuvioToZidoo"
        private const val REQUEST_PLAYER = 9529
        private const val STATE_PLAYER_LAUNCHED = "player_launched"

        /** Longer than Nuvio's own Zidoo tracking waits for a player to appear (about 13 s). */
        const val HOLD_FOR_NUVIO_MS = 14_500L
        private const val STARTUP_TIMEOUT_MS = 5L * 60 * 1000
        private const val CREDITS_GRACE_MS = 5_000L
        private const val NEAR_END_MS = 2_000L
        private const val DETACHED_RETURN_MS = 2_500L
        private const val RECORD_SAVE_EVERY_MS = 10_000L
        private const val SESSION_SAVE_EVERY_MS = 5_000L
        private const val MAX_RESULT_LEAD_MS = 15_000L
        private const val STOP_CHECK_DELAY_MS = 2_500L

        /** Outlives this screen, for the few seconds it takes to close the Zidoo player. */
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
