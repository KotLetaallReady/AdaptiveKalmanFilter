package com.katka.engine.neural

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A persisted trajectory-smoother model: the trained [NeuralNetwork] plus the
 * feature-normalisation statistics (μ/σ) that were fitted alongside it.
 *
 * Both must travel together — running inference with the wrong μ/σ feeds the
 * MLP off-distribution inputs and produces garbage α.
 *
 * @property network     Trained α-predictor.
 * @property featureMean μ per input feature.
 * @property featureStd  σ per input feature.
 */
data class LoadedSmoother(
    val network: NeuralNetwork,
    val featureMean: DoubleArray,
    val featureStd: DoubleArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LoadedSmoother) return false
        return network === other.network &&
                featureMean.contentEquals(other.featureMean) &&
                featureStd.contentEquals(other.featureStd)
    }

    override fun hashCode(): Int {
        var r = network.hashCode()
        r = 31 * r + featureMean.contentHashCode()
        r = 31 * r + featureStd.contentHashCode()
        return r
    }
}

/**
 * Saves and restores the smoother model (weights + output activation + feature
 * normalisation) to a private JSON file.
 *
 * File location: `<filesDir>/neural_smoother.json`
 *
 * JSON schema (version 2):
 * ```json
 * {
 *   "version": 2,
 *   "inputSize": 6, "hiddenSizes": [8, 4], "outputSize": 1,
 *   "outputActivation": "SIGMOID",
 *   "featureMean": [ … inputSize … ],
 *   "featureStd":  [ … inputSize … ],
 *   "weights": [[[…], …], …],
 *   "biases":  [[…], …]
 * }
 * ```
 */
object NetworkPersistenceManager {

    private const val TAG = "NNPersistence"
    private const val FILE_NAME = "neural_smoother.json"
    private const val SCHEMA_VERSION = 2

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Persist a trained smoother (network + normalisation statistics).
     * @throws Exception if the file cannot be written.
     */
    fun saveSmoother(
        context: Context,
        network: NeuralNetwork,
        featureMean: DoubleArray,
        featureStd: DoubleArray
    ) {
        val root = JSONObject().apply {
            put("version", SCHEMA_VERSION)
            put("inputSize", network.config.inputSize)
            put("hiddenSizes", JSONArray(network.config.hiddenSizes))
            put("outputSize", network.config.outputSize)
            put("outputActivation", network.config.outputActivation.name)
            put("featureMean", JSONArray(featureMean.toTypedArray()))
            put("featureStd", JSONArray(featureStd.toTypedArray()))
            put("weights", weightsToJson(network.weights))
            put("biases", biasesToJson(network.biases))
        }
        file(context).writeText(root.toString())
        Log.i(TAG, "Smoother saved — ${network.config.allSizes.toList()} — ${file(context).length() / 1024} KB")
    }

    /**
     * Load a previously saved smoother.
     * @return the restored [LoadedSmoother], or `null` if no file exists or it is corrupt.
     */
    fun loadSmoother(context: Context): LoadedSmoother? = runCatching {
        val f = file(context)
        if (!f.exists()) return null

        val root = JSONObject(f.readText())
        val inputSize = root.getInt("inputSize")
        val outputSize = root.getInt("outputSize")
        val hiddenArr = root.getJSONArray("hiddenSizes")
        val hiddenSizes = (0 until hiddenArr.length()).map { hiddenArr.getInt(it) }
        val activation = runCatching {
            OutputActivation.valueOf(root.optString("outputActivation", OutputActivation.SIGMOID.name))
        }.getOrDefault(OutputActivation.SIGMOID)

        val config = NetworkConfig(inputSize, hiddenSizes, outputSize, activation)
        val network = NeuralNetwork(config) // random init, overwritten below

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

        val featureMean = readDoubleArray(root, "featureMean", inputSize, default = 0.0)
        val featureStd = readDoubleArray(root, "featureStd", inputSize, default = 1.0)

        Log.i(TAG, "Smoother loaded — config: ${config.allSizes.toList()} ($activation)")
        LoadedSmoother(network, featureMean, featureStd)
    }.onFailure { e ->
        Log.e(TAG, "Failed to load smoother: ${e.message}", e)
    }.getOrNull()

    /** `true` if a saved smoother file exists. */
    fun exists(context: Context): Boolean = file(context).exists()

    /** Delete the saved smoother (e.g. when the user wants to retrain). */
    fun delete(context: Context): Boolean {
        val deleted = file(context).delete()
        if (deleted) Log.i(TAG, "Saved smoother deleted")
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

    private fun readDoubleArray(root: JSONObject, key: String, size: Int, default: Double): DoubleArray {
        val arr = root.optJSONArray(key) ?: return DoubleArray(size) { default }
        return DoubleArray(size) { i -> if (i < arr.length()) arr.getDouble(i) else default }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
