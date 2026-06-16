package com.katka.engine.neural

/**
 * One (features, labels) training pair consumed by [NeuralNetworkTrainer].
 *
 * For the trajectory smoother (diploma ch.3) `features` is the normalised
 * 6-feature context vector and `labels` is the single analytic optimal weight
 * α* ∈ [0,1].
 */
data class TrainingSample(
    val features: DoubleArray,
    val labels: DoubleArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrainingSample) return false
        return features.contentEquals(other.features) && labels.contentEquals(other.labels)
    }

    override fun hashCode(): Int = 31 * features.contentHashCode() + labels.contentHashCode()
}
