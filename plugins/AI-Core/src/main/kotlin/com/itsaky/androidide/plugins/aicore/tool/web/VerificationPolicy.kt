package com.itsaky.androidide.plugins.aicore.tool.web

import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolCallingBackend.EXTRA_PARAM_REQUIRED_TOOL

/**
 * Decides, from the message rather than the model's confidence, when a run must search before it
 * answers: left to itself the model approved Ktor's removed `JsonFeature` as current (ADFA-6223).
 */
object VerificationPolicy {

    /**
     * @param message what the user typed, without the attached files.
     * @param hasAttachedFiles whether files were attached, which gives a review request code to judge.
     * @return whether the run's first turn must call [WebAccess.WEB_SEARCH_TOOL].
     */
    fun requiresWebCheck(message: String, hasAttachedFiles: Boolean = false): Boolean =
        containsCode(message) ||
            asksWhetherCurrent(message) ||
            (hasAttachedFiles && asksForReview(message))

    /**
     * [config] with [tool] required, for the run's first turn only: forced on every turn, the
     * model could never answer. The copy leaves [config] as it was for the turns after.
     *
     * @param config the run's config.
     * @param tool the tool the model must call.
     * @return a new config, identical but for [EXTRA_PARAM_REQUIRED_TOOL].
     */
    fun requiring(config: LlmConfig, tool: String): LlmConfig = LlmConfig(config.backendId).apply {
        modelName = config.modelName
        temperature = config.temperature
        maxTokens = config.maxTokens
        stopSequences = config.stopSequences
        systemPrompt = config.systemPrompt
        extraParams = config.extraParams.orEmpty() + (EXTRA_PARAM_REQUIRED_TOOL to tool)
    }

    /** A fenced block, or at least [MIN_CODE_LINES] lines that read as source or build script. */
    internal fun containsCode(message: String): Boolean {
        if (message.contains(FENCE)) return true
        return message.lineSequence().count { CODE_LINE.containsMatchIn(it) } >= MIN_CODE_LINES
    }

    /** Asks whether an API, library or practice is deprecated, outdated or the latest one. */
    internal fun asksWhetherCurrent(message: String): Boolean = CURRENCY_WORDS.containsMatchIn(message)

    /** Asks for code to be reviewed, audited or analyzed. */
    internal fun asksForReview(message: String): Boolean = REVIEW_WORDS.containsMatchIn(message)

    private const val FENCE = "```"

    /** Two, so a sentence that happens to mention `foo.bar()` does not count as a snippet. */
    private const val MIN_CODE_LINES = 2

    private val CODE_LINE = Regex(
        """^\s*(""" +
            """(import|package)\s+[\w.]+""" +
            """|((private|internal|public|protected|override|suspend|inline|open|abstract)\s+)*fun\s+\w""" +
            """|(val|var)\s+\w+\s*[:=]""" +
            """|(data\s+|sealed\s+|enum\s+)?(class|interface|object)\s+\w+""" +
            """|@\w+""" +
            """|(implementation|api|kapt|ksp|testImplementation)\s*[("]""" +
            """|install\(""" +
            """|[\w.]+\([^)]*\)\s*[{;]?\s*$""" +
            """|[{}]\s*$""" +
            """)""",
    )

    // Not \b and no (?U): Android compiles with ICU, which refuses (?U), and a JVM \b is ASCII-only.
    private const val START = """(?<![\p{L}\p{N}_])"""
    private const val END = """(?![\p{L}\p{N}_])"""

    // English and Spanish, the two languages the agent is used in.
    private val CURRENCY_WORDS = Regex(
        START + """(deprecat\p{L}*|obsolete|outdated|legacy|migrat\p{L}*|up[- ]to[- ]date""" +
            """|still\s+(supported|valid|works?)""" +
            """|(latest|current|newest)\s+(stable\s+)?(version|release|api)s?|best\s+practices?""" +
            """|obsolet[oa]s?|deprecad[oa]s?|desactualizad[oa]s?|migra\p{L}*|[uú]ltima\s+versi[oó]n""" +
            """|versi[oó]n\s+actual|buenas\s+pr[aá]cticas)""" + END,
        RegexOption.IGNORE_CASE,
    )

    private val REVIEW_WORDS = Regex(
        START + """(review\p{L}*|audit\p{L}*|analy[sz]\p{L}*|examin\p{L}*|inspect\p{L}*""" +
            """|revis\p{L}*|analiz\p{L}*)""" + END,
        RegexOption.IGNORE_CASE,
    )
}
