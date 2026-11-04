package com.ikko.aura.di

import android.content.Context
import androidx.room.Room
import com.ikko.aura.data.lyrics.LyricsCacheDao
import com.ikko.aura.data.playlist.AuraDatabase
import com.ikko.aura.data.AlbumPlaysDao
import com.ikko.aura.data.playlist.MIGRATION_1_2
import com.ikko.aura.data.playlist.MIGRATION_2_3
import com.ikko.aura.data.playlist.MIGRATION_3_4
import com.ikko.aura.data.playlist.MIGRATION_4_5
import com.ikko.aura.data.playlist.MIGRATION_5_6
import com.ikko.aura.data.playlist.MIGRATION_6_7
import com.ikko.aura.data.playlist.MIGRATION_7_8
import com.ikko.aura.data.playlist.MIGRATION_8_9
import com.ikko.aura.source.remote.SourceConfigDao
import com.ikko.aura.catalog.DownloadDao
import com.ikko.aura.catalog.CatalogDao
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, com.ikko.aura.reco.MIGRATION_9_10, com.ikko.aura.reco.MIGRATION_10_11, com.ikko.aura.reco.MIGRATION_11_12)
            .build()

    @Provides
    fun providePlaylistDao(database: AuraDatabase): PlaylistDao = database.playlistDao()

    @Provides
    fun provideLyricsCacheDao(database: AuraDatabase): LyricsCacheDao = database.lyricsCacheDao()

    @Provides
    fun provideAlbumPlaysDao(database: AuraDatabase): AlbumPlaysDao = database.albumPlaysDao()

    @Provides
    fun provideCatalogDao(database: AuraDatabase): CatalogDao = database.catalogDao()

    @Provides
    fun provideDownloadDao(database: AuraDatabase): DownloadDao = database.downloadDao()

    @Provides
    fun provideRecoDao(database: AuraDatabase): com.ikko.aura.reco.RecoDao = database.recoDao()

    @Provides
    fun provideSmartDao(database: AuraDatabase): com.ikko.aura.reco.SmartDao = database.smartDao()

    @Provides
    fun provideVerifiedDiscographyDao(database: AuraDatabase): com.ikko.aura.reco.VerifiedDiscographyDao = database.verifiedDiscographyDao()

    @Provides
    fun provideSourceConfigDao(database: AuraDatabase): SourceConfigDao = database.sourceConfigDao()
}
