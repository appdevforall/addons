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
    fun givenTheStringResources_whenScanned_thenNoStringPromisesThatResultsStayOnThePhone() {
        // addendum 1 and R-F08: users who opt in to telemetry will later contribute results, so no text may promise that
        val strings = File(System.getProperty("part1MainResDir") ?: "src/main/res").walkTopDown().filter { it.isFile && it.name == "strings.xml" }.toList()
        assertTrue("strings.xml found", strings.isNotEmpty())
        assertEquals(emptyList<String>(), promisingStrings(strings.map { it.readText() }))
    }

    private fun res(body: String) = "<resources>$body</resources>"

    @Test
    fun givenStringsThatPromiseThatResultsStayOnThePhone_whenScanned_thenEveryAlternativeOfEveryPatternIsFound() {
        // the scan itself must be able to fail; every alternative of every pattern is used at least once
        val promises = listOf(
            "It leaves the phone.", "It leaves your device.", "It leaves this phone.", "It will leave the device.", "Does not leave your phone.", "Does not leave this device.",
            "Your results stay on this phone.", "Your results stays on the device.", "Results remain on your phone.", "The data remains on this device.",
            "Data is kept on the device.", "It is stored only on your phone.", "Results stay on the phone.", "Results stay on your device.",
            "Never sent anywhere", "Never shared.", "Never uploaded.", "never SENT",
            "Nothing is sent.", "Nothing is shared.", "NOTHING IS UPLOADED",
        )
        for (text in promises) assertEquals(text, 1, promisingStrings(listOf(res("""<string name="x">$text</string>"""))).size)
        assertEquals(emptyList<String>(), promisingStrings(listOf(res("""<string name="x">Measure this device for local AI.</string>"""))))
    }

    @Test
    fun givenAPromiseInAStringArrayItem_whenScanned_thenItIsFoundUnderTheNameOfTheArray() {
        val hits = promisingStrings(listOf(res("""<string-array name="tips"><item>Fine.</item><item>Your results stay on this phone.</item></string-array>""")))
        assertEquals(listOf("tips: Your results stay on this phone."), hits)
    }

    @Test
    fun givenAPromiseInAPluralsItem_whenScanned_thenItIsFoundUnderTheNameOfThePlurals() {
        val hits = promisingStrings(listOf(res("""<plurals name="rows"><item quantity="one">One row stays on the device.</item><item quantity="other">Rows are fine.</item></plurals>""")))
        assertEquals(listOf("rows: One row stays on the device."), hits)
    }

    @Test
    fun givenAPromiseInAStringWhoseNameIsNotTheFirstAttribute_whenScanned_thenItIsFound() {
        assertEquals(1, promisingStrings(listOf(res("""<string translatable="false" name="x">Your results stay on this phone.</string>"""))).size)
        assertEquals(1, promisingStrings(listOf(res("""<string formatted="false" translatable="false" name="y">Nothing is shared.</string>"""))).size)
    }

    @Test
    fun givenAPromiseWrappedOverTwoLines_whenScanned_thenItIsFound() {
        assertEquals(1, promisingStrings(listOf(res("<string name=\"x\">Your results stay\n        on this phone.</string>"))).size)
        assertEquals(1, promisingStrings(listOf(res("<string name=\"y\">Nothing   is\n\tshared.</string>"))).size)
        assertEquals(1, promisingStrings(listOf(res("<string name=\"z\"><![CDATA[It leaves\nyour phone]]></string>"))).size)
    }

    private val promisePatterns = listOf(
        "leaves? (the|your|this) (phone|device)",
        "(stay|stays|remain|remains|kept|stored only) on (the|your|this) (phone|device)",
        "never (sent|shared|uploaded)",
        "nothing is (sent|shared|uploaded)",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    /** `name: text` of every `string` and of every `item` of a `string-array` or `plurals` that matches a promise pattern (case-insensitive), except the names in [excludedFromPromiseScan]. */
    private fun promisingStrings(files: List<String>): List<String> {
        val hits = ArrayList<String>()
        for (xml in files) {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
            val doc = factory.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
            val elements = doc.getElementsByTagName("*")
            for (i in 0 until elements.length) {
                val e = elements.item(i) as org.w3c.dom.Element
                val owner = when (e.tagName) {
                    "string" -> e
                    "item" -> (e.parentNode as? org.w3c.dom.Element)?.takeIf { it.tagName == "string-array" || it.tagName == "plurals" } ?: continue
                    else -> continue
                }
                val name = owner.getAttribute("name")
                if (name in excludedFromPromiseScan) continue
                val text = e.textContent.replace(Regex("""\s+"""), " ").trim() // Android collapses whitespace runs
                if (promisePatterns.any { it.containsMatchIn(text) }) hits.add("$name: $text")
            }
        }
        return hits
    }

    /** Strings of the plugin outside Part 1 that match a pattern and are not changed by this job: excluded by resource name, with the reason. */
    private val excludedFromPromiseScan: Set<String> = emptySet()

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
