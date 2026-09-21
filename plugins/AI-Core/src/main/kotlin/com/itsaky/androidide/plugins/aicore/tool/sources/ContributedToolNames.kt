package com.itsaky.androidide.plugins.aicore.tool.sources

/**
 * Turns a source-local tool name into the globally unique one the model is shown.
 *
 * A [ContributedTool.name] is unique only within its source, so this plugin owns the global
 * namespace. It spends that ownership as cheaply as it can: the tool keeps its own name, and only a
 * tool whose name is already taken is qualified with its provider's alias. Prefixing everything
 * unconditionally bought uniqueness nobody needed and cost the model the one name the tool's own
 * description talks about — it read `add` and had to emit `aiagentmcp_test_add`.
 *
 * A name that cannot be sanitised is rejected rather than registered broken.
 */
object ContributedToolNames {

    /** Max characters kept from the provider alias, so the prefix cannot eat the whole name. */
    private const val MAX_ALIAS_LENGTH = 12

    /** Max characters in the namespaced name. Model behaviour, not the grammar, is the limit. */
    const val MAX_TOOL_NAME_LENGTH = 40

    /**
     * Short alias for a provider, taken from the last segment of its id.
     * @param providerId the contributing plugin's id.
     * @return the alias, or empty when nothing usable survives sanitising.
     */
    fun aliasFor(providerId: String): String =
        sanitize(providerId.substringAfterLast('.')).take(MAX_ALIAS_LENGTH).trim('_')

    /**
     * The names to try for one contributed tool, best first.
     *
     * The caller takes the first that is still free, so the plain name goes to whichever source
     * registered first and the loser is qualified rather than dropped.
     *
     * @param providerId the contributing plugin's id, source of the qualifying prefix.
     * @param toolName the tool's own name.
     * @return the candidates, or empty when nothing usable survives sanitising.
     */
    fun candidates(providerId: String, toolName: String): List<String> {
        val plain = sanitize(toolName).trim('_').take(MAX_TOOL_NAME_LENGTH).trim('_')
        if (plain.isEmpty()) return emptyList()
        return listOfNotNull(plain, qualify(providerId, toolName)).distinct()
    }

    /**
     * The provider-qualified name, used when a tool's own name is already taken.
     * @param providerId the contributing plugin's id, source of the prefix.
     * @param toolName the tool's own name.
     * @return the qualified name, or null when neither part survives sanitising.
     */
    fun qualify(providerId: String, toolName: String): String? {
        val tool = sanitize(toolName).trim('_')
        if (tool.isEmpty()) return null
        val alias = aliasFor(providerId)
        val full = if (alias.isEmpty()) tool else "${alias}_$tool"
        return full.take(MAX_TOOL_NAME_LENGTH).trim('_').ifEmpty { null }
    }

    /** Lowercases and reduces to `[a-z0-9_]`, collapsing runs of separators into one `_`. */
    private fun sanitize(raw: String): String {
        val mapped = raw.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
        return buildString {
            for (char in mapped) {
                if (char == '_' && endsWith("_")) continue
                append(char)
            }
        }
    }
}
