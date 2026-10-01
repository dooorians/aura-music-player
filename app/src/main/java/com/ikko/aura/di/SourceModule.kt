package com.ikko.aura.di

import com.ikko.aura.catalog.CatalogBindings
import com.ikko.aura.playback.TrackResolver
import com.ikko.aura.source.SourceId
import com.ikko.aura.source.local.MediaStoreSource
import com.ikko.aura.source.ytm.YTM
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
    fun provideTrackResolver(local: MediaStoreSource, ytm: YtmSource, bindings: CatalogBindings): TrackResolver =
        TrackResolver(bindings, mapOf(SourceId.LOCAL to local, YTM to ytm))
}
