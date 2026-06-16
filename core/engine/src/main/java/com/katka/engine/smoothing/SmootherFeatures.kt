package com.katka.engine.smoothing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The six aggregated context features the smoother network consumes
 * (diploma §3.2).
 *
 * The network does **not** see raw point coordinates — only these statistics
 * describing the motion context of the window.  This keeps the input
 * low-dimensional (6) so a tiny MLP can learn from a single user's track.
 *
 *   f1 — mean innovation magnitude over the window
 *   f2 — std  of innovation magnitude
 *   f3 — total turn angle of the Kalman track, Σ|Δβ| (degrees)
 *   f4 — mean speed over the window
 *   f5 — mean GPS accuracy over the window
 *   f6 — std of Kalman step lengths sᵢ = √(Δx² + Δy²)
 *
 * The raw vector is later standardised by [FeatureNormalizer].
 */
object SmootherFeatures {

    /** Window length L (number of consecutive filter points). */
    const val L = 11

    /** Central index (0-based) — the fixed lag of the smoother. */
    const val HALF = 5

    /** Number of features. */
    const val COUNT = 6

    /** Only turns steeper than this contribute to the suppression angle φ (deg). */
    private const val TURN_THRESHOLD_DEG = 30.0

    /** Extract the raw (un-normalised) 6-feature vector from a full window. */
    fun extract(window: List<SmootherInput>): DoubleArray {
        val innov = window.map { it.innovationMag }
        val f1 = innov.average()
        val f2 = std(innov, f1)

        val f3 = turnAnglesDeg(window).sum()

        val f4 = window.map { it.speed }.average()
        val f5 = window.map { it.accuracy }.average()

        val steps = stepLengths(window)
        val f6 = if (steps.isEmpty()) 0.0 else std(steps, steps.average())

        return doubleArrayOf(f1, f2, f3, f4, f5, f6)
    }

    /** φ — total turn angle counting only segments steeper than 30° (degrees). */
    fun suppressionAngleDeg(window: List<SmootherInput>): Double =
        turnAnglesDeg(window).filter { it > TURN_THRESHOLD_DEG }.sum()

    // ── Internals ─────────────────────────────────────────────────────────────

    /** |Δβ| (degrees) between consecutive Kalman-track segments. */
    private fun turnAnglesDeg(window: List<SmootherInput>): List<Double> {
        if (window.size < 3) return emptyList()

        val bearings = ArrayList<Double>(window.size - 1)
        for (i in 0 until window.size - 1) {
            val dx = window[i + 1].kfX - window[i].kfX
            val dy = window[i + 1].kfY - window[i].kfY
            bearings.add(atan2(dy, dx))
        }

        val turns = ArrayList<Double>(bearings.size - 1)
        for (i in 1 until bearings.size) {
            var d = bearings[i] - bearings[i - 1]
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            turns.add(abs(Math.toDegrees(d)))
        }
        return turns
    }

    private fun stepLengths(window: List<SmootherInput>): List<Double> {
        if (window.size < 2) return emptyList()
        val steps = ArrayList<Double>(window.size - 1)
        for (i in 0 until window.size - 1) {
            val dx = window[i + 1].kfX - window[i].kfX
            val dy = window[i + 1].kfY - window[i].kfY
            steps.add(hypot(dx, dy))
        }
        return steps
    }

    private fun std(xs: List<Double>, mean: Double): Double {
        if (xs.size < 2) return 0.0
        val v = xs.sumOf { (it - mean) * (it - mean) } / xs.size
        return sqrt(v)
    }
}
