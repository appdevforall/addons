package org.appdevforall.projecttotemplate

import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Turns an Android Studio project into a Code on the Go (.cgt) template
 * bundle by substituting concrete values (versions, package name, app name,
 * SDK levels, Java compatibility levels) with Pebble tokens (${{ TOKEN }}),
 * then writing templates.json and template/template.json alongside a
 * thumbnail. No dependency on the plugin API, and the substitution/sanitize
 * logic uses only the JDK (java.util.Base64, not android.util.Base64), so it
 * is unit-testable on the JVM and callable directly from the plugin's UI layer.
 */

/** Directory/file names skipped when copying the source project into the bundle. */
private val COPY_IGNORE = setOf(
    ".git", ".gradle", ".cg", ".idea", ".claude", ".androidide",
    "release.properties", "local.properties",
)

fun token(name: String): String = "\${{$name}}"

class Report(private val onLine: (String) -> Unit = {}) {
    val changed = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    val removed = mutableListOf<String>()
    val flagged = mutableListOf<String>()

    fun ok(msg: String) {
        changed += msg
        onLine("[OK]      $msg")
    }

    fun skip(msg: String) {
        skipped += msg
        onLine("[SKIP]    $msg")
    }

    fun remove(msg: String) {
        removed += msg
        onLine("[REMOVED] $msg")
    }

    fun flag(msg: String) {
        flagged += msg
        onLine("[REVIEW]  $msg")
    }
}

/**
 * Replaces the value captured by group 2 of [pattern] with a Pebble token,
 * inserting [trailingSpaces] sacrificial spaces after the token (Pebble eats
 * the character following a token unless whitespace separates them) - or
 * after group 3 instead when [spaceAfterSuffix] is set, e.g. when group 3 is
 * a closing quote that must sit directly against the token. [pattern] must
 * have exactly three capturing groups: (prefix)(value)(suffix).
 */
private fun subLineEndToken(
    text: String,
    pattern: String,
    tokenName: String,
    trailingSpaces: Int = 1,
    spaceAfterSuffix: Boolean = false,
): Pair<String, Int> {
    val regex = Regex(pattern, RegexOption.MULTILINE)
    var count = 0
    val tok = token(tokenName)
    val spaces = " ".repeat(trailingSpaces)
    val result = regex.replace(text) { m ->
        count++
        val prefix = m.groupValues[1]
        val suffix = m.groupValues[3]
        if (spaceAfterSuffix) "$prefix$tok$suffix$spaces" else "$prefix$tok$spaces$suffix"
    }
    return result to count
}

/** Tries each candidate pattern (Kotlin DSL vs. Groovy DSL variants) in turn, applying the first that matches. */
private fun subFirstMatchToken(
    text: String,
    patterns: List<String>,
    tokenName: String,
    trailingSpaces: Int = 1,
    spaceAfterSuffix: Boolean = false,
): Pair<String, Int> {
    for (pattern in patterns) {
        val (newText, n) = subLineEndToken(text, pattern, tokenName, trailingSpaces, spaceAfterSuffix)
        if (n > 0) return newText to n
    }
    return text to 0
}

private fun writePeb(path: File, newText: String, report: Report, dryRun: Boolean) {
    val pebPath = File(path.parentFile, path.name + ".peb")
    report.ok("$path -> ${pebPath.name}")
    if (dryRun) return
    pebPath.writeText(newText, Charsets.UTF_8)
    path.delete()
}

private fun lineEnding(line: String): String = when {
    line.endsWith("\r\n") -> "\r\n"
    line.endsWith("\n") -> "\n"
    else -> ""
}

/** Splits text into lines, keeping each line's terminator attached (mirrors Python's splitlines(keepends=True)). */
private fun splitKeepEnds(text: String): List<String> {
    val result = mutableListOf<String>()
    var start = 0
    for (i in text.indices) {
        if (text[i] == '\n') {
            result.add(text.substring(start, i + 1))
            start = i + 1
        }
    }
    if (start < text.length) result.add(text.substring(start))
    return result
}

