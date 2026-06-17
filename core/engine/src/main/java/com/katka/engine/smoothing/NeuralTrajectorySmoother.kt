package com.katka.engine.smoothing

import com.katka.engine.neural.NeuralNetwork

/** Inference-time fixed-lag smoother: predicts alpha and blends the Kalman and Savitzky-Golay central points. */
class NeuralTrajectorySmoother(
    private val network: NeuralNetwork,
    private val normalizer: FeatureNormalizer
) {
    private val window = SmootherWindow()

    fun reset() = window.clear()

    /** Pushes one filter step; returns a [SmoothedSample] for the central point once the window is full, else null. */
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
