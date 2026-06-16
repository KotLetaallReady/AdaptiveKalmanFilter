package com.katka.engine.smoothing

import kotlin.math.sqrt

/**
 * Per-feature standardisation (diploma §3.2): f̂ᵢ = (fᵢ − μᵢ) / σᵢ.
 *
 * μ and σ are computed once over the whole training set and then frozen, so the
 * exact same transform is applied at inference time.  Therefore the normaliser
 * is persisted **together with the network weights** (see
 * [com.katka.engine.neural.NetworkPersistenceManager]); inference without the
 * matching μ/σ would feed the MLP off-distribution inputs.
 *
 * @property mean μ per feature (length = [SmootherFeatures.COUNT]).
 * @property std  σ per feature (length = [SmootherFeatures.COUNT]).
 */
class FeatureNormalizer(
    val mean: DoubleArray,
    val std: DoubleArray
) {
    init {
        require(mean.size == std.size) { "FeatureNormalizer: mean/std length mismatch" }
    }

    /** Apply (f − μ)/σ; a (near-)zero σ is treated as 1 to avoid blow-ups. */
    fun normalize(raw: DoubleArray): DoubleArray =
        DoubleArray(raw.size) { i ->
            val s = if (std[i] > 1e-9) std[i] else 1.0
            (raw[i] - mean[i]) / s
        }

    companion object {

        /** Fit μ and σ from the raw (un-normalised) feature rows of a dataset. */
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

        /** Identity transform (μ=0, σ=1) — useful as a safe fallback. */
        fun identity(n: Int = SmootherFeatures.COUNT) =
            FeatureNormalizer(DoubleArray(n) { 0.0 }, DoubleArray(n) { 1.0 })
    }
}
