package com.ikko.aura.source.ytm

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What has been streamed, kept on disk so a song heard once plays again without the network: a bounded, least recently
 * used cache that evicts by itself and lives in the app's cache directory, so the system may also reclaim it.
 *
 * Keyed on the song's `ytm://<videoId>`, not on the stream address, which changes every few hours: an expired address
 * does not lose what was already downloaded. It is a cache and nothing more — no song is ever kept as a download.
 */
@UnstableApi
@Singleton
class StreamCache @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val cache: SimpleCache by lazy {
        SimpleCache(
            File(context.cacheDir, "ytm_streams"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context),
        )
    }

    /** [upstream] opened through the cache; a read error in the cache itself falls back to the network. */
    fun factory(upstream: androidx.media3.datasource.DataSource.Factory): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    val sizeBytes: Long get() = cache.cacheSpace

    fun clear() {
        synchronized(cache) { cache.keys.toList().forEach { cache.removeResource(it) } }
    }

    private companion object {
        const val MAX_BYTES = 512L * 1024 * 1024
    }
}
