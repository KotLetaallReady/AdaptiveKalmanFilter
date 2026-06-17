package com.katka.engine.smoothing

import com.katka.engine.neural.TrainingSample

/** A fitted normaliser plus the normalised training samples it produced. */
data class SmoothingDataset(
    val normalizer: FeatureNormalizer,
    val samples: List<TrainingSample>
)

/** Accumulates (features → optimal alpha*) training pairs from a classical-filter session. */
class SmoothingTrainingCollector {

    private val window = SmootherWindow()
    private val rawFeatureRows = mutableListOf<DoubleArray>()
    private val alphaStars = mutableListOf<Double>()

    val sampleCount: Int get() = rawFeatureRows.size

    /** Feeds one completed filter step, emitting a training pair once the window is full. */
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

    /** Fits the normaliser over all collected rows and returns the normalised dataset. */
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
