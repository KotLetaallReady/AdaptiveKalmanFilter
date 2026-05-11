package com.katka.model

/**
 * Summary statistics comparing filter output to ground-truth (or raw GPS).
 */
data class AccuracyMetrics(
    val rmse: Double,
    val mae: Double,
    val maxError: Double,
    val lag: Double,
    val stability: Double,
    val sampleCount: Int
) {
    companion object {
        val EMPTY = AccuracyMetrics(
            rmse = Double.NaN,
            mae = Double.NaN,
            maxError = Double.NaN,
            lag = Double.NaN,
            stability = Double.NaN,
            sampleCount = 0
        )
    }
}