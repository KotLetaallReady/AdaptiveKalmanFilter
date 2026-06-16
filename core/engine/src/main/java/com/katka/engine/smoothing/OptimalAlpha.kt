package com.katka.engine.smoothing

import kotlin.math.sqrt

/**
 * Analytic optimal trust weight α* (diploma §3.2) — the **training label**.
 *
 * For a window's central point we have three positions:
 *   x_KF — the Kalman estimate,
 *   x_SG — the Savitzky–Golay estimate over the Kalman points,
 *   x*   — the pseudo-truth (Savitzky–Golay over the *raw GPS* points).
 *
 * We seek the α that makes the convex blend closest to the pseudo-truth:
 *
 *     α* = argmin ‖(1−α)·x_KF + α·x_SG − x*‖²
 *
 * Letting d = x_SG − x_KF (correction direction) and e = x* − x_KF (offset),
 * the quadratic minimises analytically at
 *
 *     α* = (dᵀe) / ‖d‖²
 *
 * i.e. the scalar projection of e onto d, normalised by ‖d‖.  The result is
 * clipped to [0,1]; when ‖d‖ ≈ 0 (x_KF and x_SG coincide, direction undefined)
 * α* defaults to 0.5.
 *
 * ── Turn suppression ─────────────────────────────────────────────────────────
 * A high α would smooth hard, which cuts corners on sharp turns.  So α* is
 * damped by the total turn angle φ accumulated over the window (only segments
 * with |Δβ| > 30° count):
 *
 *     α* ← α* · max(0.05, 1 − φ/60°)
 */
object OptimalAlpha {

    private const val DEGENERATE_EPS = 1e-9

    /**
     * @param xKf          central Kalman position [x,y]
     * @param xSg          Savitzky–Golay position over Kalman points [x,y]
     * @param xStar        pseudo-truth (Savitzky–Golay over raw GPS) [x,y]
     * @param turnAngleDeg φ — total |Δβ| of segments steeper than 30° (degrees)
     */
    fun solve(
        xKf: DoubleArray,
        xSg: DoubleArray,
        xStar: DoubleArray,
        turnAngleDeg: Double
    ): Double {
        val dx = xSg[0] - xKf[0]
        val dy = xSg[1] - xKf[1]
        val ex = xStar[0] - xKf[0]
        val ey = xStar[1] - xKf[1]

        val dNorm2 = dx * dx + dy * dy
        var alpha = if (dNorm2 < DEGENERATE_EPS) {
            0.5
        } else {
            ((dx * ex + dy * ey) / dNorm2).coerceIn(0.0, 1.0)
        }

        val suppression = (1.0 - turnAngleDeg / 60.0).coerceAtLeast(0.05)
        alpha *= suppression

        return alpha.coerceIn(0.0, 1.0)
    }

    /** Helper: Euclidean norm of a 2-vector (handy for tests/diagnostics). */
    fun norm(v: DoubleArray): Double = sqrt(v[0] * v[0] + v[1] * v[1])
}
