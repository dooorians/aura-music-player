package com.ikko.aura.source.ytm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ikko.aura.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the process alive, with a notification, while [YtmDownloads] works through its queue. */
@AndroidEntryPoint
class DownloadService : Service() {
    @Inject lateinit var downloads: YtmDownloads

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Téléchargements", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(NOTIFICATION_ID, notification("Téléchargement…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job?.isActive != true) {
            job = scope.launch {
                downloads.runQueue { done, total, title ->
                    manager.notify(NOTIFICATION_ID, notification(title.ifEmpty { "Téléchargement…" }, done, total))
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(title: String, done: Int, total: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cu_arrow_down_circle)
            .setContentTitle(if (total > 0) "Téléchargement ${done + 1} / $total" else "Téléchargement")
            .setContentText(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0)
            .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "aura_downloads"
        const val NOTIFICATION_ID = 1002
    }
}
