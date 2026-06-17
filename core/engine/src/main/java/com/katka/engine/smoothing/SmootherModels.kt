package com.katka.engine.smoothing

/** One Kalman-filter step (plus its raw GPS fix) fed into the smoother window. */
data class SmootherInput(
    val timestamp: Long,
    val kfX: Double,
    val kfY: Double,
    val rawX: Double,
    val rawY: Double,
    val vx: Double,
    val vy: Double,
    val speed: Double,
    val accuracy: Double,
    val innovationMag: Double,
    val sigmaPos: Double
)

/** Smoothed result for the central point of a full window (lag = [SmootherFeatures.HALF]). */
data class SmoothedSample(
    val timestamp: Long,
    val kfX: Double,
    val kfY: Double,
    val sgX: Double,
    val sgY: Double,
    val outX: Double,
    val outY: Double,
    val alpha: Double,
    val rawX: Double,
    val rawY: Double,
    val vx: Double,
    val vy: Double,
    val speed: Double,
    val innovationMag: Double,
    val sigmaPos: Double,
    val accuracy: Double
)