private fun processGradleWrapper(projectDir: File, report: Report, dryRun: Boolean) {
    val path = File(projectDir, "gradle/wrapper/gradle-wrapper.properties")
    if (!path.exists()) {
        report.skip("$path not found")
        return
    }
    val text = path.readText()
    val (newText, n) = subLineEndToken(
        text,
        """(distributionUrl=https\\://services\.gradle\.org/distributions/gradle-)([^-\s]+)(-bin\.zip)""",
        "GRADLE_VERSION",
    )
    if (n == 0) {
        report.skip("$path: distributionUrl pattern not found, no changes made")
        return
    }
    writePeb(path, newText, report, dryRun)
}

private val ROOT_PROJECT_NAME_REGEX = Regex("""^([ \t]*rootProject\.name[ \t]*=[ \t]*)(["'])([^"']*)\2[ \t]*""")

private fun processSettingsGradle(projectDir: File, report: Report, dryRun: Boolean) {
    var path = File(projectDir, "settings.gradle.kts")
    if (!path.exists()) path = File(projectDir, "settings.gradle")
    if (!path.exists()) {
        report.skip("${File(projectDir, "settings.gradle.kts")} or settings.gradle not found")
        return
    }
    val text = path.readText()
    val lines = splitKeepEnds(text)
    var n = 0
    val newLines = lines.map { line ->
        val m = ROOT_PROJECT_NAME_REGEX.find(line)
        if (m != null) {
            n++
            val prefix = m.groupValues[1]
            val quote = m.groupValues[2]
            "$prefix$quote${token("APP_NAME")}$quote  ${lineEnding(line)}"
        } else {
            line
        }
    }
    if (n == 0) {
        report.skip("$path: rootProject.name pattern not found, no changes made")
        return
    }
    writePeb(path, newLines.joinToString(""), report, dryRun)
}

private fun processRootBuildGradle(projectDir: File, report: Report, dryRun: Boolean) {
    var path = File(projectDir, "build.gradle.kts")
    if (!path.exists()) path = File(projectDir, "build.gradle")
    if (!path.exists()) {
        report.skip("${File(projectDir, "build.gradle.kts")} or build.gradle not found")
        return
    }
    val text = path.readText()
    val (tokenized, n) = subFirstMatchToken(
        text,
        listOf(
            """(id\("com\.android\.(?:application|library)"\)\s+apply\s+false\s+version\s+")([^"]+)(")""",
            """(id\s+'com\.android\.(?:application|library)'\s+apply\s+false\s+version\s+')([^']+)(')""",
            """(id\s+"com\.android\.(?:application|library)"\s+apply\s+false\s+version\s+")([^"]+)(")""",
        ),
        "AGP_VERSION",
        spaceAfterSuffix = true,
    )
    val needsBuildscript = !tokenized.contains("kotlin-gradle-plugin")
    val newText =
        if (needsBuildscript) {
            report.ok("$path: pinned the Kotlin Gradle plugin on the buildscript classpath for AGP 9")
            kotlinBuildscriptBlock(path.name.endsWith(".kts")) + tokenized
        } else {
            tokenized
        }
    if (n == 0 && !needsBuildscript) {
        report.skip("$path: no 'apply false version' plugin lines found, no changes made")
        return
    }
    writePeb(path, newText, report, dryRun)
}

private fun kotlinBuildscriptBlock(isKts: Boolean): String {
    val coordinate = "org.jetbrains.kotlin:kotlin-gradle-plugin:${token("KOTLIN_VERSION")}"
    val classpath = if (isKts) "classpath(\"$coordinate\")" else "classpath '$coordinate'"
    return buildString {
        appendLine("buildscript {")
        appendLine("    repositories {")
        appendLine("        google()")
        appendLine("        mavenCentral()")
        appendLine("    }")
        appendLine("    dependencies {")
        appendLine("        $classpath")
        appendLine("    }")
        appendLine("}")
        appendLine()
    }
}

