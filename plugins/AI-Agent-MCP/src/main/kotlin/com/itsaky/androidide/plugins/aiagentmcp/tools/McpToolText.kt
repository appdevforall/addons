package com.itsaky.androidide.plugins.aiagentmcp.tools

/**
 * Sanitises the text an MCP server supplies before it leaves this plugin.
 *
 * Only the exposed *name* is built here. A name outside `[a-z0-9_]` is one the model can read but
 * never call, and the prefix and disambiguation rules below need a sanitised name to work on.
 * Descriptions are left alone on purpose: the agent flattens and caps every contributed description
 * itself, on its own side of the plugin boundary, and a second cap here only gave the two constants
 * room to diverge — whereupon the agent's truncation counter would quietly report nothing.
 */
object McpToolText {

    /** Max characters kept from a server label used as a tool-name prefix. */
    private const val MAX_PREFIX_LENGTH = 10

    /** Max characters kept from a tool name; the agent caps the namespaced form again. */
    private const val MAX_NAME_LENGTH = 24

    /** Numbered variants tried for a name two tools truncated onto; past this the tool is dropped. */
    private const val MAX_VARIANTS = 20

    /**
     * The exposed name for one remote tool: a short server prefix plus the tool's own name.
     * @param serverName the user's label for the server.
     * @param toolName the tool's own name.
     * @return the exposed name, or null when nothing usable survives sanitising.
     */
    fun exposedName(serverName: String, toolName: String): String? =
        exposedNameWithPrefix(identifier(serverName).take(MAX_PREFIX_LENGTH).trim('_'), toolName)

    /**
     * As [exposedName], for a prefix that is already decided (see [appPrefixes]).
     * @param prefix a sanitised prefix of at most [MAX_PREFIX_LENGTH] characters.
     */
    fun exposedNameWithPrefix(prefix: String, toolName: String): String? {
        val tool = identifier(toolName).take(MAX_NAME_LENGTH).trim('_')
        if (tool.isEmpty()) return null
        return if (prefix.isEmpty()) tool else "${prefix}_$tool"
    }

    /**
     * The name prefix of each SQLite debugger entry, told apart by app.
     *
     * The server label cannot do it: every entry is labelled "SQLite debugger (...)", and the
     * first [MAX_PREFIX_LENGTH] characters of that are the same for all of them. Each app takes the
     * first of these forms that is its own:
     * 1. `db_` and the first seven characters of the application ID's last segment (`db_notes`);
     * 2. the first five characters of the segment and four hex digits of a digest of the full
     *    application ID (`notes_3fa1`), when another app would share form 1 (`com.a.notes` and
     *    `com.b.notes`);
     * 3. the segment's first character and eight hex digits of that digest (`n_3fa1c2d4`);
     * 4. `db_` and a number, counted in application-ID order (`db_1`).
     *
     * A form is an app's own when no other app still choosing would take the same prefix and no
     * app has already settled on it; every app settles on form 1 before any takes form 2, and so
     * on. So the prefixes are distinct, and no app's later form takes another's earlier one, as
     * `com.a.db`'s form 2, `db_ec81`, would take the form 1 of `com.c.ec81`. Each depends only on
     * the set of application IDs, never on the order they were read in, so two listings of the same
     * apps give every app the same name. Each fits [MAX_PREFIX_LENGTH].
     *
     * @param applicationIds the application ID of every entry.
     * @return the prefix by application ID.
     */
    fun appPrefixes(applicationIds: Collection<String>): Map<String, String> {
        val prefixes = mutableMapOf<String, String>()
        // Sorted, so the numbering of form 4 follows the IDs rather than the order they came in.
        var choosing = applicationIds.toSortedSet().toList()
        for (form in appPrefixForms) {
            val wanted = choosing.associateWith { form(it) }
            val wanting = wanted.values.groupingBy { it }.eachCount()
            val settled = prefixes.values.toSet()
            for ((id, prefix) in wanted) {
                if (wanting.getValue(prefix) == 1 && prefix !in settled) prefixes[id] = prefix
            }
            choosing = choosing.filter { it !in prefixes }
        }
        // Form 4: only apps whose digests agree in 32 bits get here.
        var number = 1
        for (id in choosing) {
            while ("db_$number" in prefixes.values) number++
            prefixes[id] = "db_$number"
        }
        return prefixes
    }

    /** Forms 1 to 3 of [appPrefixes], each a function of one application ID alone. */
    private val appPrefixForms: List<(String) -> String> = listOf(
        { id -> "db_" + segment(id).take(MAX_PREFIX_LENGTH - 3).trim('_') },
        { id -> segment(id).take(5).trim('_') + "_" + digest(id, 2) },
        { id -> segment(id).take(1) + "_" + digest(id, 4) },
    )

    private fun segment(applicationId: String): String =
        identifier(applicationId.substringAfterLast('.')).ifEmpty { "app" }

    /** The first [bytes] bytes of the SHA-256 of [text], in hex. */
    private fun digest(text: String, bytes: Int): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .take(bytes).joinToString("") { "%02x".format(it) }

    /**
     * A free variant of [name], numbered when two tools ended up sharing one exposed name.
     *
     * Truncation is what makes this necessary: `get_pull_request_comments` and
     * `get_pull_request_committers` are distinct tools that both survive [MAX_NAME_LENGTH] as the
     * same string, and dropping the second would silently take away a tool the user switched on.
     *
     * The numbered form still fits the agent's own cap: the longest name this builds is
     * [MAX_PREFIX_LENGTH] + [MAX_NAME_LENGTH] + a two-digit suffix.
     *
     * @param name the name [exposedName] produced.
     * @param taken the names already published by this source.
     * @return the name to publish, or null when even the numbered forms are spoken for.
     */
    fun disambiguate(name: String, taken: Set<String>): String? {
        if (name !in taken) return name
        return (2..MAX_VARIANTS).map { "${name}_$it" }.firstOrNull { it !in taken }
    }

    /** Lowercases and reduces to `[a-z0-9_]`, collapsing runs of separators into one `_`. */
    private fun identifier(raw: String): String {
        val mapped = raw.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
        return buildString {
            for (char in mapped) {
                if (char == '_' && endsWith("_")) continue
                append(char)
            }
        }.trim('_')
    }
}
