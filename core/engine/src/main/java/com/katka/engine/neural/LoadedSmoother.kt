package com.katka.engine.neural

/** A restored smoother model: the trained network plus the feature normalisation (mean/std) it was trained with. */
data class LoadedSmoother(
    val network: NeuralNetwork,
    val featureMean: DoubleArray,
    val featureStd: DoubleArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LoadedSmoother) return false
        return network === other.network &&
                featureMean.contentEquals(other.featureMean) &&
                featureStd.contentEquals(other.featureStd)
    }

    override fun hashCode(): Int {
        var r = network.hashCode()
        r = 31 * r + featureMean.contentHashCode()
        r = 31 * r + featureStd.contentHashCode()
        return r
    }
}
