package com.katka.engine.neural

import com.katka.engine.model.FilterResult
import com.katka.model.Observation
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Accumulates (feature vector → Kalman-gain label) training pairs
 * from a completed **classical-filter** session.
 *
 * ── Feature vector (size = [FEATURE_SIZE] = 24) ──────────────────────────────
 *
 *  [0..19]  Innovation history — last [INNOVATION_WINDOW] steps, each stored as
 *           (iₓ / 20, i_y / 20) clipped to [−1, 1].  Oldest entry first.
 *           Provides the temporal context the network needs to distinguish
 *           GPS noise from genuine motion.
 *
 *  [20]     log(accuracy) / log(maxAccuracy) — log-normalised to [0, 1].
 *           High accuracy ≈ 0 (good fix); low accuracy ≈ 1 (degraded fix).
 *
 *  [21]     positionUncertainty / maxUncertainty — normalised to [0, 1].
 *           Encodes how confident the filter currently is.
 *
 *  [22]     dt / maxDt — time since last GPS fix, normalised to [0, 1].
 *           Large dt means the prediction step accumulated more uncertainty.
 *
 *  [23]     speed / maxSpeed — GPS-reported speed, normalised to [0, 1].
 *
 * ── Label vector (size = [LABEL_SIZE] = 4) ────────────────────────────────────
 *
 *  The four dominant entries of the classical Kalman gain K (4 × 2):
 *    [0] K[0,0]  x-measurement → x-position gain
 *    [1] K[1,1]  y-measurement → y-position gain
 *    [2] K[2,0]  x-measurement → vx gain
 *    [3] K[3,1]  y-measurement → vy gain
 *
 *  All clamped to [0, 1] (position gains are naturally bounded;
 *  velocity gains may be small but stay within range in practice).
 *
 * @param innovationWindowSize  How many past innovations to include.
 * @param maxAccuracyM          Upper bound for GPS accuracy normalisation.
 * @param maxUncertaintyM       Upper bound for position-uncertainty normalisation.
 * @param maxDtMs               Upper bound for dt normalisation (milliseconds).
 * @param maxSpeedMs            Upper bound for speed normalisation (m/s).
 */
class TrainingDataCollector(
    private val innovationWindowSize: Int  = INNOVATION_WINDOW,
    private val maxAccuracyM:         Float  = 50f,
    private val maxUncertaintyM:      Double = 100.0,
    private val maxDtMs:              Double = 5_000.0,
    private val maxSpeedMs:           Float  = 30f
) {
    companion object {
        const val INNOVATION_WINDOW = 10
        /** Total number of input features consumed by the neural network. */
        const val FEATURE_SIZE = INNOVATION_WINDOW * 2 + 4   // = 24
        /** Number of output labels (K matrix elements). */
        const val LABEL_SIZE = 4
    }

    // ── Internal state ────────────────────────────────────────────────────────

    /** Ring-buffer of the last N innovation vectors. */
    private val innovationBuffer = ArrayDeque<DoubleArray>(innovationWindowSize)

    /** Accumulated training pairs. */
    private val _samples = mutableListOf<TrainingSample>()

    // ── Public API ────────────────────────────────────────────────────────────

    /** All accumulated (feature, label) pairs — snapshot is safe to iterate. */
    val samples: List<TrainingSample> get() = _samples.toList()

    /** How many samples have been collected so far. */
    val sampleCount: Int get() = _samples.size

    /**
     * Feed one completed filter step.  A training pair is created only once the
     * innovation buffer holds [innovationWindowSize] entries (i.e. after the
     * warm-up period).
     *
     * @param obs     Sensor observation for this step.
     * @param result  FilterResult produced by the classical Kalman filter.
     * @param dtMs    Time elapsed since the previous GPS fix (milliseconds).
     */
    fun addStep(obs: Observation, result: FilterResult, dtMs: Double) {
        // 1. Update innovation buffer
        if (innovationBuffer.size >= innovationWindowSize) innovationBuffer.removeFirst()
        innovationBuffer.addLast(result.innovation.copyOf())

        // 2. Wait for a full window before generating samples
        if (innovationBuffer.size < innovationWindowSize) return

        // 3. Build feature vector and label
        val features = buildFeatures(obs, result, dtMs)
        val labels   = extractLabels(result.kalmanGain)

        _samples.add(TrainingSample(features, labels))
    }

    /** Clear both the ring-buffer and the accumulated samples. */
    fun reset() {
        innovationBuffer.clear()
        _samples.clear()
    }

    // ── Feature / label construction ──────────────────────────────────────────

    internal fun buildFeatures(
        obs:    Observation,
        result: FilterResult,
        dtMs:   Double
    ): DoubleArray {
        val vec = DoubleArray(FEATURE_SIZE)
        var idx = 0

        // — Innovation history —
        for (innov in innovationBuffer) {
            vec[idx++] = (innov.getOrElse(0) { 0.0 }).coerceIn(-20.0, 20.0) / 20.0
            vec[idx++] = (innov.getOrElse(1) { 0.0 }).coerceIn(-20.0, 20.0) / 20.0
        }

        // — GPS accuracy (log-space, normalised) —
        val logNormAcc = (
                ln(obs.accuracy.toDouble().coerceAtLeast(0.5)) /
                        ln(maxAccuracyM.toDouble().coerceAtLeast(1.0))
                ).coerceIn(0.0, 1.0)
        vec[idx++] = logNormAcc

        // — Position uncertainty —
        vec[idx++] = (result.state.positionUncertaintyMeters / maxUncertaintyM).coerceIn(0.0, 1.0)

        // — dt —
        vec[idx++] = (dtMs / maxDtMs).coerceIn(0.0, 1.0)

        // — Speed —
        vec[idx++] = (obs.speed / maxSpeedMs).coerceIn(0.0f, 1.0f).toDouble()

        return vec
    }

    private fun extractLabels(K: Array<DoubleArray>): DoubleArray = doubleArrayOf(
        (K.getOrNull(0)?.getOrNull(0) ?: 0.0).coerceIn(0.0, 1.0),  // K[0,0]
        (K.getOrNull(1)?.getOrNull(1) ?: 0.0).coerceIn(0.0, 1.0),  // K[1,1]
        (K.getOrNull(2)?.getOrNull(0) ?: 0.0).coerceIn(0.0, 1.0),  // K[2,0]
        (K.getOrNull(3)?.getOrNull(1) ?: 0.0).coerceIn(0.0, 1.0)   // K[3,1]
    )
}

/** One (features, labels) pair consumed by [NeuralNetworkTrainer]. */
data class TrainingSample(
    val features: DoubleArray,
    val labels:   DoubleArray
)