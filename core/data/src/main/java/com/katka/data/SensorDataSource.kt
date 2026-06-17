package com.katka.data

import com.katka.model.Observation
import kotlinx.coroutines.flow.Flow

/** Source of fused sensor observations (GPS + IMU) driving the Kalman filter; platform-agnostic. */
interface SensorDataSource {

    /** Cold Flow of observations; collect it to drive the filter. */
    val observations: Flow<Observation>

    /** Starts acquiring sensor data; idempotent. */
    fun start()

    /** Stops acquiring sensor data and releases resources; idempotent. */
    fun stop()

    /** Whether the source is currently active. */
    val isRunning: Boolean
}

/** Replays a fixed list of observations with no delay, for tests and previews. */
class ReplaySensorDataSource(
    private val items: List<Observation>
) : SensorDataSource {

    private val _flow = kotlinx.coroutines.flow.flow {
        items.forEach { emit(it) }
    }

    override val observations: Flow<Observation> = _flow
    override var isRunning: Boolean = false
        private set

    override fun start()  { isRunning = true  }
    override fun stop()   { isRunning = false }
}
