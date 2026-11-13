package org.appdevforall.shellhooks.engine

/** The script timeout the user can set, in seconds. */
object TimeoutSetting {
    const val DEFAULT_SECONDS = 60
    const val MIN_SECONDS = 1
    const val MAX_SECONDS = 3600

    /**
     * What the user typed, as a timeout to store, or null when it is not a whole number of seconds
     * from [MIN_SECONDS] to [MAX_SECONDS]. Nothing is clamped: a value the field shows is the
     * value stored, or nothing is stored.
     */
    fun parse(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in MIN_SECONDS..MAX_SECONDS }
}
