package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.ai.prompt.PromptTemplateEngine
import com.itsaky.androidide.plugins.aicore.backends.AiBackend
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition

/**
 * Keeps a chat's history under its backend's context window by summarizing it at the start of a
 * run, once it passes [TRIGGER_RATIO] of the budget (ADFA-6345). Without it the history only grew,
 * and a long chat ended on "prompt is too long" for every message after.
 *
 * The budget is the window the backend reports, with a fallback for one that reports none; the
 * request's size is estimated with [CHARS_PER_TOKEN]. Pure apart from the summarize call.
 */
internal object HistoryCompactor {

    /** Code tokenizes denser than prose; erring low compacts early rather than hitting a 400. */
    const val CHARS_PER_TOKEN = 3

    /** Share of the input budget an estimated request may reach before the history is summarized. */
    const val TRIGGER_RATIO = 0.8

    /** Summarize calls one compaction may make; a history longer than that loses its middle. */
    const val MAX_SUMMARIZE_CALLS = 3

    /** Most a request is kept within, whatever the window: answers degrade before larger ones fill. */
    const val MAX_BUDGET_TOKENS = 200_000

    /** Budget for the on-device backend before it reports a window: the smallest context it loads. */
    const val LOCAL_FALLBACK_BUDGET_TOKENS = 4096

    const val TEMPERATURE = 0.2f

    /** A remote summary not back by then is abandoned, and the history is cut instead. */
    const val TIMEOUT_MS = 180_000L

    /** Fraction (1/N) of an overflowing summarizer input the first turn keeps; see [summarizerInput]. */
    private const val FIRST_TURN_SHARE = 4

    /** Opens the turn after each tool batch; those are the bulk of a history and go first. */
    private const val TOOL_RESULTS_PREFIX = "<tool_response>"

    /**
     * What happened to the history before a run.
     */
    sealed interface Outcome {
        /** The request fits; the history is sent as it is. */
        data object NotNeeded : Outcome

        /** @property turn the user turn, summary under its heading, that replaces the whole history. */
        data class Summarized(val turn: ChatMessage) : Outcome

        /** The summarize call failed; the caller falls back to cutting the history. */
        data object Failed : Outcome
    }

    /**
     * @param backendId the backend the run is against.
     * @param reportedWindowTokens the window the backend reports; 0 or less when it reports none.
     * @return the token budget its requests are kept within.
     */
    fun budgetTokens(backendId: String, reportedWindowTokens: Int): Int = when {
        reportedWindowTokens > 0 -> minOf(reportedWindowTokens, MAX_BUDGET_TOKENS)
        backendId == AiBackend.LOCAL_ID -> LOCAL_FALLBACK_BUDGET_TOKENS
        else -> MAX_BUDGET_TOKENS
    }

    /**
     * Servers such as vLLM reject a request whose input plus its reply cap passes the window, so a
     * window under twice the cap sends a smaller cap rather than one no input can fit beside.
     *
     * @param budgetTokens see [budgetTokens].
     * @param capTokens the backend's own reply cap.
     * @return the reply cap the request is sent with: [capTokens], at most half the budget.
     */
    fun replyTokens(budgetTokens: Int, capTokens: Int): Int = minOf(capTokens, budgetTokens / 2)

    /**
     * The reply cap is reserved first: servers count it against the window, and on device the
     * reply fills the same context.
     *
     * @param budgetTokens see [budgetTokens].
     * @param replyTokens the reply cap the request is sent with; see [replyTokens].
     * @return the tokens a request's input may use.
     */
    fun inputBudgetTokens(budgetTokens: Int, replyTokens: Int): Int =
        budgetTokens - replyTokens(budgetTokens, replyTokens)

    /**
     * @param backendId the backend the run is against.
     * @return whether its history is summarized; on device that takes minutes, so it is cut instead.
     */
    fun summarizes(backendId: String): Boolean = backendId != AiBackend.LOCAL_ID

    /**
     * @param fixedChars see [fixedChars].
     * @param pendingUserTurn the new message, with its attached files.
     * @param inputTokens see [inputBudgetTokens].
     * @return the most characters of history the request can carry without passing [TRIGGER_RATIO].
     */
    fun historyRoomChars(fixedChars: Int, pendingUserTurn: String, inputTokens: Int): Int =
        ((inputTokens * TRIGGER_RATIO).toInt() * CHARS_PER_TOKEN - fixedChars - pendingUserTurn.length)
            .coerceAtLeast(0)

