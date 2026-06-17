package com.katka.engine.smoothing

import kotlin.math.sqrt

/** Per-feature standardisation (f - mean) / std, fitted on the training set and persisted with the model. */
class FeatureNormalizer(
    val mean: DoubleArray,
    val std: DoubleArray
) {
    init {
        require(mean.size == std.size) { "FeatureNormalizer: mean/std length mismatch" }
    }

    /** Returns the standardised feature vector; a near-zero std is treated as 1. */
    fun normalize(raw: DoubleArray): DoubleArray =
        DoubleArray(raw.size) { i ->
            val s = if (std[i] > 1e-9) std[i] else 1.0
            (raw[i] - mean[i]) / s
        }

    companion object {

        /** Fits mean and std over the raw feature rows of a dataset. */
        fun fit(rows: List<DoubleArray>): FeatureNormalizer {
            require(rows.isNotEmpty()) { "FeatureNormalizer.fit: no rows" }
            val n = rows.first().size

            val mean = DoubleArray(n)
            for (r in rows) for (i in 0 until n) mean[i] += r[i]
            for (i in 0 until n) mean[i] /= rows.size

            val std = DoubleArray(n)
            for (r in rows) for (i in 0 until n) {
                val d = r[i] - mean[i]; std[i] += d * d
            }
            for (i in 0 until n) std[i] = sqrt(std[i] / rows.size)

            return FeatureNormalizer(mean, std)
        }

        /** Identity transform (mean 0, std 1). */
        fun identity(n: Int = SmootherFeatures.COUNT) =
            FeatureNormalizer(DoubleArray(n) { 0.0 }, DoubleArray(n) { 1.0 })
    }
}
