import groovy.json.JsonSlurper
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "php-tools"
}

data class GrammarSource(
    val name: String,
    val version: String,
    val sha256: String,
) {
    val url = "https://registry.npmjs.org/tree-sitter-$name/-/tree-sitter-$name-$version.tgz"
}

val grammarSource = GrammarSource("php", "0.23.12", "4c07c4dd961ed1370328cd4f6213c8842f7ff08e7eb5b30293a3c1f0392527fb")
val grammarDir = layout.buildDirectory.dir("tree-sitter/${grammarSource.name}")

data class BundleEntry(
    val abi: String,
    val kind: String,
    val name: String,
    val version: String,
    val url: String,
    val sha256: String,
) {
    val fileName = "$name.$kind"

    fun line() = listOf(abi, kind, name, version, url, sha256).joinToString("\t")
}

val phpBundleLock = layout.projectDirectory.file("php-bundle.lock")
val phpBundleCache = layout.buildDirectory.dir("php-bundle-cache")
val bundleAssets = layout.buildDirectory.dir("generated/bundleAssets")

val termuxRepository = "https://packages.termux.dev/apt/termux-main"
val termuxArchitectures = mapOf("arm64-v8a" to "aarch64", "armeabi-v7a" to "arm")
val termuxRoots = listOf("php", "composer")
val configOnlyPackages = setOf("ca-certificates", "resolv-conf")
val ALL_ABIS = "all"

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

fun download(
    url: String,
    target: File,
) {
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
}

fun fetchVerified(
    url: String,
    sha256: String,
    target: File,
) {
    if (target.exists() && sha256Of(target) == sha256) return
    download(url, target)
    val actual = sha256Of(target)
    if (actual != sha256) {
        target.delete()
        throw GradleException("Checksum mismatch for $url (expected $sha256, got $actual)")
    }
}

fun readLock(file: File): List<BundleEntry> =
    file.readLines().filter { it.isNotBlank() }.map { line ->
        val fields = line.split('\t')
        if (fields.size != 6) throw GradleException("Malformed php-bundle.lock line: $line")
        BundleEntry(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5])
    }

fun termuxIndex(architecture: String): Map<String, Map<String, String>> =
    URI("$termuxRepository/dists/stable/main/binary-$architecture/Packages")
        .toURL()
        .readText()
        .split(Regex("\n\n+"))
        .mapNotNull { stanza ->
            val fields =
                stanza
                    .lines()
                    .filter { !it.startsWith(" ") && ": " in it }
                    .associate { it.substringBefore(": ") to it.substringAfter(": ") }
            fields["Package"]?.let { it to fields }
        }.toMap()

