package com.katka.engine.coefficient_startegy

import com.katka.engine.model.GainResult
import com.katka.model.Observation

/** Strategy that produces the Kalman gain K, the updated covariance P and the noise matrix R for one update step. */
interface CoefficientStrategy {

    /** Returns K, the posterior covariance and R for the predicted covariance, measurement matrix and observation. */
    fun computeGain(
        P_pred: Array<DoubleArray>,
        H: Array<DoubleArray>,
        obs: Observation
    ): GainResult

    /** Flushes any internal state at the start of a session. */
    fun reset() {}
}
