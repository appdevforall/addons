package org.appdevforall.maps.data

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.ServiceRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [ProjectRegionCoordinator.applyRegionToProject]'s error-routing lambda: when
 * the underlying install fails with an exception (on a plain JVM the cache root
 * can't resolve — `Environment` external storage is null), the failure must be
 * routed to the LIVE plugin logger resolved through `pluginContextProvider`.
 * `ProjectRegionCoordinatorTest` covers the provider-null side; this covers the
 * provider-present side with a recording logger.
 */
class ProjectRegionCoordinatorLoggerRoutingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class RecordingLogger : PluginLogger {
        val errors = mutableListOf<Pair<String, Throwable?>>()
        override val pluginId: String = "org.appdevforall.maps"
        override fun debug(message: String) {}
        override fun debug(message: String, throwable: Throwable) {}
        override fun info(message: String) {}
        override fun info(message: String, throwable: Throwable) {}
        override fun warn(message: String) {}
        override fun warn(message: String, throwable: Throwable) {}
        override fun error(message: String) { errors += message to null }
        override fun error(message: String, throwable: Throwable) { errors += message to throwable }
    }

    private fun contextWithLogger(recording: PluginLogger): PluginContext = object : PluginContext {
        override val androidContext: android.content.Context
            get() = throw UnsupportedOperationException("not used")
        override val services: ServiceRegistry = object : ServiceRegistry {
            override fun <T> register(serviceClass: Class<T>, implementation: T) =
                throw UnsupportedOperationException("not used")
            override fun <T> get(serviceClass: Class<T>): T? = null
            override fun <T> getAll(serviceClass: Class<T>): List<T> = emptyList()
            override fun unregister(serviceClass: Class<*>) =
                throw UnsupportedOperationException("not used")
        }
        override val eventBus: Any get() = throw UnsupportedOperationException("not used")
        override val logger: PluginLogger = recording
        override val resources: com.itsaky.androidide.plugins.ResourceManager
            get() = throw UnsupportedOperationException("not used")
        override val pluginId: String = "org.appdevforall.maps"
    }

    @Test
    fun install_failure_is_routed_to_the_plugin_logger() = runBlocking {
        val logger = RecordingLogger()
        val coordinator = ProjectRegionCoordinator(
            pluginContextProvider = { contextWithLogger(logger) },
        )
        val project = tmp.newFolder("project")
        val regionDir = tmp.newFolder("regions", "failregion")
        val info = RegionInfo(
            regionId = "failregion",
            displayName = "Fail Region",
            sizeBytes = 0L,
            downloadedAt = null,
            lastUsedAt = null,
            source = "internet",
            directory = regionDir,
        )

        val ok = coordinator.applyRegionToProject(info, project)

        assertFalse("JVM install (no external storage) must fail", ok)
        assertEquals("exactly one error routed to the plugin logger", 1, logger.errors.size)
        val (message, throwable) = logger.errors.single()
        assertTrue(
            "message must name the failing operation + region, got: $message",
            message.contains("applyRegionToProject failed") && message.contains("failregion"),
        )
        assertTrue("the causing exception must travel with the log call", throwable != null)
    }
}
