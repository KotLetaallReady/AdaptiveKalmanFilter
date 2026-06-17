package com.katka.engine.neural

/** Storage boundary for the serialised smoother model; implemented per platform (Android files, in-memory, …). */
interface SmootherStore {
    /** Persists the serialised model, overwriting any previous value. */
    fun save(text: String)

    /** Returns the serialised model, or null if nothing has been saved. */
    fun load(): String?

    /** Whether a saved model exists. */
    fun exists(): Boolean

    /** Removes the saved model, returning true if something was deleted. */
    fun delete(): Boolean
}
