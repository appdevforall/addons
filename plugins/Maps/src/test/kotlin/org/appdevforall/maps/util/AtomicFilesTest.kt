package org.appdevforall.maps.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [AtomicFiles] — the shared write / copy / overwrite / mkdir
 * behaviour relied on by RegionDownloader, RegionInstaller, and ProjectMapEmitter.
 */
class AtomicFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `writeText creates the file with the given content`() {
        val dest = File(tmp.root, "out.txt")
        AtomicFiles.writeText(dest, "hello")
        assertTrue(dest.exists())
        assertEquals("hello", dest.readText())
    }

    @Test
    fun `writeText overwrites an existing file`() {
        val dest = File(tmp.root, "out.txt")
        dest.writeText("old")
        AtomicFiles.writeText(dest, "new")
        assertEquals("new", dest.readText())
    }

    @Test
    fun `writeText creates missing parent directories`() {
        val dest = File(tmp.root, "a/b/c/deep.txt")
        assertFalse(dest.parentFile!!.exists())
        AtomicFiles.writeText(dest, "deep")
        assertTrue(dest.exists())
        assertEquals("deep", dest.readText())
    }

    @Test
    fun `writeText leaves no tmp file behind on success`() {
        val dest = File(tmp.root, "out.txt")
        AtomicFiles.writeText(dest, "x")
        assertFalse(File(tmp.root, "out.txt.tmp").exists())
    }

    @Test
    fun `copy duplicates source content to a new destination`() {
        val src = File(tmp.root, "src.bin").apply { writeText("payload") }
        val dest = File(tmp.root, "nested/dest.bin")
        AtomicFiles.copy(src, dest)
        assertTrue(dest.exists())
        assertEquals("payload", dest.readText())
        // Source is untouched.
        assertEquals("payload", src.readText())
    }

    @Test
    fun `copy overwrites an existing destination`() {
        val src = File(tmp.root, "src.bin").apply { writeText("fresh") }
        val dest = File(tmp.root, "dest.bin").apply { writeText("stale") }
        AtomicFiles.copy(src, dest)
        assertEquals("fresh", dest.readText())
        assertFalse(File(tmp.root, "dest.bin.tmp").exists())
    }

    @Test
    fun `copy invokes onChunk at least once per copy`() {
        val src = File(tmp.root, "src.bin").apply { writeText("payload") }
        val dest = File(tmp.root, "dest.bin")
        var calls = 0
        AtomicFiles.copy(src, dest) { calls++ }
        assertTrue("onChunk should be invoked during the copy", calls >= 1)
        assertEquals("payload", dest.readText())
    }

    @Test
    fun `copy invokes onChunk multiple times for a multi-chunk source`() {
        // Source larger than the 512 KB copy buffer forces more than one chunk,
        // so onChunk (the cancellation checkpoint) fires multiple times.
        val src = File(tmp.root, "big.bin").apply { writeBytes(ByteArray(1_500_000) { 7 }) }
        val dest = File(tmp.root, "big-dest.bin")
        var calls = 0
        AtomicFiles.copy(src, dest) { calls++ }
        assertTrue("expected multiple chunk checkpoints, got $calls", calls >= 3)
        assertEquals(1_500_000L, dest.length())
    }

    @Test
    fun `copy that is cancelled via onChunk leaves no temp and does not touch destination`() {
        val src = File(tmp.root, "big.bin").apply { writeBytes(ByteArray(1_500_000) { 1 }) }
        val dest = File(tmp.root, "dest.bin").apply { writeText("ORIGINAL") }
        var calls = 0
        val boom = try {
            // Throw on the second checkpoint to simulate mid-copy cancellation.
            AtomicFiles.copy(src, dest) {
                calls++
                if (calls == 2) throw IllegalStateException("cancelled")
            }
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertTrue("the throwing onChunk should propagate", boom != null)
        // The partial temp must be cleaned up...
        assertFalse(File(tmp.root, "dest.bin.tmp").exists())
        // ...and the pre-existing destination must be left intact (atomic move
        // never ran).
        assertEquals("ORIGINAL", dest.readText())
    }
}
