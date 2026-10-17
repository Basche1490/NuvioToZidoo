package io.github.basche1490.nuviotozidoo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.File

/**
 * Starts the Zidoo player the way PlexToZidoo and the Jellyfin Zidoo edition do:
 * an explicit intent to the player's activity with `position`, `from_start`,
 * `title` and `return_result`.
 */
object ZidooPlayerLauncher {

    /** The current Zidoo player (firmware 6.4.42+ on the Z9X line, 1.1.42+ on the Z9X 8K line). */
    val NEW_PLAYER = ComponentName("com.zidoo.player", "com.zidoo.player.activity.PlayerActivity")

    /** The older built-in player. */
    val CLASSIC_PLAYER = ComponentName("com.android.gallery3d", "com.android.gallery3d.app.MovieActivity")

    fun isInstalled(context: Context, component: ComponentName): Boolean =
        try {
            context.packageManager.getActivityInfo(component, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    /** The player to use for [choice], or null when no Zidoo player is installed. */
    fun pick(context: Context, choice: PlayerChoice): ComponentName? {
        val newInstalled = isInstalled(context, NEW_PLAYER)
        val classicInstalled = isInstalled(context, CLASSIC_PLAYER)
        return when (choice) {
            PlayerChoice.NEW_PLAYER -> NEW_PLAYER.takeIf { newInstalled } ?: CLASSIC_PLAYER.takeIf { classicInstalled }
            PlayerChoice.CLASSIC_PLAYER -> CLASSIC_PLAYER.takeIf { classicInstalled } ?: NEW_PLAYER.takeIf { newInstalled }
            PlayerChoice.AUTO -> NEW_PLAYER.takeIf { newInstalled } ?: CLASSIC_PLAYER.takeIf { classicInstalled }
        }
    }

    fun buildIntent(component: ComponentName, url: String, title: String?, startPositionMs: Long): Intent {
        val data = if (url.startsWith("/") && File(url).exists()) Uri.fromFile(File(url)) else Uri.parse(url)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(data, "video/*")
            component.let { setClassName(it.packageName, it.className) }
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            putExtra("title", title.orEmpty())
            if (startPositionMs > 0L) {
                putExtra("from_start", false)
                putExtra("position", startPositionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            } else {
                putExtra("from_start", true)
            }
            putExtra("return_result", true)
        }
    }

    /**
     * Whether Nuvio treats this box as a Zidoo and runs its own Zidoo tracking
     * next to this app. The same test as Nuvio's ZidooPlayerMonitor.isZidooDevice().
     */
    fun nuvioSeesZidoo(): Boolean {
        val manufacturer = Build.MANUFACTURER?.lowercase().orEmpty()
        val brand = Build.BRAND?.lowercase().orEmpty()
        val model = Build.MODEL?.lowercase().orEmpty()
        return manufacturer.contains("zidoo") || brand.contains("zidoo") ||
            model.contains("zidoo") || model.contains("z9x 8k")
    }
}
