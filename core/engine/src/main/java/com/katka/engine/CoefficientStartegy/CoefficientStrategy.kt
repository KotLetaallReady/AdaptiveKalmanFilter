package com.katka.engine.CoefficientStartegy

import com.katka.engine.model.GainResult
import com.katka.model.Observation

/**
 * Strategy responsible for producing the Kalman gain **K** and the updated
 * error covariance **P** at each measurement-update step.
 *
 * This is the single extension point that allows the Kalman filter to work
 * identically regardless of how K is computed — via the classical Riccati
 * equations or via a neural network.
 *
 * ── Classical contract ───────────────────────────────────────────────────────
 *
 *   Input to implementor:
 *     P_pred  — predicted error covariance   (n × n)
 *     H       — measurement matrix            (m × n)
 *     obs     — current sensor observation (used to compute R)
 *
 *   Required output:
 *     K         — Kalman gain                 (n × m)
 *     P_updated — posterior covariance        (n × n)  after the update step
 *     R         — measurement noise matrix    (m × m)  used in this step
 *
 * ── Neural strategy contract (future) ────────────────────────────────────────
 *
 *   A neural implementation may ignore P_pred and H entirely and infer K from
 *   a sequence of past innovations fed into an LSTM/GRU.  It still must return
 *   P_updated (can be computed with the Joseph form once K is known) and R
 *   (can be a fixed or learned constant).
 *
 * ── Thread safety ────────────────────────────────────────────────────────────
 *
 *   [computeGain] is called from the filter's processing coroutine.
 *   Implementations that maintain internal state (e.g. an innovation window
 *   for adaptive R estimation) are responsible for their own thread safety.
 */
interface CoefficientStrategy {

    /**
     * Compute the Kalman gain and the posterior covariance for one update step.
     *
     * @param P_pred  Predicted error covariance matrix — (n × n).
     * @param H       Measurement matrix — (m × n).
     * @param obs     Current observation (provides accuracy for R computation
     *                and any additional context a neural strategy might need).
     * @return        [GainResult] containing K, P_updated, and R.
     */
    fun computeGain(
        P_pred: Array<DoubleArray>,
        H: Array<DoubleArray>,
        obs: Observation
    ): GainResult

    /**
     * Called by the filter when a session is reset or the strategy should
     * flush internal state (e.g. adaptive-R innovation window).
     * Default implementation is a no-op.
     */
    fun reset() {}
}