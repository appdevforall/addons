package org.appdevforall.maps

/**
 * Sends Maps' warnings and errors to the host's plugin log, where they outlive logcat and travel
 * with a bug report. Drops the message while no plugin context is attached (unit tests, after
 * dispose).
 */
internal object PluginLog {

    fun info(message: String) {
        MapsPlugin.pluginContext?.logger?.info(message)
    }

    fun warn(message: String, error: Throwable? = null) {
        val logger = MapsPlugin.pluginContext?.logger ?: return
        if (error == null) logger.warn(message) else logger.warn(message, error)
    }

    fun error(message: String, error: Throwable) {
        MapsPlugin.pluginContext?.logger?.error(message, error)
    }
}
