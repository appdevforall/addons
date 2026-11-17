package org.appdevforall.shareyourapps.server

/** The access log's size limit, as the owner types it on the main screen: a whole number of MB. */
object LogSize {

    const val MIN_MB = 0
    const val MAX_MB = 100

    private val range = "Enter a whole number from $MIN_MB to $MAX_MB."

    /** The limit [text] gives, in MB, or null if it isn't one. */
    fun mb(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in MIN_MB..MAX_MB }

    /** Why [text] can't be used, naming the valid range, or null if it can. */
    fun problem(text: String): String? {
        val s = text.trim()
        // BigInteger, so a number too long for an Int still reads as too big, not as "not a number".
        val n = s.toBigIntegerOrNull()
        return when {
            s.isEmpty() -> range
            n == null -> "“$s” isn't a whole number. $range"
            n < MIN_MB.toBigInteger() -> "$s is less than $MIN_MB. $range"
            n > MAX_MB.toBigInteger() -> "$s is more than $MAX_MB. $range"
            else -> null
        }
    }
}
