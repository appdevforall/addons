import org.jetbrains.kotlin.gradle.dsl.JvmTarget

import java.net.URL
import java.security.MessageDigest
import java.net.HttpURLConnection


plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "ndk-installer"
}

android {
    namespace = "org.appdevforall.ndkinstaller"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.appdevforall.ndkinstaller"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    androidResources {
        noCompress += listOf("xz")
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
    compileOnly(files("../../libs/plugin-api.jar"))


    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

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
    val connection = openHttpConnection(url, readTimeoutMs = 120_000)
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

    if (expectedLength >= 0 && receivedLength != expectedLength) {
        throw GradleException(
            "Truncated download from $url: expected $expectedLength bytes, got $receivedLength"
        )
    }
    return receivedLength
}

fun httpDownloadText(url: String): String {
    val connection = openHttpConnection(url, readTimeoutMs = 30_000)
    return connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
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

val downloadAssets by tasks.registering {
    val assetsDir = project.file("src/main/assets")
    val archiveFile = assetsDir.resolve("ndk-cmake.tar.xz")

    outputs.files(archiveFile)

    doLast {
        assetsDir.mkdirs()

        val archiveUrl = "https://www.appdevforall.org/dev-assets/release/v8/ndk-cmake.tar.xz"
        val md5Url = "https://www.appdevforall.org/dev-assets/release/v8/ndk-cmake.tar.xz.md5"
        val maxAttempts = 3

        var lastError: String? = null
        for (attempt in 1..maxAttempts) {
            logger.lifecycle("Downloading ndk-cmake assets (attempt $attempt/$maxAttempts)...")
            try {
                val expectedMd5 = httpDownloadText(md5Url).trim().lowercase()
                val bytes = httpDownloadToFile(archiveUrl, archiveFile)
                val actualMd5 = md5Of(archiveFile).lowercase()

                logger.info("Downloaded $bytes bytes")
                logger.info("Expected MD5: $expectedMd5")
                logger.info("Actual MD5:   $actualMd5")

                if (expectedMd5 == actualMd5) {
                    return@doLast
                }
                lastError = "MD5 mismatch (expected=$expectedMd5, actual=$actualMd5)"
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }

            logger.warn("Attempt $attempt failed: $lastError")
            archiveFile.delete()
            if (attempt < maxAttempts) {
                val backoffMs = 2_000L * attempt
                logger.lifecycle("Retrying in ${backoffMs}ms...")
                Thread.sleep(backoffMs)
            }
        }

        throw GradleException(
            "Failed to download ndk-cmake.tar.xz after $maxAttempts attempts: $lastError"
        )
    }
}

// The archive is never committed, so fail loudly instead of packaging an NDK-less .cgp.
val ndkArchiveFile = project.file("src/main/assets/ndk-cmake.tar.xz")
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    doFirst {
        if (!ndkArchiveFile.isFile) {
            throw GradleException(
                "Missing src/main/assets/ndk-cmake.tar.xz. It is fetched at build time and " +
                    "never committed, so run './gradlew downloadAssets' before assembling."
            )
        }
    }
}