fun dependencyClosure(
    index: Map<String, Map<String, String>>,
    roots: List<String>,
): List<Map<String, String>> {
    val found = linkedMapOf<String, Map<String, String>>()
    val queue = ArrayDeque(roots)
    while (queue.isNotEmpty()) {
        val name = queue.removeFirst()
        if (name in found || name in configOnlyPackages) continue
        val stanza = index[name] ?: throw GradleException("Termux has no package named $name")
        found[name] = stanza
        stanza["Depends"]
            ?.split(',')
            ?.map { it.substringBefore('|').substringBefore('(').trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach(queue::addLast)
    }
    return found.values.toList()
}

fun readJson(url: String): Map<*, *> {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.setRequestProperty("Accept", "application/vnd.github+json")
    return try {
        JsonSlurper().parse(connection.inputStream) as Map<*, *>
    } finally {
        connection.disconnect()
    }
}

val updatePhpBundleLock =
    tasks.register("updatePhpBundleLock") {
        group = "setup"
        description = "Resolve PHP and Composer from Termux and phpactor (-PphpactorVersion=<release>) and write php-bundle.lock"

        val requestedPhpactor = providers.gradleProperty("phpactorVersion")
        val lock = phpBundleLock
        val cacheDir = phpBundleCache

        doLast {
            val entries = mutableListOf<BundleEntry>()
            termuxArchitectures.forEach { (abi, architecture) ->
                dependencyClosure(termuxIndex(architecture), termuxRoots).forEach { stanza ->
                    val portable = stanza["Architecture"] == ALL_ABIS
                    val entryAbi = if (portable) ALL_ABIS else abi
                    val name = stanza.getValue("Package")
                    if (entries.none { it.abi == entryAbi && it.name == name }) {
                        entries +=
                            BundleEntry(
                                abi = entryAbi,
                                kind = "deb",
                                name = name,
                                version = stanza.getValue("Version"),
                                url = "$termuxRepository/" + stanza.getValue("Filename").replace(":", "%3A"),
                                sha256 = stanza.getValue("SHA256"),
                            )
                    }
                }
            }

            val release =
                requestedPhpactor.orNull
                    ?.let { readJson("https://api.github.com/repos/phpactor/phpactor/releases/tags/$it") }
                    ?: readJson("https://api.github.com/repos/phpactor/phpactor/releases/latest")
            val tag = release["tag_name"] as String
            val asset =
                (release["assets"] as List<*>).map { it as Map<*, *> }.firstOrNull { it["name"] == "phpactor.phar" }
                    ?: throw GradleException("phpactor $tag has no phpactor.phar asset")
            val url = asset["browser_download_url"] as String
            val phar = File(cacheDir.get().asFile, "lock/phpactor-$tag.phar")
            download(url, phar)
            entries += BundleEntry(ALL_ABIS, "phar", "phpactor", tag, url, sha256Of(phar))

            lock.asFile.writeText(entries.joinToString("\n", postfix = "\n") { it.line() })
            val php = entries.first { it.name == "php" }.version
            val composer = entries.first { it.name == "composer" }.version
            logger.lifecycle("Wrote ${entries.size} entries: PHP $php, Composer $composer, phpactor $tag")
        }
    }

val downloadPhpBundle =
    tasks.register("downloadPhpBundle") {
        group = "setup"
        description = "Download and verify PHP for every ABI, Composer and phpactor, all bundled into the plugin"

        val lock = phpBundleLock
        val abis = termuxArchitectures.keys
        val cacheDir = phpBundleCache
        val outputDir = bundleAssets
        inputs.file(lock)
        outputs.dir(outputDir)

        doLast {
            val entries = readLock(lock.asFile)
            val missing = abis.filter { abi -> entries.none { it.abi == abi } }
            if (missing.isNotEmpty()) throw GradleException("php-bundle.lock has no runtime for $missing; run updatePhpBundleLock")
            val cache = cacheDir.get().asFile
            val output = outputDir.get().asFile
            output.deleteRecursively()

            val toolchain = File(output, "toolchain")
            lock.asFile.copyTo(File(toolchain, "php-bundle.lock"))
            entries.forEach { entry ->
                val cached = File(cache, "${entry.abi}/${entry.fileName}")
                fetchVerified(entry.url, entry.sha256, cached)
                cached.copyTo(File(toolchain, "${entry.abi}/${entry.fileName}"))
            }
        }
    }

val downloadGrammar =
    tasks.register("downloadTreeSitterGrammar") {
        group = "setup"
        description = "Download, verify, and unpack the tree-sitter grammar compiled into the plugin"

        val source = grammarSource
        val outputDir = grammarDir
        inputs.property("grammar", "${source.name} ${source.version} ${source.sha256}")
        outputs.dir(outputDir)

        doLast {
            val target = outputDir.get().asFile
            val archive = File(target.parentFile, "${source.name}-${source.version}.tgz")
            fetchVerified(source.url, source.sha256, archive)
            target.deleteRecursively()
            project.copy {
                from(project.tarTree(project.resources.gzip(archive)))
                include("package/php/src/**", "package/common/**")
                exclude("**/grammar.json", "**/node-types.json")
                eachFile { path = path.removePrefix("package/") }
                includeEmptyDirs = false
                into(target)
            }
        }
    }

tasks.named("preBuild") {
    dependsOn(downloadPhpBundle, downloadGrammar)
}

tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }.configureEach {
    dependsOn(downloadGrammar)
}

listOf(downloadPhpBundle, downloadGrammar).forEach { task ->
    task.configure { mustRunAfter(tasks.named("clean")) }
}

android {
    namespace = "com.appdevforall.php.plugin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.appdevforall.php.plugin"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DGRAMMAR_DIR=${grammarDir.get().asFile.absolutePath}"
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
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

    androidResources {
        noCompress += listOf("deb", "phar")
    }

    sourceSets {
        named("main") {
            assets.srcDir(bundleAssets.get().asFile)
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
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
