package org.appdevforall.maps

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import java.io.IOException
import org.appdevforall.maps.testing.UnusedPluginContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginLogTest {

    /** Records each call as "level:message[:error message]". */
    private class RecordingLogger : PluginLogger {
        val lines = mutableListOf<String>()
        override val pluginId: String = MapsPlugin.PLUGIN_ID
        override fun debug(message: String) = Unit
        override fun debug(message: String, throwable: Throwable) = Unit
        override fun info(message: String) { lines += "info:$message" }
        override fun info(message: String, throwable: Throwable) { lines += "info:$message:${throwable.message}" }
        override fun warn(message: String) { lines += "warn:$message" }
        override fun warn(message: String, throwable: Throwable) { lines += "warn:$message:${throwable.message}" }
        override fun error(message: String) { lines += "error:$message" }
        override fun error(message: String, throwable: Throwable) { lines += "error:$message:${throwable.message}" }
    }

    @After
    fun tearDown() {
        MapsPlugin.pluginContext = null
    }

    @Test
    fun `each level reaches the host logger, with the error when one is given`() {
        val logger = RecordingLogger()
        MapsPlugin.pluginContext = object : PluginContext by UnusedPluginContext {
            override val logger: PluginLogger = logger
        }

        PluginLog.info("started")
        PluginLog.warn("slow")
        PluginLog.warn("retry", IOException("timeout"))
        PluginLog.error("failed", IOException("disk"))

        assertEquals(
            listOf("info:started", "warn:slow", "warn:retry:timeout", "error:failed:disk"),
            logger.lines,
        )
    }

    @Test
    fun `with no plugin context every level is a no-op`() {
        PluginLog.info("started")
        PluginLog.warn("slow")
        PluginLog.warn("retry", IOException("timeout"))
        PluginLog.error("failed", IOException("disk"))
    }
}
