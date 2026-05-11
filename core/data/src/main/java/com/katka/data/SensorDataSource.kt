package com.katka.data

import com.katka.model.Observation
import kotlinx.coroutines.flow.Flow

/**
 * Source of fused sensor observations fed into the Kalman filter.
 *
 * The interface is pure-Kotlin so it can live in the `:core` module and be
 * injected into any engine or use-case without importing Android classes.
 *
 * ── Contract ─────────────────────────────────────────────────────────────────
 *
 *  • [observations] is a **cold** Flow that starts emitting when the first
 *    collector appears (i.e., when the tracking session begins).
 *  • Each emitted [Observation] represents one GPS fix, optionally enriched
 *    with the most-recent IMU reading captured at that moment.
 *  • The flow is guaranteed to be sequential — no concurrent emissions.
 *  • [start] / [stop] are lifecycle hooks for the underlying Android sensors.
 *    They are idempotent (safe to call multiple times).
 *
 * ── Implementations ───────────────────────────────────────────────────────────
 *
 *  • `AndroidSensorDataSource` (:app) — uses FusedLocationProviderClient +
 *    SensorManager (TYPE_LINEAR_ACCELERATION).
 *  • `ReplaySensorDataSource` (test) — replays a pre-recorded list, useful for
 *    deterministic integration tests and UI previews.
 */
interface SensorDataSource {

    /** Cold Flow of observations.  Collect this to drive the filter. */
    val observations: Flow<Observation>

    /**
     * Start acquiring sensor data from the underlying hardware.
     * Must be called before collecting [observations].
     *
     * On Android this triggers:
     *   - FusedLocationProviderClient.requestLocationUpdates()
     *   - SensorManager.registerListener() for TYPE_LINEAR_ACCELERATION
     */
    fun start()

    /**
     * Stop acquiring sensor data and release all system resources.
     * Should be called when the tracking session ends or the screen is not visible.
     */
    fun stop()

    /** Whether the source is currently active. */
    val isRunning: Boolean
}

// ── Test / preview stub ──────────────────────────────────────────────────────

/**
 * Simple replay implementation for unit tests and Compose Previews.
 * Emits [items] in order with no delay.
 */
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