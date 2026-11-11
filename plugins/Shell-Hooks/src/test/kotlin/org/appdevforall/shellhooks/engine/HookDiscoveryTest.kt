package org.appdevforall.shellhooks.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HookDiscoveryTest {
    private val root = tempDir("hooks")

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun runsTheNamedScriptThenTheDirectoryInByteOrder() {
        root.script("file-saved.d/b")
        root.script("file-saved.d/A")
        root.script("file-saved.d/10-x")
        root.script("file-saved.d/2-y")
        root.script("file-saved")

        val names = HookDiscovery.discover(root, "file-saved").map { it.relativeTo(root).path }

        assertEquals(listOf("file-saved", "file-saved.d/10-x", "file-saved.d/2-y", "file-saved.d/A", "file-saved.d/b"), names)
    }

    @Test
    fun skipsScriptsWithoutTheExecutableBit() {
        root.script("file-saved", executable = false)
        root.script("file-saved.d/off", executable = false)
        root.script("file-saved.d/on")

        assertEquals(listOf("on"), HookDiscovery.discover(root, "file-saved").map { it.name })
    }

    @Test
    fun skipsSamplesDotfilesBackupsAndDirectories() {
        root.script("file-saved.sample")
        root.script("file-saved.d/run.sample")
        root.script("file-saved.d/.run.swp")
        root.script("file-saved.d/run~")
        File(root, "file-saved.d/subdir").mkdirs()
        root.script("file-saved.d/run")

        assertEquals(listOf("run"), HookDiscovery.discover(root, "file-saved").map { it.name })
    }

    @Test
    fun anotherHooksScriptsAreNotPickedUp() {
        root.script("file-saved-extra")
        root.script("file-opened")

        assertTrue(HookDiscovery.discover(root, "file-saved").isEmpty())
    }

    @Test
    fun aMissingFolderHasNoScripts() {
        assertTrue(HookDiscovery.discover(File(root, "absent"), "file-saved").isEmpty())
        assertTrue(HookDiscovery.actions(File(root, "absent")).isEmpty())
    }

    @Test
    fun actionsAreTheRunnableScriptsSortedByName() {
        root.script("actions/zip")
        root.script("actions/count")
        root.script("actions/notes.sample")
        root.script("actions/readme", executable = false)

        assertEquals(listOf("count", "zip"), HookDiscovery.actions(File(root, "actions")).map { it.name })
    }
}
