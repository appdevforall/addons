package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.ai.prompt.PromptTemplateEngine
import com.itsaky.androidide.plugins.aicore.prompt.PromptVariables
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig

/**
 * Builds the second pass over an answer holding code, and reads what comes back. One pass writing
 * a long answer broke the rules its own prompt stated (ADFA-6223); this holds the draft to them.
 * The wording is `answer_review.yml`'s and `layout.answer_review`'s; this keeps limits and parsing.
 */
internal object AnswerReview {

    /**
     * The line a complete reply ends on. The code checks for it rather than for a finish reason,
     * which the service does not report: a reply without it was cut off, and the draft stands.
     */
    const val END_MARKER = "<<END OF ANSWER>>"

    const val TEMPERATURE = 0.2f

    /** A review not back by then is abandoned; the draft stands. */
    const val TIMEOUT_MS = 180_000L

    /** Longest evidence sent; a run's web results can run past what is worth the tokens. */
    const val EVIDENCE_CHARS = 60_000

    /** Fence that opens a code block; a reply holding one is what gets reviewed. */
    private const val FENCE = "```"

    private val THINKING = Regex("(?s)<think>.*?</think>")

    /** @return whether [reply] holds code, which is what a review is for. */
    fun holdsCode(reply: String): Boolean = reply.contains(FENCE)

    /**
     * @param config the loaded prompt config.
     * @param currentTime the device's date and time, as the prompt words it.
     * @return the request's system prompt.
     */
    fun systemPrompt(config: AgentPromptConfig, currentTime: String): String =
        PromptTemplateEngine.render(
            config.answerReview.instruction,
            mapOf(PromptVariables.CURRENT_TIME to currentTime, PromptVariables.END_MARKER to END_MARKER),
        )

    /**
     * @param config the loaded prompt config.
     * @param request what the user asked.
     * @param evidence what the run's tools returned, one call per entry; empty when none ran.
     * @param draft the answer to check.
     * @return the request's user turn, each part inserted verbatim.
     */
    fun prompt(config: AgentPromptConfig, request: String, evidence: String, draft: String): String {
        val values = mapOf(
            PromptVariables.ANSWER_REVIEW_NO_EVIDENCE to config.answerReview.noEvidence,
            PromptVariables.REQUEST to request.trim(),
            PromptVariables.EVIDENCE to evidence.trim().take(EVIDENCE_CHARS),
            PromptVariables.HAS_EVIDENCE to evidence.isNotBlank(),
            PromptVariables.DRAFT to draft.trim(),
        )
        return PromptTemplateEngine.render(config.layout.answerReview, values)
    }

    /**
     * The corrected answer, or null to keep the draft: when the reply was cut off before
     * [END_MARKER], came back empty, or changed nothing.
     *
     * @param raw the backend's reply text.
     * @param draft the answer that was checked.
     * @return the answer to show instead of [draft], or null.
     */
    fun corrected(raw: String, draft: String): String? {
        val text = raw.replace(THINKING, "")
        val end = text.lastIndexOf(END_MARKER)
        if (end < 0) return null
        val answer = text.substring(0, end).trim()
        if (answer.isEmpty() || answer == draft.trim()) return null
        return answer
    }

    /**
     * Renders both texts, with and without evidence, to catch a name typo.
     *
     * @param config the loaded prompt config.
     * @return one message per distinct failure; empty when every render succeeds.
     */
    fun problems(config: AgentPromptConfig): List<String> {
        val renders: List<() -> String> = listOf(
            { systemPrompt(config, "Monday, 1 January 2029, 09:00 (UTC, UTC+00:00)") },
            { prompt(config, "q", "evidence", "draft") },
            { prompt(config, "q", "", "draft") },
        )
        return renders.mapNotNull { check ->
            try {
                check()
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
        }.distinct()
    }
}
