package com.katka.metrics

import com.katka.model.AccuracyMetrics
import kotlin.math.sqrt

/**
 * Computes accuracy metrics by comparing a sequence of filtered position
 * estimates against a reference track (GPS raw positions or ground truth).
 *
 * All inputs are in local metric coordinates (metres).
 *
 * ── Metrics ──────────────────────────────────────────────────────────────────
 *
 *  RMSE     Root-Mean-Square Error — penalises large deviations heavily.
 *           RMSE = √( (1/N)·Σ ||p̂_k − p*_k||² )
 *
 *  MAE      Mean Absolute Error — robust to outliers.
 *           MAE = (1/N)·Σ ||p̂_k − p*_k||
 *
 *  maxError Single largest positional error in the sequence.
 *
 *  stability Standard deviation of consecutive positional increments —
 *           measures jitter / smoothness of the filtered output.
 *           Low stability → noisy track; high stability → smooth track.
 *
 *  lag      Estimates the systematic temporal delay of the filter relative to
 *           ground truth by finding the cross-correlation peak offset between
 *           the x-sequences of estimate and reference.  Expressed in sample
 *           units (multiply by average dt to get seconds).
 */
object MetricsEvaluator {

    /**
     * @param estimated List of (x, y) filter outputs.
     * @param reference List of (x, y) reference positions (raw GPS or ground truth).
     *                  Must have the same length as [estimated].
     */
    fun compute(
        estimated: List<Pair<Double, Double>>,
        reference: List<Pair<Double, Double>>
    ): AccuracyMetrics {
        require(estimated.size == reference.size && estimated.isNotEmpty()) {
            "estimated and reference must be non-empty and same length"
        }
        val n = estimated.size

        // ── Per-step Euclidean errors ─────────────────────────────────────────
        val errors = estimated.zip(reference).map { (e, r) ->
            val dx = e.first  - r.first
            val dy = e.second - r.second
            sqrt(dx * dx + dy * dy)
        }

        val rmse     = sqrt(errors.sumOf { it * it } / n)
        val mae      = errors.sumOf { it } / n
        val maxError = errors.max()

        // ── Stability (jitter) ────────────────────────────────────────────────
        val stability = if (n < 2) 0.0 else {
            val deltas = estimated.zipWithNext { a, b ->
                val dx = b.first  - a.first
                val dy = b.second - a.second
                sqrt(dx * dx + dy * dy)
            }
            val mean = deltas.sum() / deltas.size
            sqrt(deltas.sumOf { (it - mean) * (it - mean) } / deltas.size)
        }

        // ── Lag via cross-correlation of x-component ──────────────────────────
        val lag = estimateLag(estimated.map { it.first }, reference.map { it.first })

        return AccuracyMetrics(
            rmse = rmse,
            mae = mae,
            maxError = maxError,
            lag = lag,
            stability = stability,
            sampleCount = n
        )
    }

    /**
     * Compare two filter histories against each other (e.g., CLASSICAL vs. NEURAL).
     * Returns a pair: (metrics_A vs reference, metrics_B vs reference).
     */
    fun compareStrategies(
        strategyA: List<Pair<Double, Double>>,
        strategyB: List<Pair<Double, Double>>,
        reference: List<Pair<Double, Double>>
    ): Pair<AccuracyMetrics, AccuracyMetrics> {
        return compute(strategyA, reference) to compute(strategyB, reference)
    }

    // ── Cross-correlation lag estimator ──────────────────────────────────────

    /**
     * Find the lag (in samples) at which the cross-correlation of [signal] and
     * [reference] is maximised.  Searches lags in [−maxLag, +maxLag].
     *
     * Returns a positive value when the signal lags behind the reference.
     */
    private fun estimateLag(
        signal: List<Double>,
        reference: List<Double>,
        maxLag: Int = minOf(signal.size / 4, 20)
    ): Double {
        if (signal.size < 4 || maxLag < 1) return 0.0

        val n = signal.size
        val sMean = signal.average()
        val rMean = reference.average()
        val sDemean = signal.map { it - sMean }
        val rDemean = reference.map { it - rMean }

        var bestLag = 0
        var bestCorr = Double.NEGATIVE_INFINITY

        for (lag in -maxLag..maxLag) {
            var corr = 0.0
            var count = 0
            for (i in 0 until n) {
                val j = i + lag
                if (j < 0 || j >= n) continue
                corr += sDemean[i] * rDemean[j]
                count++
            }
            if (count > 0 && corr / count > bestCorr) {
                bestCorr = corr / count
                bestLag = lag
            }
        }
        return bestLag.toDouble()
    }
}