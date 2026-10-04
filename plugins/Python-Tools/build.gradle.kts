import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "python-tools"
}

data class GrammarSource(
    val name: String,
    val version: String,
    val sha256: String,
) {
    val url = "https://registry.npmjs.org/tree-sitter-$name/-/tree-sitter-$name-$version.tgz"
}

val grammarSource = GrammarSource("python", "0.23.6", "aab5b860d93dbf84b37fed532f7dacc0468ede5387d0c74911d68f964287662a")
val grammarDir = layout.buildDirectory.dir("tree-sitter/${grammarSource.name}")

val DOWNLOAD_ATTEMPTS = 40

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun fetchVerified(
    url: String,
    sha256: String,
    target: File,
) {
    if (target.exists() && sha256Of(target) == sha256) return
    target.parentFile.mkdirs()
    target.delete()
    logger.lifecycle("Downloading $url")
    var attempt = 0
    while (true) {
        attempt++
        val offset = target.length()
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
        val interrupted =
            try {
                val append = offset > 0 && connection.responseCode == HttpURLConnection.HTTP_PARTIAL
                connection.inputStream.use { input ->
                    FileOutputStream(target, append).use { output -> input.copyTo(output) }
                }
                null
            } catch (e: IOException) {
                e
            } finally {
                connection.disconnect()
            }
        if (interrupted == null && connection.contentLengthLong in listOf(-1L, target.length() - offset, target.length())) break
        if (attempt >= DOWNLOAD_ATTEMPTS) {
            target.delete()
            throw GradleException("Failed to download $url after $attempt attempts: ${interrupted?.message}", interrupted)
        }
        logger.lifecycle("Resuming $url at ${target.length()} bytes (attempt ${attempt + 1})")
    }
    val actual = sha256Of(target)
    if (actual != sha256) {
        target.delete()
        throw GradleException("Checksum mismatch for $url (expected $sha256, got $actual)")
    }
}

data class BundleEntry(
    val directory: String,
    val fileName: String,
    val url: String,
    val sha256: String,
)

val pythonBundleLock = layout.projectDirectory.file("python-bundle.lock")
val pythonBundleCache = layout.buildDirectory.dir("python-bundle-cache")
val pythonBundleDir = layout.buildDirectory.dir("generated/pythonBundle")

val downloadPythonBundle =
    tasks.register("downloadPythonBundle") {
        group = "setup"
        description = "Download and verify the Python wheels bundled into the plugin"

        val lock = pythonBundleLock
        val cacheDir = pythonBundleCache
        val outputDir = pythonBundleDir
        inputs.file(lock)
        outputs.dir(outputDir)

        doLast {
            val entries =
                lock.asFile.readLines().filter { it.isNotBlank() }.map { line ->
                    val fields = line.split('\t')
                    if (fields.size != 4) throw GradleException("Malformed python-bundle.lock line: $line")
                    BundleEntry(fields[0], fields[1], fields[2], fields[3])
                }
            val cache = cacheDir.get().asFile
            entries.forEach { fetchVerified(it.url, it.sha256, File(cache, "${it.directory}/${it.fileName}")) }

            val zipFile = File(outputDir.get().asFile, "python/wheelhouse.zip")
            zipFile.parentFile.deleteRecursively()
            zipFile.parentFile.mkdirs()
            ZipOutputStream(zipFile.outputStream()).use { zip ->
                entries.filter { it.directory == "wheelhouse" }.sortedBy { it.fileName }.forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.fileName).apply { time = 0L })
                    File(cache, "${entry.directory}/${entry.fileName}").inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

val downloadGrammar =
    tasks.register("downloadTreeSitterGrammar") {
        group = "setup"
        description = "Download, verify, and unpack the tree-sitter grammar compiled into the plugin"

        val source = grammarSource
        val outputDir = grammarDir
        val archive = layout.buildDirectory.file("tree-sitter/${source.name}-${source.version}.tgz")
        outputs.dir(outputDir)

        doLast {
            val archiveFile = archive.get().asFile
            fetchVerified(source.url, source.sha256, archiveFile)
            val target = outputDir.get().asFile
            target.deleteRecursively()
            project.copy {
                from(project.tarTree(project.resources.gzip(archiveFile)))
                include("package/src/**")
                eachFile { path = path.removePrefix("package/") }
                includeEmptyDirs = false
                into(target)
            }
        }
    }

tasks.named("preBuild") {
    dependsOn(downloadGrammar, downloadPythonBundle)
}

tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }.configureEach {
    dependsOn(downloadGrammar)
}

downloadGrammar.configure {
    mustRunAfter(tasks.named("clean"))
}

downloadPythonBundle.configure {
    mustRunAfter(tasks.named("clean"))
}

android {
    namespace = "com.appdevforall.python.plugin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.appdevforall.python.plugin"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DTREE_SITTER_GRAMMAR_NAME=${grammarSource.name}"
                arguments += "-DTREE_SITTER_GRAMMAR_DIR=${grammarDir.get().asFile.absolutePath}"
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    androidResources {
        noCompress += listOf("zip")
    }

    sourceSets {
        named("main") {
            assets.srcDir(pythonBundleDir.get().asFile)
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(files("../../libs/plugin-api.jar"))

    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

tasks.wrapper {
    gradleVersion = "9.6.1"
    distributionType = Wrapper.DistributionType.BIN
}

tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach {
    enabled = false
}
