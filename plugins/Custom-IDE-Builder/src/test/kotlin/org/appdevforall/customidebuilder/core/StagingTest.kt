package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StagingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val hostile = listOf("../../x.cgp", "a/b.cgp", "/abs.cgp", "..\\..\\x.cgt", "a\u0000.cgp", "x.cgp\u0000")

    @Test
    fun aNormalNameStagesUnderAFixedName() {
        assertEquals("addon.cgp", Staging.stagedFileName("My Plugin.cgp"))
        assertEquals("addon.cgt", Staging.stagedFileName("Kit.CGT"))
    }

    @Test
    fun aNameThatIsNotAnAddOnStagesNothing() {
        assertNull(Staging.stagedFileName(".."))
        assertNull(Staging.stagedFileName("notes.txt"))
        assertNull(Staging.stagedFileName(""))
    }

    @Test
    fun hostileNamesNeverReachThePath() {
        val root = tmp.newFolder("staging")
        for (name in hostile) {
            val name2 = Staging.stagedFileName(name)
            assertTrue(name, name2 == "addon.cgp" || name2 == "addon.cgt" || name2 == null)
            val file = Staging.stagedFile(root, 3, name)
            if (file != null) {
                assertEquals(name, File(File(root, "3"), name2!!).path, file.path)
                assertTrue(name, Staging.isInside(root, file))
            }
        }
        assertNull(Staging.stagedFile(root, 0, ".."))
    }

    @Test
    fun theLabelHasNoControlCharacters() {
        assertEquals("a.cgp", Staging.label("a\u0000.cgp"))
        assertFalse(Staging.label("x\u0000\n.cgp").any { it.isISOControl() })
        assertEquals("add-on", Staging.label("\u0000"))
        assertEquals(100, Staging.label("a".repeat(500)).length)
    }

    @Test
    fun containmentRejectsEscapes() {
        val root = tmp.newFolder("staging2")
        assertTrue(Staging.isInside(root, File(root, "1/addon.cgp")))
        assertFalse(Staging.isInside(root, File(root, "../outside.cgp")))
        assertFalse(Staging.isInside(root, File(root, "1/../../outside.cgp")))
        assertFalse(Staging.isInside(root, File("/abs.cgp")))
        assertFalse("the root itself is not a file inside it", Staging.isInside(root, root))
        assertFalse(Staging.isInside(root, File(root.path + "-sibling/addon.cgp")))
    }

    @Test
    fun containmentFollowsSymbolicLinks() {
        val root = tmp.newFolder("staging3")
        val outside = tmp.newFolder("outside")
        val link = File(root, "link")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: UnsupportedOperationException) {
            assumeNoException("this file system has no symbolic links", e)
        } catch (e: java.nio.file.AccessDeniedException) {
            assumeNoException("symbolic links are not permitted here", e)
        } catch (e: java.nio.file.FileSystemException) {
            // Windows without the privilege reports "not held"; anything else is a real setup error and fails the test.
            val reason = e.reason.orEmpty().lowercase()
            if ("not permitted" in reason || "privilege" in reason) assumeNoException("symbolic links are not permitted here", e)
            throw e
        }
        assertFalse(Staging.isInside(root, File(link, "addon.cgp")))
    }
}
