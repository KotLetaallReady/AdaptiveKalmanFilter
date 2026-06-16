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
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double,
    val speed: Float,
    val bearing: Float,
    val hasSpeed: Boolean,
    val hasBearing: Boolean,
    val ax: Double,
    val ay: Double,
    val az: Double,
    val axGeo: Double = 0.0,
    val ayGeo: Double = 0.0,
    val hasImu: Boolean,
    val hasRotation: Boolean = false,
    val provider: String
)