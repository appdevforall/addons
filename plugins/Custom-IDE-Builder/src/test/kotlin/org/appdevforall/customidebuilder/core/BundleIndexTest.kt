package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Reads the index the way the host does; the logic below is copied from Code on the Go. */
class BundleIndexTest {
    // Copied from CodeOnTheGo app/src/main/java/com/itsaky/androidide/assets/addons/BundledAddons.kt
    // (BundledAddons.parseIndex, isAddonFile, parse and SHA256_HEX), with BundledAddonKind reduced to
    // its dir and extension. Keep in step with it.
    private data class HostEntry(val kind: AddonKind, val fileName: String, val size: Long, val sha256: String?)

    private val sha256Hex = Regex("[0-9a-f]{64}")

    private fun hostIsAddonFile(kind: AddonKind, fileName: String): Boolean =
        '/' !in fileName &&
            !fileName.startsWith(".") &&
            fileName.endsWith(".${kind.extension}", ignoreCase = true) &&
            !(kind == AddonKind.TEMPLATE && fileName.equals("core.cgt", ignoreCase = true))

    private fun hostParse(entryName: String): Pair<AddonKind, String>? {
        val kind = AddonKind.values().firstOrNull { entryName.startsWith("${it.dir}/") } ?: return null
        val fileName = entryName.removePrefix("${kind.dir}/")
        return if (hostIsAddonFile(kind, fileName)) kind to fileName else null
    }

    private fun hostParseIndex(text: String): List<HostEntry>? {
        val entries =
            text.lines().filter { it.isNotBlank() }.map { line ->
                val fields = line.split('\t')
                if (fields.size != 3) return null
                val (kind, fileName) = hostParse(fields[0]) ?: return null
                val size = fields[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
                val sha256 = fields[2].takeIf { sha256Hex.matches(it) } ?: return null
                HostEntry(kind, fileName, size, sha256)
            }
        if (entries.distinctBy { "${it.kind.dir}/${it.fileName}" }.size != entries.size) return null
        return entries.sortedWith(compareBy({ it.kind.ordinal }, { it.fileName }))
    }

    private fun sha(c: Char) = c.toString().repeat(64)

    @Test
    fun theHostReadsBackExactlyWhatWasWritten() {
        val lines = listOf(
            IndexLine(AddonKind.TEMPLATE, "zeta.cgt", 5, sha('a')),
            IndexLine(AddonKind.PLUGIN, "org.b.cgp", 10, sha('b')),
            IndexLine(AddonKind.TEMPLATE, "alpha.cgt", 0, sha('c')),
            IndexLine(AddonKind.PLUGIN, "org.a.cgp", 99_999_999_999, sha('d')),
        )
        val text = BundleIndex.render(lines)
        val parsed = hostParseIndex(text)
        assertNotNull("the host must accept the index", parsed)
        assertEquals(
            listOf(
                HostEntry(AddonKind.PLUGIN, "org.a.cgp", 99_999_999_999, sha('d')),
                HostEntry(AddonKind.PLUGIN, "org.b.cgp", 10, sha('b')),
                HostEntry(AddonKind.TEMPLATE, "alpha.cgt", 0, sha('c')),
                HostEntry(AddonKind.TEMPLATE, "zeta.cgt", 5, sha('a')),
            ),
            parsed,
        )
    }

    @Test
    fun theTextIsExactlyTheBuildsFormat() {
        val text = BundleIndex.render(
            listOf(IndexLine(AddonKind.TEMPLATE, "t.cgt", 2, sha('2')), IndexLine(AddonKind.PLUGIN, "p.cgp", 1, sha('1'))),
        )
        assertEquals("plugins/p.cgp\t1\t${sha('1')}\ntemplates/t.cgt\t2\t${sha('2')}\n", text)
    }

    @Test
    fun anEmptySelectionIsAnEmptyIndexWhichTheHostTreatsAsAThinBuild() {
        val text = BundleIndex.render(emptyList())
        assertEquals("", text)
        // Not null: null would send the host to listing the folders. An empty list is a thin build.
        assertEquals(emptyList<HostEntry>(), hostParseIndex(text))
    }

    @Test
    fun theHostRejectsWhatItShould() {
        assertNull(hostParseIndex("plugins/a.cgp\t1\tnothex\n"))
        assertNull(hostParseIndex("templates/core.cgt\t1\t${sha('a')}\n"))
    }

    @Test
    fun everyNameTheSelectionAssignsIsAnAddonFileToTheHost() {
        val picked = listOf(
            PickedAddon("a", "My Plugin (1).cgp", java.io.File("x"), "org.weird/id with spaces"),
            PickedAddon("b", "Flutter Starter Kit.CGT", java.io.File("y"), null),
        )
        val result = AddonSelection.validate(picked.take(1)) as? SelectionResult.Invalid
        // A plugin ID that is a path is refused, not turned into a name.
        assertNotNull(result)
        val ok = AddonSelection.validate(
            listOf(
                PickedAddon("a", "My Plugin (1).cgp", java.io.File("x"), "org.example.plugin"),
                picked[1],
            ),
        ) as SelectionResult.Valid
        for (s in ok.staged) assertNotNull(s.fileName, hostParse("${s.kind.dir}/${s.fileName}"))
    }
}
