package com.itsaky.androidide.plugins.aicore.tool.handlers.edit

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Unit tests for [AtomicFileWriter]. The case that matters most is the ordinary one: replacing a file
 * that **already exists**, which `File.renameTo` reports a bare `false` for on some volumes. So
 * "overwrite it, and leave no staging file behind" is asserted directly.
 */
class AtomicFileWriterTest {

    private lateinit var dir: File

    @Before
    fun setup() {
        dir = Files.createTempDirectory("atomic-writer").toFile().canonicalFile
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun bytesOf(text: String) = text.toByteArray(StandardCharsets.UTF_8)

    private fun stagingFiles() = dir.listFiles()?.filter { it.name.endsWith(".aiedit") }.orEmpty()

    @Test
    fun givenAnExistingFile_whenReplaced_thenItHoldsTheNewBytes() {
        val file = File(dir, "Main.kt").apply { writeText("old\n") }

        val outcome = AtomicFileWriter.replace(file, "Main.kt", bytesOf("new\n"))

        assertTrue("expected success, got $outcome", outcome is AtomicFileWriter.Outcome.Written)
        assertEquals("new\n", file.readText())
    }

    @Test
    fun givenAnExistingFile_whenReplaced_thenNoStagingFileIsLeftBehind() {
        val file = File(dir, "Main.kt").apply { writeText("old\n") }

        AtomicFileWriter.replace(file, "Main.kt", bytesOf("new\n"))

        assertEquals("no .aiedit litter may survive a write", emptyList<File>(), stagingFiles())
    }

    @Test
    fun givenAFileWithUnusualPermissions_whenReplaced_thenTheyAreCarriedOver() {
        // A move adopts the temp file's 0600 mode, so a group-readable file would come back private.
        val file = File(dir, "Main.kt").apply { writeText("old\n") }
        val before = runCatching { Files.getPosixFilePermissions(file.toPath()) }.getOrNull()
        org.junit.Assume.assumeTrue("POSIX permissions unsupported here", before != null)

        AtomicFileWriter.replace(file, "Main.kt", bytesOf("new\n"))

        assertEquals(before, Files.getPosixFilePermissions(file.toPath()))
    }

    @Test
    fun givenANonWritableFile_whenReplaced_thenItFailsAndTheContentSurvives() {
        val file = File(dir, "Main.kt").apply { writeText("old\n") }
        org.junit.Assume.assumeTrue("cannot drop write permission here", file.setWritable(false))

        val outcome = AtomicFileWriter.replace(file, "Main.kt", bytesOf("new\n"))

        assertTrue(outcome is AtomicFileWriter.Outcome.Failed)
        assertTrue(
            (outcome as AtomicFileWriter.Outcome.Failed).reason.contains("not writable")
        )
        assertEquals("old\n", file.readText())
        assertEquals(emptyList<File>(), stagingFiles())
    }

    @Test
    fun givenAnEmptyReplacement_whenWritten_thenTheFileIsTruncatedRatherThanLeftAlone() {
        // An edit whose new_string deletes the file's entire contents is legal.
        val file = File(dir, "Main.kt").apply { writeText("old\n") }

        val outcome = AtomicFileWriter.replace(file, "Main.kt", ByteArray(0))

        assertTrue(outcome is AtomicFileWriter.Outcome.Written)
        assertEquals("", file.readText())
    }

    @Test
    fun givenRepeatedReplacements_whenWritten_thenEachOneLandsAndNothingAccumulates() {
        val file = File(dir, "Main.kt").apply { writeText("v0\n") }

        repeat(5) { index ->
            AtomicFileWriter.replace(file, "Main.kt", bytesOf("v${index + 1}\n"))
        }

        assertEquals("v5\n", file.readText())
        assertEquals(emptyList<File>(), stagingFiles())
    }
}
