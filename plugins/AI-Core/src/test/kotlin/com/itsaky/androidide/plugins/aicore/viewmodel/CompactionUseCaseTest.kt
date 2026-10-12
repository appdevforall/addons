package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.aicore.backends.AiBackend
import com.itsaky.androidide.plugins.aicore.prompt.ContextFilesPrompt
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage.Role
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [CompactionUseCase], which sizes a run's request and summarizes its history. */
class CompactionUseCaseTest {

    private val inFlight = mutableListOf<Boolean>()
    /** Windows the backends report; the local one has not loaded a model yet. */
    private val windows = mapOf("openai" to 8_000)
    private val useCase = CompactionUseCase({ shippedConfig }, { windows[it] ?: 0 }, { _, _ -> }) { inFlight += it }

    private val history = listOf(ChatMessage(Role.USER, "hi"), ChatMessage(Role.ASSISTANT, "hello"))

    @Test
    fun givenTheLocalBackend_whenCappingAFile_thenItGetsAQuarterOfItsBudget() {
        val local = HistoryCompactor.LOCAL_FALLBACK_BUDGET_TOKENS * HistoryCompactor.CHARS_PER_TOKEN / 4

        assertEquals(local, useCase.maxFileChars(AiBackend.LOCAL_ID))
        assertEquals(ContextFilesPrompt.MAX_FILE_CHARS, useCase.maxFileChars("claude"))
    }

    @Test
    fun givenABackend_whenCappingAllAttachments_thenTheyShareThirtyPercentOfItsBudget() {
        assertEquals(3_686, useCase.maxAttachmentChars(AiBackend.LOCAL_ID))
        assertEquals(180_000, useCase.maxAttachmentChars("claude"))
    }

    @Test
    fun givenABackendReportingItsWindow_whenBudgeting_thenThatWindowIsTheBudget() {
        assertEquals(8_000, useCase.budgetTokens("openai"))
        assertEquals(8_000 * HistoryCompactor.CHARS_PER_TOKEN / 4, useCase.maxFileChars("openai"))
    }

    @Test
    fun givenASmallRequest_whenEstimated_thenNothingIsCompactedUnlessForced() {
        assertNull(useCase.estimateIfOverBudget(AiBackend.LOCAL_ID, "sys", emptyList(), history, "next", forced = false))
        assertNotNull(useCase.estimateIfOverBudget(AiBackend.LOCAL_ID, "sys", emptyList(), history, "next", forced = true))
    }

    @Test
    fun givenAnEmptyHistory_whenForced_thenThereIsNothingToCompact() {
        assertNull(useCase.estimateIfOverBudget(AiBackend.LOCAL_ID, "sys", emptyList(), emptyList(), "next", forced = true))
    }

    @Test
    fun givenAServiceThatSummarizes_whenCompacting_thenTheCallIsFlaggedInFlightAroundIt() {
        val service = mockk<LlmInferenceService>()
        every { service.generateWithHistory(any(), any(), any()) } returns
            CompletableFuture.completedFuture(LlmInferenceService.LlmResponse.success("summary", 1, 1))

        val outcome = runBlocking { useCase.compact(service, AiBackend.LOCAL_ID, history, replyTokens = 1_000) }

        assertEquals("Summary of the earlier conversation:\nsummary", (outcome as HistoryCompactor.Outcome.Summarized).turn.content)
        assertEquals(listOf(true, false), inFlight)
    }

    @Test
    fun givenAServiceThatThrows_whenCompacting_thenTheOutcomeIsFailedAndTheFlagCleared() {
        val service = mockk<LlmInferenceService>()
        every { service.generateWithHistory(any(), any(), any()) } throws IllegalStateException("down")

        val outcome = runBlocking { useCase.compact(service, AiBackend.LOCAL_ID, history, replyTokens = 1_000) }

        assertEquals(HistoryCompactor.Outcome.Failed, outcome)
        assertEquals(listOf(true, false), inFlight)
    }
}
