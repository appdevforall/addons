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

/** A whole-number argument as a model sends it; see [parseToolInt]. */
sealed interface ToolInt {
    /** The call left the argument out. */
    data object Absent : ToolInt

    /** The argument holds [value]. */
    data class Present(val value: Int) : ToolInt

    /** The argument is there but is not a whole number; [raw] is what the model sent. */
    data class Invalid(val raw: String) : ToolInt
}

/**
 * Reads a whole number out of a tool-call argument: a JSON number, or the string a model emits
 * when the tool-call grammar has no integer type (`"96"`, `"96.0"`). A blank string counts as absent.
 * @param value the raw argument value, or null when absent.
 * @return the number, or why there is none.
 */
fun parseToolInt(value: Any?): ToolInt = when (value) {
    null -> ToolInt.Absent
    is Int -> ToolInt.Present(value)
    // A JSON decoder may hand 96 over as 96.0.
    is Number -> value.toDouble().wholeIntOrNull()?.let(ToolInt::Present) ?: ToolInt.Invalid(value.toString())
    else -> value.toString().trim().let { text ->
        if (text.isEmpty()) ToolInt.Absent else text.wholeIntOrNull()?.let(ToolInt::Present) ?: ToolInt.Invalid(text)
    }
}

// The reading a JSON number gets; BigDecimal, unlike toDouble, rejects "96f" and hex.
private fun String.wholeIntOrNull(): Int? = toIntOrNull() ?: toBigDecimalOrNull()?.toDouble()?.wholeIntOrNull()

private fun Double.wholeIntOrNull(): Int? =
    takeIf { it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt()

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
 * What a file write changes, apart from its file: the tool and its [ToolHandler.changeArgs] as the
 * tool will read them, so a retry that spells an argument another way, or adds one the tool
 * ignores, is still the same change.
 * @param args the call's raw arguments.
 * @return a value equal for two calls that make the same change to the same file.
 */
fun ToolHandler.changeOf(args: Map<String, Any?>): Any {
    val normalized = normalizeToolArgs(this, args)
    return toolName to changeArgs.associateWith { normalized[it]?.toString() }
}

/**
 * The project paths a call reads or writes, as the tool will see them: normalized keys first, then
 * [ToolHandler.pathDefaults] for a path the call left the handler to fill in.
 * @param args the call's raw arguments.
 * @return the paths named, trimmed, without the blanks.
 */
fun ToolHandler.pathsIn(args: Map<String, Any?>): Set<String> {
    val normalized = normalizeToolArgs(this, args)
    return trackedPathArgs.mapNotNullTo(mutableSetOf()) { key ->
        (normalized[key]?.toString() ?: pathDefaults[key])?.trim()?.takeIf { it.isNotEmpty() }
    }
}
