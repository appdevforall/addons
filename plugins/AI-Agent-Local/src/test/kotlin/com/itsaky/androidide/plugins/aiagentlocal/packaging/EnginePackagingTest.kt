package com.itsaky.androidide.plugins.aiagentlocal.packaging

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * The two prebuilt Part 1 engine programs (`part1-engine/`, built once with the NDK, never rebuilt by Gradle):
 * their bytes are pinned here, and the built `.cgp` must hold exactly those bytes (no strip, no re-alignment that
 * changes them). Gradle passes the paths; unit tests depend on `assemblePluginDebug`, so the `.cgp` is fresh.
 */
class EnginePackagingTest {

    private val libDir = File(System.getProperty("part1EngineLibDir") ?: "src/main/jniLibs/arm64-v8a")
    private val engineDir = File(System.getProperty("part1EngineDir") ?: "part1-engine")
    private val pluginDir = File(System.getProperty("builtPluginDir") ?: "build/plugin")

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val pinned = mapOf(
        "libpart1_membw.so" to "eba453063d98f4da40136572d672a2de5705561d53a7740a5b675dd63fdaf5b6",
        "libpart1_gpubw.so" to "17b03e3529036dc9bce61ea089a0fe8fe6c3ca0535afcee4f43a1bb2ed78d222",
    )

    @Test
    fun givenTheCommittedEnginePrograms_whenHashed_thenTheyHaveThePinnedSha256() {
        for ((name, hash) in pinned) {
            val f = File(libDir, name)
            assertTrue("missing ${f.absolutePath}", f.isFile)
            assertEquals(name, hash, sha256(f.readBytes()))
        }
        assertEquals(pinned.keys, libDir.listFiles { f -> f.name.endsWith(".so") }!!.map { it.name }.toSet())
    }

    @Test
    fun givenTheBuiltDebugCgp_whenItsEngineEntriesAreRead_thenTheyAreByteIdenticalToTheCommittedFiles() {
        val cgp = builtCgp()
        ZipFile(cgp).use { zip ->
            for (name in pinned.keys) {
                val entry = zip.getEntry("lib/arm64-v8a/$name") ?: error("${cgp.name} has no lib/arm64-v8a/$name")
                val packaged = zip.getInputStream(entry).use { it.readBytes() }
                assertArrayEquals(name, File(libDir, name).readBytes(), packaged)
                assertEquals(name, pinned.getValue(name), sha256(packaged))
            }
        }
    }

    @Test
    fun givenTheBuiltDebugCgp_whenItsNativeLibrariesAreListed_thenOnlyArm64V8aIsPackaged() {
        ZipFile(builtCgp()).use { zip ->
            val abis = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("lib/") && it.name.endsWith(".so") }
                .map { it.name.removePrefix("lib/").substringBefore('/') }.toSet()
            assertEquals(setOf("arm64-v8a"), abis)
        }
    }

    @Test
    fun givenThePackagedEngineSource_whenItsVersionIsRead_thenItIsTheExpectedOne() {
        assertEquals("0.2.4", File(engineDir, "VERSION").readText().trim())
        for (doc in listOf("ENGINE.md", "EXIT-CODES.md", "build.sh", "src/membw.c", "src/gpubw/gpubw.c",
            "part1-staticprobe-v1.schema.json", "part1-pinprobe-v1.schema.json", "part1-gpuprobe-v1.schema.json")) {
            assertTrue("part1-engine/$doc is missing", File(engineDir, doc).isFile)
        }
    }

    @Test
    fun givenThePackagedEngineDirectory_whenScanned_thenItHoldsNoLabPathAndNoBinaryOutput() {
        val bad = ArrayList<String>()
        for (f in engineDir.walkTopDown().filter { it.isFile }) {
            val rel = f.relativeTo(engineDir).path
            if (rel.startsWith("history") || rel.startsWith("raw") || rel.startsWith("out")) bad.add("$rel must not be packaged")
            if (f.readText(Charsets.ISO_8859_1).contains("/home/")) bad.add("$rel names a lab path")
        }
        assertEquals(emptyList<String>(), bad)
    }

    private fun builtCgp(): File {
        val cgps = pluginDir.listFiles { f -> f.name.endsWith("-debug.cgp") }.orEmpty()
        // No .cgp is built by the unit-test task itself (see build.gradle.kts); the assemble task's own last action
        // also checks these bytes and fails the build, so a skip here never hides a difference in a shipped build.
        Assume.assumeTrue("no debug .cgp under ${pluginDir.absolutePath}; run assemblePluginDebug first", cgps.isNotEmpty())
        assertEquals("one debug .cgp under ${pluginDir.absolutePath}", 1, cgps.size)
        return cgps[0]
    }
}
