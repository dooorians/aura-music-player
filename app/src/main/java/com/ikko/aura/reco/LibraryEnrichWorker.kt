package com.ikko.aura.reco

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
 * Runs [LastFmEnricher.enrichLibraryNow] when the system judges it cheap: an unmetered network and a battery that is not low.
 * If either goes away mid-run, WorkManager cancels the coroutine and runs it again later; what was fetched is kept.
 */
class LibraryEnrichWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps { fun enricher(): LastFmEnricher }

    override suspend fun doWork(): Result {
        val enricher = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).enricher()
        enricher.enrichLibraryNow()
        return Result.success()
    }

    companion object {
        private const val NAME = "lastfm-library-enrich"

        /** The constraints the work waits for; public so a test can state them. */
        val CONSTRAINTS: Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        fun schedule(context: Context) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<LibraryEnrichWorker>()
                    .setConstraints(CONSTRAINTS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
            }
        }
    }
}
