package com.katka.engine.neural

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Compact feedforward MLP — pure Kotlin, no external ML library.
 *
 * Architecture:
 *   input (24) → hidden ReLU layers → output (4, linear)
 *
 * Output: [K[0,0], K[1,1], K[2,0], K[3,1]] — the four dominant
 * Kalman-gain elements for a 4-state / 2-measurement filter.
 *
 * Weights are stored row-major:
 *   weights[l][i][j] = connection from neuron j in layer l
 *                       to neuron i in layer l+1.
 *
 * @param config  Describes the layer sizes.
 * @param seed    RNG seed for reproducible weight initialisation.
 */
class NeuralNetwork(val config: NetworkConfig, seed: Long = 42L) {

    /** Learnable weights for each inter-layer connection. */
    val weights: Array<Array<DoubleArray>>

    /** Learnable bias for each neuron except the input layer. */
    val biases: Array<DoubleArray>

    init {
        val rng = Random(seed)
        val sizes = config.allSizes
        val nLayers = sizes.size - 1

        weights = Array(nLayers) { l ->
            val fanIn = sizes[l]
            // He-initialisation (√(2/fanIn)) keeps gradients well-scaled for ReLU
            val std = sqrt(2.0 / fanIn)
            Array(sizes[l + 1]) {
                DoubleArray(fanIn) { rng.nextDouble(-std, std) }
            }
        }
        biases = Array(nLayers) { l -> DoubleArray(sizes[l + 1]) { 0.0 } }
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    /**
     * Forward pass — returns only the final output vector.
     * Hidden activations: ReLU.  Output activation: linear (identity).
     */
    fun predict(input: DoubleArray): DoubleArray {
        var act = input
        for (l in weights.indices) {
            val W = weights[l]
            val b = biases[l]
            val isLast = l == weights.lastIndex
            act = DoubleArray(W.size) { i ->
                var s = b[i]
                for (j in act.indices) s += W[i][j] * act[j]
                if (isLast) s else relu(s)   // linear output, ReLU hidden
            }
        }
        return act
    }

    /**
     * Forward pass — returns pre-activations and post-activations per layer
     * for use during backpropagation.
     */
    fun forwardWithCache(input: DoubleArray): ForwardCache {
        val nLayers = weights.size
        val preActs = arrayOfNulls<DoubleArray>(nLayers)
        val acts = arrayOfNulls<DoubleArray>(nLayers + 1)
        acts[0] = input

        for (l in weights.indices) {
            val W = weights[l]
            val b = biases[l]
            val isLast = l == nLayers - 1
            val prevAct = acts[l]!!

            val pre = DoubleArray(W.size) { i ->
                var s = b[i]
                for (j in prevAct.indices) s += W[i][j] * prevAct[j]
                s
            }
            preActs[l] = pre
            acts[l + 1] = DoubleArray(pre.size) { if (isLast) pre[it] else relu(pre[it]) }
        }

        @Suppress("UNCHECKED_CAST")
        return ForwardCache(
            preActivations = preActs as Array<DoubleArray>,
            activations = acts as Array<DoubleArray>
        )
    }

    // ── Activations ───────────────────────────────────────────────────────────

    private fun relu(x: Double) = if (x > 0.0) x else 0.0

    // ── Data classes ──────────────────────────────────────────────────────────

    /**
     * Intermediate values cached during a forward pass,
     * consumed by [NeuralNetworkTrainer.trainBatch].
     */
    data class ForwardCache(
        /** Pre-activation (z) for each hidden+output layer. */
        val preActivations: Array<DoubleArray>,
        /** Post-activation (a); index 0 is the raw input. */
        val activations: Array<DoubleArray>
    )
}

// ── NetworkConfig ─────────────────────────────────────────────────────────────

/**
 * Immutable descriptor for the MLP topology.
 *
 * @param inputSize    Number of input features (must equal
 *                     [TrainingDataCollector.FEATURE_SIZE]).
 * @param hiddenSizes  Sizes of each hidden layer (e.g. [32, 16]).
 * @param outputSize   Number of output neurons (must equal
 *                     [TrainingDataCollector.LABEL_SIZE]).
 */
data class NetworkConfig(
    val inputSize: Int,
    val hiddenSizes: List<Int>,
    val outputSize: Int
) {
    /** Flat array of all layer sizes: [input, h1, h2, …, output]. */
    val allSizes: IntArray
        get() = (listOf(inputSize) + hiddenSizes + listOf(outputSize)).toIntArray()

    companion object {
        /** Default topology that works well for the 24-feature Kalman task. */
        fun default() = NetworkConfig(
            inputSize = TrainingDataCollector.FEATURE_SIZE,
            hiddenSizes = listOf(32, 16),
            outputSize = TrainingDataCollector.LABEL_SIZE
        )
    }
}