package com.katka.engine.neural

/** Loads and saves the trained smoother through a [SmootherStore], (de)serialising via [SmootherCodec]. */
class SmootherRepository(private val store: SmootherStore) {

    /** Whether a trained model is available. */
    fun exists(): Boolean = store.exists()

    /** Restores the trained model, or null if absent or corrupt. */
    fun load(): LoadedSmoother? = store.load()?.let { SmootherCodec.decode(it) }

    /** Serialises and persists the network together with its feature normalisation. */
    fun save(network: NeuralNetwork, featureMean: DoubleArray, featureStd: DoubleArray) {
        store.save(SmootherCodec.encode(network, featureMean, featureStd))
    }

    /** Deletes the persisted model. */
    fun delete(): Boolean = store.delete()
}
