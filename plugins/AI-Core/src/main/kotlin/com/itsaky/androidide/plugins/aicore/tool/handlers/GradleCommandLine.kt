package com.itsaky.androidide.plugins.aicore.tool.handlers

/** Tasks and Gradle arguments as the host takes them. */
internal data class GradleInvocation(val tasks: List<String>, val arguments: List<String>)

/**
 * Reads the model's `tasks` and `arguments` into a [GradleInvocation]. Knows Gradle's command line,
 * not the tool's argument names, so [RunGradleTaskHandler] keeps the call shape to itself.
 */
internal object GradleCommandLine {

    // Gradle options whose value is the next token rather than `=value`.
    private val VALUE_OPTIONS = setOf(
        "-x", "--exclude-task", "--tests", "-p", "--project-dir", "-I", "--init-script",
        "-g", "--gradle-user-home", "--console", "--warning-mode", "--max-workers", "--include-build",
    )

    /**
     * Leading options, and everything from the first option after a task, move from [tasks] to the
     * arguments: models write `tasks="test --tests Foo"` as often as not.
     * @param tasks the tasks value as the model gave it: a string, a list, or null.
     * @param arguments the arguments value, read the same way.
     * @return the invocation; its task list is empty when the call names none.
     */
    fun parse(tasks: Any?, arguments: Any?): GradleInvocation {
        val taskTokens = tokensOf(tasks)
        var start = 0
        while (start < taskTokens.size && taskTokens[start].startsWith("-")) {
            // `-x lint test` must not run lint, so a leading option's own value stays with it.
            start += if (taskTokens[start] in VALUE_OPTIONS) 2 else 1
        }
        start = start.coerceAtMost(taskTokens.size)
        val firstOption = (start until taskTokens.size)
            .firstOrNull { taskTokens[it].startsWith("-") } ?: taskTokens.size
        return GradleInvocation(
            tasks = taskTokens.subList(start, firstOption),
            arguments = taskTokens.subList(0, start) +
                taskTokens.subList(firstOption, taskTokens.size) + tokensOf(arguments),
        )
    }

    /**
     * Splits a tool argument into command-line tokens: a string on whitespace with single or
     * double quotes grouping, so `--tests "com.example.Foo*"` stays one value; a list per entry.
     */
    fun tokensOf(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is List<*> -> value.filterNotNull().flatMap { splitCommandLine(it.toString()) }
        else -> splitCommandLine(value.toString())
    }.filter(String::isNotEmpty)

    private fun splitCommandLine(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var inToken = false
        for (c in text) {
            when {
                quote != null && c == quote -> quote = null
                quote != null -> current.append(c)
                c == '"' || c == '\'' -> { quote = c; inToken = true }
                c.isWhitespace() -> if (inToken) {
                    tokens += current.toString()
                    current.clear()
                    inToken = false
                }
                else -> { current.append(c); inToken = true }
            }
        }
        if (inToken) tokens += current.toString()
        return tokens
    }
}
