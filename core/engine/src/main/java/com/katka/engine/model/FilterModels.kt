package com.katka.engine.model

import com.katka.engine.MatrixOps
import com.katka.engine.model.KalmanState

// ── FilterMode ───────────────────────────────────────────────────────────────

enum class FilterMode {
    /** Kalman gain K computed via the classical Riccati equations. */
    CLASSICAL,
    /** Kalman gain K produced by a neural network (future). */
    NEURAL
}

// ── GainResult ───────────────────────────────────────────────────────────────

/**
 * Output of a [core.engine.CoefficientStrategy].
 *
 * @property K          Kalman gain matrix  (n × m), n = state dim, m = measurement dim.
 * @property P_updated  Posterior error covariance (n × n) after Joseph-form update.
 * @property R          Measurement noise covariance (m × m) used in this step.
 */
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

// ── FilterResult ─────────────────────────────────────────────────────────────

/**
 * Complete output produced by [core.engine.KalmanFilter.process] for one time step.
 *
 * In addition to the posterior state estimate, it exposes intermediate quantities
 * useful for debugging, diagnostics, and UI charting.
 *
 * @property timestamp          Unix epoch ms matching the input [com.katka.model.Observation].
 * @property state              Posterior state estimate x̂_k|k.
 * @property predicted          Prior (predicted) state x̂_k|k-1 before measurement update.
 * @property innovation         y = z − H·x̂_k|k-1  (residual vector, measurement-space).
 * @property innovationCovS     S = H·P_pred·Hᵀ + R (innovation covariance, m×m).
 * @property kalmanGain         K used this step (n × m).
 * @property measurementNoiseR  R matrix actually used (m × m).
 * @property filterMode         Which strategy produced K.
 * @property dt                 Time delta since previous step (seconds).
 */
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
    /** Normalised Innovation Squared (NIS) — chi² statistic for filter consistency check. */
    val nis: Double by lazy {
        // NIS = yᵀ · S⁻¹ · y
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

// ── AccuracyMetrics ──────────────────────────────────────────────────────────

/**
 * Summary statistics comparing filter output to ground-truth (or raw GPS).
 *
 * @property rmse       Root-Mean-Square Error (metres) — penalises large deviations.
 * @property mae        Mean Absolute Error (metres) — robust to outliers.
 * @property maxError   Maximum single-step error (metres).
 * @property lag        Estimated lag in metres (cross-correlation peak offset).
 * @property stability  Standard deviation of innovations (metres) — lower = smoother.
 * @property sampleCount Number of samples used to compute these metrics.
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
            rmse = Double.NaN, mae = Double.NaN, maxError = Double.NaN,
            lag = Double.NaN, stability = Double.NaN, sampleCount = 0
        )
    }
}