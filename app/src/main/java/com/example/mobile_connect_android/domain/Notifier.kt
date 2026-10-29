package com.example.mobile_connect_android.domain

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.mobile_connect_android.MainActivity
import com.example.mobile_connect_android.data.TermNote

class Notifier(private val context: Context) {
    companion object {
        const val CHANNEL_ID = "terminal_events"
        const val CHANNEL_NAME = "Terminal Events"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for terminal session status and completed commands"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun post(note: TermNote, title: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_terminal_id", note.term)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            note.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val iconRes = android.R.drawable.stat_notify_chat

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle(title)
            .setContentText(note.body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(note.id, builder.build())
    }

    fun remove(ids: List<Int>) {
        ids.forEach { notificationManager.cancel(it) }
    }
}
