package org.appdevforall.maps.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Branch-coverage supplement for [AtomicFiles] — covers the no-parent-dir error
 * branches of both [AtomicFiles.writeText] and [AtomicFiles.copy], and the
 * "a stale `.tmp` already exists, delete it first" branch in each. The
 * happy-path / cancellation behaviour is covered by `AtomicFilesTest`.
 */
class AtomicFilesCoverageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `writeText throws when destination has no parent dir`() {
        // A bare relative filename has no parent component → parentFile == null.
        val noParent = File("atomic-files-no-parent.txt")
        assertThrows(IllegalStateException::class.java) {
            AtomicFiles.writeText(noParent, "x")
        }
    }

    @Test
    fun `copy throws when destination has no parent dir`() {
        val src = File(tmp.root, "src.bin").apply { writeText("payload") }
        val noParent = File("atomic-files-copy-no-parent.bin")
        assertThrows(IllegalStateException::class.java) {
            AtomicFiles.copy(src, noParent)
        }
    }

    @Test
    fun `writeText deletes a stale tmp file before writing`() {
        val dest = File(tmp.root, "out.txt")
        // Pre-seed the sibling .tmp that a prior crashed write would have left.
        val stale = File(tmp.root, "out.txt.tmp").apply { writeText("STALE") }
        assertTrue(stale.exists())
        AtomicFiles.writeText(dest, "fresh")
        assertEquals("fresh", dest.readText())
        // The stale tmp was deleted, then re-created+renamed, so none lingers.
        assertFalse(File(tmp.root, "out.txt.tmp").exists())
    }

    @Test
    fun `copy deletes a stale tmp file before writing`() {
        val src = File(tmp.root, "src.bin").apply { writeText("payload") }
        val dest = File(tmp.root, "dest.bin")
        val stale = File(tmp.root, "dest.bin.tmp").apply { writeText("STALE") }
        assertTrue(stale.exists())
        AtomicFiles.copy(src, dest)
        assertEquals("payload", dest.readText())
        assertFalse(File(tmp.root, "dest.bin.tmp").exists())
    }
}
