package com.remotefix.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.content.pm.PackageManager
import android.Manifest
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class SessionPresenceService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), foregroundType)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification =
        SessionPresenceService.buildNotification(this, getString(R.string.notification_text), alert = false)

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        requireNotNull(getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "remotefix_active_session"
        private const val NOTIFICATION_ID = 4812

        fun start(context: Context) {
            val intent = Intent(context, SessionPresenceService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun update(context: Context, message: String, alert: Boolean = false): Boolean {
            if (!hasNotificationAccess(context)) return false
            val manager = context.applicationContext.getSystemService(NotificationManager::class.java) ?: return false
            return runCatching {
                manager.notify(NOTIFICATION_ID, buildNotification(context.applicationContext, message, alert))
                true
            }.getOrDefault(false)
        }

        fun hasNotificationAccess(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false
            val manager = context.applicationContext.getSystemService(NotificationManager::class.java) ?: return false
            if (!manager.areNotificationsEnabled()) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
            ) return false
            return true
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionPresenceService::class.java))
        }

        private fun buildNotification(context: Context, message: String, alert: Boolean): Notification {
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_remotefix)
                .setContentTitle(context.getString(R.string.notification_title))
                .setContentText(message)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(!alert)
                .setSilent(!alert)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
        }
    }
}
