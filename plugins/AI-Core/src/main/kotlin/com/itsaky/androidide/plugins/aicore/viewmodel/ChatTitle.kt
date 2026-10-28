package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.ai.prompt.PromptTemplateEngine
import com.itsaky.androidide.plugins.aicore.prompt.PromptVariables
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig

/**
 * Builds the request that asks the selected backend to name a chat, and cleans what comes back.
 * The wording is `chat_title.yml`'s and `layout.chat_title`'s; this keeps the limits and parsing.
 *
 * Kept free of Android and of the service, so the parts that decide what the user sees as a title
 * are unit-testable.
 */
internal object ChatTitle {

    /**
     * Cloud models that reason before answering count that reasoning against this cap, so it is
     * sized for them; a local model stops at end-of-turn long before reaching it.
     */
    const val MAX_TOKENS = 512

    const val TEMPERATURE = 0.2f

    /** The first reply waits on the title; one not written by then is dropped for the fallback. */
    const val TIMEOUT_MS = 20_000L

    /** Longest excerpt of the prompt sent; the gist is at the start, and less prefills faster. */
    const val EXCERPT_CHARS = 500

    /**
     * [com.itsaky.androidide.plugins.services.LlmInferenceService.LlmConfig.extraParams] key
     * carrying the [excerpt] the title is to be named from; must match ai-agent-local's
     * `LocalLlmBackend.EXTRA_PARAM_CHAT_TITLE_SOURCE`.
     *
     * A backend whose one cached prompt would be replaced by this request answers with the text
     * itself, which [sanitize] cuts to a title, instead of generating one. Every other backend
     * ignores the key and writes the title as before (ADFA-6757).
     */
    const val EXTRA_PARAM_SOURCE = "chat_title_source"

    const val MAX_WORDS = 8

    /** One toolbar line; longer still ellipsizes there, but the sidebar row wraps nothing. */
    const val MAX_CHARS = 60

    private val THINKING = Regex("(?s)<think>.*?</think>")
    private val LABEL = Regex("^(?i)title\\s*:\\s*")
    private val EDGE_MARKS = Regex("^[\\s#*_`\"'“”‘’>-]+|[\\s*_`\"'“”‘’.,;:!-]+$")
    private val WHITESPACE = Regex("\\s+")

    /**
     * @param config the loaded prompt config.
     * @return the request's system prompt.
     */
    fun systemPrompt(config: AgentPromptConfig): String =
        PromptTemplateEngine.render(config.chatTitle.instruction, emptyMap())

    /**
     * @param userText the conversation's first user message.
     * @return the part of it a title is named from: trimmed and cut to [EXCERPT_CHARS].
     */
    fun excerpt(userText: String): String = userText.trim().take(EXCERPT_CHARS)

    /**
     * The [excerpt] on one line, for [EXTRA_PARAM_SOURCE]. [sanitize] keeps only a reply's first line,
     * so a message that opens "Hi,", or with a code fence, would otherwise be titled "Hi" or "kotlin".
     *
     * @param userText the conversation's first user message.
     * @return the excerpt with every run of whitespace, line breaks included, as one space.
     */
    fun echoSource(userText: String): String = excerpt(userText).replace(WHITESPACE, " ")

    /**
     * @param config the loaded prompt config.
     * @param userText the conversation's first user message.
     * @return the request's user turn, the message's [excerpt] inserted verbatim.
     */
    fun prompt(config: AgentPromptConfig, userText: String): String {
        val values = mapOf(PromptVariables.USER_TEXT to excerpt(userText))
        return PromptTemplateEngine.render(config.layout.chatTitle, values)
    }

    /**
     * Renders both texts, to catch a name typo.
     *
     * @param config the loaded prompt config.
     * @return one message per distinct failure; empty when both render.
     */
    fun problems(config: AgentPromptConfig): List<String> {
        val renders: List<() -> String> = listOf({ systemPrompt(config) }, { prompt(config, "q") })
        return renders.mapNotNull { check ->
            try {
                check()
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
        }.distinct()
    }

    /**
     * Reduces a model's answer to a title: its first non-empty line, without reasoning blocks, a
     * `Title:` label, quotes, markdown or trailing punctuation, capped at [MAX_WORDS] and [MAX_CHARS].
     *
     * @param raw the backend's reply text.
     * @return the title, or null when nothing usable is left.
     */
    fun sanitize(raw: String): String? {
        val line = raw.replace(THINKING, "")
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        val cleaned = line.stripEdgeMarks()
            .replace(LABEL, "")
            .stripEdgeMarks()
            .replace(WHITESPACE, " ")
        if (cleaned.isEmpty()) return null
        val words = cleaned.split(' ')
        val capped = if (words.size > MAX_WORDS) words.take(MAX_WORDS).joinToString(" ") else cleaned
        return capped.take(MAX_CHARS).trimEnd().ifEmpty { null }
    }

    private fun String.stripEdgeMarks(): String = replace(EDGE_MARKS, "")
}
