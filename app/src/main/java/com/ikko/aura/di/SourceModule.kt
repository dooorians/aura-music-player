package com.ikko.aura.di

import com.ikko.aura.catalog.CatalogBindings
import com.ikko.aura.playback.TrackResolver
import com.ikko.aura.source.SourceId
import com.ikko.aura.source.local.MediaStoreSource
import com.ikko.aura.source.SourceProvider
import com.ikko.aura.source.remote.RemoteProvider
import com.ikko.aura.source.remote.RemoteSources
import com.ikko.aura.source.ytm.YTM
import com.ikko.aura.source.ytm.YtmDownloads
import com.ikko.aura.source.ytm.YtmSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SourceModule {

    @Provides
    @Singleton
    fun provideTrackResolver(
        local: MediaStoreSource,
        ytm: YtmSource,
        bindings: CatalogBindings,
        remote: RemoteSources,
        downloads: YtmDownloads,
        jioSaavnSource: com.ikko.aura.source.jiosaavn.JioSaavnSource,
        jioSaavnClient: com.ikko.aura.source.jiosaavn.JioSaavnClient,
    ): TrackResolver {
        val remoteProviders = java.util.concurrent.ConcurrentHashMap<SourceId, SourceProvider>()
        return TrackResolver(bindings, { id: SourceId ->
            when {
                id == SourceId.LOCAL -> local
                id == YTM -> ytm
                id == jioSaavnSource.id -> jioSaavnSource
                // An added source, by the id it was given: looked up as asked, since they come and go.
                remote.isRemoteId(id.value) -> remoteProviders.getOrPut(id) { RemoteProvider(id, remote, downloads) }
                else -> null
            }
        }, { it }, { title, artist ->
            // Fallback dynamically: query JioSaavn!
            val results = runCatching { jioSaavnClient.searchSongs("$title $artist") }.getOrNull() ?: emptyList()
            results.mapNotNull { res -> 
                jioSaavnSource.playable(res.id)
            }
        })
    }
}