private val NAMESPACE_PATTERNS = listOf(
    """(namespace\s*=\s*")([^"]+)(")""",
    """(namespace\s+')([^']+)(')""",
    """(namespace\s+")([^"]+)(")""",
)
private val APPLICATION_ID_PATTERNS = listOf(
    """(applicationId\s*=\s*")([^"]+)(")""",
    """(applicationId\s+')([^']+)(')""",
    """(applicationId\s+")([^"]+)(")""",
)

private val KTS_KOTLIN_PATTERN = Regex(
    """^([ \t]*)kotlin\("android"\)\s+version\s+"([^"]+)"[ \t]*\r?\n?""",
    RegexOption.MULTILINE,
)
private val GROOVY_KOTLIN_PATTERN = Regex(
    """^([ \t]*)id\s+(['"])(?:org\.jetbrains\.kotlin\.android|kotlin-android)\2\s+version\s+\2([^'"]+)\2[ \t]*\r?\n?""",
    RegexOption.MULTILINE,
)

private fun findFirst(text: String, patterns: List<String>): String? {
    for (pattern in patterns) {
        val m = Regex(pattern, RegexOption.MULTILINE).find(text)
        if (m != null) return m.groupValues[2]
    }
    return null
}

/** Returns the base package name (from namespace/applicationId) so sources can be re-packaged, or null. */
private fun processAppBuildGradle(
    projectDir: File,
    module: String,
    report: Report,
    dryRun: Boolean,
): String? {
    var path = File(projectDir, "$module/build.gradle.kts")
    if (!path.exists()) path = File(projectDir, "$module/build.gradle")
    if (!path.exists()) {
        report.skip("${File(projectDir, "$module/build.gradle.kts")} or build.gradle not found")
        return null
    }
    var text = path.readText()
    var total = 0

    // Captured before substitution runs below, so it reflects the concrete
    // package name even though the build.gradle occurrence gets tokenized.
    val basePackage = findFirst(text, NAMESPACE_PATTERNS) ?: findFirst(text, APPLICATION_ID_PATTERNS)

    val r1 = subFirstMatchToken(
        text,
        listOf(
            """(id\("com\.android\.application"\)\s+version\s+")([^"]+)(")""",
            """(id\s+'com\.android\.application'\s+version\s+')([^']+)(')""",
            """(id\s+"com\.android\.application"\s+version\s+")([^"]+)(")""",
        ),
        "AGP_VERSION",
        spaceAfterSuffix = true,
    )
    text = r1.first
    total += r1.second

    val m = KTS_KOTLIN_PATTERN.find(text) ?: GROOVY_KOTLIN_PATTERN.find(text)
    if (m != null) {
        text = text.substring(0, m.range.first) + text.substring(m.range.last + 1)
        total += 1
        report.ok("$path: dropped the Kotlin plugin declaration; AGP 9 compiles Kotlin itself")
    } else {
        report.skip("$path: Kotlin plugin line not found (ok for Java-only projects)")
    }

    fun apply(patterns: List<String>, tokenName: String, spaceAfterSuffix: Boolean = false) {
        val (t, count) = subFirstMatchToken(text, patterns, tokenName, spaceAfterSuffix = spaceAfterSuffix)
        text = t
        total += count
    }

    apply(NAMESPACE_PATTERNS, "PACKAGE_NAME", spaceAfterSuffix = true)
    apply(
        listOf("""(compileSdk\s*=\s*)(\d+)()""", """(compileSdk(?:Version)?\s+)(\d+)()"""),
        "COMPILE_SDK",
    )
    apply(APPLICATION_ID_PATTERNS, "PACKAGE_NAME", spaceAfterSuffix = true)
    apply(
        listOf("""(minSdk\s*=\s*)(\d+)()""", """(minSdk(?:Version)?\s+)(\d+)()"""),
        "MIN_SDK",
    )
    apply(
        listOf("""(targetSdk\s*=\s*)(\d+)()""", """(targetSdk(?:Version)?\s+)(\d+)()"""),
        "TARGET_SDK",
    )
    apply(
        listOf(
            """(sourceCompatibility\s*=\s*)(JavaVersion\.\w+|[\d.]+)()""",
            """(sourceCompatibility\s+)(JavaVersion\.\w+|[\d.]+)()""",
        ),
        "JAVA_SOURCE_COMPAT",
    )
    apply(
        listOf(
            """(targetCompatibility\s*=\s*)(JavaVersion\.\w+|[\d.]+)()""",
            """(targetCompatibility\s+)(JavaVersion\.\w+|[\d.]+)()""",
        ),
        "JAVA_TARGET_COMPAT",
    )
    apply(
        listOf("""(jvmTarget\s*=\s*")([^"]+)(")""", """(jvmTarget\s*=\s*')([^']+)(')"""),
        "JAVA_TARGET",
        spaceAfterSuffix = true,
    )

    if (total == 0) {
        report.skip("$path: no recognized patterns found, no changes made")
        return basePackage
    }
    writePeb(path, text, report, dryRun)
    return basePackage
}

