package org.appdevforall.shellhooks.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class HookInvocationTest {
    private val wrapper = File("/data/plugin/cogo-hook-run.sh")
    private val hooksDir = File("/home/.config/codeonthego/hooks")
    private val script = ResolvedScript(File("$hooksDir/file-saved"), File("$hooksDir/file-saved"), ScriptSource.GLOBAL, hooksDir)
    private val project = File("/projects/my app")

    @Test
    fun theWrapperGetsTheScriptThenTheEventArgumentsAsSeparateWords() {
        for (name in HOSTILE_NAMES) {
            val event = HookEvent.fileSaved(File(project, name), project)

            val spec = HookInvocation.spec(wrapper, script, event, "26.47")

            assertEquals("sh", spec.executable)
            assertEquals(listOf(wrapper.path, script.executable.path, File(project, name).path), spec.arguments)
        }
    }

    @Test
    fun theWorkingDirectoryIsLeftToTheWrapper() {
        // The host refuses a directory outside the open project, which project-closed would hit.
        assertNull(HookInvocation.spec(wrapper, script, HookEvent.projectClosed(project), null).workingDirectory)
    }

    @Test
    fun environmentNamesTheHookProjectAndScript() {
        val env = HookInvocation.environment(script, HookEvent.fileSaved(File(project, "a.kt"), project), "26.47")

        assertEquals(
            mapOf(
                "COGO_HOOK" to "file-saved",
                "COGO_PROJECT_DIR" to project.path,
                "COGO_IDE_VERSION" to "26.47",
                "COGO_HOOKS_DIR" to hooksDir.path,
                "COGO_HOOK_SCRIPT" to script.original.path,
                "COGO_HOOK_SOURCE" to "global",
            ),
            env,
        )
    }

    @Test
    fun theTimeoutIsPassedToTheWrapperOnlyWhenGiven() {
        val event = HookEvent.buildStarted(project)

        assertEquals("30", HookInvocation.spec(wrapper, script, event, null, timeoutSeconds = 30).environment["COGO_TIMEOUT_SECONDS"])
        assertFalse(HookInvocation.spec(wrapper, script, event, null).environment.containsKey("COGO_TIMEOUT_SECONDS"))
    }

    @Test
    fun noProjectIsAnEmptyProjectDirAndAnUnknownVersionIsOmitted() {
        val env = HookInvocation.environment(script, HookEvent.buildStarted(null), null)

        assertEquals("", env["COGO_PROJECT_DIR"])
        assertFalse(env.containsKey("COGO_IDE_VERSION"))
    }

    @Test
    fun eachHookPassesTheDocumentedArguments() {
        val file = File(project, "a.kt")
        assertEquals(emptyList<String>(), HookEvent.ideStarted(project).args)
        assertEquals(listOf(project.path), HookEvent.projectOpened(project).args)
        assertEquals(listOf(project.path), HookEvent.projectClosed(project).args)
        assertEquals(listOf(file.path), HookEvent.fileOpened(file, project).args)
        assertEquals(listOf(file.path), HookEvent.fileClosed(file, project).args)
        assertEquals(listOf(file.path), HookEvent.fileActivated(file, project).args)
        assertEquals(listOf(""), HookEvent.fileActivated(null, project).args)
        assertEquals(listOf(file.path), HookEvent.fileSaved(file, project).args)
        assertEquals(emptyList<String>(), HookEvent.buildStarted(project).args)
        assertEquals(emptyList<String>(), HookEvent.buildSucceeded(project).args)
        assertEquals(listOf("BUILD_FAILED"), HookEvent.buildFailed("BUILD_FAILED", project).args)
        assertEquals(listOf("cancelled"), HookEvent.buildFailed(null, project).args)
    }

    @Test
    fun hookIdsAreTheScriptFileNames() {
        assertEquals(
            listOf(
                "ide-started", "project-opened", "project-closed", "file-opened", "file-closed",
                "file-activated", "file-saved", "build-started", "build-succeeded", "build-failed",
            ),
            Hook.entries.map { it.id },
        )
        Hook.entries.forEach { assertEquals(it, Hook.fromId(it.id)) }
    }
}
