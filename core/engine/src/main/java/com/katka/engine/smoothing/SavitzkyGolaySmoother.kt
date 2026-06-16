package com.katka.engine.smoothing

import kotlin.math.abs

/**
 * Savitzky–Golay smoothing for the **centre** of a symmetric window
 * (diploma §3.1).
 *
 * Instead of averaging the window points with equal weights (moving average),
 * a local polynomial of fixed degree is fitted to all window points by least
 * squares, and the value of that polynomial at the centre is taken as the
 * smoothed estimate.  This preserves the geometry of the trajectory much better
 * than a moving average, because the polynomial adapts to the local shape.
 *
 * Here the degree is **2** (quadratic):  p(τ) = c₀ + c₁·τ + c₂·τ².
 *
 * ── Closed form for the centre value ─────────────────────────────────────────
 *
 * With the window indexed symmetrically τ = i − HALF (i = 0..N−1), all odd
 * moments Στ and Στ³ vanish, so the normal equations decouple and the centre
 * value (p(0) = c₀) is:
 *
 *     x_SG = (S4·Σx − S2·Στ²x) / (N·S4 − S2²)
 *
 * where N = window size, S2 = Στ², S4 = Στ⁴.  This is exact, allocation-free,
 * and needs no matrix inversion.
 */
object SavitzkyGolaySmoother {

    /** Quadratic-fit value at the centre of a symmetric 1-D window. */
    fun smoothCentre(values: DoubleArray): Double {
        val n = values.size
        require(n > 0) { "smoothCentre: empty window" }
        val half = (n - 1) / 2

        var sumX = 0.0
        var sumT2X = 0.0
        var s2 = 0.0
        var s4 = 0.0
        for (i in 0 until n) {
            val t = (i - half).toDouble()
            val t2 = t * t
            sumX += values[i]
            sumT2X += t2 * values[i]
            s2 += t2
            s4 += t2 * t2
        }

        val nN = n.toDouble()
        val denom = nN * s4 - s2 * s2
        // Degenerate (e.g. window of 1) → fall back to the mean.
        if (abs(denom) < 1e-12) return sumX / nN
        return (s4 * sumX - s2 * sumT2X) / denom
    }

    /** Quadratic-fit centre value applied independently to the x and y axes. */
    fun smoothCentre2D(points: List<DoubleArray>): DoubleArray {
        val xs = DoubleArray(points.size) { points[it][0] }
        val ys = DoubleArray(points.size) { points[it][1] }
        return doubleArrayOf(smoothCentre(xs), smoothCentre(ys))
    }
}
