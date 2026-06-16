package com.katka.engine.smoothing

/**
 * Sliding window of the last [SmootherFeatures.L] filter points.
 *
 * This is the single shared piece of state used by **both** the training
 * collector and the inference smoother, so the feature/Savitzky–Golay
 * computation is defined exactly once (no drift between train and inference).
 *
 * The central point sits at index [SmootherFeatures.HALF]; once the window is
 * full, every newly pushed point produces one new central estimate, i.e. a
 * fixed-lag smoother with lag = HALF.
 */
class SmootherWindow(private val length: Int = SmootherFeatures.L) {

    private val buf = ArrayDeque<SmootherInput>(length)

    val isFull: Boolean get() = buf.size >= length

    fun push(input: SmootherInput) {
        if (buf.size >= length) buf.removeFirst()
        buf.addLast(input)
    }

    fun clear() = buf.clear()

    /** The central element of a full window (the point being smoothed). */
    fun centralInput(): SmootherInput = buf[(length - 1) / 2]

    /** Savitzky–Golay estimate over the Kalman points (x_SG). */
    fun sgKf(): DoubleArray = SavitzkyGolaySmoother.smoothCentre2D(kfPoints())

    /** Savitzky–Golay estimate over the raw GPS points (pseudo-truth x*). */
    fun sgRaw(): DoubleArray = SavitzkyGolaySmoother.smoothCentre2D(rawPoints())

    /** Raw (un-normalised) 6-feature vector for the current window. */
    fun rawFeatures(): DoubleArray = SmootherFeatures.extract(buf)

    /** φ for the turn-suppression term of the optimal α (degrees). */
    fun turnSuppressionPhiDeg(): Double = SmootherFeatures.suppressionAngleDeg(buf)

    private fun kfPoints(): List<DoubleArray> = buf.map { doubleArrayOf(it.kfX, it.kfY) }
    private fun rawPoints(): List<DoubleArray> = buf.map { doubleArrayOf(it.rawX, it.rawY) }
}
