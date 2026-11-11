package com.appdevforall.jvm.plugin

import org.json.JSONObject
import java.io.File

internal data class RunConfiguration(val mainClass: String, val arguments: String)

internal class RunConfigurationStore(private val file: File) {

    @Synchronized
    fun get(projectRoot: File): RunConfiguration? {
        val entry = read().optJSONObject(projectRoot.absolutePath) ?: return null
        return RunConfiguration(entry.getString(KEY_MAIN_CLASS), entry.getString(KEY_ARGUMENTS))
    }

    @Synchronized
    fun put(projectRoot: File, configuration: RunConfiguration) {
        val all = read().put(
            projectRoot.absolutePath,
            JSONObject()
                .put(KEY_MAIN_CLASS, configuration.mainClass)
                .put(KEY_ARGUMENTS, configuration.arguments),
        )
        file.parentFile?.mkdirs()
        val staged = File(file.parentFile, "${file.name}.tmp")
        staged.writeText(all.toString())
        check(staged.renameTo(file)) { "Could not save the run configuration to $file" }
    }

    private fun read(): JSONObject = if (file.isFile) JSONObject(file.readText()) else JSONObject()

    private companion object {
        const val KEY_MAIN_CLASS = "mainClass"
        const val KEY_ARGUMENTS = "arguments"
    }
}
