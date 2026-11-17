package org.appdevforall.shellhooks.engine

/**
 * Quoting for the one place a command must be a string: the Terminal takes a command line, not
 * an argument list, and runs it with bash. Hooks run headless never pass through here; their
 * values travel as argv.
 */
object ShellQuote {
    /**
     * [value] as one bash word. Inside single quotes nothing is special, so a single quote is
     * the only character the shell needs handled: close the quote, emit an escaped quote, reopen.
     * `$(...)`, backticks and `"` stay literal.
     *
     * Control characters are written as `$'\xHH'` bytes instead. The Terminal prints the command
     * line before running it, so a raw escape sequence in a file's name would reach the terminal
     * and could, for example, set the clipboard (OSC 52). The line printed is then one line of
     * plain text, and the program still gets the exact bytes. `$'...'` is bash's: dash has no such
     * quoting. A NUL is refused: bash ends a `$'...'` string there, so no quoting carries one,
     * and no path or environment value can hold one anyway.
     */
    fun quote(value: String): String {
        require('\u0000' !in value) { "A shell word cannot hold a NUL character" }
        val out = StringBuilder("'")
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            when {
                isControl(cp) -> {
                    out.append("'$'")
                    while (i < value.length && isControl(value.codePointAt(i))) {
                        val c = value.codePointAt(i)
                        for (byte in String(Character.toChars(c)).toByteArray(Charsets.UTF_8)) {
                            out.append("\\x").append("%02x".format(byte.toInt() and 0xff))
                        }
                        i += Character.charCount(c)
                    }
                    out.append("''")
                    continue
                }
                cp == '\''.code -> out.append("'\\''")
                else -> out.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return out.append("'").toString()
    }

    /** C0 controls (newline and tab included), DEL, and the C1 controls a terminal may act on. */
    private fun isControl(cp: Int): Boolean = cp < 0x20 || cp in 0x7f..0x9f

    /** [words] as a command line in which each word reaches the program unchanged. */
    fun join(words: List<String>): String = words.joinToString(" ") { quote(it) }

    /**
     * `NAME='value' ... word ...`: [environment] set for this one command. Names are not quoted,
     * so only names the plugin itself chose may be passed.
     */
    fun command(
        environment: Map<String, String>,
        words: List<String>,
    ): String {
        environment.keys.forEach { require(NAME.matches(it)) { "Not an environment variable name: $it" } }
        val assignments = environment.entries.joinToString(" ") { (name, value) -> name + "=" + quote(value) }
        return listOf(assignments, join(words)).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
}
