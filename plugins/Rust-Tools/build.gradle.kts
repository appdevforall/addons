import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "rust-tools"
}

data class GrammarSource(
    val name: String,
    val version: String,
    val sha256: String,
) {
    val url = "https://registry.npmjs.org/tree-sitter-$name/-/tree-sitter-$name-$version.tgz"
}

val grammarSource = GrammarSource("rust", "0.23.3", "5c1d96a9628875c7ba122da1a3aa0bf62b547b5a720673fa318336de27506c66")
val grammarDir = layout.buildDirectory.dir("tree-sitter/${grammarSource.name}")

val DOWNLOAD_ATTEMPTS = 40
val TERMUX_REPOSITORY = "https://packages.termux.dev/apt/termux-main"
val TERMUX_PREFIX_IN_DEB = "data/data/com.termux/files/usr"
val RUST_TARGET_DIR = "lib/rustlib/aarch64-linux-android"

val rustBundleSelection: Map<String, List<String>> =
    mapOf(
        "rust" to listOf(
            "bin/rustc",
            "bin/cargo",
            "bin/rustdoc",
            "bin/rustfmt",
            "bin/cargo-fmt",
            "lib/librustc_driver-*.so",
            "$RUST_TARGET_DIR/lib/librustc_driver-*.so",
            "$RUST_TARGET_DIR/lib/*.rmeta",
            "$RUST_TARGET_DIR/bin/rust-objcopy",
        ),
        "rust-std-aarch64-linux-android" to listOf("$RUST_TARGET_DIR/lib/*"),
        "rust-src" to listOf("lib/rustlib/src/**"),
        "rust-analyzer" to listOf("bin/rust-analyzer"),
        "libllvm" to listOf("lib/libLLVM*.so*"),
        "clang" to listOf("bin/clang", "bin/clang++", "bin/clang-[0-9]*", "lib/libclang-cpp.so*", "lib/clang/*/include/**"),
        "lld" to listOf("bin/lld", "bin/ld.lld"),
        "libcompiler-rt" to listOf("lib/clang/*/lib/linux/libclang_rt.builtins-aarch64-android.a"),
        "ndk-sysroot" to listOf("lib/*", "include/**"),
        "libffi" to listOf("lib/libffi*.so*"),
        "libxml2" to listOf("lib/libxml2*.so*"),
    )

val rustBundleExclusions = listOf("$RUST_TARGET_DIR/lib/librustc_public*")

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