private fun processStringsXml(projectDir: File, module: String, report: Report, dryRun: Boolean) {
    val path = File(projectDir, "$module/src/main/res/values/strings.xml")
    if (!path.exists()) {
        report.skip("$path not found")
        return
    }
    val text = path.readText()
    val (newText, n) = subLineEndToken(
        text,
        """(<string name="app_name">)([^<]*)(</string>)""",
        "APP_NAME",
    )
    if (n == 0) {
        report.skip("$path: app_name string not found, no changes made")
        return
    }
    writePeb(path, newText, report, dryRun)
}

/**
 * Replaces every occurrence of the app's base package (as found in the
 * module build.gradle) with the PACKAGE_NAME token across all .java and .kt
 * files under the module's source tree - both in `package ...` declarations
 * and in `import ...` statements referencing the base package or a subpackage.
 */
private fun processJavaKtSources(
    projectDir: File,
    module: String,
    basePackage: String?,
    report: Report,
    dryRun: Boolean,
) {
    if (basePackage.isNullOrEmpty()) {
        report.skip(
            "${File(projectDir, module)}: no base package found in build.gradle, " +
                "skipping .java/.kt package substitution"
        )
        return
    }

    val srcMain = File(projectDir, "$module/src/main")
    val sourceFiles = srcMain.walkTopDown()
        .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
        .sortedWith(compareBy({ it.extension }, { it.path }))
        .toList()
    if (sourceFiles.isEmpty()) {
        report.skip("$srcMain: no .java/.kt source files found")
        return
    }

    val baseEscaped = Regex.escape(basePackage)

    for (path in sourceFiles) {
        var text = path.readText()
        val isJava = path.extension == "java"

        val packagePattern: String
        val importPattern: String
        if (isJava) {
            packagePattern = """^([ \t]*package[ \t]+)($baseEscaped)((?:\.[\w.]*)?[ \t]*;[ \t]*)$"""
            importPattern = """^([ \t]*import[ \t]+)($baseEscaped)((?:\.[\w.*]*)?[ \t]*;[ \t]*)$"""
        } else {
            packagePattern = """^([ \t]*package[ \t]+)($baseEscaped)((?:\.[\w.]*)?[ \t]*)$"""
            importPattern = """^([ \t]*import[ \t]+)($baseEscaped)((?:\.[\w.*]*)?[ \t]*)$"""
        }

        val (t1, n1) = subLineEndToken(text, packagePattern, "PACKAGE_NAME")
        text = t1
        // No sacrificial space here: unlike other substitutions, imports are
        // always followed by more package/class path text, not eaten by Pebble.
        val (t2, n2) = subLineEndToken(text, importPattern, "PACKAGE_NAME", trailingSpaces = 0)
        text = t2
        val total = n1 + n2

        if (total == 0) {
            report.skip("$path: no substitutions made")
            continue
        }
        writePeb(path, text, report, dryRun)
    }
}

