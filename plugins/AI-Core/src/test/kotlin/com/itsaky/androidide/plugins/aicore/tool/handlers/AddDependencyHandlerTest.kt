package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ServiceRegistry
import com.itsaky.androidide.plugins.services.IdeProjectManipulationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Unit tests for [AddDependencyHandler], which hands the host's service an absolute build file. */
class AddDependencyHandlerTest {

    private lateinit var projectRoot: File
    private lateinit var service: IdeProjectManipulationService
    private lateinit var handler: AddDependencyHandler
    private val path = slot<String>()

    @Before
    fun setup() {
        projectRoot = Files.createTempDirectory("adddependency-project").toFile().canonicalFile
        PathGuard.setProjectRootForTesting(projectRoot.absolutePath)
        service = mockk()
        every { service.addDependency(any(), capture(path)) } returns true
        val services = mockk<ServiceRegistry>()
        every { services.get(IdeProjectManipulationService::class.java) } returns service
        val context = mockk<PluginContext>()
        every { context.services } returns services
        handler = AddDependencyHandler(context)
    }

    @After
    fun tearDown() {
        PathGuard.setProjectRootForTesting(null)
        PathGuard.setProjectRootProvider(null)
        projectRoot.deleteRecursively()
    }

    @Test
    fun givenNoBuildFile_whenAdding_thenTheServiceGetsTheAppBuildFileUnderTheProjectRoot() =
        runBlocking {
            val result = handler.execute(mapOf("dependency" to "io.ktor:ktor-client-okhttp:3.0.0"))

            assertTrue(result.success)
            assertEquals(File(projectRoot, "app/build.gradle.kts").absolutePath, path.captured)
        }

    @Test
    fun givenARelativeBuildFile_whenAdding_thenTheServiceGetsItResolvedAgainstTheProjectRoot() =
        runBlocking {
            handler.execute(mapOf("dependency" to "a:b:1", "build_file" to "lib/build.gradle.kts"))

            assertEquals(File(projectRoot, "lib/build.gradle.kts").absolutePath, path.captured)
        }

    @Test
    fun givenABuildFileOutsideTheProject_whenAdding_thenTheServiceIsNeverCalled() = runBlocking {
        val result = handler.execute(mapOf("dependency" to "a:b:1", "build_file" to "../elsewhere.kts"))

        assertFalse(result.success)
        verify(exactly = 0) { service.addDependency(any(), any()) }
    }
}
