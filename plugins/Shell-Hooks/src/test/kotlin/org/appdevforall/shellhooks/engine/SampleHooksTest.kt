package org.appdevforall.shellhooks.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SampleHooksTest {
    private val appFiles = tempDir("samples")
    private val paths = HookPaths(appFiles)

    @After
    fun tearDown() {
        appFiles.deleteRecursively()
    }

    @Test
    fun writesOneSamplePerHookAndOneAction() {
        val written = SampleHooks.write(paths)

        assertEquals(Hook.entries.size + 1, written)
        Hook.entries.forEach { assertTrue(File(paths.globalHooks, "${it.id}.sample").isFile) }
        assertTrue(File(paths.actions, SampleHooks.ACTION_SAMPLE).isFile)
    }

    @Test
    fun noSampleEverRuns() {
        SampleHooks.write(paths)

        Hook.entries.forEach { assertTrue(it.id, HookDiscovery.discover(paths.globalHooks, it.id).isEmpty()) }
        assertTrue(HookDiscovery.actions(paths.actions).isEmpty())
        paths.globalHooks.walk().filter { it.isFile }.forEach { assertFalse(it.path, it.canExecute()) }
    }

    @Test
    fun eachSampleDocumentsItsArguments() {
        Hook.entries.forEach { assertTrue(it.id, SampleHooks.hookSample(it).contains(it.argsDoc)) }
    }

    @Test
    fun theHooksFolderIsTheTerminalsConfigFolder() {
        assertEquals(File(appFiles, "home/.config/codeonthego/hooks"), paths.globalHooks)
        assertEquals(File(appFiles, "usr"), paths.prefix)
    }
}
