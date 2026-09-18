package com.itsaky.androidide.plugins.aicore.tool

/**
 * The GBNF handed to the local backend, forcing one well-formed `<tool_call>`.
 *
 * Kept beside the tool set it is derived from: the `tool` alternatives are the registered tool
 * names, so a grammar built from a stale tool list silently forbids every tool the model was just
 * told about.
 */
object ToolCallGrammar {

    /**
     * Builds the grammar for a tool set.
     *
     * Control characters are excluded from string values so `org.json` accepts what comes back;
     * argument keys stay `[a-z_]+`, which constrains keys but not the quoted tool names.
     *
     * @param toolNames every tool the model may call, including the terminal one.
     * @return the GBNF grammar string.
     */
    fun build(toolNames: List<String>): String {
        val toolAlternatives = toolNames.distinct().joinToString(" | ") { "\"~$it~\"" }
        return """
            root ::= "<tool_call>{~tool~:" tool ",~args~:" args "}</tool_call>"
            tool ::= $toolAlternatives
            args ::= "{}" | "{" pair ("," pair)* "}"
            pair ::= "~" key "~:~" val "~"
            key  ::= [a-z_]+
            val  ::= char*
            char ::= [^~\\\x00-\x1F] | "\\" [~\\/bfnrt]
        """.trimIndent().replace("~", "\\\"")
    }
}
