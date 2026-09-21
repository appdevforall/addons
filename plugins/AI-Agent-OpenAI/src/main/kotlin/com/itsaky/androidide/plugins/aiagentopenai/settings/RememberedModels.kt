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
}
