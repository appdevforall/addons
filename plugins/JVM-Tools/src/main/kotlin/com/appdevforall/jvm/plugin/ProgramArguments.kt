package com.appdevforall.jvm.plugin

internal object ProgramArguments {

    fun parse(input: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var inArgument = false
        var quote: Char? = null
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                quote == '\'' -> if (c == '\'') quote = null else current.append(c)
                quote == '"' -> when {
                    c == '"' -> quote = null
                    c == '\\' && i + 1 < input.length && input[i + 1] in "\"\\" -> current.append(input[++i])
                    else -> current.append(c)
                }
                c.isWhitespace() -> if (inArgument) {
                    args += current.toString()
                    current.clear()
                    inArgument = false
                }
                c == '\'' || c == '"' -> {
                    quote = c
                    inArgument = true
                }
                c == '\\' && i + 1 < input.length -> {
                    current.append(input[++i])
                    inArgument = true
                }
                else -> {
                    current.append(c)
                    inArgument = true
                }
            }
            i++
        }
        require(quote == null) { "Close the $quote quote in the program arguments." }
        if (inArgument) args += current.toString()
        return args
    }
}
