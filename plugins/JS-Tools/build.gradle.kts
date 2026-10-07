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
    pluginName = "js-tools"
}

data class GrammarSource(
    val name: String,
    val version: String,
    val sha256: String,
) {
    val url = "https://registry.npmjs.org/tree-sitter-$name/-/tree-sitter-$name-$version.tgz"
}

val grammarSources =
    listOf(
        GrammarSource("javascript", "0.23.1", "90e80b25a67517a4daf6ad751557bee21efbda7b7a5a554897933245d1734398"),
        GrammarSource("typescript", "0.23.2", "0fdf63c35930a75885145d75ee63cb879da2d49db9bfe454bd8e60b08ba778a1"),
    )
val grammarRoot = layout.buildDirectory.dir("tree-sitter")

data class HighlightQuery(
    val grammar: String,
    val licenseFrom: String,
    val fragments: List<String>,
)

val highlightQueries =
    listOf(
        HighlightQuery("javascript", "javascript", listOf("literals", "jsx", "javascript-parameters", "ecma", "variables")),
        HighlightQuery("typescript", "typescript", listOf("literals", "typescript", "ecma", "variables")),
        HighlightQuery("tsx", "typescript", listOf("literals", "jsx", "typescript", "ecma", "variables")),
    )
val queryFragments = layout.projectDirectory.dir("src/main/queries")
val queryAssets = layout.buildDirectory.dir("generated/queryAssets")

data class BundleEntry(
    val abi: String,
    val kind: String,
    val name: String,
    val version: String,
    val url: String,
    val sha256: String,
) {
    val fileName = name.removePrefix("@").replace('/', '-') + if (kind == "deb") ".deb" else ".tgz"

    fun line() = listOf(abi, kind, name, version, url, sha256).joinToString("\t")
}

val nodeBundleLock = layout.projectDirectory.file("node-bundle.lock")
val nodeBundleCache = layout.buildDirectory.dir("node-bundle-cache")
val bundleAssets = layout.buildDirectory.dir("generated/bundleAssets")

val termuxRepository = "https://packages.termux.dev/apt/termux-main"
val termuxArchitectures = mapOf("arm64-v8a" to "aarch64", "armeabi-v7a" to "arm")
val nodePackages = mapOf("lts" to "nodejs-lts", "current" to "nodejs")
val ALL_ABIS = "all"
val configOnlyPackages = setOf("ca-certificates", "resolv-conf")

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
        if (fields.size != 6) throw GradleException("Malformed node-bundle.lock line: $line")
        BundleEntry(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5])
    }