/**
 * Moves the base package's directory tree under src/main/java (e.g.
 * com/example/app, including nested subpackages) into a single directory
 * literally named PACKAGE_NAME, then removes the now-empty parent dirs.
 */
private fun flattenPackageDirectory(
    projectDir: File,
    module: String,
    basePackage: String?,
    report: Report,
    dryRun: Boolean,
) {
    if (basePackage.isNullOrEmpty()) {
        report.skip("no base package found, skipping java/ package directory flattening")
        return
    }

    val javaRoot = File(projectDir, "$module/src/main/java")
    if (!javaRoot.isDirectory) {
        report.skip("$javaRoot not found")
        return
    }

    val pkgDir = basePackage.split(".").fold(javaRoot) { acc, part -> File(acc, part) }
    if (!pkgDir.isDirectory) {
        report.skip("$pkgDir not found, skipping java/ package directory flattening")
        return
    }

    val targetDir = File(javaRoot, "PACKAGE_NAME")
    report.ok("$pkgDir -> $targetDir")
    if (dryRun) return

    pkgDir.copyRecursively(targetDir, overwrite = true)
    pkgDir.deleteRecursively()

    var parent = pkgDir.parentFile
    while (parent != null && parent != javaRoot && parent.isDirectory && parent.listFiles()?.isEmpty() == true) {
        val next = parent.parentFile
        parent.delete()
        parent = next
    }
}

private fun cleanupBuildDirs(projectDir: File, report: Report, dryRun: Boolean) {
    val moduleRoots = mutableSetOf<File>()
    for (name in listOf("build.gradle.kts", "build.gradle", "build.gradle.kts.peb", "build.gradle.peb")) {
        projectDir.walkTopDown().filter { it.isFile && it.name == name }.forEach { it.parentFile?.let(moduleRoots::add) }
    }
    moduleRoots.add(projectDir)
    for (mod in moduleRoots.sortedBy { it.path }) {
        val buildDir = File(mod, "build")
        if (buildDir.isDirectory) {
            report.remove("$buildDir")
            if (!dryRun) buildDir.deleteRecursively()
        }
    }
}

private val KEYSTORE_EXTENSIONS = setOf("jks", "keystore", "p12")

private fun cleanupKeystores(projectDir: File, report: Report, dryRun: Boolean) {
    projectDir.walkTopDown()
        .filter { it.isFile && it.extension in KEYSTORE_EXTENSIONS }
        .forEach {
            report.remove("$it")
            if (!dryRun) it.delete()
        }
}

/**
 * When keystore cleanup is skipped, keystores are still copied into the bundle,
 * so flag every one loudly instead of silently shipping signing material.
 */
private fun flagKeystores(projectDir: File, report: Report) {
    projectDir.walkTopDown()
        .filter { it.isFile && it.extension in KEYSTORE_EXTENSIONS }
        .forEach {
            report.flag("$it is a signing keystore; remove it before distributing this template")
        }
}

private fun flagPersonalInfoFiles(projectDir: File, report: Report) {
    val candidates = listOf("local.properties", "google-services.json", "key.properties", "GoogleService-Info.plist")
    for (name in candidates) {
        projectDir.walkTopDown().filter { it.isFile && it.name == name }.forEach {
            report.flag("$it may contain machine-specific or personal information; review/delete manually")
        }
    }
}

private fun detectSourceLanguages(projectDir: File, module: String): Set<String> {
    val srcMain = File(projectDir, "$module/src/main")
    val languages = mutableSetOf<String>()
    if (srcMain.walkTopDown().any { it.isFile && it.extension == "java" }) languages += "java"
    if (srcMain.walkTopDown().any { it.isFile && it.extension == "kt" }) languages += "kotlin"
    return languages
}

