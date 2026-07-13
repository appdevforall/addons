package org.appdevforall.maps.data

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ServiceRegistry
import com.itsaky.androidide.plugins.extensions.IProject
import com.itsaky.androidide.plugins.services.IdeProjectService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Focused tests for [ProjectRegionCoordinator] — the project/active-region
 * facade extracted from `RegionManagerFragment`. Temp-dir based like
 * [ActiveRegionStoreTest]; the underlying stores have their own exhaustive
 * suites, so this covers the coordinator's routing + its one policy rule
 * (apply-then-activate pairing).
 */
class ProjectRegionCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** No live PluginContext (standalone JVM) — every host lookup degrades to null. */
    private val coordinator = ProjectRegionCoordinator(pluginContextProvider = { null })

    private fun activeFile(project: File) = File(
        project,
        "${ProjectRegionCoordinator.DEFAULT_PROJECT_MAPS_SUBPATH}/${ActiveRegionStore.ACTIVE_REGION_FILE}",
    )

    @Test
    fun `currentProjectRoot is null without a plugin context`() = runBlocking {
        assertNull(coordinator.currentProjectRoot())
    }

    @Test
    fun `no-arg readActiveRegionId is null when no project is open`() = runBlocking {
        assertNull(coordinator.readActiveRegionId())
    }

    @Test
    fun `write then read round-trips through the coordinator`() = runBlocking {
        val project = tmp.newFolder("project")
        coordinator.writeActiveRegionId(project, "addis-ababa")
        assertEquals("addis-ababa", coordinator.readActiveRegionId(project))
    }

    @Test
    fun `write lands the sentinel under the default maps subpath`() = runBlocking {
        val project = tmp.newFolder("project")
        coordinator.writeActiveRegionId(project, "lagos")
        assertEquals("lagos", activeFile(project).readText().trim())
    }

    @Test
    fun `clear removes the active marker`() = runBlocking {
        val project = tmp.newFolder("project")
        coordinator.writeActiveRegionId(project, "addis-ababa")
        coordinator.clearActiveRegionId(project)
        assertNull(coordinator.readActiveRegionId(project))
    }

    @Test
    fun `custom mapsSubpath is honored`() = runBlocking {
        val custom = ProjectRegionCoordinator(
            pluginContextProvider = { null },
            mapsSubpath = "custom/maps",
        )
        val project = tmp.newFolder("project")
        custom.writeActiveRegionId(project, "lagos")
        assertEquals(
            "lagos",
            File(project, "custom/maps/${ActiveRegionStore.ACTIVE_REGION_FILE}").readText().trim(),
        )
        assertEquals("lagos", custom.readActiveRegionId(project))
    }

    @Test
    fun `applyAndActivate does not write active-txt when the apply fails`() = runBlocking {
        // A bare temp dir is not a Maps project, so the region copy fails —
        // the pairing rule says active.txt must then stay absent (otherwise the
        // project would reference a region whose tiles were never copied).
        val project = tmp.newFolder("project")
        val regionDir = tmp.newFolder("regions", "test-region")
        val info = RegionInfo(
            regionId = "test-region",
            displayName = "Test Region",
            sizeBytes = 0L,
            downloadedAt = null,
            lastUsedAt = null,
            source = "internet",
            directory = regionDir,
        )

        val ok = coordinator.applyAndActivate(info, project)

        assertFalse("apply into a non-Maps project must fail", ok)
        assertFalse("failed apply must NOT mark the region active", activeFile(project).exists())
        assertNull(coordinator.readActiveRegionId(project))
    }

    // ----- currentProjectRoot host-chain arms (hand-rolled interface fakes;
    //       this module deliberately has no mocking library) -----

    /** PluginContext whose ServiceRegistry serves exactly [service] (or nothing). */
    private fun contextWith(service: IdeProjectService?): PluginContext = object : PluginContext {
        override val androidContext: android.content.Context
            get() = throw UnsupportedOperationException("not used")
        override val services: ServiceRegistry = object : ServiceRegistry {
            override fun <T> register(serviceClass: Class<T>, implementation: T) =
                throw UnsupportedOperationException("not used")
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(serviceClass: Class<T>): T? =
                if (serviceClass == IdeProjectService::class.java) service as T? else null
            override fun <T> getAll(serviceClass: Class<T>): List<T> = emptyList()
            override fun unregister(serviceClass: Class<*>) =
                throw UnsupportedOperationException("not used")
        }
        override val eventBus: Any get() = throw UnsupportedOperationException("not used")
        override val logger: com.itsaky.androidide.plugins.PluginLogger
            get() = throw UnsupportedOperationException("not used")
        override val resources: com.itsaky.androidide.plugins.ResourceManager
            get() = throw UnsupportedOperationException("not used")
        override val pluginId: String = "org.appdevforall.maps"
    }

    private fun projectServiceReturning(project: IProject?): IdeProjectService =
        object : IdeProjectService {
            override fun getCurrentProject(): IProject? = project
            override fun getAllProjects(): List<IProject> = emptyList()
            override fun getProjectByPath(path: File): IProject? = null
            override fun getModuleContext(name: String): com.itsaky.androidide.plugins.services.ModuleContext =
                throw UnsupportedOperationException("not used")
        }

    private fun projectAt(root: File): IProject = object : IProject {
        override val name: String = root.name
        override val rootDir: File = root
        override val type: com.itsaky.androidide.plugins.extensions.ProjectType
            get() = throw UnsupportedOperationException("not used")
        override fun getModules() = throw UnsupportedOperationException("not used")
        override fun getBuildFiles() = throw UnsupportedOperationException("not used")
    }

    @Test
    fun `currentProjectRoot is null when the host has no project service`() = runBlocking {
        val c = ProjectRegionCoordinator(pluginContextProvider = { contextWith(service = null) })
        assertNull(c.currentProjectRoot())
    }

    @Test
    fun `currentProjectRoot is null when no project is open`() = runBlocking {
        val c = ProjectRegionCoordinator(
            pluginContextProvider = { contextWith(projectServiceReturning(project = null)) },
        )
        assertNull(c.currentProjectRoot())
    }

    @Test
    fun `currentProjectRoot returns the open project's root`() = runBlocking {
        val root = tmp.newFolder("openproj")
        val c = ProjectRegionCoordinator(
            pluginContextProvider = { contextWith(projectServiceReturning(projectAt(root))) },
        )
        assertEquals(root, c.currentProjectRoot())
    }

    @Test
    fun `no-arg readActiveRegionId resolves through the open project`() = runBlocking {
        val root = tmp.newFolder("openproj2")
        val c = ProjectRegionCoordinator(
            pluginContextProvider = { contextWith(projectServiceReturning(projectAt(root))) },
        )
        c.writeActiveRegionId(root, "lagos-v3")
        assertEquals("lagos-v3", c.readActiveRegionId())
    }
}
