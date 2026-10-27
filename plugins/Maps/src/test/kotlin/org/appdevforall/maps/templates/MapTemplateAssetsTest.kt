package org.appdevforall.maps.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Asserts the unified region-map template ships BOTH the Kotlin and Java source
 * variants (so `showLanguageOption()` can scaffold either), reads the bundled
 * region from the fixed flat layout, and no longer carries the old
 * `templates/region/` apply-emitter assets or the demo MainActivity.
 *
 * Reads template assets straight off disk (src/main/assets/), the same source the
 * plugin AssetManager serves at runtime.
 */
class MapTemplateAssetsTest {

    private val templateRoot = File("src/main/assets/templates/region-map")

    private fun asset(rel: String) = File(templateRoot, rel)

    @Test
    fun `template ships MapRegionActivity in both kotlin and java`() {
        val base = "app/src/main/java/PACKAGE_NAME"
        assertTrue("Kotlin MapRegionActivity missing", asset("$base/MapRegionActivity.kt.peb").exists())
        assertTrue("Java MapRegionActivity missing", asset("$base/MapRegionActivity.java.peb").exists())
    }

    @Test
    fun `template ships PmtilesHttpServer in both kotlin and java`() {
        val base = "app/src/main/java/PACKAGE_NAME"
        assertTrue("Kotlin PmtilesHttpServer missing", asset("$base/PmtilesHttpServer.kt.peb").exists())
        assertTrue("Java PmtilesHttpServer missing", asset("$base/PmtilesHttpServer.java.peb").exists())
    }

    @Test
    fun `both source variants use the CGT package delimiter`() {
        val base = "app/src/main/java/PACKAGE_NAME"
        // CGT/Pebble runtime delimiter is `${'$'}{{PACKAGE_NAME}}`, not the plain
        // `{{PACKAGE_NAME}}` token the old apply-emitter used.
        for (f in listOf(
            "MapRegionActivity.kt.peb",
            "MapRegionActivity.java.peb",
            "PmtilesHttpServer.kt.peb",
            "PmtilesHttpServer.java.peb",
        )) {
            val text = asset("$base/$f").readText()
            assertTrue("$f should use the CGT \${{PACKAGE_NAME}} delimiter", text.contains("\${{PACKAGE_NAME}}"))
            assertFalse(
                "$f should NOT use the bare {{PACKAGE_NAME}} token",
                text.contains(Regex("(?<!\\$)\\{\\{PACKAGE_NAME}}")),
            )
        }
    }

    @Test
    fun `activity reads the fixed flat region layout`() {
        val kt = asset("app/src/main/java/PACKAGE_NAME/MapRegionActivity.kt.peb").readText()
        // Fixed flat paths — NOT a per-region subdir, NOT active.txt scanning.
        assertTrue(kt.contains("maps/tiles.pmtiles"))
        assertTrue(kt.contains("maps/style.json"))
        assertTrue(kt.contains("maps/meta.json"))
        assertFalse("no active.txt scanning in the emitted app", kt.contains("active.txt"))
        assertFalse("no region-id.txt scanning in the emitted app", kt.contains("region-id.txt"))
        assertFalse("no findActiveRegionId in the emitted app", kt.contains("findActiveRegionId"))
    }

    @Test
    fun `style uses loopback PMTILES placeholders and ships flat`() {
        val style = asset("app/src/main/assets/maps/style.json").readText()
        assertTrue(style.contains("PMTILES_URL_TILES"))
        assertTrue(style.contains("PMTILES_URL_BASEMAP"))
        // asset:// is unreliable in the emitted app — must not be the recommended scheme.
        assertFalse("style must not use pmtiles://asset:///", style.contains("pmtiles://asset://"))
    }

