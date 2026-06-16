package com.katka.engine.smoothing

import com.katka.engine.neural.TrainingSample

/** Result of [SmoothingTrainingCollector.buildDataset]: the fitted normaliser plus normalised samples. */
data class SmoothingDataset(
    val normalizer: FeatureNormalizer,
    val samples: List<TrainingSample>
)

/**
 * Accumulates (features → optimal α*) training pairs from a **classical**
 * filter session (diploma §3.1–3.2).
 *
 * Replaces the old `TrainingDataCollector` (which produced the wrong target —
 * a Kalman-gain / R-scaling label).  Here the target is the analytically
 * optimal trust weight α* between the Kalman point and its Savitzky–Golay
 * approximation.
 *
 * Usage:
 * ```
 * collector.addStep(input)        // for every filter step of the session
 * ...
 * val ds = collector.buildDataset()      // fits μ/σ and returns normalised samples
 * trainer.train(ds.samples, epochs)      // train the MLP
 * persistence.saveSmoother(ctx, net, ds.normalizer)
 * ```
 *
 * Features are stored **un-normalised** during collection; μ/σ can only be
 * computed once the whole window set is known, so [buildDataset] fits the
 * [FeatureNormalizer] at the end and normalises every sample with it.
 */
class SmoothingTrainingCollector {

    private val window = SmootherWindow()
    private val rawFeatureRows = mutableListOf<DoubleArray>()
    private val alphaStars = mutableListOf<Double>()

    /** Number of (features, α*) pairs gathered so far. */
    val sampleCount: Int get() = rawFeatureRows.size

    /** Feed one completed classical-filter step. Produces a pair once the window is full. */
    fun addStep(input: SmootherInput) {
        window.push(input)
        if (!window.isFull) return

        val centre = window.centralInput()
        val xKf = doubleArrayOf(centre.kfX, centre.kfY)
        val xSg = window.sgKf()
        val xStar = window.sgRaw()
        val phi = window.turnSuppressionPhiDeg()

        rawFeatureRows.add(window.rawFeatures())
        alphaStars.add(OptimalAlpha.solve(xKf, xSg, xStar, phi))
    }

    fun reset() {
        window.clear()
        rawFeatureRows.clear()
        alphaStars.clear()
    }

    /** Fit the normaliser over all collected rows and emit normalised training samples. */
    fun buildDataset(): SmoothingDataset {
        require(rawFeatureRows.isNotEmpty()) { "buildDataset: no samples collected" }
        val normalizer = FeatureNormalizer.fit(rawFeatureRows)
        val samples = rawFeatureRows.indices.map { i ->
            TrainingSample(
                features = normalizer.normalize(rawFeatureRows[i]),
                labels = doubleArrayOf(alphaStars[i])
            )
        }
        return SmoothingDataset(normalizer, samples)
    }
}