private fun runPipeline(
    projectDir: File,
    module: String,
    dryRun: Boolean,
    skipCleanup: Boolean,
    report: Report,
): Set<String> {
    processGradleWrapper(projectDir, report, dryRun)
    processSettingsGradle(projectDir, report, dryRun)
    processRootBuildGradle(projectDir, report, dryRun)

    val languages = detectSourceLanguages(projectDir, module)

    val basePackage = processAppBuildGradle(projectDir, module, report, dryRun)

    processStringsXml(projectDir, module, report, dryRun)
    processJavaKtSources(projectDir, module, basePackage, report, dryRun)
    flattenPackageDirectory(projectDir, module, basePackage, report, dryRun)

    if (!skipCleanup) {
        cleanupBuildDirs(projectDir, report, dryRun)
        cleanupKeystores(projectDir, report, dryRun)
    } else {
        // Cleanup is skipped, so keystores are shipped as-is: flag them so the
        // warning is loudest in exactly the run where it matters most.
        flagKeystores(projectDir, report)
    }
    // Always warn about machine-specific / personal files, cleanup or not.
    flagPersonalInfoFiles(projectDir, report)

    return languages
}

private fun copyProject(source: File, dest: File, ignore: Set<String>) {
    // onEnter prunes descent into any ignored directory (so its whole subtree is
    // skipped and never copied); the name filter drops ignored files at any depth.
    source.walkTopDown()
        .onEnter { it == source || it.name !in ignore }
        .filter { it != source && it.name !in ignore }
        .forEach { src ->
            val relative = src.relativeTo(source)
            val target = File(dest, relative.path)
            if (src.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                src.copyTo(target, overwrite = true)
            }
        }
}

fun buildTemplateJson(templateName: String, includeLanguage: Boolean): JSONObject {
    val optional = JSONObject()
    if (includeLanguage) optional.put("language", JSONObject().put("identifier", "LANGUAGE"))
    optional.put("minsdk", JSONObject().put("identifier", "MIN_SDK"))

    val required = JSONObject()
        .put("appName", JSONObject().put("identifier", "APP_NAME"))
        .put("packageName", JSONObject().put("identifier", "PACKAGE_NAME"))
        .put("saveLocation", JSONObject().put("identifier", "SAVE_LOCATION"))

    val system = JSONObject()
        .put("agpVersion", JSONObject().put("identifier", "AGP_VERSION"))
        .put("kotlinVersion", JSONObject().put("identifier", "KOTLIN_VERSION"))
        .put("gradleVersion", JSONObject().put("identifier", "GRADLE_VERSION"))
        .put("compileSdk", JSONObject().put("identifier", "COMPILE_SDK"))
        .put("targetSdk", JSONObject().put("identifier", "TARGET_SDK"))
        .put("javaSourceCompat", JSONObject().put("identifier", "JAVA_SOURCE_COMPAT"))
        .put("javaTargetCompat", JSONObject().put("identifier", "JAVA_TARGET_COMPAT"))
        .put("javaTarget", JSONObject().put("identifier", "JAVA_TARGET"))

    return JSONObject()
        .put("name", templateName)
        .put("description", "Creates a new $templateName project")
        .put("version", "0.1")
        .put(
            "parameters",
            JSONObject().put("required", required).put("optional", optional),
        )
        .put("system", system)
}

