package com.reeltranscribe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * The notifications Android requires while the app runs in the background
 * ("foreground service" notifications). They also give you a Stop button.
 */
object Notifications {
    private const val CHANNEL_ID = "status"
    const val ID_BUBBLE = 1
    const val ID_CAPTURE = 2

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, "Bubble and capture status", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shown while the bubble is on or audio is being captured." }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun bubble(context: Context, hideIntent: PendingIntent): Notification =
        base(context)
            .setContentTitle("Transcribe bubble is on")
            .setContentText("Tap the bubble while a reel plays to transcribe it.")
            .addAction(0, "Turn off bubble", hideIntent)
            .build()

    fun capture(context: Context, text: String, stopIntent: PendingIntent): Notification =
        base(context)
            .setContentTitle("Capturing reel audio")
            .setContentText(text)
            .addAction(0, "Stop", stopIntent)
            .build()

    private fun base(context: Context) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bubble)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
}
