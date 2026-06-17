package com.katka.engine.model

import com.katka.engine.MatrixOps

/** Which strategy produced the Kalman gain. */
enum class FilterMode {
    CLASSICAL,
    NEURAL
}

/** Output of a CoefficientStrategy: gain K, posterior covariance and the noise matrix R used this step. */
data class GainResult(
    val K: Array<DoubleArray>,
    val P_updated: Array<DoubleArray>,
    val R: Array<DoubleArray>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GainResult) return false
        return K.contentDeepEquals(other.K) &&
                P_updated.contentDeepEquals(other.P_updated) &&
                R.contentDeepEquals(other.R)
    }

    override fun hashCode(): Int {
        var result = K.contentDeepHashCode()
        result = 31 * result + P_updated.contentDeepHashCode()
        result = 31 * result + R.contentDeepHashCode()
        return result
    }
}

/** Complete result of one filter step: posterior and prior state plus diagnostics. */
data class FilterResult(
    val timestamp: Long,
    val state: KalmanState,
    val predicted: KalmanState,
    val innovation: DoubleArray,
    val innovationCovS: Array<DoubleArray>,
    val kalmanGain: Array<DoubleArray>,
    val measurementNoiseR: Array<DoubleArray>,
    val filterMode: FilterMode,
    val dt: Double
) {
    /** Normalised Innovation Squared (yᵀ·S⁻¹·y) — a chi² filter-consistency statistic. */
    val nis: Double by lazy {
        try {
            val SInv = MatrixOps.inverse(innovationCovS)
            val SInvY = MatrixOps.mulVec(SInv, innovation)
            innovation.indices.sumOf { i -> innovation[i] * SInvY[i] }
        } catch (_: Exception) {
            Double.NaN
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FilterResult) return false
        return timestamp == other.timestamp && state == other.state
    }

    override fun hashCode(): Int = timestamp.hashCode() * 31 + state.hashCode()
}
