package com.katka.engine.smoothing

import com.katka.engine.neural.NeuralNetwork

/**
 * Inference-time fixed-lag trajectory smoother (diploma §3.1).
 *
 * Second stage of the pipeline: fed one Kalman filter step at a time, it
 * maintains a window of [SmootherFeatures.L] points and, once full, emits a
 * smoothed estimate of the **central** point (lag = [SmootherFeatures.HALF]):
 *
 *     features  = SmootherFeatures.extract(window)      (6 raw stats)
 *     f̂         = normalizer.normalize(features)
 *     α         = network.predict(f̂)                    (sigmoid output ∈ (0,1))
 *     x_SG      = SavitzkyGolay(window of Kalman points)
 *     x_out     = (1−α)·x_KF + α·x_SG
 *
 * The classical Kalman filter is unchanged; this only post-processes its output.
 *
 * @param network    Trained α-predictor (6→8→4→1, sigmoid output).
 * @param normalizer μ/σ fitted on the training set (must match [network]).
 */
class NeuralTrajectorySmoother(
    private val network: NeuralNetwork,
    private val normalizer: FeatureNormalizer
) {
    private val window = SmootherWindow()

    fun reset() = window.clear()

    /**
     * Push one filter step.
     * @return a [SmoothedSample] for the central point once the window is full, else `null`.
     */
    fun push(input: SmootherInput): SmoothedSample? {
        window.push(input)
        if (!window.isFull) return null

        val centre = window.centralInput()
        val xKf = doubleArrayOf(centre.kfX, centre.kfY)
        val xSg = window.sgKf()

        val features = normalizer.normalize(window.rawFeatures())
        val alpha = network.predict(features)[0].coerceIn(0.0, 1.0)

        val outX = (1.0 - alpha) * xKf[0] + alpha * xSg[0]
        val outY = (1.0 - alpha) * xKf[1] + alpha * xSg[1]

        return SmoothedSample(
            timestamp = centre.timestamp,
            kfX = centre.kfX, kfY = centre.kfY,
            sgX = xSg[0], sgY = xSg[1],
            outX = outX, outY = outY,
            alpha = alpha,
            rawX = centre.rawX, rawY = centre.rawY,
            vx = centre.vx, vy = centre.vy,
            speed = centre.speed,
            innovationMag = centre.innovationMag,
            sigmaPos = centre.sigmaPos,
            accuracy = centre.accuracy
        )
    }
}
