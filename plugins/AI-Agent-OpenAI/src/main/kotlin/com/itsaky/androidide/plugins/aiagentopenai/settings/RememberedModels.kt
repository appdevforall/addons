package com.itsaky.androidide.plugins.aiagentopenai.settings

/**
 * Encodes the last fetched model list for storage, so reopening the settings pane offers the
 * dropdown again without another request to the server.
 *
 * Pure, so the round trip is unit-testable. Newline-delimited rather than JSON: a model id never
 * contains a newline, and a parse that cannot throw is one less way to lose the list.
 */
internal object RememberedModels {

    /** Separator; safe because no model id contains a newline. */
    private const val SEPARATOR = "\n"

    /**
     * Cap on remembered entries. A large OpenRouter catalog runs to hundreds of ids, and
     * SharedPreferences is the wrong place for an unbounded list.
     */
    const val MAX_REMEMBERED = 200

    /**
     * Flattens [models] for storage, dropping blanks and duplicates.
     *
     * @return the encoded form, or null when there is nothing worth remembering
     */
    fun encode(models: List<String>): String? = models
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_REMEMBERED)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(SEPARATOR)

    /**
     * Restores what [encode] wrote.
     *
     * @param stored the encoded value, or null when nothing is stored
     * @return the remembered ids, empty when there are none
     */
    fun decode(stored: String?): List<String> = stored
        ?.split(SEPARATOR)
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.distinct()
        .orEmpty()

    /** Separator between a model id and its window; safe because no model id contains a tab. */
    private const val WINDOW_SEPARATOR = "\t"

    /**
     * Cap on remembered windows. Higher than [MAX_REMEMBERED] because the chosen model may be any
     * entry of a catalog, not only one of the first ids the picker offers.
     */
    const val MAX_REMEMBERED_WINDOWS = 1_000

    /**
     * Flattens the context windows a listing reported, as (model id -> tokens), for storage.
     *
     * @return the encoded form, or null when no entry carried a usable window
     */
    fun encodeWindows(windows: Map<String, Int>): String? = windows.entries
        .map { it.key.trim() to it.value }
        .filter { (id, tokens) -> id.isNotEmpty() && tokens > 0 }
        .distinctBy { it.first }
        .take(MAX_REMEMBERED_WINDOWS)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(SEPARATOR) { (id, tokens) -> "$id$WINDOW_SEPARATOR$tokens" }

    /**
     * Restores what [encodeWindows] wrote, skipping any line it could not have written.
     *
     * @param stored the encoded value, or null when nothing is stored
     */
    fun decodeWindows(stored: String?): Map<String, Int> = stored
        ?.split(SEPARATOR)
        ?.mapNotNull { line ->
            val id = line.substringBefore(WINDOW_SEPARATOR, "").trim()
            val tokens = line.substringAfter(WINDOW_SEPARATOR, "").trim().toIntOrNull()
            if (id.isEmpty() || tokens == null || tokens <= 0) null else id to tokens
        }
        ?.toMap()
        .orEmpty()
}
