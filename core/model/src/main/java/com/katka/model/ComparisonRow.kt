package com.katka.model

/** One comparison-session row: the same central point as raw GPS, Kalman estimate x_KF, Savitzky-Golay x_SG and smoothed output. */
data class ComparisonRow(
    val stepIndex:     Int,
    val timestampMs:   Long,
    val rawLat:        Double,
    val rawLon:        Double,
    val gpsAccuracyM:  Float,
    val gpsSpeedMs:    Float,
    val kfLat:         Double,
    val kfLon:         Double,
    val kfVx:          Double,
    val kfVy:          Double,
    val kfSigmaPos:    Double,
    val kfInnov:       Double,
    val sgLat:         Double,
    val sgLon:         Double,
    val smoothedLat:   Double,
    val smoothedLon:   Double,
    /** Trust weight alpha in [0,1] predicted by the smoother network. */
    val alpha:         Double
)
