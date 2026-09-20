package com.ikko.aura.di

import com.ikko.aura.data.LocalMusicRepository
import com.ikko.aura.data.MusicRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMusicRepository(impl: LocalMusicRepository): MusicRepository
}