fun readJson(
    url: String,
    abbreviated: Boolean = false,
): Map<*, *> {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    if (abbreviated) connection.setRequestProperty("Accept", "application/vnd.npm.install-v1+json")
    return try {
        JsonSlurper().parse(connection.inputStream) as Map<*, *>
    } finally {
        connection.disconnect()
    }
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
    root: String,
): List<Map<String, String>> {
    val found = linkedMapOf<String, Map<String, String>>()
    val queue = ArrayDeque(listOf(root))
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

fun termuxEntry(
    abi: String,
    stanza: Map<String, String>,
): BundleEntry =
    BundleEntry(
        abi = abi,
        kind = "deb",
        name = stanza.getValue("Package"),
        version = stanza.getValue("Version"),
        url = "$termuxRepository/" + stanza.getValue("Filename").replace(":", "%3A"),
        sha256 = stanza.getValue("SHA256"),
    )

val versionOrder =
    Comparator<String> { a, b ->
        val left = a.split('.').map(String::toInt)
        val right = b.split('.').map(String::toInt)
        left.zip(right).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: left.size.compareTo(right.size)
    }

fun isRelease(version: String) = version.isNotEmpty() && version.all { it.isDigit() || it == '.' }

fun satisfies(
    version: String,
    range: String,
): Boolean =
    range.trim().split(Regex("\\s+")).all { comparator ->
        val operator = comparator.takeWhile { it in "<>=^~" }
        val bound = comparator.removePrefix(operator)
        val (major, minor) = bound.split('.').map(String::toInt)
        val order = versionOrder.compare(version, bound)
        when (operator) {
            "^" -> order >= 0 && versionOrder.compare(version, if (major > 0) "${major + 1}.0.0" else "0.${minor + 1}.0") < 0
            "~" -> order >= 0 && versionOrder.compare(version, "$major.${minor + 1}.0") < 0
            ">=" -> order >= 0
            ">" -> order > 0
            "<=" -> order <= 0
            "<" -> order < 0
            else -> order == 0
        }
    }

fun newestMatching(
    name: String,
    range: String,
): Pair<String, Map<*, *>> {
    val versions = readJson("https://registry.npmjs.org/$name", abbreviated = true)["versions"] as Map<*, *>
    val version =
        versions.keys
            .map { it as String }
            .filter { isRelease(it) && satisfies(it, range) }
            .maxWithOrNull(versionOrder)
            ?: throw GradleException("No release of $name matches '$range'")
    return version to versions[version] as Map<*, *>
}

fun npmEntry(
    abi: String,
    kind: String,
    name: String,
    version: String,
    tarball: String,
    cache: File,
): BundleEntry {
    val file = File(cache, "lock/$abi/${name.removePrefix("@").replace('/', '-')}-$version.tgz")
    download(tarball, file)
    return BundleEntry(abi, kind, name, version, tarball, sha256Of(file))
}

fun tarball(release: Map<*, *>): String = (release["dist"] as Map<*, *>)["tarball"] as String

val updateNodeBundleLock =
    tasks.register("updateNodeBundleLock") {
        group = "setup"
        description = "Resolve Node.js (-PnodeRelease=lts|current), npm, TypeScript (-PtypescriptVersion=<version>), " +
            "typescript-language-server (-PtypescriptLanguageServerVersion=<version>) and the Node.js type " +
            "declarations, and write node-bundle.lock"

        val release = providers.gradleProperty("nodeRelease").orElse("lts")
        val requestedTypescript = providers.gradleProperty("typescriptVersion").orElse("^6.0.0")
        val requestedServer = providers.gradleProperty("typescriptLanguageServerVersion").orElse(">=6.0.0")
        val lock = nodeBundleLock
        val cacheDir = nodeBundleCache

        doLast {
            val nodePackage =
                nodePackages[release.get()]
                    ?: throw GradleException("nodeRelease must be one of ${nodePackages.keys}, not '${release.get()}'")
            val cache = cacheDir.get().asFile
            val entries = mutableListOf<BundleEntry>()
            var nodeVersion = ""

            termuxArchitectures.forEach { (abi, architecture) ->
                val index = termuxIndex(architecture)
                val node = dependencyClosure(index, nodePackage)
                nodeVersion = node.first().getValue("Version")
                entries += node.map { termuxEntry(abi, it) }
                entries += termuxEntry(abi, index["npm"] ?: throw GradleException("Termux has no npm package"))
            }

            val (typescriptVersion, typescript) = newestMatching("typescript", requestedTypescript.get())
            entries += npmEntry(ALL_ABIS, "npm", "typescript", typescriptVersion, tarball(typescript), cache)
            val (serverVersion, server) = newestMatching("typescript-language-server", requestedServer.get())
            entries += npmEntry(ALL_ABIS, "npm", "typescript-language-server", serverVersion, tarball(server), cache)

            val nodeMajor = nodeVersion.substringAfter(':').substringBefore('.')
            val (typesVersion, types) = newestMatching("@types/node", "^$nodeMajor.0.0")
            entries += npmEntry(ALL_ABIS, "types", "@types/node", typesVersion, tarball(types), cache)
            ((types["dependencies"] as Map<*, *>?) ?: emptyMap<String, String>()).forEach { (name, range) ->
                val (version, dependency) = newestMatching(name as String, range as String)
                entries += npmEntry(ALL_ABIS, "types", name, version, tarball(dependency), cache)
            }

            lock.asFile.writeText(entries.joinToString("\n", postfix = "\n") { it.line() })
            logger.lifecycle(
                "Wrote ${entries.size} entries: $nodePackage $nodeVersion, TypeScript $typescriptVersion, " +
                    "typescript-language-server $serverVersion, @types/node $typesVersion",
            )
        }
    }

val downloadNodeBundle =
    tasks.register("downloadNodeBundle") {
        group = "setup"
        description = "Download and verify the Node.js runtime for every ABI, TypeScript, the TypeScript language " +
            "server and the Node.js type declarations, all bundled into the plugin"

        val lock = nodeBundleLock
        val abis = termuxArchitectures.keys
        val cacheDir = nodeBundleCache
        val outputDir = bundleAssets
        inputs.file(lock)
        outputs.dir(outputDir)

        doLast {
            val entries = readLock(lock.asFile)
            val missing = abis.filter { abi -> entries.none { it.abi == abi } }
            if (missing.isNotEmpty()) throw GradleException("node-bundle.lock has no runtime for $missing; run updateNodeBundleLock")
            val cache = cacheDir.get().asFile
            val output = outputDir.get().asFile
            output.deleteRecursively()

            val toolchain = File(output, "toolchain")
            lock.asFile.copyTo(File(toolchain, "node-bundle.lock"))
            entries.filter { it.kind != "types" }.forEach { entry ->
                val cached = File(cache, "${entry.abi}/${entry.fileName}")
                fetchVerified(entry.url, entry.sha256, cached)
                cached.copyTo(File(toolchain, "${entry.abi}/${entry.fileName}"))
            }

            entries.filter { it.kind == "types" }.forEach { entry ->
                val cached = File(cache, "types/${entry.fileName}")
                fetchVerified(entry.url, entry.sha256, cached)
                project.copy {
                    from(project.tarTree(project.resources.gzip(cached)))
                    eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
                    includeEmptyDirs = false
                    into(File(output, "types/node_modules/${entry.name}"))
                }
            }
        }
    }

val downloadGrammars =
    tasks.register("downloadTreeSitterGrammars") {
        group = "setup"
        description = "Download, verify, and unpack the tree-sitter grammars compiled into the plugin"

        val sources = grammarSources
        val outputDir = grammarRoot
        inputs.property("grammars", sources.map { "${it.name} ${it.version} ${it.sha256}" })
        outputs.dir(outputDir)

        doLast {
            val root = outputDir.get().asFile
            sources.forEach { source ->
                val archive = File(root, "${source.name}-${source.version}.tgz")
                fetchVerified(source.url, source.sha256, archive)
                val target = File(root, source.name)
                target.deleteRecursively()
                project.copy {
                    from(project.tarTree(project.resources.gzip(archive)))
                    include("package/LICENSE", "package/src/**", "package/common/**", "package/typescript/src/**", "package/tsx/src/**")
                    exclude("**/grammar.json", "**/node-types.json")
                    eachFile { path = path.removePrefix("package/") }
                    includeEmptyDirs = false
                    into(target)
                }
            }
        }
    }

val assembleHighlightQueries =
    tasks.register("assembleHighlightQueries") {
        group = "setup"
        description = "Concatenate the highlight query fragments for each grammar, most specific first"

        val queries = highlightQueries
        val fragments = queryFragments
        val grammars = grammarRoot
        val outputDir = queryAssets
        dependsOn(downloadGrammars)
        inputs.dir(fragments)
        inputs.property("queries", queries.map { "${it.grammar} ${it.licenseFrom} ${it.fragments}" })
        outputs.dir(outputDir)

        doLast {
            val output = outputDir.get().asFile
            output.deleteRecursively()
            queries.forEach { query ->
                val directory = File(output, "treesitter/${query.grammar}")
                directory.mkdirs()
                File(directory, "highlights.scm").writeText(
                    query.fragments.joinToString("\n") { fragments.file("$it.scm").asFile.readText() },
                )
                File(grammars.get().asFile, "${query.licenseFrom}/LICENSE").copyTo(File(directory, "LICENSE"))
            }
        }
    }

tasks.named("preBuild") {
    dependsOn(downloadNodeBundle, downloadGrammars, assembleHighlightQueries)
}

tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }.configureEach {
    dependsOn(downloadGrammars)
}

listOf(downloadNodeBundle, downloadGrammars, assembleHighlightQueries).forEach { task ->
    task.configure { mustRunAfter(tasks.named("clean")) }
}

android {
    namespace = "com.appdevforall.js.plugin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.appdevforall.js.plugin"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DGRAMMARS_DIR=${grammarRoot.get().asFile.absolutePath}"
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
        noCompress += listOf("deb", "tgz")
    }

    sourceSets {
        named("main") {
            assets.srcDir(bundleAssets.get().asFile)
            assets.srcDir(queryAssets.get().asFile)
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
