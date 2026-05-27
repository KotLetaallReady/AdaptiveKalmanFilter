package com.katka.engine.coefficient_startegy

import android.util.Log
import com.katka.engine.MatrixOps
import com.katka.engine.model.GainResult
import com.katka.model.Observation
import kotlin.math.ln

/**
 * Classical Kalman gain computation following the Riccati equations.
 *
 * ── What this class is responsible for ──────────────────────────────────────
 *
 *  1. Computing the measurement noise covariance **R** from GPS accuracy.
 *  2. Computing the Kalman gain **K** = P_pred·Hᵀ·S⁻¹.
 *  3. Updating the error covariance **P** with the Joseph stabilised form.
 *
 * ── How R is computed mathematically ────────────────────────────────────────
 *
 *  Android's `Location.getAccuracy()` returns the horizontal accuracy radius
 *  r (metres) at 68 % confidence level, which corresponds to 1σ for a
 *  circularly symmetric 2-D Gaussian:
 *
 *    σ_gps = r   (1-σ in metres)
 *    R = diag(σ_gps², σ_gps²)
 *
 *  This is the *GPS chipset's own uncertainty estimate*, already fused from
 *  satellite geometry (HDOP), signal quality, and multipath corrections.
 *
 *  ── Accuracy clamping ─────────────────────────────────────────────────────
 *  Raw accuracy values can spike (indoors: > 50 m) or drop unrealistically
 *  low (differential GPS: < 1 m). We clamp to [minAccuracy, maxAccuracy].
 *
 *  ── Adaptive R via Sage-Husa estimator (optional) ─────────────────────────
 *  When [adaptiveR] = true, R is blended with an online estimate derived from
 *  the innovation sequence, allowing the filter to self-calibrate when GPS
 *  accuracy is systematically mis-reported (e.g., tunnels, urban canyons).
 *
 *  The Sage-Husa recursive formula:
 *
 *    R̂_k = (1 − d_k)·R̂_{k-1} + d_k·(y_k·y_kᵀ − H·P_pred·Hᵀ)
 *    d_k  = (1 − b) / (1 − b^{k+1})      b ∈ (0, 1), forgetting factor
 *
 *  where y_k is the innovation delivered back via [updateInnovation].
 *
 * @param minAccuracyM   Minimum GPS accuracy to accept (metres). Readings below
 *                       this are clamped up (default 1.0).
 * @param maxAccuracyM   Maximum GPS accuracy to accept (metres). Readings above
 *                       this are clamped down; the fix is still used but trusted
 *                       less (default 50.0).
 * @param adaptiveR      Enable Sage-Husa online R estimation (default false).
 * @param adaptiveWindow Sliding window size for innovation history (default 20).
 * @param forgettingB    Forgetting factor b in [0.9, 1.0) for Sage-Husa (default 0.97).
 */