    /**
     * @param systemPrompt the run's system prompt.
     * @param toolDefinitions the tools the run offers.
     * @return characters every request of the run spends outside the history.
     */
    fun fixedChars(systemPrompt: String?, toolDefinitions: List<ToolDefinition>): Int =
        systemPrompt.orEmpty().length + toolDefinitions.sumOf {
            it.name.length + it.description.orEmpty().length + it.parametersSchema.orEmpty().toString().length
        }

    /**
     * @param fixedChars see [fixedChars].
     * @param history the turns before the new message.
     * @param pendingUserTurn the new message, with its attached files.
     * @return the request's estimated size in tokens.
     */
    fun estimateTokens(fixedChars: Int, history: List<ChatMessage>, pendingUserTurn: String): Int =
        (fixedChars + charsOf(history) + pendingUserTurn.length) / CHARS_PER_TOKEN

    /**
     * @param estimatedTokens see [estimateTokens].
     * @param inputTokens see [inputBudgetTokens].
     * @param forced a Retry after a context overflow, which the estimate missed.
     * @return whether the history is summarized before the request is sent.
     */
    fun needsCompaction(estimatedTokens: Int, inputTokens: Int, forced: Boolean = false): Boolean =
        forced || estimatedTokens > inputTokens * TRIGGER_RATIO

    /**
     * Cuts [history] until it fits [maxChars], so the summarize request cannot overflow the way the
     * run would have. Tool calls go first with their results, then exchanges, never the first turn,
     * which holds the goal; what still overflows is cut to that turn's start and the last turn's end.
     *
     * @param history the turns to summarize, oldest first.
     * @param maxChars the most characters of history the summarize request may carry.
     * @return the turns to send, oldest first; [history] itself when it already fits.
     */
    fun summarizerInput(history: List<ChatMessage>, maxChars: Int): List<ChatMessage> {
        val kept = history.toMutableList()
        while (charsOf(kept) > maxChars && (dropOldestToolExchange(kept) || dropOldestExchange(kept))) Unit
        return if (charsOf(kept) <= maxChars) kept else firstAndLast(kept, maxChars.coerceAtLeast(0))
    }

    /**
     * Removes the oldest tool results after the first turn, with the assistant turn that called them.
     *
     * @return false when no tool results are left.
     */
    private fun dropOldestToolExchange(kept: MutableList<ChatMessage>): Boolean {
        val results = (1 until kept.size).firstOrNull { isToolResults(kept[it]) } ?: return false
        kept.removeAt(results)
        if (results > 1 && kept[results - 1].role == ChatMessage.Role.ASSISTANT) kept.removeAt(results - 1)
        return true
    }

    /**
     * Removes the oldest exchange between the first and the last, a user turn with its replies;
     * with none left, the first prompt's replies, so the list still opens on that prompt.
     *
     * @return false when only the first prompt and the last exchange are left.
     */
    private fun dropOldestExchange(kept: MutableList<ChatMessage>): Boolean {
        val starts = kept.indices.filter { it == 0 || kept[it].role == ChatMessage.Role.USER }
        val range = when {
            starts.size > 2 -> starts[1] until starts[2]
            starts.size == 2 && starts[1] > 1 -> 1 until starts[1]
            else -> return false
        }
        kept.subList(range.first, range.last + 1).clear()
        return true
    }

    /**
     * The first turn's start and the last turn's end, together within [maxChars]; the first gets
     * up to [FIRST_TURN_SHARE] of it, and whatever the last turn leaves unused.
     */
    private fun firstAndLast(kept: List<ChatMessage>, maxChars: Int): List<ChatMessage> {
        val last = kept.last()
        if (kept.size == 1) return listOf(ChatMessage(last.role, last.content.orEmpty().takeLast(maxChars)))
        val first = kept.first()
        val firstLength = first.content.orEmpty().length
        val firstShare = minOf(firstLength, maxChars / FIRST_TURN_SHARE)
        val lastChars = minOf(last.content.orEmpty().length, maxChars - firstShare)
        val firstChars = minOf(firstLength, maxChars - lastChars)
        return listOf(
            ChatMessage(first.role, first.content.orEmpty().take(firstChars)),
            ChatMessage(last.role, last.content.orEmpty().takeLast(lastChars)),
        )
    }

