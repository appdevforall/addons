package com.appdevforall.php.plugin

import org.json.JSONException
import org.json.JSONObject
import java.io.File

internal sealed interface RunTarget {
    data class Php(val arguments: List<String>) : RunTarget

    data class Script(val name: String) : RunTarget

    data class Invalid(val reason: String) : RunTarget
}

internal object PhpDomain {

    const val WEB_PORT = 8080

    private val GRADLE_MARKERS = listOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")
    private val SOURCE_DIRS = listOf("", "src", "public")
    private val ENTRY_FILES = listOf("index.php", "main.php", "app.php", "src/main.php", "src/index.php")
    private val PHP_COMMAND = Regex("""php\s+([^&|;<>$`'"\\]+)""")
    private const val WEB_ROOT = "public"

    val SOURCE_EXTENSIONS = setOf("php", "phtml")

    fun isPhpProject(root: File?): Boolean {
        if (root == null) return false
        if (GRADLE_MARKERS.any { File(root, it).exists() }) return false
        if (File(root, COMPOSER_JSON).isFile) return true
        return SOURCE_DIRS.any { dir -> File(root, dir).listFiles()?.any { it.isFile && it.extension in SOURCE_EXTENSIONS } == true }
    }

    fun isRunnable(file: File): Boolean = file.extension == "php"

    fun hasComposerJson(root: File): Boolean = File(root, COMPOSER_JSON).isFile

    fun runTarget(root: File): RunTarget? {
        val manifest = when (val read = readComposerJson(root)) {
            is ComposerJson.Invalid -> return RunTarget.Invalid(read.reason)
            is ComposerJson.Missing -> null
            is ComposerJson.Valid -> read.json
        }
        manifest?.optJSONObject("scripts")?.let { scripts ->
            if (scripts.has("start")) {
                val start = scripts.optString("start").trim()
                return PHP_COMMAND.matchEntire(start)
                    ?.let { RunTarget.Php(it.groupValues[1].trim().split(Regex("\\s+"))) }
                    ?: RunTarget.Script("start")
            }
        }
        if (File(root, "$WEB_ROOT/index.php").isFile) {
            return RunTarget.Php(listOf("-S", "localhost:$WEB_PORT", "-t", WEB_ROOT))
        }
        return ENTRY_FILES.firstOrNull { File(root, it).isFile }?.let { RunTarget.Php(listOf(it)) }
    }

    fun hasPhpUnit(root: File): Boolean = File(root, "vendor/bin/phpunit").isFile

    fun hasTestScript(root: File): Boolean =
        (readComposerJson(root) as? ComposerJson.Valid)?.json?.optJSONObject("scripts")?.has("test") == true

    private sealed interface ComposerJson {
        data object Missing : ComposerJson

        data class Valid(val json: JSONObject) : ComposerJson

        data class Invalid(val reason: String) : ComposerJson
    }

    private fun readComposerJson(root: File): ComposerJson {
        val file = File(root, COMPOSER_JSON)
        if (!file.isFile) return ComposerJson.Missing
        return try {
            ComposerJson.Valid(JSONObject(file.readText()))
        } catch (e: JSONException) {
            ComposerJson.Invalid("$COMPOSER_JSON is not valid JSON: ${e.message}")
        }
    }
}

private const val COMPOSER_JSON = "composer.json"
