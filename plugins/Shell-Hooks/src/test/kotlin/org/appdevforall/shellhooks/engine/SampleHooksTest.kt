package org.appdevforall.shellhooks.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

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

    // A name can hold terminal control codes, and the action's output goes to the Terminal.
    @Test
    fun theSampleActionCountsWithoutPrintingTheFileName() {
        val bash = listOf("/bin/bash", "/usr/bin/bash").map(::File).firstOrNull { it.canExecute() }
        assumeTrue("needs bash", bash != null)
        SampleHooks.write(paths)
        val target = File(appFiles, "x\u001b]52;c;ZWNobw==\u0007.txt").apply { writeText("one two\n") }

        val process = ProcessBuilder(bash!!.path, File(paths.actions, SampleHooks.ACTION_SAMPLE).path, target.path).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor(10, TimeUnit.SECONDS)

        assertEquals(listOf("1", "2", "8"), out.trim().split(Regex("\\s+")))
        assertFalse(out, out.contains('\u001b'))
    }

    // The shell's own error for an unreadable file names it, control codes and all.
    @Test
    fun theSampleActionNamesNoFileWhenItCannotReadIt() {
        val bash = listOf("/bin/bash", "/usr/bin/bash").map(::File).firstOrNull { it.canExecute() }
        assumeTrue("needs bash", bash != null)
        SampleHooks.write(paths)
        // Gone before the run: a missing file fails even for root, which a mode-000 file would not.
        val missing = File(appFiles, "x\u001b]52;c;ZWNobw==\u0007.txt")

        val process = ProcessBuilder(bash!!.path, File(paths.actions, SampleHooks.ACTION_SAMPLE).path, missing.path).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor(10, TimeUnit.SECONDS)

        assertEquals(1, process.exitValue())
        assertEquals("word-count: cannot read the file", out.trim())
        assertFalse(out, out.contains('\u001b'))
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
