package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TemplateCheckTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(vararg names: String): File {
        val f = tmp.newFile()
        ZipOutputStream(f.outputStream()).use { z ->
            for (n in names) {
                z.putNextEntry(ZipEntry(n))
                z.write("x".toByteArray())
                z.closeEntry()
            }
        }
        return f
    }

    @Test
    fun aPlainTemplateBundlePasses() {
        assertNull(TemplateCheck.problem(zip("template.json", "app/src/Main.kt", "app/res/", "dots..in..names.txt")))
    }

    @Test
    fun aFileThatOnlyStartsWithTheZipSignatureIsNotATemplate() {
        val f = tmp.newFile()
        f.writeBytes(byteArrayOf(0x50, 0x4B, 3, 4) + ByteArray(200) { 1 })
        val problem = TemplateCheck.problem(f)
        assertNotNull(problem)
        assertTrue(problem, problem!!.contains("not a readable zip file"))
    }

    @Test
    fun aTruncatedZipOrPlainTextIsNotATemplate() {
        val whole = zip("a.txt", "b.txt").readBytes()
        val cut = tmp.newFile().also { it.writeBytes(whole.copyOf(whole.size / 2)) }
        assertNotNull(TemplateCheck.problem(cut))
        val text = tmp.newFile().also { it.writeText("not a zip") }
        assertNotNull(TemplateCheck.problem(text))
        assertNotNull(TemplateCheck.problem(File(tmp.root, "missing.cgt")))
    }

    @Test
    fun entryNamesThatEscapeTheUnpackFolderAreRefused() {
        val hostile = mapOf(
            "/etc/passwd" to "an absolute path",
            "../outside.txt" to "climbs",
            "a/../../outside.txt" to "climbs",
            "a/b/.." to "climbs",
            "a\\b.txt" to "backslash",
            "..\\outside.txt" to "backslash",
            "C:/Windows/x" to "an absolute path",
            "a\u0001b" to "control character",
        )
        for ((name, expected) in hostile) {
            val problem = TemplateCheck.problem(zip("fine.txt", name))
            assertNotNull(name, problem)
            assertTrue("$name: $problem", problem!!.contains(expected))
        }
    }

    @Test
    fun unsafeNameIsNullForOrdinaryPaths() {
        for (ok in listOf("a", "a/b/c.txt", "dir/", ".hidden", "a..b/c", "..a/b", "a/b..")) assertNull(ok, TemplateCheck.unsafeName(ok))
        assertEquals("empty", TemplateCheck.unsafeName(""))
    }
}