fun sha256Of(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

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

fun runTool(
    command: List<String>,
    environment: Map<String, String> = emptyMap(),
) {
    val process = ProcessBuilder(command).redirectErrorStream(true).apply { environment().putAll(environment) }.start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    if (exitCode != 0) throw GradleException("${command.joinToString(" ")} failed (exit $exitCode):\n$output")
}

fun extractDebData(
    deb: File,
    into: File,
) {
    RandomAccessFile(deb, "r").use { archive ->
        val magic = ByteArray(8).also { archive.readFully(it) }
        if (String(magic) != "!<arch>\n") throw GradleException("${deb.name} is not a Debian package")
        val header = ByteArray(60)
        while (archive.filePointer + header.size <= archive.length()) {
            archive.readFully(header)
            val name = String(header, 0, 16).trim().trimEnd('/')
            val size = String(header, 48, 10).trim().toLong()
            if (name.startsWith("data.tar")) {
                val member = File(into, name)
                FileOutputStream(member).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var remaining = size
                    while (remaining > 0) {
                        val read = archive.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) throw GradleException("${deb.name} ends inside $name")
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
                runTool(listOf("tar", "-xf", member.absolutePath, "-C", into.absolutePath))
                member.delete()
                return
            }
            archive.seek(archive.filePointer + size + (size and 1L))
        }
    }
    throw GradleException("${deb.name} has no data.tar member")
}

data class BundleEntry(
    val kind: String,
    val fileName: String,
    val url: String,
    val sha256: String,
) {
    val packageName = fileName.substringBefore('_')
}

fun readBundleLock(lock: File): List<BundleEntry> =
    lock.readLines().filter { it.isNotBlank() }.map { line ->
        val fields = line.split('\t')
        if (fields.size != 4 || fields[0] != "deb") throw GradleException("Malformed rust-bundle.lock line: $line")
        BundleEntry(fields[0], fields[1], fields[2], fields[3])
    }

val rustBundleLock = layout.projectDirectory.file("rust-bundle.lock")
val rustBundleCache = layout.buildDirectory.dir("rust-bundle-cache")
val rustBundleWork = layout.buildDirectory.dir("rust-bundle-work")
val rustBundleDir = layout.buildDirectory.dir("generated/rustBundle")

val downloadRustBundle =
    tasks.register("downloadRustBundle") {
        group = "setup"
        description = "Download, verify, and trim the Rust toolchain bundled into the plugin"

        val lock = rustBundleLock
        val cacheDir = rustBundleCache
        val workDir = rustBundleWork
        val outputDir = rustBundleDir
        val selection = rustBundleSelection
        val exclusions = rustBundleExclusions
        inputs.file(lock)
        inputs.property("selection", selection.toString())
        inputs.property("exclusions", exclusions.toString())
        outputs.dir(outputDir)

        doLast {
            val entries = readBundleLock(lock.asFile)
            val locked = entries.map { it.packageName }.toSet()
            if (locked != selection.keys) {
                throw GradleException("rust-bundle.lock pins $locked but the selection covers ${selection.keys}")
            }

            val cache = cacheDir.get().asFile
            val work = workDir.get().asFile.apply { deleteRecursively() }
            val stage = File(work, "stage/rust").apply { mkdirs() }
            val excluded = exclusions.map { FileSystems.getDefault().getPathMatcher("glob:$it") }

            entries.forEach { entry ->
                val deb = File(cache, entry.fileName)
                fetchVerified(entry.url, entry.sha256, deb)
                val unpacked = File(work, "unpacked/${entry.packageName}").apply { mkdirs() }
                extractDebData(deb, unpacked)

                val prefix = unpacked.toPath().resolve(TERMUX_PREFIX_IN_DEB)
                val patterns = selection.getValue(entry.packageName)
                val matchers = patterns.map { it to FileSystems.getDefault().getPathMatcher("glob:$it") }
                val used = mutableSetOf<String>()
                Files.walk(prefix).use { paths ->
                    paths.filter { !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { source ->
                        val relative = prefix.relativize(source)
                        val pattern = matchers.firstOrNull { it.second.matches(relative) }?.first ?: return@forEach
                        if (excluded.any { it.matches(relative) }) return@forEach
                        used += pattern
                        val target = stage.toPath().resolve(relative.toString())
                        Files.createDirectories(target.parent)
                        if (Files.isSymbolicLink(source)) {
                            Files.createSymbolicLink(target, Files.readSymbolicLink(source))
                        } else {
                            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
                        }
                    }
                }
                val unmatched = patterns - used
                if (unmatched.isNotEmpty()) {
                    throw GradleException("${entry.fileName} has no files matching $unmatched; update rustBundleSelection")
                }
                unpacked.deleteRecursively()
            }

            val toolchainDir = File(outputDir.get().asFile, "toolchain").apply {
                deleteRecursively()
                mkdirs()
            }
            runTool(
                listOf("tar", "--no-xattrs", "-cJf", File(toolchainDir, "rust-aarch64.tar.xz").absolutePath, "-C", stage.parentFile.absolutePath, "rust"),
                mapOf("COPYFILE_DISABLE" to "1", "XZ_OPT" to "-6 -T0"),
            )
            File(toolchainDir, "rust-aarch64.id").writeText(sha256Of(lock.asFile.readText() + selection + exclusions))
            work.deleteRecursively()
        }
    }

tasks.register("updateRustBundleLock") {
    group = "setup"
    description = "Pin rust-bundle.lock to the package versions Termux publishes today"

    val lock = rustBundleLock
    doLast {
        val index = URI("$TERMUX_REPOSITORY/dists/stable/main/binary-aarch64/Packages").toURL().readText()
        val packages =
            index.split("\n\n").map { stanza ->
                stanza.lines().filter { ':' in it && !it.startsWith(" ") }.associate { it.substringBefore(':') to it.substringAfter(':').trim() }
            }.filter { "Package" in it }.associateBy { it.getValue("Package") }
        val lines =
            readBundleLock(lock.asFile).map { entry ->
                val published = packages[entry.packageName] ?: throw GradleException("Termux no longer publishes ${entry.packageName}")
                val path = published.getValue("Filename")
                listOf("deb", path.substringAfterLast('/'), "$TERMUX_REPOSITORY/$path", published.getValue("SHA256")).joinToString("\t")
            }
        lock.asFile.writeText(lines.joinToString("\n", postfix = "\n"))
        logger.lifecycle("Pinned ${lines.size} packages in ${lock.asFile.name}")
    }
}

val downloadGrammar =
    tasks.register("downloadTreeSitterGrammar") {
        group = "setup"
        description = "Download, verify, and unpack the tree-sitter grammar compiled into the plugin"

        val source = grammarSource
        val outputDir = grammarDir
        val archive = layout.buildDirectory.file("tree-sitter/${source.name}-${source.version}.tgz")
        inputs.property("grammarVersion", source.version)
        inputs.property("grammarUrl", source.url)
        inputs.property("grammarSha256", source.sha256)
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
    dependsOn(downloadGrammar, downloadRustBundle)
}

tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }.configureEach {
    dependsOn(downloadGrammar)
}

downloadGrammar.configure {
    mustRunAfter(tasks.named("clean"))
}

downloadRustBundle.configure {
    mustRunAfter(tasks.named("clean"))
}

android {
    namespace = "com.appdevforall.rust.plugin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.appdevforall.rust.plugin"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
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
        noCompress += listOf("xz")
    }

    sourceSets {
        named("main") {
            assets.srcDir(rustBundleDir.get().asFile)
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

tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach {
    enabled = false
}