    @Test
    fun `manifest launches MapRegionActivity with netsec config`() {
        val manifest = asset("app/src/main/AndroidManifest.xml.peb").readText()
        assertTrue(manifest.contains("android:name=\".MapRegionActivity\""))
        assertTrue(manifest.contains("networkSecurityConfig"))
        assertTrue(manifest.contains("android.permission.INTERNET"))
        assertTrue(manifest.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertFalse("old MainActivity launcher should be gone", manifest.contains(".MainActivity"))
    }

    @Test
    fun `build gradle pins minSdk 23 and noCompress pmtiles`() {
        val gradle = asset("app/build.gradle.kts.peb").readText()
        assertTrue(gradle.contains("noCompress.add(\"pmtiles\")"))
        assertTrue(gradle.contains("minSdk = 23"))
        assertTrue(gradle.contains("org.maplibre.gl:android-sdk-opengl:13.1.0"))
    }

    @Test
    fun `build gradle keeps glyph fonts uncompressed`() {
        // The loopback server seeks fonts via AssetManager.openFd too, so .pbf
        // glyphs must stay uncompressed like the pmtiles.
        val gradle = asset("app/build.gradle.kts.peb").readText()
        assertTrue(gradle.contains("noCompress.add(\"pbf\")"))
    }

    @Test
    fun `style declares glyphs and label layers`() {
        val style = asset("app/src/main/assets/maps/style.json").readText()
        // Labels need a glyphs endpoint + the runtime substitution placeholder.
        assertTrue("style must declare glyphs", style.contains("\"glyphs\""))
        assertTrue("style must carry the GLYPHS_URL placeholder", style.contains("GLYPHS_URL"))
        // Symbol layers for place names + street names against the OSM schema.
        assertTrue("place label layer missing", style.contains("\"source-layer\": \"place\""))
        assertTrue(
            "street label layer missing",
            style.contains("\"source-layer\": \"transportation_name\""),
        )
        assertTrue("symbol layer missing", style.contains("\"type\": \"symbol\""))
        // Font stack matches the bundled, no-space font dir.
        assertTrue("text-font must reference notosans", style.contains("notosans"))
    }

    @Test
    fun `template ships latin glyph fonts under notosans`() {
        val base = "app/src/main/assets/maps/fonts/notosans"
        for (range in listOf("0-255", "256-511", "512-767", "7680-7935", "8192-8447")) {
            assertTrue("missing glyph range $range", asset("$base/$range.pbf").exists())
        }
    }

    @Test
    fun `template cgt filename matches the host's name sanitization for unregister`() {
        // deactivate() unregisters by this exact filename; it must match what the host
        // (CgtTemplateBuilder.DIR_NAME_REGEX = [^\p{L}\p{N}]) derives from the name.
        assertEquals("OfflineOSMMap.cgt", MapTemplateBuilder.REGION_MAP_TEMPLATE_FILE)
    }

    @Test
    fun `template emits the OFL license next to the bundled fonts`() {
        // OFL §2: the license + copyright must ship WITH the fonts in the emitted app.
        val ofl = asset("app/src/main/assets/maps/fonts/notosans/OFL.txt")
        assertTrue("OFL.txt must ship beside the glyph fonts", ofl.exists())
        val text = ofl.readText()
        assertTrue("OFL.txt must be the SIL OFL", text.contains("SIL OPEN FONT LICENSE", ignoreCase = true))
        assertTrue("OFL.txt must carry the Noto copyright", text.contains("Noto Project Authors"))
    }

    @Test
    fun `style json stays valid JSON after the label-layer additions`() {
        // A hand-edited symbol-layer block is trailing-comma-prone; parse it for real
        // so a malformed edit fails the build, not silently at runtime on-device.
        val style = asset("app/src/main/assets/maps/style.json").readText()
        // org.json is on the test classpath (used by the emitted activity too).
        val root = org.json.JSONObject(style)
        assertTrue("style must have a layers array", root.has("layers"))
        assertTrue("style must declare glyphs", root.has("glyphs"))
        val layers = root.getJSONArray("layers")
        var symbolLayers = 0
        for (i in 0 until layers.length()) {
            if (layers.getJSONObject(i).optString("type") == "symbol") symbolLayers++
        }
        assertTrue("expected place/street/water label layers", symbolLayers >= 3)
    }

    @Test
    fun `both activities substitute GLYPHS_URL off the loopback server`() {
        val base = "app/src/main/java/PACKAGE_NAME"
        for (f in listOf("MapRegionActivity.kt.peb", "MapRegionActivity.java.peb")) {
            val text = asset("$base/$f").readText()
            assertTrue("$f must substitute GLYPHS_URL", text.contains("GLYPHS_URL"))
            assertTrue("$f must serve fonts off the loopback", text.contains("/maps/fonts/{fontstack}/{range}.pbf"))
        }
    }

    @Test
    fun `both activities center on location else fit the whole bbox`() {
        val base = "app/src/main/java/PACKAGE_NAME"
        for (f in listOf("MapRegionActivity.kt.peb", "MapRegionActivity.java.peb")) {
            val text = asset("$base/$f").readText()
            assertTrue("$f must use last-known location", text.contains("lastKnownLatLng"))
            assertTrue("$f must fit the bbox bounds", text.contains("readBboxBounds"))
            assertTrue("$f must frame bounds via newLatLngBounds", text.contains("newLatLngBounds"))
            // The old geometric-center default is gone.
            assertFalse("$f should not center on the bbox geometric center", text.contains("readBboxCenter"))
        }
    }

    @Test
    fun `both activities draw the region bbox outline`() {
        val base = "app/src/main/java/PACKAGE_NAME"
        for (f in listOf("MapRegionActivity.kt.peb", "MapRegionActivity.java.peb")) {
            val text = asset("$base/$f").readText()
            assertTrue("$f must draw the bbox outline", text.contains("addBboxOutline"))
            assertTrue("$f bbox outline must be a LineLayer", text.contains("LineLayer"))
        }
    }

    @Test
    fun `template ships a thumbnail icon for the New-Project grid`() {
        // Without a thumbnail the New-Project tile renders blank (CgtTemplateBuilder
        // thumbnailFromAssets). 512x512 PNG beside the template dir.
        assertTrue("template thumb.png missing", asset("thumb.png").exists())
    }

    @Test
    fun `template bundles the offline maplibre maven repo with maplibre vendored`() {
        // The project-local repo travels with the generated project so the app
        // builds with zero network on an offline device (E13). It must contain
        // MapLibre's AAR + a manifest enumerating every vendored file.
        val repo = File(templateRoot, "offline-maplibre-repo")
        assertTrue("offline-maplibre-repo missing", repo.isDirectory)
        val maplibreAar = File(
            repo,
            "org/maplibre/gl/android-sdk-opengl/13.1.0/android-sdk-opengl-13.1.0.aar",
        )
        assertTrue("MapLibre AAR not vendored", maplibreAar.exists())
        val manifest = File(repo, "repo-manifest.txt")
        assertTrue("repo-manifest.txt missing", manifest.exists())
        val lines = manifest.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue("manifest must list the MapLibre AAR",
            lines.any { it.endsWith("android-sdk-opengl-13.1.0.aar") })
        // Every manifest path must point at a real file on disk.
        for (rel in lines) {
            assertTrue("manifest lists missing file: $rel", File(repo, rel).exists())
        }
    }

    private val maplibreDir =
        File(templateRoot, "offline-maplibre-repo/org/maplibre/gl/android-sdk-opengl/13.1.0")

    @Test
    fun `vendored maplibre aar carries the two ABIs Code on the Go runs on`() {
        // x86 and x86_64 are removed from the upstream AAR because no phone CoGo runs on uses them.
        val abis = mutableSetOf<String>()
        java.util.zip.ZipFile(File(maplibreDir, "android-sdk-opengl-13.1.0.aar")).use { zf ->
            val e = zf.entries()
            while (e.hasMoreElements()) {
                val name = e.nextElement().name
                Regex("^jni/([^/]+)/").find(name)?.let { abis.add(it.groupValues[1]) }
            }
        }
        assertEquals("AAR native libs", setOf("arm64-v8a", "armeabi-v7a"), abis)
    }

    @Test
    fun `maplibre module metadata matches the vendored aar`() {
        // A re-stripped AAR must update the size and every digest in every file entry, or the
        // .module describes a file that is no longer there.
        val aar = File(maplibreDir, "android-sdk-opengl-13.1.0.aar")
        val module = File(maplibreDir, "android-sdk-opengl-13.1.0.module").readText()
        val bytes = aar.readBytes()
        val entries = Regex("\"size\": (\\d+)").findAll(module).map { it.groupValues[1] }.toList()
        assertTrue("module lists no files", entries.isNotEmpty())
        assertEquals("module size", entries.map { aar.length().toString() }, entries)
        val algorithms = mapOf("md5" to "MD5", "sha1" to "SHA-1", "sha256" to "SHA-256", "sha512" to "SHA-512")
        for ((key, algorithm) in algorithms) {
            val recorded = Regex("\"$key\": \"([^\"]*)\"").findAll(module).map { it.groupValues[1] }.toList()
            // This .module carries no sha512; check it only once one is recorded.
            if (key == "sha512" && recorded.isEmpty()) continue
            val actual = java.security.MessageDigest.getInstance(algorithm)
                .digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals("module $key", entries.map { actual }, recorded)
        }
    }

    @Test
    fun `settings declares the project-local offline repo`() {
        val settings = asset("settings.gradle.kts.peb").readText()
        assertTrue(
            "settings must declare the offline-maplibre-repo maven repo",
            settings.contains("offline-maplibre-repo"),
        )
    }

    @Test
    fun `build gradle aligns deps to the bundled offline repo and packages both ARM ABIs`() {
        val gradle = asset("app/build.gradle.kts.peb").readText()
        // Aligned to the bundled offline repo's versions (E12 enumeration).
        assertTrue("material must align to bundled 1.9.0",
            gradle.contains("com.google.android.material:material:1.9.0"))
        assertTrue("coordinatorlayout must align to bundled 1.1.0",
            gradle.contains("androidx.coordinatorlayout:coordinatorlayout:1.1.0"))
        assertFalse("must not pin the un-bundled material 1.10.0",
            gradle.contains("material:1.10.0"))
        assertFalse("must not pin the un-bundled coordinatorlayout 1.2.0",
            gradle.contains("coordinatorlayout:1.2.0"))
        // The ABI filter matches the native libs the vendored AAR carries.
        assertTrue("must package arm64-v8a", gradle.contains("abiFilters.add(\"arm64-v8a\")"))
        assertTrue("must package armeabi-v7a", gradle.contains("abiFilters.add(\"armeabi-v7a\")"))
    }

    /** Every version string a `com.android.application` plugin request names in [text]. */
    private fun agpVersions(text: String): List<String> =
        Regex("""id\("com\.android\.application"\)[^\n]*?version "([^"]+)"""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()

    @Test
    fun `root and app modules request the host's AGP version, not a hardcoded one`() {
        // A hardcoded "8.11.0" in the root broke sync once the host bundle moved to AGP 9.3.1.
        val root = agpVersions(asset("build.gradle.kts.peb").readText())
        val app = agpVersions(asset("app/build.gradle.kts.peb").readText())
        assertEquals("root AGP request", listOf("\${{AGP_VERSION}}"), root)
        assertEquals("app AGP request", listOf("\${{AGP_VERSION}}"), app)
    }

    @Test
    fun `root puts the host's Kotlin Gradle plugin on the buildscript classpath`() {
        // AGP 9's built-in Kotlin compiles with whatever kotlin-gradle-plugin is on the classpath.
        val root = asset("build.gradle.kts.peb").readText()
        assertTrue(
            "root must declare the Kotlin Gradle plugin classpath",
            root.contains("classpath(\"org.jetbrains.kotlin:kotlin-gradle-plugin:\${{KOTLIN_VERSION}}\")"),
        )
    }

    @Test
    fun `app module uses no construct AGP 9 removed`() {
        val gradle = asset("app/build.gradle.kts.peb").readText()
        assertFalse("kotlin(\"android\") fails to apply under AGP 9", gradle.contains("kotlin(\"android\")"))
        assertFalse("org.jetbrains.kotlin.android fails to apply under AGP 9", gradle.contains("org.jetbrains.kotlin.android"))
        assertFalse("kotlinOptions {} was removed in AGP 9", gradle.contains("kotlinOptions"))
    }

    @Test
    fun `kotlin variant configures built-in Kotlin and keeps the metadata-version skip`() {
        val gradle = asset("app/build.gradle.kts.peb").readText()
        val kotlinBlock = gradle.substringAfter("kotlin {", missingDelimiterValue = "")
        assertTrue("app must configure kotlin { compilerOptions }", kotlinBlock.contains("compilerOptions"))
        assertTrue("jvmTarget must follow the host", kotlinBlock.contains("JvmTarget.fromTarget(\"\${{JAVA_TARGET}}\")"))
        assertTrue("MapLibre's newer Kotlin metadata needs the skip flag", kotlinBlock.contains("-Xskip-metadata-version-check"))
    }

    @Test
    fun `core-ktx is raised to a version that ships as an AAR`() {
        // appcompat 1.6.1 asks for core-ktx 1.2.0; the host bundle carries only its POM and is
        // searched first, so the build fails. 1.9.0 ships as an AAR in the bundle and here.
        val gradle = asset("app/build.gradle.kts.peb").readText()
        assertTrue("core-ktx must be raised to 1.9.0", gradle.contains("\"androidx.core:core-ktx:1.9.0\""))
        assertTrue(
            "core-ktx 1.9.0 AAR must be vendored",
            File(templateRoot, "offline-maplibre-repo/androidx/core/core-ktx/1.9.0/core-ktx-1.9.0.aar").exists(),
        )
    }

    @Test
    fun `generated app docs say the app runs on both 64-bit and 32-bit ARM`() {
        val docs = listOf(
            asset("README.md.peb"),
            File("src/main/assets/docs/osm-tutorial.html"),
        )
        for (doc in docs) {
            // Collapse whitespace so a re-wrapped line in the HTML does not hide the phrase.
            val text = doc.readText().replace(Regex("\\s+"), " ")
            assertTrue("${doc.name} must say 64-bit and 32-bit ARM", text.contains("64-bit and 32-bit ARM phones"))
            assertTrue("${doc.name} must name arm64-v8a", text.contains("arm64-v8a"))
            assertTrue("${doc.name} must name armeabi-v7a", text.contains("armeabi-v7a"))
            assertFalse(
                "${doc.name} still says 64-bit ARM only",
                text.contains(Regex("64-bit ARM[^.]*\\bonly\\b", RegexOption.IGNORE_CASE)),
            )
            assertFalse("${doc.name} still says it will not install on 32-bit", text.contains("not install on a 32-bit"))
        }
    }

    private val repoManifest =
        File(templateRoot, "offline-maplibre-repo/repo-manifest.txt").readLines().map { it.trim() }.toSet()

    /** The files a generated Kotlin or Java project resolved from the vendored repo, offline. */
    private val resolvedFromRepo =
        javaClass.getResource("/templates/vendored-repo-resolved-files.txt")!!.readText()
            .lines().filter { it.isNotBlank() && !it.startsWith("#") }

    @Test
    fun `every file a generated project resolves from the vendored repo ships with the template`() {
        val missing = resolvedFromRepo.filterNot {
            it in repoManifest && File(templateRoot, "offline-maplibre-repo/$it").isFile
        }
        assertEquals("resolved but not shipped (the manifest decides what is emitted)", emptyList<String>(), missing)
    }

    @Test
    fun `the vendored repo carries only the kotlin-stdlib jars a generated project resolves`() {
        val stdlib = Regex("""^org/jetbrains/kotlin/kotlin-stdlib[^/]*/.*\.jar$""")
        assertEquals(
            resolvedFromRepo.filter { stdlib.matches(it) }.toSet(),
            repoManifest.filter { stdlib.matches(it) }.toSet(),
        )
    }

    @Test
    fun `old MainActivity and region template assets are gone`() {
        val base = "app/src/main/java/PACKAGE_NAME"
        assertFalse(asset("$base/MainActivity.kt.peb").exists())
        assertFalse(asset("$base/MainActivity.java.peb").exists())
        assertFalse(asset("app/src/main/res/layout/activity_main.xml").exists())
        assertFalse("demo-server style.json should be gone", asset("app/src/main/assets/style.json").exists())
        assertFalse(
            "templates/region/ should be deleted",
            File("src/main/assets/templates/region").exists(),
        )
    }
}
