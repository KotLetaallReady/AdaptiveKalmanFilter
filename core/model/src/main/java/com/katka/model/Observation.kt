package com.katka.model

/** One fused sensor observation — a GPS fix plus optional IMU data — delivered to the Kalman filter. */
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
