package com.itsaky.androidide.plugins.aicore.tool

/**
 * Parses Gemini's own call dialect — `print(default_api.run_app())`, usually inside a `<tool_code>`
 * block — into [ToolCall]s.
 *
 * Gemini falls back to this surface when the tools are described in prose rather than declared
 * through its function-calling API. It is not the envelope either system prompt teaches, so a reply
 * written in it ran nothing and was pasted into the chat instead (ADFA-5410).
 *
 * Pure and free of Android types, so the quoting rules that decide whether a call runs are
 * unit-testable without a device.
 */
object ToolCodeParser {

    /** The call itself. Anchored on `default_api.`, which no ordinary prose contains. */
    private val DEFAULT_API_CALL = Regex("""default_api\.([A-Za-z_]\w*)\s*\(""")

    /** Anything that reads as this dialect, whether or not a call could be parsed out of it. */
    private val TOOL_CODE_MARKER =
        Regex("""<tool_code>|```+\s*tool_code|default_api\.""", RegexOption.IGNORE_CASE)

    /** A keyword argument's name. */
    private val IDENTIFIER = Regex("""[A-Za-z_]\w*""")

    /**
     * Whether [text] is written in this dialect.
     *
     * True even for a block [parse] rejects, so a call this side cannot read is still reported to
     * the user rather than mistaken for an ordinary prose reply.
     *
     * @param text the model's raw reply.
     * @return true when the reply reads as `tool_code`.
     */
    fun looksLikeToolCode(text: String): Boolean = TOOL_CODE_MARKER.containsMatchIn(text)

    /**
     * Every `default_api.<tool>(…)` call in [text], in the order they appear.
     *
     * Only keyword arguments are read. Mapping a positional argument to a parameter needs the
     * tool's schema, which is not here, and a tool run with its arguments in the wrong slots is
     * worse than one that did not run — so such a call is skipped for [looksLikeToolCode] to report.
     *
     * @param text the model's raw reply.
     * @return the calls found; empty when there are none this side can read.
     */
    fun parse(text: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        for (match in DEFAULT_API_CALL.findAll(text)) {
            val open = match.range.last
            val close = matchingBracket(text, open)
            if (close < 0) continue
            val args = parseKeywordArgs(text.substring(open + 1, close)) ?: continue
            calls += ToolCall(match.groupValues[1], args)
        }
        return calls
    }

