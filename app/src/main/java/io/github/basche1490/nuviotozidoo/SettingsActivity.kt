package io.github.basche1490.nuviotozidoo

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The app's launcher screen: status, setup steps and settings, all reachable with a TV remote. */
class SettingsActivity : Activity() {

    private val scope = MainScope()
    private lateinit var settings: Settings
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = Settings(this)
        list = findViewById(R.id.settings_list)
        findViewById<TextView>(R.id.version).text =
            getString(R.string.version_line, packageManager.getPackageInfo(packageName, 0).versionName)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render() {
        val focusedTag = currentFocus?.tag
        list.removeAllViews()

        section(R.string.section_status)
        info(playerStatus())
        if (ZidooPlayerLauncher.nuvioSeesZidoo()) info(getString(R.string.status_nuvio_tracks_zidoo))

        section(R.string.section_setup)
        info(getString(R.string.setup_steps))

        section(R.string.section_playback)
        toggleRow("skip_intro", R.string.setting_skip_intro, R.string.setting_skip_intro_desc, settings.skipIntro) {
            settings.skipIntro = !settings.skipIntro
        }
        toggleRow("skip_recap", R.string.setting_skip_recap, R.string.setting_skip_recap_desc, settings.skipRecap) {
            settings.skipRecap = !settings.skipRecap
        }
        row(
            "credits",
            getString(R.string.setting_episode_credits),
            getString(
                if (settings.episodeCredits == CreditsAction.UP_NEXT) R.string.value_credits_up_next else R.string.value_credits_keep
            ),
            getString(R.string.setting_episode_credits_desc),
        ) {
            settings.episodeCredits =
                if (settings.episodeCredits == CreditsAction.UP_NEXT) CreditsAction.KEEP_PLAYING else CreditsAction.UP_NEXT
        }
        row(
            "countdown",
            getString(R.string.setting_countdown),
            countdownLabel(settings.countdownSeconds),
            getString(R.string.setting_countdown_desc),
        ) {
            val choices = Settings.COUNTDOWN_CHOICES
            val index = choices.indexOf(settings.countdownSeconds)
            settings.countdownSeconds = choices[(index + 1).mod(choices.size)]
        }
        toggleRow(
            "film_credits", R.string.setting_skip_film_credits, R.string.setting_skip_film_credits_desc,
            settings.skipFilmCredits,
        ) { settings.skipFilmCredits = !settings.skipFilmCredits }
        toggleRow("remember", R.string.setting_remember, R.string.setting_remember_desc, settings.rememberPositions) {
            settings.rememberPositions = !settings.rememberPositions
        }

        section(R.string.section_advanced)
        row("player", getString(R.string.setting_player), playerLabel(settings.player), getString(R.string.setting_player_desc)) {
            val values = PlayerChoice.entries
            settings.player = values[(settings.player.ordinal + 1) % values.size]
        }
        toggleRow("hold", R.string.setting_hold, R.string.setting_hold_desc, settings.holdForNuvio) {
            settings.holdForNuvio = !settings.holdForNuvio
        }
        row("test", getString(R.string.action_test_api), null, getString(R.string.action_test_api_desc)) {
            scope.launch {
                val ok = HttpZidooPlayerApi().isReachable()
                toast(getString(if (ok) R.string.test_api_ok else R.string.test_api_failed))
            }
        }
        row("forget", getString(R.string.action_forget), null, getString(R.string.action_forget_desc)) {
            ResumeStore(this).clearAll()
            toast(getString(R.string.forget_done))
        }

        val toFocus = focusedTag?.let { list.findViewWithTag<View>(it) } ?: firstFocusable()
        toFocus?.requestFocus()
    }

    private fun firstFocusable(): View? =
        (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isFocusable }

    private fun playerStatus(): String {
        val newPlayer = ZidooPlayerLauncher.isInstalled(this, ZidooPlayerLauncher.NEW_PLAYER)
        val classic = ZidooPlayerLauncher.isInstalled(this, ZidooPlayerLauncher.CLASSIC_PLAYER)
        return getString(
            when {
                newPlayer -> R.string.status_new_player
                classic -> R.string.status_classic_player
                else -> R.string.status_no_player
            }
        )
    }

    private fun countdownLabel(seconds: Int): String =
        if (seconds == 0) getString(R.string.value_countdown_off) else getString(R.string.value_seconds, seconds)

    private fun playerLabel(choice: PlayerChoice): String = getString(
        when (choice) {
            PlayerChoice.AUTO -> R.string.value_player_auto
            PlayerChoice.NEW_PLAYER -> R.string.value_player_new
            PlayerChoice.CLASSIC_PLAYER -> R.string.value_player_classic
        }
    )

    // ------------------------------------------------------------- row builders

    private fun section(titleRes: Int) {
        list.addView(TextView(this).apply {
            text = getString(titleRes)
            setTextColor(getColor(R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isAllCaps = true
            letterSpacing = 0.08f
            setPadding(dp(4), dp(28), dp(4), dp(8))
        })
    }

    private fun info(textValue: String) {
        list.addView(TextView(this).apply {
            text = textValue
            setTextColor(getColor(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(0f, 1.25f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        })
    }

    private fun toggleRow(tag: String, titleRes: Int, descRes: Int, value: Boolean, onClick: () -> Unit) =
        row(tag, getString(titleRes), getString(if (value) R.string.value_on else R.string.value_off), getString(descRes), onClick)

    private fun row(tag: String, titleText: String, valueText: String?, description: String?, onClick: () -> Unit) {
        val container = LinearLayout(this).apply {
            this.tag = tag
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.row_background)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4) }
            setOnClickListener {
                onClick()
                render()
            }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = titleText
            setTextColor(getColorStateList(R.color.row_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            isDuplicateParentStateEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (valueText != null) {
            top.addView(TextView(this).apply {
                text = valueText
                setTextColor(getColorStateList(R.color.row_value))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                isDuplicateParentStateEnabled = true
            })
        }
        top.isDuplicateParentStateEnabled = true
        container.addView(top)
        if (!description.isNullOrBlank()) {
            container.addView(TextView(this).apply {
                text = description
                setTextColor(getColorStateList(R.color.row_description))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(4), 0, 0)
                isDuplicateParentStateEnabled = true
            })
        }
        list.addView(container)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
