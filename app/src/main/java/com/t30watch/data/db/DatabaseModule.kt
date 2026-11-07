package com.t30watch.data.db

import android.content.Context
import androidx.room.Room
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
    fun provideDatabase(@ApplicationContext context: Context): T30Database =
        Room.databaseBuilder(context, T30Database::class.java, "t30.db").build()

    @Provides
    fun provideKnownDeviceDao(db: T30Database): KnownDeviceDao = db.knownDeviceDao()

    @Provides
    fun provideMeasurementDao(db: T30Database): MeasurementDao = db.measurementDao()

    @Provides
    fun provideHistoryDao(db: T30Database): HistoryDao = db.historyDao()
}
