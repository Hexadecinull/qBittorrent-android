package org.qbittorrent.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {

    private const val CHANNEL_ID = "qbittorrent_service"
    const val NOTIFICATION_ID = 1001

    enum class State {
        STARTING, RUNNING, ERROR
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    fun buildNotification(context: Context, state: State): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, QBittorrentService::class.java).apply {
                action = QBittorrentService.ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (title, text) = when (state) {
            State.STARTING -> Pair(
                context.getString(R.string.notification_title_starting),
                context.getString(R.string.notification_text_starting)
            )
            State.RUNNING -> Pair(
                context.getString(R.string.notification_title_running),
                context.getString(R.string.notification_text_running, QBittorrentService.WEB_UI_PORT)
            )
            State.ERROR -> Pair(
                context.getString(R.string.notification_title_error),
                context.getString(R.string.notification_text_error)
            )
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(state == State.RUNNING || state == State.STARTING)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_notification,
                context.getString(R.string.action_stop),
                stopIntent
            )
            .build()
    }
}
