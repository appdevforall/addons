package com.itsaky.androidide.plugins.aiagentlocal.device

import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.D5Like
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level rules of the engine, staticread and store packages (P1-04A1): not wired in, nothing forbidden, nothing logged. */
class NewCodeRulesTest {

    private val srcRoot = File(System.getProperty("part1MainSrcDir") ?: "src/main/kotlin")
    private val newPackages = listOf("engine", "staticread", "store")
    private val base = "com/itsaky/androidide/plugins/aiagentlocal"

    private fun newFiles(): List<File> =
        newPackages.flatMap { File(srcRoot, "$base/device/$it").walkTopDown().filter { f -> f.isFile && f.name.endsWith(".kt") }.toList() }

    private fun existingFiles(): List<File> =
        srcRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.filter { f ->
            newPackages.none { p -> f.path.replace('\\', '/').contains("$base/device/$p/") }
        }.toList()

    @Test
    fun givenTheExistingSources_whenScanned_thenNoneReferencesTheNewPackages() {
        val existing = existingFiles()
        assertTrue("existing sources found: ${existing.size}", existing.size > 20)
        val pattern = Regex("aiagentlocal\\.device\\.(engine|staticread|store)\\b")
        val bad = existing.filter { f -> pattern.containsMatchIn(f.readText()) }.map { it.name }
        assertEquals(emptyList<String>(), bad)
        // the plugin class itself does not mention them either
        assertTrue(existing.any { it.name == "LocalLlmPlugin.kt" })
    }

    @Test
    fun givenTheNewSources_whenScanned_thenNoForbiddenApiOrFieldAppears() {
        // names are assembled from parts so that this file does not hold them as words either
        val forbidden = listOf(
            "Build.get" + "Serial", "Build." + "SERIAL", "ANDROID" + "_ID", "get" + "Imei", "get" + "DeviceId", "get" + "Meid", "Wifi" + "Manager",
            "get" + "MacAddress", "Account" + "Manager", "get" + "HostAddress", "Network" + "Interface", "getSubscriberId", "SIM" + "_SERIAL",
            "getLine1Number", "Telephony" + "Manager", "Settings" + ".Secure", "Advertising" + "Id", "getExternalStorage", "getExternalFilesDir",
        )
        val files = newFiles()
        assertTrue(files.size >= 14)
        val bad = ArrayList<String>()
        for (f in files) for (w in forbidden) if (f.readText().contains(w)) bad.add("${f.name}: $w")
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun givenTheNewSources_whenScanned_thenNothingIsLoggedOrPrinted() {
        val bad = ArrayList<String>()
        val loggers = listOf(Regex("\\bLog\\.[a-z]\\("), Regex("println\\("), Regex("\\bprint\\("), Regex("Timber"), Regex("\\blogger\\b", RegexOption.IGNORE_CASE), Regex("System\\.(out|err)"), Regex("printStackTrace"), Regex("android\\.util\\.Log"))
        for (f in newFiles()) for (r in loggers) if (r.containsMatchIn(f.readText())) bad.add("${f.name}: ${r.pattern}")
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun givenTheFingerprint_whenTheResultsArePrinted_thenItNeverAppears() {
        val r = D5Like().reader().read()
        val fp = "synthetic/d5-like"
        assertTrue(!r.toString().contains(fp))
        assertTrue(!r.conditions.toString().contains(fp))
    }

    @Test
    fun givenTheProducedStaticObjects_whenScannedForForbiddenKeys_thenNoneAppears() {
        val forbiddenKeys = setOf("serial", "serialno", "android_id", "imei", "meid", "account", "accounts", "ip", "ip_address", "mac", "mac_address", "wifi", "ssid", "model_name", "prompt", "project", "path")
        val phones = listOf(D5Like(), D5Like().also { it.files.files.clear() })
        val seen = HashSet<String>()
        for (p in phones) {
            fun walk(x: Any?) {
                when (x) {
                    is Map<*, *> -> x.forEach { (k, v) -> seen.add((k as String).lowercase()); walk(v) }
                    is List<*> -> x.forEach { walk(it) }
                }
            }
            walk(p.reader().read().static)
        }
        assertEquals(emptySet<String>(), seen.intersect(forbiddenKeys))
        assertTrue(seen.containsAll(listOf("identity", "cpus", "build_fingerprint")))
    }
}
