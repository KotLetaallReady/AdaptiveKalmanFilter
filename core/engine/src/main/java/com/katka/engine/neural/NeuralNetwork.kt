package com.katka.engine.neural

import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Compact feedforward MLP — pure Kotlin, no external ML library.
 *
 * Architecture (trajectory-smoother task, diploma §3.3):
 *   input (6 context features) → hidden ReLU layers (8, 4) → output (1, sigmoid)
 *
 * Output: the trust weight α ∈ (0,1) blending the Kalman point and its
 * Savitzky–Golay approximation.  A sigmoid output keeps α in range and starts
 * near 0.5 (zero biases, small weights) — the natural "undecided" prior.
 *
 * The topology and output activation are configurable via [NetworkConfig], so
 * the same class also supports a plain linear-regression head if needed.
 *
 * Weights are stored row-major:
 *   weights[l][i][j] = connection from neuron j in layer l
 *                       to neuron i in layer l+1.
 *
 * @param config  Describes the layer sizes and the output activation.
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
                if (isLast) outputActivate(s) else relu(s)   // configurable output, ReLU hidden
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
            acts[l + 1] = DoubleArray(pre.size) { if (isLast) outputActivate(pre[it]) else relu(pre[it]) }
        }

        @Suppress("UNCHECKED_CAST")
        return ForwardCache(
            preActivations = preActs as Array<DoubleArray>,
            activations = acts as Array<DoubleArray>
        )
    }

    // ── Activations ───────────────────────────────────────────────────────────

    private fun relu(x: Double) = if (x > 0.0) x else 0.0

    /** Output-layer activation, selected by [NetworkConfig.outputActivation]. */
    private fun outputActivate(x: Double): Double = when (config.outputActivation) {
        OutputActivation.LINEAR -> x
        OutputActivation.SIGMOID -> 1.0 / (1.0 + exp(-x))
    }

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

// ── Output activation ─────────────────────────────────────────────────────────

/** Activation applied to the output layer (hidden layers are always ReLU). */
enum class OutputActivation {
    /** Identity — for unbounded regression targets. */
    LINEAR,
    /** σ(z) = 1/(1+e⁻ᶻ) — for targets bounded to (0,1), e.g. the trust weight α. */
    SIGMOID
}

// ── NetworkConfig ─────────────────────────────────────────────────────────────

/**
 * Immutable descriptor for the MLP topology.
 *
 * @param inputSize        Number of input features.
 * @param hiddenSizes      Sizes of each hidden layer (e.g. [8, 4]).
 * @param outputSize       Number of output neurons.
 * @param outputActivation Activation on the output layer (default SIGMOID for α).
 */
data class NetworkConfig(
    val inputSize: Int,
    val hiddenSizes: List<Int>,
    val outputSize: Int,
    val outputActivation: OutputActivation = OutputActivation.SIGMOID
) {
    /** Flat array of all layer sizes: [input, h1, h2, …, output]. */
    val allSizes: IntArray
        get() = (listOf(inputSize) + hiddenSizes + listOf(outputSize)).toIntArray()

    companion object {
        /**
         * Default smoother topology (diploma §3.3): 6 → 8 → 4 → 1, sigmoid output.
         * Input size = number of context features (see SmootherFeatures.COUNT);
         * output = the single trust weight α.
         */
        fun default() = NetworkConfig(
            inputSize = 6,
            hiddenSizes = listOf(8, 4),
            outputSize = 1,
            outputActivation = OutputActivation.SIGMOID
        )
    }
}