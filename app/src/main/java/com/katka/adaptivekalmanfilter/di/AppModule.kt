package com.katka.adaptivekalmanfilter.di

import android.content.Context
import com.katka.adaptivekalmanfilter.sensor_data_source.AndroidSensorDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * [AndroidSensorDataSource] живёт в Singleton-скоупе — один экземпляр
     * на всё приложение.  Это важно: FusedLocationProviderClient и
     * SensorManager дорого создавать, а переподписка на GPS при ротации
     * экрана нежелательна.
     *
     * ViewModel держит ссылку на source и вызывает start()/stop() сама.
     */
    @Provides
    @Singleton
    fun provideAndroidSensorDataSource(
        @ApplicationContext context: Context
    ): AndroidSensorDataSource = AndroidSensorDataSource(
        context            = context,
        gpsIntervalMs      = 1_000L,
        minDisplacementM   = 0f
    )
}