    /**
     * The oldest turns of [turns] that fit [maxChars], never all of them, and never ending on a call
     * whose results would be left for the next chunk.
     */
    private fun leadingChunk(turns: List<ChatMessage>, maxChars: Int): List<ChatMessage> {
        var end = 0
        var chars = 0
        while (end < turns.size - 1 && chars + turns[end].content.orEmpty().length <= maxChars) {
            chars += turns[end].content.orEmpty().length
            end++
        }
        if (end > 0 && isToolResults(turns[end]) && turns[end - 1].role == ChatMessage.Role.ASSISTANT) end--
        return turns.take(end)
    }

    /**
     * Summarizes [history] and returns the turn that replaces it. A history too long for one request
     * is summarized in order, each chunk on top of the summary so far, so its start is not dropped.
     *
     * @param config the loaded prompt config.
     * @param history the turns before the new message, oldest first; not empty.
     * @param budgetTokens see [budgetTokens].
     * @param replyTokens the summary's output cap; see [inputBudgetTokens].
     * @param summarize sends the turns with [request] as the last user turn and [systemPrompt];
     *   returns the reply, or null when it failed.
     * @return [Outcome.Summarized], or [Outcome.Failed] when a call failed or wrote nothing.
     */
    suspend fun compact(
        config: AgentPromptConfig,
        history: List<ChatMessage>,
        budgetTokens: Int,
        replyTokens: Int,
        summarize: suspend (turns: List<ChatMessage>, request: String, systemPrompt: String) -> String?,
    ): Outcome {
        val systemPrompt = systemPrompt(config)
        val request = PromptTemplateEngine.render(config.compaction.request, emptyMap())
        val room = inputBudgetTokens(budgetTokens, replyTokens) * CHARS_PER_TOKEN - systemPrompt.length - request.length
        var carried: ChatMessage? = null
        var remaining = history
        for (call in 1 until MAX_SUMMARIZE_CALLS) {
            val prefix = listOfNotNull(carried)
            if (charsOf(prefix + remaining) <= room) break
            val chunk = leadingChunk(remaining, room - charsOf(prefix))
            if (chunk.isEmpty()) break
            val summary = summarize(prefix + chunk, request, systemPrompt).cleaned() ?: return Outcome.Failed
            carried = summaryTurn(config, summary)
            remaining = remaining.drop(chunk.size)
        }
        val input = summarizerInput(listOfNotNull(carried) + remaining, room)
        val summary = summarize(input, request, systemPrompt).cleaned() ?: return Outcome.Failed
        return Outcome.Summarized(summaryTurn(config, summary))
    }

    /**
     * @param config the loaded prompt config.
     * @param summary the summary text.
     * @return the user turn that stands in for the summarized conversation.
     */
    fun summaryTurn(config: AgentPromptConfig, summary: String): ChatMessage {
        val heading = PromptTemplateEngine.render(config.compaction.summaryHeading, emptyMap())
        return ChatMessage(ChatMessage.Role.USER, "$heading\n$summary")
    }

    /** @return the system prompt of a compaction request. */
    fun systemPrompt(config: AgentPromptConfig): String =
        PromptTemplateEngine.render(config.compaction.instruction, emptyMap())

    /**
     * Renders every compaction text, to catch a name typo.
     *
     * @param config the loaded prompt config.
     * @return one message per distinct failure; empty when all render.
     */
    fun problems(config: AgentPromptConfig): List<String> {
        val texts = listOf(config.compaction.instruction, config.compaction.request, config.compaction.summaryHeading)
        return texts.mapNotNull { text ->
            try {
                PromptTemplateEngine.render(text, emptyMap())
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
        }.distinct()
    }

    private fun charsOf(history: List<ChatMessage>): Int = history.sumOf { it.content.orEmpty().length }

    private fun isToolResults(turn: ChatMessage): Boolean = turn.content.orEmpty().startsWith(TOOL_RESULTS_PREFIX)

    private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
