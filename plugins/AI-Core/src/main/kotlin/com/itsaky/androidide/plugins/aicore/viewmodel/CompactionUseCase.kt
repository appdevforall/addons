package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.ai.prompt.PromptConfigProvider
import com.itsaky.androidide.plugins.aicore.prompt.ContextFilesPrompt
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sizes a run's request against its backend's budget and, when it no longer fits, asks the backend
 * to summarize the history (ADFA-6345). Publishing the result is [ChatViewModel]'s job.
 *
 * @param config supplies the cached prompt config.
 * @param contextWindow the window a backend reports, in tokens; 0 when it reports none.
 * @param logWarn records a summarize call that failed.
 * @param summarizing told true before each summarize call and false after, so a Stop can cancel it.
 */
internal class CompactionUseCase(
    private val config: PromptConfigProvider<AgentPromptConfig>,
    private val contextWindow: (backendId: String) -> Int,
    private val logWarn: (String, Throwable?) -> Unit,
    private val summarizing: (Boolean) -> Unit,
) {

    /**
     * @property tokens the request's estimated size.
     * @property budgetTokens the backend's budget it was measured against.
     * @property historyRoomChars see [HistoryCompactor.historyRoomChars]; what a cut history may keep.
     */
    data class Estimate(val tokens: Int, val budgetTokens: Int, val historyRoomChars: Int)

    /**
     * @param backendId the backend the run is against.
     * @return its token budget; see [HistoryCompactor.budgetTokens].
     */
    fun budgetTokens(backendId: String): Int =
        HistoryCompactor.budgetTokens(backendId, contextWindow(backendId))

    /**
     * @param backendId the backend the run is against.
     * @return the longest attachment sent whole: a quarter of the budget, so one file cannot fill it.
     */
    fun maxFileChars(backendId: String): Int = minOf(
        ContextFilesPrompt.MAX_FILE_CHARS,
        budgetTokens(backendId) * HistoryCompactor.CHARS_PER_TOKEN / FILE_BUDGET_SHARE,
    )

    /**
     * @param backendId the backend the run is against.
     * @return the most all of a message's attachments are sent with, so several cannot fill it.
     */
    fun maxAttachmentChars(backendId: String): Int =
        budgetTokens(backendId) * HistoryCompactor.CHARS_PER_TOKEN * ATTACHMENTS_PERCENT / 100

    /**
     * @param backendId the backend the run is against.
     * @param systemPrompt the run's system prompt.
     * @param toolDefinitions the tools every request of the run carries.
     * @param history the turns before the new message.
     * @param pendingUserTurn the new message with its attached files.
     * @param forced a Retry after a context overflow; see [HistoryCompactor.needsCompaction].
     * @return the estimate when [history] has to be summarized first; null when it is sent as it is.
     */
    fun estimateIfOverBudget(
        backendId: String,
        systemPrompt: String?,
        toolDefinitions: List<LlmInferenceService.ToolDefinition>,
        history: List<LlmInferenceService.ChatMessage>,
        pendingUserTurn: String,
        forced: Boolean,
    ): Estimate? {
        if (history.isEmpty()) return null
        val budget = budgetTokens(backendId)
        val fixedChars = HistoryCompactor.fixedChars(systemPrompt, toolDefinitions)
        val tokens = HistoryCompactor.estimateTokens(fixedChars, history, pendingUserTurn)
        if (!HistoryCompactor.needsCompaction(tokens, budget, forced)) return null
        return Estimate(tokens, budget, HistoryCompactor.historyRoomChars(fixedChars, pendingUserTurn, budget))
    }

    /**
     * Summarizes [history] through [llmService], without tools.
     *
     * @param llmService the inference service the run uses.
     * @param backendId the backend the run is against.
     * @param history the turns before the new message, oldest first; not empty.
     * @param replyTokens the reply cap of the backend, which each summary is held to.
     * @return what replaces [history]; see [HistoryCompactor.compact].
     */
    suspend fun compact(
        llmService: LlmInferenceService,
        backendId: String,
        history: List<LlmInferenceService.ChatMessage>,
        replyTokens: Int,
    ): HistoryCompactor.Outcome = HistoryCompactor.compact(
        config.config(),
        history,
        budgetTokens(backendId),
        replyTokens,
    ) { turns, request, systemPrompt ->
        summarize(llmService, backendId, turns, request, systemPrompt, replyTokens)
    }

    /** @return the reply, or null when the call failed, was refused or timed out. */
    private suspend fun summarize(
        llmService: LlmInferenceService,
        backendId: String,
        turns: List<LlmInferenceService.ChatMessage>,
        request: String,
        systemPrompt: String,
        replyTokens: Int,
    ): String? {
        val callConfig = LlmInferenceService.LlmConfig(backendId).apply {
            temperature = HistoryCompactor.TEMPERATURE
            maxTokens = replyTokens
            this.systemPrompt = systemPrompt
        }
        summarizing(true)
        return try {
            val response = withTimeoutOrNull(HistoryCompactor.TIMEOUT_MS) {
                llmService.generateWithHistory(turns, request, callConfig).await()
            }
            when {
                response == null -> null.also { logWarn("compaction timed out", null) }
                !response.success -> null.also { logWarn("compaction refused: ${response.error}", null) }
                else -> response.text
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            logWarn("compaction failed", e)
            null
        } finally {
            summarizing(false)
        }
    }

    private companion object {
        /** One attachment may take at most this fraction (1/N) of the budget. */
        const val FILE_BUDGET_SHARE = 4

        /** Share of the budget, in percent, all of one message's attachments may take together. */
        const val ATTACHMENTS_PERCENT = 30
    }
}
