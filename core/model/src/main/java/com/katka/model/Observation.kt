package com.katka.model

/**
 * One fused sensor observation delivered to the Kalman filter.
 *
 * Produced by the sensor layer (AndroidSensorDataSource) and consumed by
 * [core.engine.KalmanFilter.process].
 *
 * ── GPS fields ──────────────────────────────────────────────────────────────
 * [latitude], [longitude]  — WGS-84 degrees.
 * [accuracy]               — Android Location.getAccuracy():
 *                            horizontal 1-σ radius in metres at 68 % confidence.
 *                            This is the primary input for computing R mathematically.
 * [altitude]               — metres above sea level (optional, not used by 2-D filter).
 * [speed]                  — m/s reported by the GPS chipset (optional, can augment z).
 * [bearing]                — degrees from north (optional).
 *
 * ── IMU fields ──────────────────────────────────────────────────────────────
 * [ax], [ay], [az]         — linear acceleration in m/s² in the device frame
 *                            (TYPE_LINEAR_ACCELERATION, gravity already removed).
 * [hasImu]                 — true when the current reading contains fresh IMU data.
 *                            The filter uses IMU as a control input u only when true.
 */
data class Observation(
    /** Unix epoch time in milliseconds. */
    val timestamp: Long,

    // GPS
    val latitude: Double,
    val longitude: Double,
    /** 1-σ horizontal accuracy radius (metres). Used to derive R. */
    val accuracy: Float,
    val altitude: Double = 0.0,
    val speed: Float = 0f,
    val bearing: Float = 0f,
    val hasSpeed: Boolean = false,
    val hasBearing: Boolean = false,

    // IMU (Sensor.TYPE_LINEAR_ACCELERATION)
    val ax: Double = 0.0,
    val ay: Double = 0.0,
    val az: Double = 0.0,
    val hasImu: Boolean = false,

    /** Provider that delivered the GPS fix ("gps", "fused", etc.). */
    val provider: String = "unknown"
)