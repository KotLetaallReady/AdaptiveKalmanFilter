package com.katka.engine.smoothing

/**
 * Data-transfer objects for the neural trajectory smoother (diploma, chapter 3).
 *
 * The smoother is the **second stage** of the pipeline: it runs *after* the
 * classical Kalman filter and post-processes the filtered trajectory with a
 * fixed lag.  Stage 1 (the Kalman filter) is untouched.
 */

/**
 * One filter step fed into the smoother window.
 *
 * Carries everything the smoother needs about a single point of the Kalman
 * trajectory plus the matching raw GPS fix (used to build the pseudo-truth
 * during training, and to draw the comparison overlay).
 *
 * @property timestamp     Unix epoch ms of this step (matches the observation).
 * @property kfX,kfY       Kalman posterior position xₖ|ₖ in local metres (x_KF).
 * @property rawX,rawY     Raw GPS fix in the same local metres.
 * @property vx,vy         Kalman velocity estimate (m/s) — readout only.
 * @property speed         Object speed used for feature f4 (m/s).
 * @property accuracy      GPS horizontal accuracy used for feature f5 (m).
 * @property innovationMag ‖yₖ‖ innovation magnitude used for features f1,f2 (m).
 * @property sigmaPos      Positional 1-σ uncertainty — readout only (m).
 */
data class SmootherInput(
    val timestamp: Long,
    val kfX: Double,
    val kfY: Double,
    val rawX: Double,
    val rawY: Double,
    val vx: Double,
    val vy: Double,
    val speed: Double,
    val accuracy: Double,
    val innovationMag: Double,
    val sigmaPos: Double
)

/**
 * Smoother output for the central point of a full window (lag = [SmootherFeatures.HALF]).
 *
 * @property timestamp     Timestamp of the *central* step the sample refers to.
 * @property kfX,kfY       Central Kalman estimate x_KF.
 * @property sgX,sgY       Savitzky–Golay estimate x_SG (quadratic fit over the
 *                         window of Kalman points, evaluated at the centre).
 * @property outX,outY     Smoothed output x_out = (1−α)·x_KF + α·x_SG.
 * @property alpha         Trust weight α ∈ [0,1] predicted by the network.
 * @property rawX,rawY     Central raw GPS fix (for the comparison overlay / metrics).
 * @property vx,vy         Central Kalman velocity (readout).
 * @property speed         Central object speed (m/s, readout / CSV).
 * @property innovationMag Central innovation magnitude (readout).
 * @property sigmaPos      Central positional uncertainty (readout).
 * @property accuracy      Central GPS accuracy (readout).
 */
data class SmoothedSample(
    val timestamp: Long,
    val kfX: Double,
    val kfY: Double,
    val sgX: Double,
    val sgY: Double,
    val outX: Double,
    val outY: Double,
    val alpha: Double,
    val rawX: Double,
    val rawY: Double,
    val vx: Double,
    val vy: Double,
    val speed: Double,
    val innovationMag: Double,
    val sigmaPos: Double,
    val accuracy: Double
)
