package com.jossephus.chuchu.service.terminal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jossephus.chuchu.MainActivity
import com.jossephus.chuchu.R
import java.util.concurrent.atomic.AtomicInteger

/** Posts long-running command results without exposing the command text itself. */
internal object CommandCompletionNotifier {
    const val ACTION_OPEN_TAB = "com.jossephus.chuchu.action.OPEN_TERMINAL_TAB"
    const val EXTRA_TAB_ID = "extra_terminal_tab_id"

    // Channel importance is immutable after a channel is created. Keep a new
    // id so users upgrading from the original DEFAULT channel get the
    // heads-up behavior without having to delete the old channel manually.
    private const val CHANNEL_ID = "chuchu_command_completion_heads_up"
    private const val CHANNEL_NAME = "Command completion"
    private const val NOTIFICATION_ID_START = 3000
    private val nextNotificationId = AtomicInteger(NOTIFICATION_ID_START)

    fun post(
        context: Context,
        tabId: String,
        label: String,
        completion: CompletedCommand,
    ) {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        val manager = NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) return

        val requestCode = tabId.hashCode()
        val pendingFlags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        val tapIntent =
            Intent(appContext, MainActivity::class.java).apply {
                action = ACTION_OPEN_TAB
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_TAB_ID, tabId)
            }
        val tapPending =
            PendingIntent.getActivity(appContext, requestCode, tapIntent, pendingFlags)

        val succeeded = completion.exitCode == 0
        val title =
            if (succeeded) {
                "Command finished"
            } else {
                "Command failed (exit ${completion.exitCode})"
            }
        val text = "$label  ·  ${formatDuration(completion.durationMs)}"
        val notification =
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(tapPending)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build()

        runCatching {
            manager.notify(nextNotificationId.getAndIncrement(), notification)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Notifies when a long-running terminal command completes"
                // Keep the alert noticeable without adding another loud
                // notification sound on top of the foreground-service notice.
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 120L)
            },
        )
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = durationMs / 1000.0
        return if (seconds < 10.0) "%.1f s".format(java.util.Locale.US, seconds)
        else "%.0f s".format(java.util.Locale.US, seconds)
    }
}
