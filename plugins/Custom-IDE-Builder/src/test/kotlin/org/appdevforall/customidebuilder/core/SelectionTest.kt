package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SelectionTest {
    private fun plugin(label: String, file: String, id: String?) = PickedAddon(label, file, File(file), id)

    private fun template(label: String, file: String) = PickedAddon(label, file, File(file), null)

    private fun problems(vararg picked: PickedAddon): List<String> = (AddonSelection.validate(picked.toList()) as SelectionResult.Invalid).problems

    @Test
    fun validSelectionGetsEntryNames() {
        val r = AddonSelection.validate(
            listOf(plugin("A", "a.cgp", "org.example.a"), plugin("B", "b.CGP", "org.example.b"), template("T", "Starter Kit.cgt")),
        ) as SelectionResult.Valid
        assertEquals(
            listOf("org.example.a.cgp", "org.example.b.cgp", "Starter_Kit.cgt"),
            r.staged.map { it.fileName },
        )
        assertEquals(listOf(AddonKind.PLUGIN, AddonKind.PLUGIN, AddonKind.TEMPLATE), r.staged.map { it.kind })
        assertEquals("assets/plugins/org.example.a.cgp", r.staged[0].apkEntryName)
    }

    @Test
    fun anEmptySelectionIsValid() {
        assertEquals(0, (AddonSelection.validate(emptyList()) as SelectionResult.Valid).staged.size)
    }

    @Test
    fun duplicatePluginIdsAreRejectedWhateverTheFileNames() {
        val p = problems(plugin("First", "one.cgp", "org.same"), plugin("Second", "two.cgp", "org.same"))
        assertEquals(1, p.size)
        assertTrue(p[0], p[0].contains("Second") && p[0].contains("First") && p[0].contains("org.same"))
    }

    @Test
    fun duplicateTemplateFileNamesAreRejectedIgnoringCase() {
        val p = problems(template("One", "kit.cgt"), template("Two", "KIT.CGT"))
        assertEquals(1, p.size)
        assertTrue(p[0].contains("same file name"))
    }

    @Test
    fun coreCgtIsNeverIncluded() {
        for (name in listOf("core.cgt", "Core.CGT")) {
            val p = problems(template("core", name))
            assertTrue(p[0], p[0].contains("core.cgt"))
        }
    }

    @Test
    fun otherFileTypesAreRejected() {
        assertTrue(problems(template("x", "thing.zip"))[0].contains("not a .cgp or .cgt"))
    }

    @Test
    fun pluginIdProblems() {
        assertTrue(problems(plugin("n", "n.cgp", null))[0].contains("manifest"))
        for (bad in listOf("", ".", "..", "a/b", "a\\b", "../files", " x")) {
            assertTrue("\"$bad\"", problems(plugin("p", "p.cgp", bad))[0].contains("not a valid name"))
        }
    }

    @Test
    fun allProblemsAreReportedAtOnce() {
        val p = problems(plugin("A", "a.cgp", null), template("core", "core.cgt"), template("w", "w.txt"))
        assertEquals(3, p.size)
    }

    @Test
    fun entryNamesAreCleaned() {
        assertEquals("a_b_c", AddonSelection.entryNameFor("a b/c"))
        assertEquals("hidden", AddonSelection.entryNameFor(".hidden"))
        assertEquals("addon", AddonSelection.entryNameFor("..."))
        assertEquals("org.example-1_x", AddonSelection.entryNameFor("org.example-1_x"))
    }

    @Test
    fun twoIdsThatCleanToOneNameAreCaught() {
        val p = problems(plugin("A", "a.cgp", "org.a b"), plugin("B", "b.cgp", "org.a_b"))
        assertTrue(p[0], p[0].contains("differ only in characters"))
    }
}
