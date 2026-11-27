package org.appdevforall.shellhooks.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
    fun aNameAboveUFFFFIsInByteOrderToo() {
        // U+FF41 (fullwidth a) is EF BD A1 in UTF-8 and U+1F600 (an emoji) is F0 9F 98 80, so the
        // emoji runs second. Their UTF-16 units, FF41 against D83D DE00, would put it first.
        val fullwidthA = "\uFF41"
        val emoji = "\uD83D\uDE00"
        root.script("file-saved.d/$emoji")
        root.script("file-saved.d/$fullwidthA")
        root.script("file-saved")
        assumeTrue("the file system keeps the names", File(root, "file-saved.d").list().orEmpty().toSet() == setOf(emoji, fullwidthA))

        val names = HookDiscovery.discover(root, "file-saved").map { it.relativeTo(root).path }

        assertEquals(listOf("file-saved", "file-saved.d/$fullwidthA", "file-saved.d/$emoji"), names)
    }

    @Test
    fun skipsScriptsWithoutTheExecutableBit() {
        root.script("file-saved", executable = false)
        root.script("file-saved.d/off", executable = false)
        root.script("file-saved.d/on")

        assertEquals(listOf("on"), HookDiscovery.discover(root, "file-saved").map { it.name })
    }

    @Test
    fun projectHooksNeedNoExecutableBit() {
        // Shared storage is mounted noexec; a project's scripts run from a private copy.
        root.script("file-saved", executable = false)
        root.script("file-saved.d/lint", executable = false)
        root.script("file-saved.d/lint.sample", executable = false)

        assertEquals(
            listOf("file-saved", "lint"),
            HookDiscovery.discover(root, "file-saved", requireExecutable = false).map { it.name },
        )
    }

    @Test
    fun actionsAreOfferedForFilesButNotFolders() {
        root.script("actions/count")
        val file = root.script("notes.txt", executable = false)
        val folder = File(root, "src").apply { mkdirs() }

        assertEquals(listOf("count"), HookDiscovery.actionsFor(file, File(root, "actions")).map { it.name })
        assertTrue(HookDiscovery.actionsFor(folder, File(root, "actions")).isEmpty())
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
