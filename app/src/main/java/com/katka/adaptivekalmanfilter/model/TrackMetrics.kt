package com.katka.adaptivekalmanfilter.model

import kotlin.math.sqrt

/**
 * Accuracy metrics of one track relative to a reference track (local metres).
 *
 * Used to score both the Kalman track and the neural-smoothed track against the
 * raw GPS fixes.  Note (diploma §4): the smoother is expected to *slightly
 * increase* the mean deviation from raw GPS while producing a geometrically
 * smoother path — that is smoothing, not a regression.
 */
object TrackMetrics {

    /**
     * @param estimated estimated points (x,y) in local metres
     * @param reference reference points (x,y) in local metres (same ordering)
     */
    fun compute(
        estimated: List<Pair<Double, Double>>,
        reference: List<Pair<Double, Double>>
    ): MetricsUiModel {
        val n = minOf(estimated.size, reference.size)
        if (n < 2) return MetricsUiModel.EMPTY

        val errors = (0 until n).map { i ->
            val dx = estimated[i].first - reference[i].first
            val dy = estimated[i].second - reference[i].second
            sqrt(dx * dx + dy * dy)
        }
        val rmse = sqrt(errors.sumOf { it * it } / n)
        val mae = errors.sum() / n
        val maxErr = errors.max()

        // Jitter — std of consecutive step lengths of the estimated track.
        val deltas = (1 until n).map { i ->
            val dx = estimated[i].first - estimated[i - 1].first
            val dy = estimated[i].second - estimated[i - 1].second
            sqrt(dx * dx + dy * dy)
        }
        val meanStep = if (deltas.isEmpty()) 0.0 else deltas.sum() / deltas.size
        val jitter = if (deltas.isEmpty()) 0.0
        else sqrt(deltas.sumOf { (it - meanStep) * (it - meanStep) } / deltas.size)

        return MetricsUiModel(
            rmse = "%.2f м".format(rmse),
            mae = "%.2f м".format(mae),
            maxError = "%.2f м".format(maxErr),
            stability = "%.3f м".format(jitter),
            lag = "—",
            sampleCount = n
        )
    }
}