class ClassicalCoefficientStrategy(
    private val minAccuracyM: Float = 1.0f,
    private val maxAccuracyM: Float = 50.0f,
    private val adaptiveR: Boolean = false,
    private val adaptiveWindow: Int = 20,
    private val forgettingB: Double = 0.97
) : CoefficientStrategy {

    // ── Adaptive-R state ─────────────────────────────────────────────────────

    /** Circular buffer of recent innovations y_k (each is a 2-vector). */
    private val innovationWindow = ArrayDeque<DoubleArray>(adaptiveWindow)

    /** Running R̂ estimate (Sage-Husa); null until we have enough innovations. */
    private var adaptiveREstimate: Array<DoubleArray>? = null

    /** Step counter (used for Sage-Husa weight d_k). */
    private var stepCount = 0

    /**
     * Feed back the innovation y_k from the filter so the adaptive estimator
     * can update R̂.  Must be called after each [computeGain] call if [adaptiveR]
     * is enabled — the filter coordinator is responsible for this.
     *
     * @param innovation   y = z − H·x̂_pred  (measurement-space residual)
     * @param H            Measurement matrix (m × n)
     * @param P_pred       Predicted covariance used in that step (n × n)
     */
    fun updateInnovation(
        innovation: DoubleArray,
        H: Array<DoubleArray>,
        P_pred: Array<DoubleArray>
    ) {
        if (!adaptiveR) return

        if (innovationWindow.size >= adaptiveWindow) {
            innovationWindow.removeFirst()
            hphtWindow.removeFirst()   // ← новый буфер
        }
        innovationWindow.addLast(innovation)

        // Запоминаем HPHt этого шага для усреднения по окну
        val HP   = MatrixOps.mul(H, P_pred)
        val HPHt = MatrixOps.mul(HP, MatrixOps.transpose(H))
        hphtWindow.addLast(HPHt)      // ← новый буфер

        stepCount++
        if (innovationWindow.size < 3) return

        val m = innovation.size

        // S_yy = (1/N)·Σ(y_k · y_kᵀ)
        val Syy = MatrixOps.zeros(m, m)
        for (y in innovationWindow) {
            val yyT = MatrixOps.outerProduct(y, y)
            for (i in 0 until m) for (j in 0 until m) Syy[i][j] += yyT[i][j]
        }
        for (i in 0 until m) for (j in 0 until m) Syy[i][j] /= innovationWindow.size

        // Среднее H·P·Hᵀ по окну вместо текущего одного шага
        val HPHt_mean = MatrixOps.zeros(m, m)
        for (hpht in hphtWindow) {
            for (i in 0 until m) for (j in 0 until m) HPHt_mean[i][j] += hpht[i][j]
        }
        for (i in 0 until m) for (j in 0 until m) HPHt_mean[i][j] /= hphtWindow.size

        // R̂_raw = S_yy − mean(H·P·Hᵀ)
        val Rraw = MatrixOps.sub(Syy, HPHt_mean)

        // Clamp диагонали — минимум (minAccuracyM)² чтобы не уйти ниже физического предела
        val minVariance = (minAccuracyM * minAccuracyM).toDouble()
        val Rclamped = MatrixOps.copy(Rraw)
        for (i in 0 until m) {
            // Диагональ не ниже минимальной дисперсии GPS
            Rclamped[i][i] = Rclamped[i][i].coerceAtLeast(minVariance)
        }
        // Off-diagonal — симметризуем и не даём выйти за пределы диагоналей
        for (i in 0 until m) for (j in 0 until m) {
            if (i != j) {
                val sym = (Rclamped[i][j] + Rclamped[j][i]) / 2.0
                val maxOffDiag = 0.99 * kotlin.math.sqrt(Rclamped[i][i] * Rclamped[j][j])
                Rclamped[i][j] = sym.coerceIn(-maxOffDiag, maxOffDiag)
                Rclamped[j][i] = Rclamped[i][j]
            }
        }

        // Sage-Husa blend
        val dk = (1.0 - forgettingB) / (1.0 - Math.pow(forgettingB, stepCount.toDouble() + 1))
        val prev = adaptiveREstimate ?: Rclamped
        val blended = MatrixOps.zeros(m, m)
        for (i in 0 until m) for (j in 0 until m) {
            blended[i][j] = (1.0 - dk) * prev[i][j] + dk * Rclamped[i][j]
        }
        adaptiveREstimate = blended
    }

    // ── CoefficientStrategy implementation ──────────────────────────────────

    override fun computeGain(
        P_pred: Array<DoubleArray>,
        H: Array<DoubleArray>,
        obs: Observation
    ): GainResult {
        // 1. Compute R ─────────────────────────────────────────────────────
        val R = computeR(obs, H, P_pred)

        // 2. Innovation covariance  S = H·P_pred·Hᵀ + R ───────────────────
        val HP   = MatrixOps.mul(H, P_pred)
        val HPHt = MatrixOps.mul(HP, MatrixOps.transpose(H))
        val S    = MatrixOps.add(HPHt, R)

        // Add tiny epsilon for numerical safety before inverting S
        val sScale = (S[0][0] + S[1][1]) / 2.0
        val Sreg = MatrixOps.addDiagEps(S, eps = sScale * 1e-6 + 1e-6)

        // 3. Kalman gain  K = P_pred·Hᵀ·S⁻¹ ──────────────────────────────
        val PHt  = MatrixOps.mul(P_pred, MatrixOps.transpose(H))
        val SInv = MatrixOps.inverse(Sreg)
        val K    = MatrixOps.mul(PHt, SInv)
        val kDiagSum = K.indices.sumOf { i -> K.getOrNull(i)?.getOrNull(i) ?: 0.0 }
        if (kDiagSum.isNaN() || kDiagSum.isInfinite()) {
            Log.w("ClassicalStrategy", "⚠ K вырожден на шаге, возвращаем P_pred без обновления")
            return GainResult(
                K         = MatrixOps.zeros(P_pred.size, H.size),
                P_updated = P_pred,
                R         = R  // ← тот же R
            )
        }
        // 4. Posterior covariance — Joseph stabilised form ─────────────────
        //
        //   P = (I − K·H)·P_pred·(I − K·H)ᵀ + K·R·Kᵀ
        //
        //   This is mathematically equivalent to the standard form P = (I−KH)·P_pred
        //   but remains symmetric and positive-semi-definite even when K is slightly
        //   off due to floating-point rounding — crucial for long tracking sessions.
        val n        = P_pred.size
        val I        = MatrixOps.identity(n)
        val KH       = MatrixOps.mul(K, H)
        val IminusKH = MatrixOps.sub(I, KH)

        val left  = MatrixOps.mul(MatrixOps.mul(IminusKH, P_pred), MatrixOps.transpose(IminusKH))
        val KRKt  = MatrixOps.mul(MatrixOps.mul(K, R), MatrixOps.transpose(K))
        val PJoseph = MatrixOps.symmetrise(MatrixOps.add(left, KRKt))

        return GainResult(K = K, P_updated = PJoseph, R = R)
    }

    private val hphtWindow = ArrayDeque<Array<DoubleArray>>(adaptiveWindow)

    override fun reset() {
        innovationWindow.clear()
        hphtWindow.clear()
        adaptiveREstimate = null
        stepCount = 0
    }

    // ── R computation ────────────────────────────────────────────────────────

    /**
     * Build the 2×2 measurement noise covariance matrix R.
     *
     * Base case (GPS-derived, always present):
     *   σ = clamp(accuracy, minAccuracyM, maxAccuracyM)
     *   R_base = diag(σ², σ²)
     *
     * If adaptive estimation is running and has enough data, blend:
     *   R = α·R_base + (1−α)·R̂_adaptive
     *   α = accuracy_weight(accuracy)  — how much we trust the chipset's claim
     */
    private fun computeR(
        obs: Observation,
        H: Array<DoubleArray>,
        P_pred: Array<DoubleArray>
    ): Array<DoubleArray> {
        val clampedAccuracy = obs.accuracy.coerceIn(minAccuracyM, maxAccuracyM).toDouble()
        val variance = clampedAccuracy * clampedAccuracy
        val Rbase = MatrixOps.diagonal(doubleArrayOf(variance, variance))

        if (!adaptiveR) return Rbase

        val Radaptive = adaptiveREstimate ?: return Rbase

        // Alpha: weight for the chipset estimate.
        // High accuracy (small number) → trust chipset more.
        // Low accuracy (large number) → lean on the adaptive estimate.
        val alpha = chipsetTrustWeight(obs.accuracy)
        val m = Rbase.size
        return Array(m) { i ->
            DoubleArray(m) { j ->
                alpha * Rbase[i][j] + (1.0 - alpha) * Radaptive[i][j]
            }
        }
    }

    /**
     * Map GPS accuracy to a chipset trust weight α ∈ [0.1, 0.95].
     *
     * Logic: very accurate fixes (< 5 m) → α ≈ 0.9 (trust chipset strongly).
     *        Very inaccurate fixes (> 40 m) → α ≈ 0.1 (trust adaptive estimate).
     *
     * Uses a soft linear interpolation in log-space for smooth transition.
     */
    private fun chipsetTrustWeight(accuracy: Float): Double {
        val lo = ln(minAccuracyM.toDouble().coerceAtLeast(0.5))
        val hi = ln(maxAccuracyM.toDouble())
        val v  = ln(accuracy.toDouble().coerceIn(minAccuracyM.toDouble(), maxAccuracyM.toDouble()))
        val t  = ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)   // 0 = best, 1 = worst
        return 0.95 - t * 0.85   // 0.95 → 0.10
    }
}