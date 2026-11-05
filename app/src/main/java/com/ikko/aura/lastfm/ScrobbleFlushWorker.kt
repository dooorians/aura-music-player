package com.ikko.aura.lastfm

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Sends the listens Last.fm could not take when they were made (airplane mode, no signal, the service busy).
 *
 * Run by the system only once there is a network, and retried with an exponential backoff it schedules itself: nothing in
 * the app wakes up to check whether the network is back, and nothing is lost if the app is never opened again.
 */
class ScrobbleFlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps { fun lastFm(): LastFm }

    override suspend fun doWork(): Result {
        val lastFm = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).lastFm()
        return if (lastFm.flushPending()) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "lastfm-scrobble-flush"

        fun schedule(context: Context) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<ScrobbleFlushWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                // KEEP: one waiting flush sends everything, however many listens were kept meanwhile.
                WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
            }
        }
    }
}