// Generic template thumbnail (phone mockup), used as a stand-in until a project-specific one is supplied.
private const val THUMB_PNG_B64 =
        "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" +
        "AASAABAAAkAACAABIAAEgAAQAAJAAAgAASAABCAABIAAEAACQAAIAAEgAASAABAAAkAACAABIAAE" +
        "gAAQAAJAAAgAASAABIAAEAACQAAIAAEgAASAABAAAkAACAABIAAEgAAQAAJAAAgAASAABIAAEAAC" +
        "QAAIAAEgAASAABAAAkAACAABIAAEgAAQAAJAAAgAASAABIAAEAACQAAIAAEgAASAABAAAkAAAkAA" +
        "CAABIAAEgAAQAAJAAAgAASAABIAAEAACQAAIAAEgAASAABAAAkAACAABIAAEgAAQAAJAAAgAASAA" +
        "BIAAEAACQAAIAAEgAASAABAAAkAACAABIAAEgAAQAAJAAAgAASAABIAAEAACQAAIAAEgAASAABAA" +
        "AkAACAABIAAEgAAQAAJAAAgAASAABCAABIAAEAACQAAIAAEgAASAABAAAkAACAABIAAEgAAQAAJA" +
        "AAgAASAABIAAEAACQAAIAAEsJFf8wi5RNPyyI4h57o/hX+pFi+u7SyZ/yYfA2oAv6wLFs+fMRV/A" +
        "mLduPZww8XpNK8C4re04BZx6AmivVlpRAONq5ghwyiFghYfA7i4le8ApO0DKeXrKEEDq71C3B/D2" +
        "YyWNl2wNAfRjT1aAf7ICpP2VW8khsOs5OQS8dQQYrtS6DoGpu1OptgK8daXq7kqt5AyQ5jvbbgH4" +
        "asbHfN0tAfPTUrryFWB2R3Zdbw35mG0aT0x5ngvk2AL6lzXaj7UAjgdQ9xeqf+nsureAyRPa4S5t" +
        "nw35mOft8HBZXMBz2Zz17oTuR6PqPqnC93tjftWvv/+dOrlu8eb8G0BoE959DM39enww5tc9PA5v" +
        "A6RML5ydfwXol4BhPQhPT58N+hX/+V/zTHlcAXIsA+cOoJ968/nwDwp4ff513U9/3ATS9QfQjL5b" +
        "AroVofrr4dE54MD+/9sw/276ed46CTkC6BMIsfsVw93d7Wbzi5mPvm63z4+PzfLfJNC9FtSfBc6c" +
        "wdkDmD4TCEMFIca4+1rs/qzYv51S90+Pm+f+dffoH5eA/rlgOvcucJPhfu6O/2F/L+pm/nWqqxjT" +
        "bvpx96vOk+OlSNNP67aAupoPP9/bQTe57nSY3pu62ROaBqpYh2IGfyiEpoC6e+1vJtv75uH8//r9" +
        "U4HuFaF+K2i/UPIGMN0ExvlXab//T76T4tpXgGYT2K33qXtdqPnQTz+0G0AodPz9+2PV+E5J/6Ha" +
        "vxi8hi2gHXk1FtCOv/liGF4nKmj7f3EQSMsEqvH8X63gaWD/rw/zfaDaL/+hKvbhPwlg3Aaq+eqf" +
        "o4KQI7DpSWDsYHhZqPgAhnmPT/zmu3+qrj+AaQHVZO03/3kB+08mp78rD2BZwKSD6fxDoaOvpt//" +
        "O1n5s80/VwDjVjD9OYmj5Mf/vIFx78/0ZtD5r354eRgYz34h4/+PC10AJlv95O8DZJt/jgsfludB" +
        "j/7XV4HFye/avyFkPuTlWsDyLJDtcZ91BXiZwIvfFXDwYZ/WEsD0Vkre9k8+EGRcB3JNIRy/0VD0" +
        "0A9+Jb3DYPLekhXgyLTTu41lNTe2qrVhZTMRwvsPHgAAAAAAAAAAAAAAAAC4Tv8HzCUtAAlZ8mgA" +
        "AAAASUVORK5CYII="

data class ConversionResult(
    val outputDir: File,
    val templateDir: File,
    val cgtFile: File?,
    val report: Report,
    val languages: Set<String>,
    /** The templatized project directory (project files + template/), or null for a dry run. */
    val projectBundleDir: File?,
)

/**
 * Filesystem-safe form of a user-supplied template name. Maps anything outside
 * [A-Za-z0-9._-] to '_' and strips leading/trailing dots, so the name can never
 * contain a path separator or resolve to "." / ".." and escape the output
 * directory. Falls back to "template" if nothing usable remains.
 */
internal fun sanitizeTemplateName(name: String): String =
    name.trim()
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('.')
        .ifEmpty { "template" }

