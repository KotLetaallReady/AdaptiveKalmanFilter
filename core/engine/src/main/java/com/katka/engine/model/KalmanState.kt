package com.katka.engine.model

import com.katka.engine.MatrixOps
import kotlin.collections.get

/**
 * Complete state of the Kalman filter at a single time step.
 *
 * State vector:  x = [x, y, vx, vy]ᵀ
 *   x, y  — position in local metric coordinates (metres relative to a reference point)
 *   vx, vy — velocity (m/s) estimated by the filter
 *
 * P — 4×4 error covariance matrix.
 *     P[i][i] is the variance of state component i.
 *     Off-diagonal entries capture correlations (e.g., position–velocity coupling).
 */
data class KalmanState(
    val x: Double,
    val y: Double,
    val vx: Double,
    val vy: Double,
    val P: Array<DoubleArray>
) {
    /** Expose the state as a plain vector for matrix math. */
    fun toVector(): DoubleArray = doubleArrayOf(x, y, vx, vy)

    /**
     * 1-σ positional uncertainty derived from the diagonal of P.
     * σ_pos = √(P[0][0] + P[1][1])  — combined horizontal uncertainty, metres.
     */
    val positionUncertaintyMeters: Double
        get() = Math.sqrt(P[0][0] + P[1][1])

    val velocityUncertaintyMs: Double
        get() = Math.sqrt(P[2][2] + P[3][3])

    // ── Companion ────────────────────────────────────────────────────────────

    companion object {

        /** STATE_DIM is fixed at 4: [x, y, vx, vy]. */
        const val DIM = 4

        /**
         * Build an initial state from the very first GPS fix.
         *
         * @param x   initial x-position (metres)
         * @param y   initial y-position (metres)
         * @param posVariance  initial position variance (σ_pos² in m²).
         *            Use accuracy² from the first GPS fix, or a large value (e.g. 100)
         *            when the fix quality is unknown.
         * @param velVariance  initial velocity variance (σ_v² in (m/s)²).
         *            Typically set to a moderate value (e.g. 10) since we don't
         *            know the starting speed.
         */
        fun initial(
            x: Double,
            y: Double,
            posVariance: Double = 100.0,
            velVariance: Double = 10.0
        ): KalmanState {
            val P = MatrixOps.zeros(DIM, DIM)
            P[0][0] = posVariance
            P[1][1] = posVariance
            P[2][2] = velVariance
            P[3][3] = velVariance
            return KalmanState(x = x, y = y, vx = 0.0, vy = 0.0, P = P)
        }
    }

    // ── equals / hashCode (Array fields need custom impl) ───────────────────

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KalmanState) return false
        return x == other.x && y == other.y && vx == other.vx && vy == other.vy &&
                P.contentDeepEquals(other.P)
    }

    override fun hashCode(): Int {
        var result = x.hashCode()
        result = 31 * result + y.hashCode()
        result = 31 * result + vx.hashCode()
        result = 31 * result + vy.hashCode()
        result = 31 * result + P.contentDeepHashCode()
        return result
    }
}