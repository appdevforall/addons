package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class ApkRewriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun rewriteWith(addons: List<StagedAddon>): Triple<File, File, RewriteReport> {
        val source = tmp.newFile("source.apk")
        TestSupport.syntheticApk(source)
        val output = tmp.newFile("out.apk")
        val report = ApkRewriter.rewrite(source, output, addons)
        return Triple(source, output, report)
    }

    private fun twoAddons() = listOf(
        TestSupport.staged(tmp.root, AddonKind.PLUGIN, "org.example.plugin.cgp", TestSupport.noise(1234, 10)),
        TestSupport.staged(tmp.root, AddonKind.TEMPLATE, "starter.cgt", TestSupport.text(999)),
    )

    @Test
    fun dropsTheOldBundleTheOldIndexAndTheV1Signature() {
        val (_, output, report) = rewriteWith(twoAddons())
        val names = RawZipFile(output).use { z -> z.entries.map { it.name } }
        for (gone in listOf("assets/plugins/old.cgp", "assets/templates/old.cgt", "META-INF/MANIFEST.MF", "META-INF/OLD.SF", "META-INF/OLD.RSA")) {
            assertFalse("$gone should be gone", gone in names)
        }
        assertEquals(
            setOf("assets/plugins/old.cgp", "assets/templates/old.cgt", "assets/bundled-addons.tsv", "META-INF/MANIFEST.MF", "META-INF/OLD.SF", "META-INF/OLD.RSA"),
            report.dropped.toSet(),
        )
        assertTrue("META-INF/services/example.Service" in names)
        assertTrue("assets/keep.txt" in names)
    }

    @Test
    fun copiesEveryOtherEntryByteForByteWithoutRecompressing() {
        val (source, output, _) = rewriteWith(twoAddons())
        RawZipFile(source).use { src ->
            RawZipFile(output).use { out ->
                val byName = out.entries.associateBy { it.name }
                // Expected from the fixture and a written-out list, not from ApkRewriter.isDropped, so a wrongly
                // dropped entry (the manifest, say) cannot hide in both sides.
                val removed = setOf("assets/plugins/old.cgp", "assets/templates/old.cgt", "assets/bundled-addons.tsv", "META-INF/MANIFEST.MF", "META-INF/OLD.SF", "META-INF/OLD.RSA")
                val fixture = src.entries.map { it.name }
                assertTrue("the fixture should hold the removed names", fixture.containsAll(removed))
                val expected = fixture.filterNot { it in removed }
                assertEquals(
                    setOf("AndroidManifest.xml", "classes.dex", "resources.arsc", "lib/arm64-v8a/libnative.so", "res/raw/data.bin", "assets/keep.txt", "META-INF/services/example.Service"),
                    expected.toSet(),
                )
                val kept = src.entries.filter { it.name in expected }
                assertEquals(expected.size, kept.size)
                for (name in expected) assertTrue("$name is missing from the output", name in byName)
                for (s in kept) {
                    val o = byName.getValue(s.name)
                    assertEquals("method of ${s.name}", s.method, o.method)
                    assertEquals("crc of ${s.name}", s.crc, o.crc)
                    assertEquals("size of ${s.name}", s.size, o.size)
                    assertEquals("compressed size of ${s.name}", s.compressedSize, o.compressedSize)
                    assertEquals("time of ${s.name}", s.dosTime, o.dosTime)
                    assertEquals("date of ${s.name}", s.dosDate, o.dosDate)
                    assertArrayEquals("raw bytes of ${s.name}", TestSupport.rawBytes(src, s), TestSupport.rawBytes(out, o))
                }
                // The deflated entries really are compressed in the output, not stored.
                assertEquals(8, byName.getValue("classes.dex").method)
                assertTrue(byName.getValue("classes.dex").compressedSize < byName.getValue("classes.dex").size / 10)
            }
        }
    }

    @Test
    fun theOutputIsAZipJavaCanReadWithTheSameContent() {
        val (_, output, _) = rewriteWith(twoAddons())
        ZipFile(output).use { zip ->
            val dex = zip.getInputStream(zip.getEntry("classes.dex")).readBytes()
            assertArrayEquals(TestSupport.text(200_000), dex)
            assertArrayEquals(TestSupport.noise(10_001, 3), zip.getInputStream(zip.getEntry("lib/arm64-v8a/libnative.so")).readBytes())
        }
    }

    @Test
    fun storedEntriesAreAlignedAndSharedLibrariesToSixteenKilobytes() {
        val (_, output, _) = rewriteWith(twoAddons())
        assertEquals(emptyList<String>(), ZipAlignment.misaligned(output))
        RawZipFile(output).use { z ->
            val so = z.entries.first { it.name == "lib/arm64-v8a/libnative.so" }
            assertEquals(0L, z.dataOffset(so) % 16384)
            val arsc = z.entries.first { it.name == "resources.arsc" }
            assertEquals(0L, z.dataOffset(arsc) % 4)
            for (e in z.entries.filter { it.isStored && it.compressedSize > 0 }) assertEquals(e.name, 0L, z.dataOffset(e) % ZipAlignment.requiredFor(e.name))
        }
    }

    @Test
    fun addsTheChosenAddonsAndAFreshIndex() {
        val addons = twoAddons()
        val (_, output, report) = rewriteWith(addons)
        ZipFile(output).use { zip ->
            assertArrayEquals(TestSupport.noise(1234, 10), zip.getInputStream(zip.getEntry("assets/plugins/org.example.plugin.cgp")).readBytes())
            assertArrayEquals(TestSupport.text(999), zip.getInputStream(zip.getEntry("assets/templates/starter.cgt")).readBytes())
            val index = zip.getInputStream(zip.getEntry("assets/bundled-addons.tsv")).readBytes().toString(Charsets.UTF_8)
            assertEquals(report.index, index)
            val lines = index.lines().filter { it.isNotEmpty() }
            assertEquals(2, lines.size)
            assertTrue(lines[0].startsWith("plugins/org.example.plugin.cgp\t1234\t"))
            assertTrue(lines[1].startsWith("templates/starter.cgt\t999\t"))
            assertFalse("the old index line must not survive", index.contains("old.cgp"))
        }
        assertEquals(
            listOf("assets/plugins/org.example.plugin.cgp", "assets/templates/starter.cgt", "assets/bundled-addons.tsv"),
            report.added,
        )
    }

    @Test
    fun anEmptySelectionLeavesAnEmptyIndexAndNoAddons() {
        val (_, output, report) = rewriteWith(emptyList())
        assertEquals("", report.index)
        ZipFile(output).use { zip ->
            assertEquals(0L, zip.getEntry("assets/bundled-addons.tsv").size)
            assertTrue(zip.entries().asSequence().none { it.name.startsWith("assets/plugins/") || it.name.startsWith("assets/templates/") })
        }
    }

    @Test
    fun whichNamesAreDropped() {
        for (name in listOf("assets/plugins/a.cgp", "assets/plugins/B.CGP", "assets/templates/t.cgt", "assets/templates/T.CGT", "assets/bundled-addons.tsv", "META-INF/SIG-OLD.SF", "META-INF/SIG-OLD.XYZ", "META-INF/sig-x.abc", "META-INF/MANIFEST.MF", "META-INF/CERT.SF", "META-INF/CERT.RSA", "META-INF/CERT.DSA", "META-INF/CERT.EC", "META-INF/cert.rsa")) {
            assertTrue(name, ApkRewriter.isDropped(name))
        }
        for (name in listOf("assets/plugins/", "assets/templates/x/y.cgt", "assets/templates/xml/layout.xml", "assets/templates/core.cgt", "assets/templates/.hidden.cgt", "assets/templates/a.cgp", "assets/plugins/a.cgt", "assets/plugins/readme.txt", "META-INF/sub/SIG-X.ABC", "META-INF/SIGN.TXT", "assets/other/a.cgp", "assets/pluginsX", "classes.dex", "META-INF/services/a.SF", "META-INF/com/android/build/gradle/app-metadata.properties", "META-INF/CERT.TXT", "lib/x/libplugins.so")) {
            assertFalse(name, ApkRewriter.isDropped(name))
        }
    }

    @Test
    fun theIdesOwnFilesInTheTemplatesFolderAreKept() {
        val source = tmp.newFile("templates.apk")
        val xml = "<layout/>".toByteArray()
        TestSupport.syntheticApk(
            source,
            mapOf(
                "assets/templates/xml/layout.xml" to (java.util.zip.ZipEntry.DEFLATED to xml),
                "assets/templates/xml/menu.xml" to (java.util.zip.ZipEntry.DEFLATED to xml),
                "assets/templates/old.cgt" to (java.util.zip.ZipEntry.STORED to "old".toByteArray()),
            ),
        )
        val output = tmp.newFile("templates-out.apk")
        val report = ApkRewriter.rewrite(source, output, emptyList())
        val names = RawZipFile(output).use { z -> z.entries.map { it.name } }
        assertTrue("assets/templates/xml/layout.xml" in names)
        assertTrue("assets/templates/xml/menu.xml" in names)
        assertFalse("assets/templates/old.cgt" in names)
        assertEquals(emptyList<String>(), report.dropped.filter { it.startsWith("assets/templates/xml/") })
    }

    @Test
    fun aTopLevelSigFileLosesItsBlockToo() {
        val source = tmp.newFile("sig.apk")
        TestSupport.syntheticApk(
            source,
            mapOf(
                "META-INF/SIG-OLD.SF" to (java.util.zip.ZipEntry.DEFLATED to "x".toByteArray()),
                "META-INF/SIG-OLD.XYZ" to (java.util.zip.ZipEntry.DEFLATED to "y".toByteArray()),
                "META-INF/sub/SIG-KEEP.XYZ" to (java.util.zip.ZipEntry.DEFLATED to "z".toByteArray()),
            ),
        )
        val output = tmp.newFile("sig-out.apk")
        ApkRewriter.rewrite(source, output, emptyList())
        val names = RawZipFile(output).use { z -> z.entries.map { it.name } }
        assertFalse("META-INF/SIG-OLD.SF" in names)
        assertFalse("META-INF/SIG-OLD.XYZ" in names)
        assertTrue("META-INF/sub/SIG-KEEP.XYZ" in names)
    }

    @Test
    fun progressEndsAtExactlyOneOnlyAfterTheZipIsClosed() {
        val source = tmp.newFile("p.apk")
        TestSupport.syntheticApk(source)
        val seen = ArrayList<Double>()
        ApkRewriter.rewrite(source, tmp.newFile("p-out.apk"), twoAddons(), onProgress = { seen.add(it) })
        assertTrue(seen.isNotEmpty())
        assertEquals(1.0, seen.last(), 0.0)
        assertTrue("1.0 is reported once, at the end", seen.dropLast(1).all { it < 1.0 })
    }

    @Test
    fun aSourceWithTrailingJunkBetweenEntriesAndDirectoryIsStillCopied() {
        val source = tmp.newFile("signed-like.apk")
        TestSupport.syntheticApk(source)
        // Put 4 KB of junk, like a signing block, between the last entry and the central directory.
        val bytes = source.readBytes()
        val eocd = bytes.size - 22
        val cdOffset = (bytes[eocd + 16].toInt() and 0xFF) or ((bytes[eocd + 17].toInt() and 0xFF) shl 8) or
            ((bytes[eocd + 18].toInt() and 0xFF) shl 16) or ((bytes[eocd + 19].toInt() and 0xFF) shl 24)
        val junk = ByteArray(4096) { 0x5A }
        val patched = bytes.copyOfRange(0, cdOffset) + junk + bytes.copyOfRange(cdOffset, bytes.size)
        val shift = junk.size
        // Central directory records hold offsets of local headers, which do not move; only the end record's does.
        val newCd = cdOffset + shift
        patched[eocd + shift + 16] = newCd.toByte()
        patched[eocd + shift + 17] = (newCd ushr 8).toByte()
        patched[eocd + shift + 18] = (newCd ushr 16).toByte()
        patched[eocd + shift + 19] = (newCd ushr 24).toByte()
        source.writeBytes(patched)
        val out = tmp.newFile("out2.apk")
        ApkRewriter.rewrite(source, out, emptyList())
        assertFalse(out.readBytes().toString(Charsets.ISO_8859_1).contains("ZZZZZZZZ"))
        ZipFile(out).use { assertTrue(it.getEntry("classes.dex") != null) }
    }

    @Test(expected = ZipFormatException::class)
    fun twoAddonsForOneEntryAreRefused() {
        val a = TestSupport.staged(tmp.root, AddonKind.PLUGIN, "same.cgp", TestSupport.text(10))
        val b = StagedAddon(AddonKind.PLUGIN, "same.cgp", a.file)
        val source = tmp.newFile("s.apk")
        TestSupport.syntheticApk(source)
        ApkRewriter.rewrite(source, tmp.newFile("o.apk"), listOf(a, b))
    }

    @Test(expected = ZipFormatException::class)
    fun aFileThatIsNotAZipIsRefused() {
        val notZip = tmp.newFile("not.apk").also { it.writeBytes(ByteArray(100)) }
        ApkRewriter.rewrite(notZip, tmp.newFile("o.apk"), emptyList())
    }
}
