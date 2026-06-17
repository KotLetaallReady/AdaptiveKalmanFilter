package com.katka.engine

/** Logging abstraction that keeps the engine free of android.util.Log; the default [NoOp] is silent. */
interface Logger {
    fun d(message: String) {}
    fun i(message: String) {}
    fun w(message: String) {}
    fun e(message: String, throwable: Throwable? = null) {}

    /** Silent default logger. */
    object NoOp : Logger
}
