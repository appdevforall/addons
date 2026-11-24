package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/** Signs with apksig and checks with apksig's own verifier: the check that matters most. */
class PipelineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val minSdk = 26

    private fun signedHost(key: SigningKey): File {
        val unsigned = tmp.newFile("host-unsigned.apk")
        TestSupport.syntheticApk(unsigned)
        val signed = tmp.newFile("host.apk")
        Signing.sign(unsigned, signed, key, minSdk)
        return signed
    }

    private fun addons() = listOf(
        TestSupport.staged(tmp.root, AddonKind.PLUGIN, "org.example.p.cgp", TestSupport.noise(2048, 11)),
        TestSupport.staged(tmp.root, AddonKind.TEMPLATE, "kit.cgt", TestSupport.text(4096)),
    )

    @Test
    fun signsAndVerifiesAllSchemes() {
        val key = TestSupport.signingKey("Alice")
        val host = signedHost(key)
        val report = Signing.verify(host, minSdk)
        assertTrue(report.errors.toString(), report.verified)
        assertTrue(report.v1 && report.v2 && report.v3)
        assertEquals(key.fingerprint, report.signerSha256)
    }

    @Test
    fun rebundlesASignedHostWithADifferentKeyAndTheResultVerifies() {
        val hostKey = TestSupport.signingKey("Host")
        val userKey = TestSupport.signingKey("User")
        val host = signedHost(hostKey)
        val work = tmp.newFolder("work")
        val progress = ArrayList<BuildStage>()
        val out = Pipeline.run(
            BuildInput(host, emptyList(), addons(), userKey, minSdk, work, "Code-on-the-Go-1.0-custom.apk"),
            onProgress = { s, _ -> if (progress.lastOrNull() != s) progress.add(s) },
        )
        assertEquals(listOf(BuildStage.CHECKING, BuildStage.REWRITING, BuildStage.SIGNING, BuildStage.VERIFYING), progress)
        assertTrue(out.verify.verified && out.verify.v1 && out.verify.v2 && out.verify.v3)
        assertEquals("signed with the user's key, not the host's", userKey.fingerprint, out.verify.signerSha256)
        assertNotEquals(hostKey.fingerprint, out.verify.signerSha256)
        assertEquals(emptyList<String>(), ZipAlignment.misaligned(out.apk))
        assertEquals("only the finished APK is left in the work folder", listOf("Code-on-the-Go-1.0-custom.apk"), work.list()!!.toList())

        ZipFile(out.apk).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertFalse("the host's old v1 signature is gone", "META-INF/OLD.SF" in names)
            assertTrue("apksig wrote its own", names.any { it.startsWith("META-INF/") && it.endsWith(".SF") })
            assertArrayEquals(TestSupport.noise(2048, 11), zip.getInputStream(zip.getEntry("assets/plugins/org.example.p.cgp")).readBytes())
            assertTrue(zip.getEntry("assets/bundled-addons.tsv").size > 0)
            assertTrue("assets/plugins/old.cgp" !in names)
            assertArrayEquals(TestSupport.text(200_000), zip.getInputStream(zip.getEntry("classes.dex")).readBytes())
        }
    }

    @Test
    fun aThinBuildAlsoSignsAndVerifies() {
        val out = Pipeline.run(BuildInput(signedHost(TestSupport.signingKey("H")), emptyList(), emptyList(), TestSupport.signingKey("U"), minSdk, tmp.newFolder("w"), "thin.apk"))
        assertTrue(out.verify.verified)
        ZipFile(out.apk).use { assertEquals(0L, it.getEntry("assets/bundled-addons.tsv").size) }
    }

    @Test
    fun aTamperedOutputDoesNotVerify() {
        val host = signedHost(TestSupport.signingKey("H"))
        val bytes = host.readBytes()
        // Flip a byte inside classes.dex's data, well before the central directory.
        RawZipFile(host).use { z ->
            val dex = z.entries.first { it.name == "classes.dex" }
            bytes[(z.dataOffset(dex) + 5).toInt()] = (bytes[(z.dataOffset(dex) + 5).toInt()].toInt() xor 0x55).toByte()
        }
        val tampered = tmp.newFile("tampered.apk").also { it.writeBytes(bytes) }
        assertFalse(Signing.verify(tampered, minSdk).verified)
    }

    @Test
    fun splitInstallsAreRefusedWithAClearMessage() {
        try {
            Pipeline.run(BuildInput(signedHost(TestSupport.signingKey("H")), listOf("/data/app/split_config.arm64.apk"), emptyList(), TestSupport.signingKey("U"), minSdk, tmp.newFolder("w"), "x.apk"))
            throw AssertionError("accepted")
        } catch (e: BuildException) {
            assertTrue(e.message, e.message!!.contains("split"))
        }
    }

    @Test
    fun aPluginFileIsNotTheHostApk() {
        val cgp = tmp.newFile("plugin.cgp")
        try {
            Pipeline.run(BuildInput(cgp, emptyList(), emptyList(), TestSupport.signingKey("U"), minSdk, tmp.newFolder("w"), "x.apk"))
            throw AssertionError("accepted")
        } catch (e: BuildException) {
            assertTrue(e.message, e.message!!.contains("plugin file"))
        }
    }

    @Test
    fun aMissingHostIsRefused() {
        try {
            Pipeline.run(BuildInput(File(tmp.root, "nope.apk"), emptyList(), emptyList(), TestSupport.signingKey("U"), minSdk, tmp.newFolder("w"), "x.apk"))
            throw AssertionError("accepted")
        } catch (e: BuildException) {
            assertTrue(e.message!!.contains("cannot be read"))
        }
    }

    @Test
    fun cancellationStopsTheBuildAndLeavesNoFiles() {
        val work = tmp.newFolder("w")
        var calls = 0
        try {
            Pipeline.run(
                BuildInput(signedHost(TestSupport.signingKey("H")), emptyList(), addons(), TestSupport.signingKey("U"), minSdk, work, "x.apk"),
                checkCancelled = { if (++calls > 3) throw java.util.concurrent.CancellationException("stop") },
            )
            throw AssertionError("not cancelled")
        } catch (e: java.util.concurrent.CancellationException) {
            // expected
        }
        assertEquals(emptyList<String>(), work.list()!!.toList())
    }

    @Test
    fun spaceNeededCoversTwoCopies() {
        assertTrue(Pipeline.spaceNeeded(100, 10) >= 220)
    }
}
