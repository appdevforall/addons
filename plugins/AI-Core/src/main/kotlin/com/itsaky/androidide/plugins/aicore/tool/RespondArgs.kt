package com.itsaky.androidide.plugins.aicore.tool

/**
 * Keys a model uses for the `respond` payload, in preference order. `message` is what both
 * system prompts document; the rest are what models substitute anyway, and a `respond` call
 * whose text is under the wrong key is a finished answer that would otherwise be discarded.
 */
private val RESPOND_MESSAGE_KEYS = listOf("message", "text", "response", "answer", "content")

/**
 * Reads the user-facing answer out of a `respond` call's arguments, tolerating the keys a model
 * substitutes for `message`. The same tolerance the handlers get from `ToolHandler.argAliases`,
 * which `respond` never had because it has no handler.
 * @param args the `respond` call's arguments.
 * @return the answer, or null when no key carries usable text.
 */
fun respondMessageOf(args: Map<String, Any?>): String? =
    RESPOND_MESSAGE_KEYS.firstNotNullOfOrNull { key ->
        args[key]?.toString()?.takeIf { it.isNotBlank() }
    }

/**
 * Whether a call names the terminal tool, as loosely as a model may name it.
 *
 * Matched the way [ToolRouter] matches a handler rather than by equality: a cloud backend ignores
 * the grammar, so `Respond` and `  respond ` both arrive, and a terminal call that fails to be
 * recognised as one is routed as an ordinary tool — which is how a contributed tool could end up
 * being handed the user's final answer.
 *
 * @param name the name the model emitted.
 * @param terminalTool the answer-carrying pseudo-tool's registered name.
 * @return true when the call is the terminal one.
 */
fun isTerminalToolName(name: String, terminalTool: String): Boolean =
    name.trim().equals(terminalTool.trim(), ignoreCase = true)
