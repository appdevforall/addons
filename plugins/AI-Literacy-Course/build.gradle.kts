import org.jetbrains.kotlin.gradle.dsl.JvmTarget

import com.android.build.gradle.tasks.MergeSourceSetFolders

import java.net.URL
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "ai-literacy-course"
}

android {
    namespace = "org.appdevforall.ailiteracycourse"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.appdevforall.ailiteracycourse"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    androidResources {
        // The course bundle is an already-compressed ZIP (its mp4/pdf/nested-zip
        // payloads are themselves compressed). Storing it uncompressed keeps the
        // .cgp from paying a pointless second deflate pass over ~110 MB.
        noCompress += listOf("zip")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // plugin-api is the IDE-side contract: compile against it, the IDE provides it.
    compileOnly(files("../../libs/plugin-api.jar"))

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // WebViewAssetLoader: serves the extracted course over a virtual https
    // origin so video byte-ranges and the NeuroPocket service worker work.
    implementation("androidx.webkit:webkit:1.11.0")
}

tasks.wrapper {
    gradleVersion = "8.14.3"
    distributionType = Wrapper.DistributionType.BIN
}

// Disable AAR metadata checks that fail under the plugin-builder pipeline
// (same workaround the other examples use).
tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach {
    enabled = false
}

// --- Build-time asset downloads (mirrors ndk-installer) ---------------
//
// Neither the 110 MB course ZIP nor the PDF.js viewer is committed to git. This
// task pulls each from its source and verifies a pinned MD5. scripts/update-libs.sh
// runs `downloadAssets` automatically before assemblePlugin for any plugin that
// declares it.

fun openHttpConnection(url: String, readTimeoutMs: Int): HttpURLConnection {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = true
    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
    connection.connectTimeout = 15000
    connection.readTimeout = readTimeoutMs

    val code = connection.responseCode
    if (code != 200) {
        throw GradleException("Failed to download $url (HTTP $code)")
    }
    return connection
}

fun httpDownloadToFile(url: String, target: File): Long {
    val connection = openHttpConnection(url, readTimeoutMs = 300_000)
    val expectedLength = connection.contentLengthLong
    var receivedLength = 0L

    connection.inputStream.use { input ->
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                receivedLength += read
            }
        }
    }

    // Drive serves this file chunked (no Content-Length), so expectedLength is
    // typically -1 here; the MD5 check below is the real integrity gate.
    if (expectedLength >= 0 && receivedLength != expectedLength) {
        throw GradleException(
            "Truncated download from $url: expected $expectedLength bytes, got $receivedLength"
        )
    }
    return receivedLength
}

/**
 * Copy [src] zip to [dest], dropping `*.map` source-map entries. The official
 * PDF.js dist ships ~9 MB of source maps that point at TypeScript we don't bundle
 * — dead weight inside the .cgp and on-device. Everything else is passed through
 * verbatim, so [dest] keeps the dist's `build/` + `web/` root layout that
 * CourseInstaller extracts.
 */
fun repackWithoutMaps(src: File, dest: File) {
    ZipInputStream(src.inputStream().buffered()).use { zin ->
        ZipOutputStream(dest.outputStream().buffered()).use { zout ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.isDirectory || entry.name.endsWith(".map")) continue
                zout.putNextEntry(ZipEntry(entry.name))
                zin.copyTo(zout)
                zout.closeEntry()
            }
        }
    }
}

fun md5Of(file: File): String {
    val md = MessageDigest.getInstance("MD5")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            md.update(buffer, 0, read)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Download [url] to [target], retrying with backoff, and fail the build unless
 * the bytes hash to [expectedMd5]. The MD5 gate is the real integrity check:
 * it also catches a host serving an HTML interstitial/quota page instead of the
 * file (Google Drive does this), since that won't match the pinned hash.
 */
fun org.gradle.api.Task.downloadVerified(
    url: String,
    target: File,
    expectedMd5: String,
    label: String,
    maxAttempts: Int = 3,
) {
    var lastError: String? = null
    for (attempt in 1..maxAttempts) {
        logger.lifecycle("Downloading $label (attempt $attempt/$maxAttempts)...")
        try {
            val bytes = httpDownloadToFile(url, target)
            val actualMd5 = md5Of(target).lowercase()
            logger.info("Downloaded $bytes bytes; expected MD5=$expectedMd5 actual=$actualMd5")
            if (expectedMd5 == actualMd5) return
            lastError = "MD5 mismatch (expected=$expectedMd5, actual=$actualMd5)."
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
        }
        logger.warn("Attempt $attempt failed: $lastError")
        target.delete()
        if (attempt < maxAttempts) {
            val backoffMs = 2_000L * attempt
            logger.lifecycle("Retrying in ${backoffMs}ms...")
            Thread.sleep(backoffMs)
        }
    }
    throw GradleException("Failed to download $label after $maxAttempts attempts: $lastError")
}

val downloadCourse by tasks.registering {
    val courseArchive = project.file("src/main/assets/ai-literacy-course.zip")
    outputs.files(courseArchive)

    doLast {
        courseArchive.parentFile.mkdirs()
        // Google Drive large-file (>100 MB) endpoint. A plain share/uc URL returns
        // an HTML "can't scan for viruses" interstitial instead of bytes; this
        // endpoint + confirm=t serves the file directly. MD5 from the LAIA-provided
        // zip (110 MB -> 119 MB extracted).
        val fileId = "1zRTVzgzZwVCtij55JnFTLtPFwsSlZo-t"
        val url = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
        downloadVerified(
            url, courseArchive,
            expectedMd5 = "c2785f29c12056bd54b8cc885355ecc9",
            label = "AI Literacy course bundle",
        )
    }
}

val downloadPdfjs by tasks.registering {
    val pdfjsArchive = project.file("src/main/assets/pdfjs.zip")
    outputs.files(pdfjsArchive)

    doLast {
        pdfjsArchive.parentFile.mkdirs()
        // A bare WebView can't render application/pdf, so the course shell links
        // each PDF through this bundled viewer. Pin the modern (.mjs) dist, verify
        // its MD5, then strip ~9 MB of source maps into pdfjs.zip. The dist's
        // build/ + web/ root layout is what CourseInstaller extracts.
        val version = "4.8.69"
        val url =
            "https://github.com/mozilla/pdf.js/releases/download/v$version/pdfjs-$version-dist.zip"
        val download = File.createTempFile("pdfjs-dist", ".zip")
        try {
            downloadVerified(
                url, download,
                expectedMd5 = "772b68fe1f36e3df7c0827bb4f3e1cec",
                label = "PDF.js $version dist",
            )
            repackWithoutMaps(download, pdfjsArchive)
        } finally {
            download.delete()
        }
    }
}

// Aggregator: scripts/update-libs.sh runs `downloadAssets` before assemblePlugin.
val downloadAssets by tasks.registering {
    dependsOn(downloadCourse, downloadPdfjs)
}

// Neither archive is committed, so fail loudly instead of packaging a course
// .cgp with no lessons and no PDF viewer. Matched by type, not by name: a name
// filter that stops matching would silently unwire this guard.
val downloadedAssets = listOf("ai-literacy-course.zip", "pdfjs.zip").map {
    project.file("src/main/assets/$it")
}
tasks.withType<MergeSourceSetFolders>().configureEach {
    doFirst {
        val missing = downloadedAssets.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing ${missing.joinToString { it.name }} under src/main/assets/. They are " +
                    "fetched at build time and never committed, so run './gradlew downloadAssets' " +
                    "before assembling."
            )
        }
    }
}
