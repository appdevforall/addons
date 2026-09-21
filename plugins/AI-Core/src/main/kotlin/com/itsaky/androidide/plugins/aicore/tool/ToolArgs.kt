package com.itsaky.androidide.plugins.aicore.tool

/** Argument values a model uses to mean "true"; the tool-call grammar has no boolean type. */
private val TRUE_WORDS = setOf("true", "yes", "1")

/**
 * Reads a boolean out of a tool-call argument, tolerating the strings a model emits instead
 * (`"true"`, `"yes"`, `"1"`, any casing). Shared so the dialog and the handler cannot disagree about
 * `replace_all`; the no-argument [String.lowercase] keeps `"TRUE"` matching under tr-TR.
 * @param value the raw argument value, or null when absent.
 * @return true only for an explicit affirmative; false for null and anything else.
 */
fun parseToolBoolean(value: Any?): Boolean = when (value) {
    null -> false
    is Boolean -> value
    else -> value.toString().trim().lowercase() in TRUE_WORDS
}

/**
 * Argument keys as the handlers declare them: the `path` → `file_path` near-miss small models
 * produce, then [ToolHandler.argAliases]; a canonical key the model did supply always wins.
 * Shared so the progress guard reads a call the same way the executor will run it.
 * @param handler the handler the call resolved to.
 * @param args the model's raw arguments.
 * @return the arguments with every alias resolved.
 */
fun normalizeToolArgs(handler: ToolHandler, args: Map<String, Any?>): Map<String, Any?> {
    val pathAlias = if ("file_path" in Executor.requiredArgsForTool(handler.toolName)) {
        mapOf("path" to "file_path")
    } else {
        emptyMap()
    }
    val normalized = args.toMutableMap()
    (pathAlias + handler.argAliases).forEach { (alias, canonical) ->
        if (canonical !in normalized && alias in normalized) {
            normalized[canonical] = normalized[alias]
        }
    }
    return normalized
}

/**
 * The project paths a call names, as the tool will see them: normalized keys first, then
 * [ToolHandler.pathDefaults] for a path the call left the handler to fill in.
 * @param args the call's raw arguments.
 * @return the paths named, trimmed, without the blanks.
 */
fun ToolHandler.pathsIn(args: Map<String, Any?>): Set<String> {
    val normalized = normalizeToolArgs(this, args)
    return pathArgs.mapNotNullTo(mutableSetOf()) { key ->
        (normalized[key]?.toString() ?: pathDefaults[key])?.trim()?.takeIf { it.isNotEmpty() }
    }
}