    /**
     * The index of the bracket closing the one at [open], skipping over string literals.
     *
     * @return the closing index, or -1 when the call is unterminated.
     */
    private fun matchingBracket(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            val c = text[i]
            if (c == '"' || c == '\'') {
                val end = endOfString(text, i)
                if (end < 0) return -1
                i = end
                continue
            }
            if (c == '(' || c == '[' || c == '{') depth++
            if (c == ')' || c == ']' || c == '}') {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    /**
     * The index just past the string literal starting at [start], triple-quoted or not.
     *
     * Triple quotes matter: a model writing a file's contents reaches for them, and reading one as
     * an empty `''` would end the argument list in the middle of the payload.
     *
     * @return the index just past the literal, or -1 when it is unterminated.
     */
    private fun endOfString(text: String, start: Int): Int {
        val quote = text[start]
        val delimiter = if (text.startsWith("$quote$quote$quote", start)) "$quote$quote$quote" else "$quote"
        var i = start + delimiter.length
        while (i < text.length) {
            if (text[i] == '\\') {
                i += 2
                continue
            }
            if (text.startsWith(delimiter, i)) return i + delimiter.length
            i++
        }
        return -1
    }

    /**
     * Reads `name=value` pairs out of an argument list.
     *
     * @param args the text between the call's brackets.
     * @return the arguments, or null when one of them is positional or malformed.
     */
    private fun parseKeywordArgs(args: String): Map<String, Any?>? {
        if (args.isBlank()) return emptyMap()
        val parsed = mutableMapOf<String, Any?>()
        for (part in splitTopLevel(args) ?: return null) {
            val assignment = topLevelAssignment(part) ?: return null
            val name = part.substring(0, assignment).trim()
            if (!IDENTIFIER.matches(name)) return null
            parsed[name] = parseValue(part.substring(assignment + 1).trim())
        }
        return parsed
    }

    /**
     * Splits an argument list on the commas that separate arguments, not the ones inside them.
     *
     * @return the arguments, or null when a string literal is unterminated.
     */
    private fun splitTopLevel(args: String): List<String>? {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < args.length) {
            val c = args[i]
            if (c == '"' || c == '\'') {
                val end = endOfString(args, i)
                if (end < 0) return null
                i = end
                continue
            }
            if (c == '(' || c == '[' || c == '{') depth++
            if (c == ')' || c == ']' || c == '}') depth--
            if (c == ',' && depth == 0) {
                parts += args.substring(start, i)
                start = i + 1
            }
            i++
        }
        parts += args.substring(start)
        return parts.filter { it.isNotBlank() }
    }

    /**
     * The index of the `=` binding an argument's name to its value.
     *
     * @return the index, or null when the part carries no top-level assignment (so, positional).
     */
    private fun topLevelAssignment(part: String): Int? {
        var depth = 0
        var i = 0
        while (i < part.length) {
            val c = part[i]
            if (c == '"' || c == '\'') {
                val end = endOfString(part, i)
                if (end < 0) return null
                i = end
                continue
            }
            if (c == '(' || c == '[' || c == '{') depth++
            if (c == ')' || c == ']' || c == '}') depth--
            // Not `==`, `!=`, `<=` or `>=`: those are an expression, never a keyword argument.
            if (c == '=' && depth == 0 && part.getOrNull(i + 1) != '=' &&
                part.getOrNull(i - 1) !in listOf('=', '!', '<', '>')
            ) {
                return i
            }
            i++
        }
        return null
    }

    /**
     * Python string prefixes, which reach a path argument as part of its value if not stripped.
     *
     * The lookahead is what keeps this off an ordinary bare word: only a prefix immediately
     * followed by a quote is one.
     */
    private val STRING_PREFIX_REGEX = Regex("""^[rbuf]{1,2}(?=["'])""", RegexOption.IGNORE_CASE)

    /**
     * Reads one argument value: a string literal, a Python literal, or the raw text.
     *
     * @param raw the value as written, already trimmed.
     * @return the value, with a string literal unquoted and unescaped.
     */
    private fun parseValue(raw: String): Any? {
        if (raw.isEmpty()) return ""
        // `r"app/src/…"` is still a path; leaving the prefix on fails the file operation instead.
        val literal = raw.substring(STRING_PREFIX_REGEX.find(raw)?.value?.length ?: 0)
        val quote = literal[0]
        if ((quote == '"' || quote == '\'') && endOfString(literal, 0) == literal.length) {
            val delimiter = if (literal.startsWith("$quote$quote$quote")) 3 else 1
            return unescape(literal.substring(delimiter, literal.length - delimiter))
        }
        return when (raw) {
            "True" -> true
            "False" -> false
            "None" -> null
            // A list or dict stays raw text, as it does coming out of the JSON envelope.
            else -> raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: raw
        }
    }

    /**
     * Resolves the escape sequences in a string literal's body.
     *
     * @param value the literal's contents, without its quotes.
     * @return the text the model meant; an unknown escape is left as written.
     */
    private fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            if (value[i] != '\\' || i == value.length - 1) {
                out.append(value[i])
                i++
                continue
            }
            when (val escape = value[i + 1]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                '\\' -> out.append('\\')
                '"' -> out.append('"')
                '\'' -> out.append('\'')
                // A backslash before a newline is a line continuation: both characters go.
                '\n' -> Unit
                'u' -> {
                    val hex = value.substring(i + 2, minOf(i + 6, value.length))
                    val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                    if (code != null) {
                        out.append(code.toChar())
                        i += 6
                        continue
                    }
                    out.append('\\').append(escape)
                }
                else -> out.append('\\').append(escape)
            }
            i += 2
        }
        return out.toString()
    }
}
