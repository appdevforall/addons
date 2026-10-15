package com.itsaky.androidide.plugins.aiagentopenai.settings

/**
 * The context window the user typed, judged. Blank means automatic: the window then comes from
 * the server's model listing or OpenAI's published figures.
 */
sealed interface ContextWindowSetting {

    /** Nothing entered, so the backend resolves the window itself. */
    data object Automatic : ContextWindowSetting

    /** A usable window, in tokens. */
    data class Tokens(val count: Int) : ContextWindowSetting

    /** The input cannot be used. [reason] says which rule it broke; nothing is stored. */
    data class Rejected(val reason: Reason) : ContextWindowSetting

    /** Why an input was refused. Carries no text; the wording lives in `strings.xml`. */
    enum class Reason { NOT_A_NUMBER, TOO_SMALL, TOO_LARGE }

    companion object {
        /** Below this no system prompt and tool list fits, so it is a typo rather than a choice. */
        const val MIN_TOKENS = 512

        /** Above the largest window any model offers, so it is a typo rather than a choice. */
        const val MAX_TOKENS = 10_000_000

        /** Separators a user may type inside a number, as in `128,000` or `128 000`. */
        private val GROUPING = Regex("""[,_\s]""")

        /**
         * Judges [input] as typed.
         *
         * @return [Automatic] for blank, [Tokens] within [MIN_TOKENS]..[MAX_TOKENS], else [Rejected]
         */
        fun parse(input: String?): ContextWindowSetting {
            val digits = input.orEmpty().replace(GROUPING, "")
            if (digits.isEmpty()) return Automatic
            if (!digits.all(Char::isDigit)) return Rejected(Reason.NOT_A_NUMBER)
            // Too many digits for an Int is still a number, just far too large a one.
            val count = digits.toIntOrNull() ?: return Rejected(Reason.TOO_LARGE)
            return when {
                count < MIN_TOKENS -> Rejected(Reason.TOO_SMALL)
                count > MAX_TOKENS -> Rejected(Reason.TOO_LARGE)
                else -> Tokens(count)
            }
        }

        /** [count] as stored, or null when it is out of range — a value [parse] would refuse. */
        fun storedOrNull(count: Int): Int? = count.takeIf { it in MIN_TOKENS..MAX_TOKENS }
    }
}
