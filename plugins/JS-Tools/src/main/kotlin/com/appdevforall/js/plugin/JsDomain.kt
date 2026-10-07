package com.appdevforall.js.plugin

import org.json.JSONException
import org.json.JSONObject
import java.io.File

internal sealed interface RunTarget {
    data class Source(val file: File) : RunTarget

    data class Script(val name: String) : RunTarget

    data class Invalid(val reason: String) : RunTarget
}

internal object JsDomain {

    private val GRADLE_MARKERS = listOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")
    private val PROJECT_MARKERS = listOf(PACKAGE_JSON, "tsconfig.json", "jsconfig.json")
    private val TYPE_CHECK_CONFIGS = listOf("tsconfig.json", "jsconfig.json")
    private val ENTRY_NAMES = listOf("index", "main")
    private val NODE_START = Regex("""node\s+([^\s&|;]+)""")
    private const val NPM_TEST_PLACEHOLDER = "no test specified"

    val SOURCE_EXTENSIONS = setOf("js", "mjs", "cjs", "jsx", "ts", "mts", "cts", "tsx")
    val RUNNABLE_EXTENSIONS = listOf("js", "ts", "mjs", "mts", "cjs", "cts")

    fun isJsProject(root: File?): Boolean {
        if (root == null) return false
        if (GRADLE_MARKERS.any { File(root, it).exists() }) return false
        if (PROJECT_MARKERS.any { File(root, it).isFile }) return true
        return sourceDirs(root).any { dir -> dir.listFiles()?.any { it.isFile && it.extension in SOURCE_EXTENSIONS } == true }
    }

    fun isRunnable(file: File): Boolean = file.extension in RUNNABLE_EXTENSIONS

    fun hasPackageJson(root: File): Boolean = File(root, PACKAGE_JSON).isFile

    fun typeCheckConfig(root: File): File? = TYPE_CHECK_CONFIGS.map { File(root, it) }.firstOrNull { it.isFile }

    fun runTarget(root: File): RunTarget? {
        val manifest = when (val read = readPackageJson(root)) {
            is PackageJson.Invalid -> return RunTarget.Invalid(read.reason)
            is PackageJson.Missing -> null
            is PackageJson.Valid -> read.json
        }
        val start = manifest?.optJSONObject("scripts")?.optString("start").orEmpty().trim()
        NODE_START.matchEntire(start)?.let { match ->
            runnableFile(root, match.groupValues[1])?.let { return RunTarget.Source(it) }
        }
        if (start.isNotEmpty()) return RunTarget.Script("start")
        manifest?.optString("main").orEmpty().takeIf { it.isNotEmpty() }?.let { main ->
            runnableFile(root, main)?.let { return RunTarget.Source(it) }
        }
        return sourceDirs(root)
            .flatMap { dir -> ENTRY_NAMES.flatMap { name -> RUNNABLE_EXTENSIONS.map { File(dir, "$name.$it") } } }
            .firstOrNull { it.isFile }
            ?.let(RunTarget::Source)
    }

    fun testScript(root: File): String? {
        val manifest = (readPackageJson(root) as? PackageJson.Valid)?.json ?: return null
        val test = manifest.optJSONObject("scripts")?.optString("test").orEmpty()
        return test.takeIf { it.isNotBlank() && NPM_TEST_PLACEHOLDER !in it }
    }

    private fun runnableFile(root: File, path: String): File? =
        File(root, path).takeIf { it.isFile && isRunnable(it) }

    private fun sourceDirs(root: File): List<File> = listOf(root, File(root, "src"))

    private sealed interface PackageJson {
        data object Missing : PackageJson

        data class Valid(val json: JSONObject) : PackageJson

        data class Invalid(val reason: String) : PackageJson
    }

    private fun readPackageJson(root: File): PackageJson {
        val file = File(root, PACKAGE_JSON)
        if (!file.isFile) return PackageJson.Missing
        return try {
            PackageJson.Valid(JSONObject(file.readText()))
        } catch (e: JSONException) {
            PackageJson.Invalid("$PACKAGE_JSON is not valid JSON: ${e.message}")
        }
    }
}

private const val PACKAGE_JSON = "package.json"
