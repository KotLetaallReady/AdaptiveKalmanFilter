package com.katka.engine.coefficient_startegy

import com.katka.engine.MatrixOps
import com.katka.engine.model.GainResult
import com.katka.engine.neural.NeuralNetwork
import com.katka.engine.neural.TrainingDataCollector
import com.katka.model.Observation
import kotlin.math.ln

/**
 * [CoefficientStrategy] that replaces the classical Riccati gain computation
 * with a call to a trained [NeuralNetwork].
 *
 * ── How the network is used ──────────────────────────────────────────────────
 *
 *   Input  (24 features) — built from the rolling innovation window, GPS
 *                          accuracy, position uncertainty, dt, and speed.
 *                          See [TrainingDataCollector] for the exact layout.
 *
 *   Output (4 values)    — [K[0,0], K[1,1], K[2,0], K[3,1]].
 *                          The four dominant gain elements; off-diagonal
 *                          cross-terms are set to zero (valid simplification
 *                          for near-symmetric covariances).
 *
 * ── Fallback behaviour ────────────────────────────────────────────────────────
 *
 *   If [network] is null (i.e. the user has not yet trained the model),
 *   the strategy transparently delegates to [ClassicalCoefficientStrategy].
 *   The UI can detect this via [isNetworkReady].
 *
 * ── R computation ─────────────────────────────────────────────────────────────
 *
 *   R is still computed classically from GPS accuracy — the network only
 *   replaces K.  This keeps R physically interpretable and avoids
 *   compounding errors from a jointly inferred R.
 *
 * @param network          Trained neural network (null → fallback to classical).
 * @param minAccuracyM     Minimum GPS accuracy clamp (metres).
 * @param maxAccuracyM     Maximum GPS accuracy clamp (metres).
 * @param maxSpeedMs       Speed normalisation upper bound (m/s).
 */
class NeuralCoefficientStrategy(
    private val network: NeuralNetwork?,
    private val minAccuracyM: Float = 1f,
    private val maxAccuracyM: Float = 50f,
    private val maxSpeedMs: Float = 30f
) : CoefficientStrategy {

    // Fallback for the case when the network is not trained yet
    private val classicalFallback = ClassicalCoefficientStrategy(
        minAccuracyM = minAccuracyM,
        maxAccuracyM = maxAccuracyM
    )

    /** `true` when a trained network is available and will be used. */
    val isNetworkReady: Boolean get() = network != null

    // ── Innovation ring-buffer (mirrors TrainingDataCollector) ────────────────

    private val innovBuf = ArrayDeque<DoubleArray>(INNOV_WINDOW)

    /** Must be called after each filter step so features stay up-to-date. */
    fun updateInnovation(innovation: DoubleArray, dtMs: Double = 0.0) {
        if (innovBuf.size >= INNOV_WINDOW) innovBuf.removeFirst()
        innovBuf.addLast(innovation.copyOf())
        lastDtMs = dtMs
    }

    @Volatile private var lastDtMs: Double = 0.0

    // ── CoefficientStrategy implementation ───────────────────────────────────

    override fun computeGain(
        P_pred: Array<DoubleArray>,
        H: Array<DoubleArray>,
        obs: Observation
    ): GainResult {
        if (network == null || innovBuf.size < INNOV_WINDOW) {
            return classicalFallback.computeGain(P_pred, H, obs)
        }

        val features = buildFeatures(obs, P_pred)

        // Сеть предсказывает α — масштаб R
        val alpha = network.predict(features)[0].coerceIn(0.1, 5.0)

        // R адаптируется, K считается классически — физически корректно
        val clampedAcc = obs.accuracy.coerceIn(minAccuracyM, maxAccuracyM).toDouble()
        val baseVariance = clampedAcc * clampedAcc
        val R = MatrixOps.diagonal(doubleArrayOf(
            alpha * baseVariance,
            alpha * baseVariance
        ))

        // Дальше — стандартный Riccati с новым R
        val HP   = MatrixOps.mul(H, P_pred)
        val HPHt = MatrixOps.mul(HP, MatrixOps.transpose(H))
        val S    = MatrixOps.add(HPHt, R)
        val Sreg = MatrixOps.addDiagEps(S, eps = 1e-9)
        val PHt  = MatrixOps.mul(P_pred, MatrixOps.transpose(H))
        val SInv = MatrixOps.inverse(Sreg)
        val K    = MatrixOps.mul(PHt, SInv)

        // Joseph form
        val n = P_pred.size
        val I = MatrixOps.identity(n)
        val IminusKH = MatrixOps.sub(I, MatrixOps.mul(K, H))
        val left = MatrixOps.mul(MatrixOps.mul(IminusKH, P_pred), MatrixOps.transpose(IminusKH))
        val KRKt = MatrixOps.mul(MatrixOps.mul(K, R), MatrixOps.transpose(K))
        val P_updated = MatrixOps.symmetrise(MatrixOps.add(left, KRKt))

        return GainResult(K = K, P_updated = P_updated, R = R)
    }

    override fun reset() {
        innovBuf.clear()
        classicalFallback.reset()
    }

    // ── Feature construction (must match TrainingDataCollector exactly) ───────

    private fun buildFeatures(obs: Observation, P_pred: Array<DoubleArray>): DoubleArray {
        val vec = DoubleArray(TrainingDataCollector.FEATURE_SIZE)
        var idx = 0

        // Innovation history — pad at the front with zeros if buffer is short
        val padded = List(INNOV_WINDOW) { i -> innovBuf.getOrNull(i) ?: ZERO_INNOV }
        for (innov in padded) {
            vec[idx++] = innov[0].coerceIn(-20.0, 20.0) / 20.0
            vec[idx++] = innov[1].coerceIn(-20.0, 20.0) / 20.0
        }

        // GPS accuracy (log-normalised)
        vec[idx++] = (
                ln(obs.accuracy.toDouble().coerceAtLeast(0.5)) /
                        ln(maxAccuracyM.toDouble().coerceAtLeast(1.0))
                ).coerceIn(0.0, 1.0)

        // Position uncertainty from predicted P diagonal
        val sigmaPos = kotlin.math.sqrt(
            (P_pred.getOrNull(0)?.getOrNull(0) ?: 0.0).coerceAtLeast(0.0)
        )
        vec[idx++] = (sigmaPos / MAX_UNCERTAINTY_M).coerceIn(0.0, 1.0)

        // dt placeholder — not available here; kept at 0.0 (coordinator sets it via updateInnovation)
        vec[idx++] = (lastDtMs / MAX_DT_MS).coerceIn(0.0, 1.0)

        // Speed
        vec[idx++] = (obs.speed / maxSpeedMs).coerceIn(0.0f, 1.0f).toDouble()

        return vec
    }

    companion object {
        private const val INNOV_WINDOW = TrainingDataCollector.INNOVATION_WINDOW
        private const val MAX_UNCERTAINTY_M = 100.0
        private const val MAX_DT_MS = 5_000.0
        private val ZERO_INNOV = doubleArrayOf(0.0, 0.0)
    }
}