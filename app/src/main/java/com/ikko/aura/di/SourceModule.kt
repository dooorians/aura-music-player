package com.ikko.aura.di

import com.ikko.aura.playback.TrackResolver
import com.ikko.aura.source.SourceId
import com.ikko.aura.source.local.LegacyLocalBindings
import com.ikko.aura.source.local.MediaStoreSource
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
    fun provideTrackResolver(local: MediaStoreSource): TrackResolver =
        TrackResolver(LegacyLocalBindings, mapOf(SourceId.LOCAL to local))
}
