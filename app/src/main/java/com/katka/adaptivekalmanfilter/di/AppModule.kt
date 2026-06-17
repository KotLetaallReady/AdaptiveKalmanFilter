package com.katka.adaptivekalmanfilter.di

import android.content.Context
import com.katka.android.AndroidLogger
import com.katka.android.AndroidSensorDataSource
import com.katka.android.FileSmootherStore
import com.katka.engine.Logger
import com.katka.engine.neural.SmootherRepository
import com.katka.engine.neural.SmootherStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** Single app-wide [AndroidSensorDataSource]; the GPS/sensor clients are costly to create and re-subscribe. */
    @Provides
    @Singleton
    fun provideAndroidSensorDataSource(
        @ApplicationContext context: Context
    ): AndroidSensorDataSource = AndroidSensorDataSource(
        context            = context,
        gpsIntervalMs      = 1_000L,
        minDisplacementM   = 0f
    )


    /** File-backed [SmootherStore] — the engine's persistence boundary. */
    @Provides
    @Singleton
    fun provideSmootherStore(
        @ApplicationContext context: Context
    ): SmootherStore = FileSmootherStore(context)

    @Provides
    @Singleton
    fun provideSmootherRepository(store: SmootherStore): SmootherRepository =
        SmootherRepository(store)

    /** Routes engine logging to Logcat (the engine itself is Android-free). */
    @Provides
    @Singleton
    fun provideLogger(): Logger = AndroidLogger()
}
