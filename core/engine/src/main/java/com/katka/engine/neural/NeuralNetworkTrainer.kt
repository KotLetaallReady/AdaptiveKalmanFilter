package com.katka.engine.neural

import kotlin.math.sqrt

/**
 * Trains a [NeuralNetwork] using mini-batch **Adam** (adaptive moment estimation)
 * and **MSE** loss.
 *
 * ── Adam hyper-parameters (defaults follow the original paper) ────────────────
 *
 *   learningRate  α  — global step size (default 1e-3)
 *   beta1            — first-moment decay  (default 0.9)
 *   beta2            — second-moment decay (default 0.999)
 *   eps              — numerical stability epsilon (default 1e-8)
 *
 * ── Usage ─────────────────────────────────────────────────────────────────────
 *
 *   val trainer = NeuralNetworkTrainer(network)
 *   val losses  = trainer.train(dataset, epochs = 50)
 *   // network weights are now updated in-place
 *
 * @param network      The network whose weights will be modified in-place.
 * @param learningRate Adam step size.
 * @param batchSize    Number of samples per mini-batch (larger → smoother gradient,
 *                     smaller → more updates per epoch).
 * @param beta1        Exponential decay for first-moment estimate.
 * @param beta2        Exponential decay for second-moment estimate.
 * @param eps          Small constant to avoid division by zero in Adam.
 */
class NeuralNetworkTrainer(
    private val network: NeuralNetwork,
    private val learningRate: Double = 1e-3,
    private val batchSize: Int = 32,
    private val beta1: Double = 0.9,
    private val beta2: Double = 0.999,
    private val eps: Double = 1e-8
) {
    // ── Adam moment buffers (mirrors network.weights / biases in shape) ───────

    private val mW: Array<Array<DoubleArray>> = zeroLike(network.weights)
    private val vW: Array<Array<DoubleArray>> = zeroLike(network.weights)
    private val mB: Array<DoubleArray> = zeroLike(network.biases)
    private val vB: Array<DoubleArray> = zeroLike(network.biases)

    /** Global step counter — used for Adam bias correction. */
    private var t = 0

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Train for [epochs] full passes over [dataset].
     *
     * @return Mean MSE loss per epoch (useful for plotting a learning curve in the UI).
     */
    fun train(dataset: List<TrainingSample>, epochs: Int): List<Double> {
        require(dataset.isNotEmpty()) { "Cannot train on an empty dataset" }
        val lossPerEpoch = ArrayList<Double>(epochs)

        repeat(epochs) {
            val shuffled = dataset.shuffled()
            var epochLoss = 0.0
            var nBatches = 0
            for (batch in shuffled.chunked(batchSize)) {
                epochLoss += trainBatch(batch)
                nBatches++
            }
            lossPerEpoch.add(epochLoss / nBatches)
        }
        return lossPerEpoch
    }

    /**
     * Process a single mini-batch: forward → compute loss gradient → backprop → Adam update.
     *
     * @return Mean MSE loss over the batch (for monitoring only).
     */
    fun trainBatch(batch: List<TrainingSample>): Double {
        val L = network.weights.size
        val dW = zeroLike(network.weights)
        val dB = zeroLike(network.biases)
        var batchLoss = 0.0

        // ── Accumulate gradients over the batch ───────────────────────────────
        for (sample in batch) {
            val cache = network.forwardWithCache(sample.features)
            val output = cache.activations[L]
            val target = sample.labels

            // MSE loss: L = (1/m)·Σ(ŷᵢ − yᵢ)²
            // ∂L/∂ŷᵢ = (2/m)·(ŷᵢ − yᵢ)
            val m = output.size.toDouble()
            batchLoss += output.indices.sumOf { i ->
                val e = output[i] - target[i]; e * e
            } / m

            var delta = DoubleArray(output.size) { i -> (2.0 / m) * (output[i] - target[i]) }

            // ── Backprop through layers (last → first) ────────────────────────
            for (l in L - 1 downTo 0) {
                val pre = cache.preActivations[l]
                val prevAct = cache.activations[l]
                val isLast = l == L - 1

                // δ after activation derivative:
                //   output layer  → ×1 (linear) or ×ŷ(1−ŷ) (sigmoid)
                //   hidden layers → ×1(z > 0)  (ReLU)
                val actDelta = if (isLast) {
                    when (network.config.outputActivation) {
                        OutputActivation.LINEAR -> delta
                        OutputActivation.SIGMOID ->
                            DoubleArray(output.size) { i -> delta[i] * output[i] * (1.0 - output[i]) }
                    }
                } else {
                    DoubleArray(pre.size) { i -> if (pre[i] > 0.0) delta[i] else 0.0 }
                }

                // Accumulate ∂L/∂W[l] and ∂L/∂b[l] (divided by batch size)
                val bs = batch.size.toDouble()
                for (i in actDelta.indices) {
                    dB[l][i] += actDelta[i] / bs
                    for (j in prevAct.indices) dW[l][i][j] += actDelta[i] * prevAct[j] / bs
                }

                // Propagate delta to previous layer: δ_prev = Wᵀ · actDelta
                delta = DoubleArray(prevAct.size) { j ->
                    network.weights[l].indices.sumOf { i -> network.weights[l][i][j] * actDelta[i] }
                }
            }
        }

        // ── Adam parameter update ─────────────────────────────────────────────
        t++
        // Bias-corrected learning rate
        val lrCorrected = learningRate *
                sqrt(1.0 - beta2.pow(t)) / (1.0 - beta1.pow(t))

        for (l in 0 until L) {
            for (i in network.weights[l].indices) {
                // Biases
                mB[l][i] = beta1 * mB[l][i] + (1.0 - beta1) * dB[l][i]
                vB[l][i] = beta2 * vB[l][i] + (1.0 - beta2) * dB[l][i] * dB[l][i]
                network.biases[l][i] -= lrCorrected * mB[l][i] / (sqrt(vB[l][i]) + eps)

                // Weights
                for (j in network.weights[l][i].indices) {
                    mW[l][i][j] = beta1 * mW[l][i][j] + (1.0 - beta1) * dW[l][i][j]
                    vW[l][i][j] = beta2 * vW[l][i][j] + (1.0 - beta2) * dW[l][i][j] * dW[l][i][j]
                    network.weights[l][i][j] -= lrCorrected * mW[l][i][j] / (sqrt(vW[l][i][j]) + eps)
                }
            }
        }

        return batchLoss / batch.size
    }

    /** Reset Adam moments (call if restarting training on a new dataset). */
    fun resetOptimiserState() {
        for (l in mW.indices) {
            for (i in mW[l].indices) {
                mW[l][i].fill(0.0); vW[l][i].fill(0.0)
            }
            mB[l].fill(0.0); vB[l].fill(0.0)
        }
        t = 0
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun zeroLike(src: Array<Array<DoubleArray>>) =
        Array(src.size) { l -> Array(src[l].size) { i -> DoubleArray(src[l][i].size) } }

    private fun zeroLike(src: Array<DoubleArray>) =
        Array(src.size) { l -> DoubleArray(src[l].size) }

    private fun Double.pow(n: Int): Double {
        var r = 1.0; repeat(n) { r *= this }; return r
    }
}