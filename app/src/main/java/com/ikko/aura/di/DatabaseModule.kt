package com.ikko.aura.di

import android.content.Context
import androidx.room.Room
import com.ikko.aura.data.lyrics.LyricsCacheDao
import com.ikko.aura.data.playlist.AuraDatabase
import com.ikko.aura.data.AlbumPlaysDao
import com.ikko.aura.data.playlist.MIGRATION_1_2
import com.ikko.aura.data.playlist.MIGRATION_2_3
import com.ikko.aura.data.playlist.PlaylistDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAuraDatabase(@ApplicationContext context: Context): AuraDatabase =
        Room.databaseBuilder(context, AuraDatabase::class.java, "aura.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides
    fun providePlaylistDao(database: AuraDatabase): PlaylistDao = database.playlistDao()

    @Provides
    fun provideLyricsCacheDao(database: AuraDatabase): LyricsCacheDao = database.lyricsCacheDao()

    @Provides
    fun provideAlbumPlaysDao(database: AuraDatabase): AlbumPlaysDao = database.albumPlaysDao()
}
