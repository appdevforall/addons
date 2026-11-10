package com.itsaky.androidide.plugins.aiagentlocal.device

import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.D5Like
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level rules of the Part 1 packages: engine, staticread and store (P1-04A1: nothing forbidden, nothing logged) and quick (P1-04A2: the
 * only package the plugin reaches, no network, no identifier in a log line).
 */
class NewCodeRulesTest {

    private val srcRoot = File(System.getProperty("part1MainSrcDir") ?: "src/main/kotlin")
    private val newPackages = listOf("engine", "staticread", "store", "quick")
    private val part1Packages = listOf("engine", "staticread", "store", "quick", "descriptor", "match")
    private val base = "com/itsaky/androidide/plugins/aiagentlocal"

    private fun newFiles(): List<File> =
        newPackages.flatMap { File(srcRoot, "$base/device/$it").walkTopDown().filter { f -> f.isFile && f.name.endsWith(".kt") }.toList() }

    private fun existingFiles(): List<File> =
        srcRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.filter { f ->
            part1Packages.none { p -> f.path.replace('\\', '/').contains("$base/device/$p/") }
        }.toList()

    @Test
    fun givenTheExistingSources_whenScanned_thenOnlyThePluginAndTheBackendReachPart1AndOnlyThroughTheQuickPackage() {
        val existing = existingFiles()
        assertTrue("existing sources found: ${existing.size}", existing.size > 20)
        val inner = Regex("aiagentlocal\\.device\\.(engine|staticread|store|descriptor|match)\\b")
        assertEquals(emptyList<String>(), existing.filter { f -> inner.containsMatchIn(f.readText()) }.map { it.name })
        val quick = Regex("aiagentlocal\\.device\\.quick\\b")
        // the plugin class builds the controller; nothing else outside the device packages names the package
        assertEquals(listOf("LocalLlmPlugin.kt"), existing.filter { f -> quick.containsMatchIn(f.readText()) }.map { it.name }.sorted())
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
        // the quick package logs only through an injected log function; the one class that holds the plugin logger is AndroidQuick.kt
        for (f in newFiles()) for (r in loggers) {
            if (f.name == "AndroidQuick.kt" && r.pattern.contains("logger")) continue
            if (r.containsMatchIn(f.readText())) bad.add("${f.name}: ${r.pattern}")
        }
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun givenTheQuickSources_whenScanned_thenNoNetworkApiIsUsedAndNoLogLineInterpolatesAFingerprintOrKey() {
        val network = listOf("java." + "net.", "android." + "net.", "Http" + "URLConnection", "okhttp", "Sock" + "et(", "URL" + "(", "Retrofit", "WebView")
        val bad = ArrayList<String>()
        val quick = File(srcRoot, "$base/device/quick").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        assertTrue(quick.size >= 6)
        for (f in quick) {
            val text = f.readText()
            for (w in network) if (text.contains(w)) bad.add("${f.name}: $w")
            // no string resource and no log text may carry an identifier either: log( and logger calls
            for ((i, line) in text.lines().withIndex()) {
                if (Regex("\\blog\\(|logger\\.").containsMatchIn(line) && Regex("\\$\\{?[A-Za-z_.()]*([Ff]ingerprint|[Kk]ey)").containsMatchIn(line)) bad.add("${f.name}:${i + 1} logs a fingerprint or key")
            }
        }
        assertEquals(emptyList<String>(), bad)
        // every string resource of Part 1 is free of identifiers and addresses
        val strings = File(srcRoot.parentFile, "res/values/strings.xml").readText()
        assertTrue(!strings.contains("fingerprint", ignoreCase = true) && !strings.contains("device key", ignoreCase = true))
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