/**
 * Full pipeline: copies [projectDir] into a new bundle next to it, templatizes
 * the copy, writes templates.json / template/template.json / thumb.png, and
 * (unless [dryRun]) zips the bundle into a .cgt file.
 */
fun createTemplateBundle(
    projectDir: File,
    module: String = "app",
    templateName: String,
    skipCleanup: Boolean = false,
    dryRun: Boolean = false,
    onLine: (String) -> Unit = {},
): ConversionResult {
    val report = Report(onLine)
    // The template name is user-typed; use a path-safe form for every filesystem
    // path (bundle subdir, templates.json "path", .cgt filename) so it can't
    // escape outputDir. The original name is kept only for display metadata below.
    val safeName = sanitizeTemplateName(templateName)
    val outputDir = File(projectDir.parentFile, "${projectDir.name}-cgt")
    val dest = File(outputDir, safeName)

    // build/ is regenerated on the consuming side and cleanupBuildDirs would
    // only delete it again, so skip copying it entirely unless the user opts to
    // keep it (skipCleanup) - saving hundreds of MB of file I/O on a phone.
    val copyIgnore = if (skipCleanup) COPY_IGNORE else COPY_IGNORE + "build"

    if (dryRun) {
        // Preview against a disposable copy so a dry run never touches the real output path.
        val tmpRoot = File.createTempFile("cgt-dryrun", "").apply { delete(); mkdirs() }
        val tmpDest = File(tmpRoot, safeName)
        try {
            copyProject(projectDir, tmpDest, copyIgnore)
            val languages = runPipeline(tmpDest, module, dryRun, skipCleanup, report)
            report.ok("would write ${File(outputDir, "templates.json")}")
            report.ok("would write ${File(dest, "template/template.json")}")
            report.ok("would write ${File(dest, "template/thumb.png")}")
            return ConversionResult(outputDir, File(dest, "template"), null, report, languages, null)
        } finally {
            tmpRoot.deleteRecursively()
        }
    }

    // Wipe the whole output dir, not just dest/: it is reused across runs and
    // then zipped wholesale, so a leftover subtree from a previous template name
    // would otherwise be bundled into this run's .cgt.
    if (outputDir.exists()) {
        report.remove("$outputDir")
        outputDir.deleteRecursively()
    }
    outputDir.mkdirs()
    copyProject(projectDir, dest, copyIgnore)
    report.ok("$projectDir -> $dest")

    val languages = runPipeline(dest, module, dryRun, skipCleanup, report)
    val includeLanguage = languages.size > 1

    val templatesJson = File(outputDir, "templates.json")
    val templatesJsonBody = JSONObject().put(
        "templates",
        org.json.JSONArray().put(JSONObject().put("path", safeName)),
    )
    templatesJson.writeText(templatesJsonBody.toString(2) + "\n")
    report.ok("$templatesJson")

    val templateDir = File(dest, "template")
    templateDir.mkdirs()

    val templateJsonFile = File(templateDir, "template.json")
    templateJsonFile.writeText(buildTemplateJson(templateName, includeLanguage).toString(4) + "\n")
    report.ok("$templateJsonFile")

    val thumbPng = File(templateDir, "thumb.png")
    thumbPng.writeBytes(Base64.getDecoder().decode(THUMB_PNG_B64))
    report.ok("$thumbPng (generic thumbnail, replace with an app-specific one if desired)")

    val cgtFile = File(outputDir.parentFile, "$safeName.cgt")
    zipDirectory(outputDir, cgtFile)
    report.ok("$cgtFile")

    return ConversionResult(outputDir, templateDir, cgtFile, report, languages, dest)
}

/** Zips the contents of [sourceDir] (not the directory itself) into [destZip], storing only files at max compression. */
private fun zipDirectory(sourceDir: File, destZip: File) {
    if (destZip.exists()) destZip.delete()
    ZipOutputStream(destZip.outputStream()).use { zos ->
        zos.setLevel(9)
        sourceDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val entryName = file.relativeTo(sourceDir).path
                zos.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
    }
}
