package com.ikko.aura.player

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ikko.aura.MainActivity
import com.ikko.aura.R

/**
 * What the listener sees when Android refused to start playback from the background
 * (`ForegroundServiceStartNotAllowedException`, Android 12+): a quiet notification whose tap opens the app, from
 * where playback is allowed to start. Without notification permission (Android 13+, refused by the user) nothing is
 * shown, and nothing fails either.
 */
internal object PlaybackResumeNotice {

    private const val CHANNEL_ID = "aura_playback_resume"
    private const val NOTIFICATION_ID = 1003

    fun post(context: Context) {
        runCatching {
            if (!canNotify(
                    permissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED,
                    notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                )
            ) return

            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_playback),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cu_headphones)
                .setContentTitle("Lecture en pause")
                .setContentText("Touche pour reprendre la lecture dans Aura.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    /** Posting is pointless, and refused, without the Android 13+ permission or with the app's notifications off. */
    fun canNotify(permissionGranted: Boolean, notificationsEnabled: Boolean): Boolean =
        permissionGranted && notificationsEnabled
}
