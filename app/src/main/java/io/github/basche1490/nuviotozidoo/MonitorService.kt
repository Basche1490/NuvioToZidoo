package io.github.basche1490.nuviotozidoo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps this app's process alive while the Zidoo player plays. Zidoo firmware
 * stops background apps aggressively; without this, position saving, skipping
 * and the hand-back to Nuvio could stop partway through a film.
 */
class MonitorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE)
        val notification = buildNotification(title)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not start in the foreground", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(title: String?): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
                )
            }
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(title ?: getString(R.string.app_name))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "NuvioToZidoo"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 7529
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, title: String?) {
            val intent = Intent(context, MonitorService::class.java).putExtra(EXTRA_TITLE, title)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not start the keep-alive service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
