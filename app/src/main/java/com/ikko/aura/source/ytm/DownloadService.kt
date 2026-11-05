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
        val inForeground = com.ikko.aura.util.ForegroundStart.attempt {
            startForeground(NOTIFICATION_ID, notification("Téléchargement…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
        if (!inForeground) {
            // Refused (the app went to the background first): the queue stays pending and YtmDownloads.resume picks it
            // up at the next launch. Running on without the foreground would get the process killed mid-file.
            if (job?.isActive != true) stopSelf(startId)
            return START_NOT_STICKY
        }
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

    /**
     * Android 15+ gives a dataSync service six hours a day. When they are spent this is called, and a service still in
     * the foreground a few seconds later crashes the app (ForegroundServiceDidNotStopInTimeException). The download
     * in flight is put back in the queue by YtmDownloads.resume at the next launch.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "aura_downloads"
        const val NOTIFICATION_ID = 1002
    }
}
