package org.appdevforall.shellhooks.engine

/**
 * Quoting for the one place a command must be a string: the Terminal takes a command line, not
 * an argument list. Hooks run headless never pass through here; their values travel as argv.
 */
object ShellQuote {
    /**
     * [value] as one POSIX shell word. Inside single quotes nothing is special, so the only
     * character to handle is the single quote itself: close the quote, emit an escaped quote,
     * reopen. `$(...)`, backticks, `"` and newlines all stay literal.
     */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

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
