package com.nuvio.app.features.recordings.internal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nuvio.app.features.settings.AppIconPlatform

/** The notifications of the recordings feature: the ongoing capture, and "could not start". */
internal object RecordingNotifications {
    const val CHANNEL_ID = "recordings"
    const val ONGOING_ID = 0x7EC0
    private const val COULD_NOT_START_ID = 0x7EC1

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recordings", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun openApp(context: Context): PendingIntent {
        val launch = Intent().apply {
            component = AppIconPlatform.currentLauncherComponent(context)
            action = Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * [percent] null = no estimate (indeterminate bar). [cancelScheduleId] adds the Cancel action
     * (a service intent, so it works with the app closed).
     */
    fun ongoing(context: Context, title: String, text: String, percent: Int?, cancelScheduleId: String?): Notification {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context))
        if (percent == null) builder.setProgress(0, 0, true) else builder.setProgress(100, percent.coerceIn(0, 100), false)
        if (cancelScheduleId != null) {
            val cancel = PendingIntent.getService(
                context, cancelScheduleId.hashCode(), RecordingService.cancelIntent(context, cancelScheduleId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Cancel", cancel)
        }
        return builder.build()
    }

    /** Shown when Android refused to start the service from the background (an inexact alarm). The schedule stays; opening the app runs it. */
    fun couldNotStart(context: Context) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
            .setContentTitle("Recording couldn't start")
            .setContentText("Open Tuvora")
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(COULD_NOT_START_ID, n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted: nothing more we can say.
        }
    }
}
