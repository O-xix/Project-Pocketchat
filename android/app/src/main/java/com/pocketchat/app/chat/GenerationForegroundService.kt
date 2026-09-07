package com.pocketchat.app.chat

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pocketchat.app.MainActivity

private const val CHANNEL_ID = "pocketchat_generation"
private const val GENERATING_NOTIFICATION_ID = 1001
private const val COMPLETE_NOTIFICATION_ID = 1002

/**
 * FR-042: a minimal foreground service whose only job is to exist with an
 * active `startForeground()` notification for the duration of one chat
 * response — that's what exempts the hosting *process* (where
 * [ChatViewModel]'s actual generation coroutine runs, completely unchanged)
 * from Android's background CPU/Doze restrictions, without needing to
 * relocate the generation pipeline itself into service-owned code.
 * [ChatViewModel] starts/stops this explicitly around one `sendMessage()`
 * call when (and only when) the app is backgrounded during it — see its
 * `ProcessLifecycleOwner` observer. Scoped to normal chat responses only,
 * per the ticket: memory-update passes already have their own in-app
 * progress/review flow (FR-010/FR-022) and aren't covered here.
 *
 * `foregroundServiceType="dataSync"` (manifest): none of Android's
 * predefined types precisely fits "keep an on-device compute task running"
 * — `dataSync` is the conventional choice other apps use for that general
 * shape of background work, chosen over the API 34+ `specialUse` type to
 * avoid that type's additional Play-review-justification expectations,
 * which don't fit this project's CI-verified-builds-only distribution model
 * (NFR-008) anyway.
 */
class GenerationForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        startForeground(GENERATING_NOTIFICATION_ID, buildGeneratingNotification(this))
        // Not START_STICKY: ChatViewModel starts/stops this around one specific
        // generation call. If the process dies, there's no in-flight response
        // left to resume by having the OS restart this service on its own.
        return START_NOT_STICKY
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, GenerationForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Safe to call even if the service isn't running. */
        fun stop(context: Context) {
            context.stopService(Intent(context, GenerationForegroundService::class.java))
        }
    }
}

private fun buildGeneratingNotification(context: Context): Notification {
    val openApp = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle("PocketChat")
        .setContentText("Generating a response…")
        .setSmallIcon(android.R.drawable.ic_popup_sync)
        .setOngoing(true)
        .setContentIntent(openApp)
        .build()
}

/**
 * FR-042: the one-shot notification for when a response finishes while the
 * app was backgrounded — [ChatViewModel] calls this only in that case, never
 * while the app is in the foreground and the user can already see the reply
 * land. A silent no-op if `POST_NOTIFICATIONS` was never granted (API 33+)
 * or the user denied it — best-effort, not worth a permission-rationale flow
 * for a single notification.
 */
fun postCompletionNotification(context: Context, responsePreview: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    ensureChannel(context)
    val openApp = PendingIntent.getActivity(
        context, 1, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle("PocketChat")
        .setContentText(responsePreview.take(120))
        .setSmallIcon(android.R.drawable.ic_popup_sync)
        .setAutoCancel(true)
        .setContentIntent(openApp)
        .build()
    NotificationManagerCompat.from(context).notify(COMPLETE_NOTIFICATION_ID, notification)
}

private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Response generation", NotificationManager.IMPORTANCE_LOW)
        )
    }
}
