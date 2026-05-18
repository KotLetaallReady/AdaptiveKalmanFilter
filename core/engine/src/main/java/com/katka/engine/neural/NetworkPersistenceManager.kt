package com.katka.engine.neural

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saves and restores [NeuralNetwork] weights to a private JSON file.
 *
 * File location: `<filesDir>/neural_kalman_weights.json`
 *
 * JSON schema:
 * ```json
 * {
 *   "inputSize":   24,
 *   "hiddenSizes": [32, 16],
 *   "outputSize":  4,
 *   "weights": [[[…], …], …],   // per layer, per neuron, per weight
 *   "biases":  [[…], …]          // per layer, per neuron
 * }
 * ```
 *
 * Usage:
 * ```kotlin
 * // Save after training
 * NetworkPersistenceManager.save(context, network)
 *
 * // Load on next launch
 * val network = NetworkPersistenceManager.load(context)
 *     ?: /* train from scratch */
 * ```
 */
object NetworkPersistenceManager {

    private const val TAG       = "NNPersistence"
    private const val FILE_NAME = "neural_kalman_weights.json"

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Persist [network] weights to disk.
     * @throws Exception if the file cannot be written (disk full, permissions, etc.)
     */
    fun save(context: Context, network: NeuralNetwork) {
        val root = JSONObject().apply {
            put("inputSize",   network.config.inputSize)
            put("hiddenSizes", JSONArray(network.config.hiddenSizes))
            put("outputSize",  network.config.outputSize)
            put("weights",     weightsToJson(network.weights))
            put("biases",      biasesToJson(network.biases))
        }
        file(context).writeText(root.toString())
        Log.i(TAG, "Network saved — ${network.config.allSizes.toList()} — ${file(context).length() / 1024} KB")
    }

    /**
     * Load a previously saved [NeuralNetwork].
     * @return The restored network, or `null` if no file exists or the file is corrupt.
     */
    fun load(context: Context): NeuralNetwork? = runCatching {
        val f = file(context)
        if (!f.exists()) return null

        val root       = JSONObject(f.readText())
        val inputSize  = root.getInt("inputSize")
        val outputSize = root.getInt("outputSize")
        val hiddenArr  = root.getJSONArray("hiddenSizes")
        val hiddenSizes = (0 until hiddenArr.length()).map { hiddenArr.getInt(it) }

        val config  = NetworkConfig(inputSize, hiddenSizes, outputSize)
        val network = NeuralNetwork(config)   // initialised with random weights (overwritten below)

        // Restore weights
        val wLayers = root.getJSONArray("weights")
        for (l in 0 until wLayers.length()) {
            val neurons = wLayers.getJSONArray(l)
            for (i in 0 until neurons.length()) {
                val ws = neurons.getJSONArray(i)
                for (j in 0 until ws.length()) network.weights[l][i][j] = ws.getDouble(j)
            }
        }

        // Restore biases
        val bLayers = root.getJSONArray("biases")
        for (l in 0 until bLayers.length()) {
            val bs = bLayers.getJSONArray(l)
            for (i in 0 until bs.length()) network.biases[l][i] = bs.getDouble(i)
        }

        Log.i(TAG, "Network loaded — config: ${config.allSizes.toList()}")
        network
    }.onFailure { e ->
        Log.e(TAG, "Failed to load network weights: ${e.message}", e)
    }.getOrNull()

    /** `true` if a saved network file exists. */
    fun exists(context: Context): Boolean = file(context).exists()

    /** Delete the saved weights file (e.g. when the user wants to retrain). */
    fun delete(context: Context): Boolean {
        val deleted = file(context).delete()
        if (deleted) Log.i(TAG, "Saved network deleted")
        return deleted
    }

    // ── Serialisation helpers ─────────────────────────────────────────────────

    private fun weightsToJson(weights: Array<Array<DoubleArray>>): JSONArray =
        JSONArray().also { layers ->
            for (layer in weights) {
                layers.put(JSONArray().also { neurons ->
                    for (neuron in layer) neurons.put(JSONArray(neuron.toTypedArray()))
                })
            }
        }

    private fun biasesToJson(biases: Array<DoubleArray>): JSONArray =
        JSONArray().also { layers ->
            for (layer in biases) layers.put(JSONArray(layer.toTypedArray()))
        }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}