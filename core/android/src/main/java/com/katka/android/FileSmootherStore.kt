package com.katka.android

import android.content.Context
import com.katka.engine.neural.SmootherStore
import java.io.File

/** File-backed [SmootherStore] persisting the model in the app's private files dir. */
class FileSmootherStore(
    private val context: Context,
    private val fileName: String = "neural_smoother.model"
) : SmootherStore {

    private fun file() = File(context.filesDir, fileName)

    override fun save(text: String) = file().writeText(text)

    override fun load(): String? = file().takeIf { it.exists() }?.readText()

    override fun exists(): Boolean = file().exists()

    override fun delete(): Boolean = file().delete()
}
