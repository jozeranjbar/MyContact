package com.mycontact.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Incoming message/call notifications while the app is backgrounded —
 * native equivalent of the original app's Notification API usage
 * (only shown while the page/app is hidden, matching shouldNotify()).
 */
object NotificationHelper {
    private const val CHANNEL_ID = "mycontact_messages"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "پیام و تماس", NotificationManager.IMPORTANCE_HIGH)
            mgr.createNotificationChannel(channel)
        }
    }

    fun notify(context: Context, title: String, body: String, tag: String = "mycontact") {
        val intent = Intent(context, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(tag, 1, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted yet — silently skip, mirrors
            // Notification.permission !== 'granted' in the original.
        }
    }
}
