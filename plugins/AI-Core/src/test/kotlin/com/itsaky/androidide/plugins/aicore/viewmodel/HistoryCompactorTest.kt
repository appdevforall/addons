package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.aicore.backends.AiBackend
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedWith
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage.Role
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [HistoryCompactor], which keeps a chat under its context budget (ADFA-6345). */
class HistoryCompactorTest {

    private val history = listOf(
        ChatMessage(Role.USER, "add a login screen"),
        ChatMessage(Role.ASSISTANT, "I created LoginActivity.kt"),
    )

    @Test
    fun givenTheShippedConfig_whenChecked_thenEveryTextRenders() {
        assertEquals(emptyList<String>(), HistoryCompactor.problems(shippedConfig))
    }

    @Test
    fun givenANameTypo_whenChecked_thenTheProblemNamesTheText() {
        val config = shippedWith("compaction.yml") { it.replace("conversation:\"", "conversation: {{X}}\"") }

        assertEquals(listOf("compaction.yml: compaction.summary_heading: unknown name {{X}}"), HistoryCompactor.problems(config))
    }

    @Test
    fun givenAReportedWindow_whenBudgeting_thenTheBudgetIsThatWindow() {
        assertEquals(4096, HistoryCompactor.budgetTokens(AiBackend.LOCAL_ID, 4096))
        assertEquals(128_000, HistoryCompactor.budgetTokens("openai", 128_000))
    }

    @Test
    fun givenAWindowAboveTheCap_whenBudgeting_thenTheBudgetIsCapped() {
        assertEquals(HistoryCompactor.MAX_BUDGET_TOKENS, HistoryCompactor.budgetTokens("gemini", 1_048_576))
    }

    @Test
    fun givenNoReportedWindow_whenBudgeting_thenTheLocalBackendGetsItsSmallestContext() {
        assertEquals(HistoryCompactor.LOCAL_FALLBACK_BUDGET_TOKENS, HistoryCompactor.budgetTokens(AiBackend.LOCAL_ID, 0))
        assertEquals(HistoryCompactor.MAX_BUDGET_TOKENS, HistoryCompactor.budgetTokens("claude", 0))
    }

    @Test
    fun givenTheOnDeviceBackend_whenAskedWhetherItSummarizes_thenItDoesNot() {
        assertEquals(false, HistoryCompactor.summarizes(AiBackend.LOCAL_ID))
        assertEquals(true, HistoryCompactor.summarizes("claude"))
    }

    @Test
    fun givenABudget_whenSizingTheRoomForHistory_thenFixedPartsAndThePromptComeOffTheTrigger() {
        val room = HistoryCompactor.historyRoomChars(fixedChars = 1_000, pendingUserTurn = "x".repeat(200), budgetTokens = 1_000)

        assertEquals(800 * HistoryCompactor.CHARS_PER_TOKEN - 1_200, room)
        assertEquals(0, HistoryCompactor.historyRoomChars(fixedChars = 10_000, pendingUserTurn = "", budgetTokens = 1_000))
    }

    @Test
    fun givenARequest_whenEstimating_thenEveryPartCountsAtThreeCharsPerToken() {
        val estimate = HistoryCompactor.estimateTokens(fixedChars = 30, history = history, pendingUserTurn = "x".repeat(12))

        assertEquals((30 + 18 + 26 + 12) / 3, estimate)
    }

    @Test
    fun givenAnEstimateUnderEightyPercent_whenChecked_thenNoCompactionIsNeeded() {
        assertFalse(HistoryCompactor.needsCompaction(estimatedTokens = 800, budgetTokens = 1_000))
        assertTrue(HistoryCompactor.needsCompaction(estimatedTokens = 801, budgetTokens = 1_000))
    }

    @Test
    fun givenARetryAfterAFailure_whenChecked_thenCompactionIsForcedWhateverTheEstimate() {
        assertTrue(HistoryCompactor.needsCompaction(estimatedTokens = 1, budgetTokens = 1_000, forced = true))
    }

    @Test
    fun givenAHistory_whenCompacted_thenOneSummarizeCallReplacesItWithTheSummaryTurn() {
        val calls = mutableListOf<List<ChatMessage>>()

        val outcome = runBlocking {
            HistoryCompactor.compact(shippedConfig, history, budgetTokens = 10_000, replyTokens = 1_000) { turns, _, _ ->
                calls += turns
                "  The user wants a login screen; LoginActivity.kt exists.  "
            }
        }

        assertEquals(listOf(history), calls)
        val turn = (outcome as HistoryCompactor.Outcome.Summarized).turn
        assertEquals(Role.USER, turn.role)
        assertEquals("Summary of the earlier conversation:\nThe user wants a login screen; LoginActivity.kt exists.", turn.content)
    }

    @Test
    fun givenASummarizerThatFails_whenCompacting_thenTheOutcomeIsFailed() {
        val failed = runBlocking { HistoryCompactor.compact(shippedConfig, history, 10_000, 1_000) { _, _, _ -> null } }
        val blank = runBlocking { HistoryCompactor.compact(shippedConfig, history, 10_000, 1_000) { _, _, _ -> " \n" } }

        assertEquals(HistoryCompactor.Outcome.Failed, failed)
        assertEquals(HistoryCompactor.Outcome.Failed, blank)
    }

    @Test
    fun givenTheSummarizeRequest_whenSent_thenItAsksForTextWithoutTools() {
        var sentRequest = ""
        var sentSystemPrompt = ""

        runBlocking {
            HistoryCompactor.compact(shippedConfig, history, 10_000, 1_000) { _, request, systemPrompt ->
                sentRequest = request
                sentSystemPrompt = systemPrompt
                "summary"
            }
        }

        assertTrue(sentRequest.endsWith("Do not call tools; respond with the summary text only."))
        assertTrue(sentSystemPrompt.startsWith("You summarize a conversation"))
    }

    @Test
    fun givenAHistoryOverTheBudget_whenPreparingTheSummarizerInput_thenToolResultsGoFirstWithTheirCall() {
        val turns = listOf(
            ChatMessage(Role.USER, "u".repeat(10)),
            ChatMessage(Role.ASSISTANT, "a".repeat(10)),
            ChatMessage(Role.USER, "<tool_response>" + "r".repeat(20)),
            ChatMessage(Role.ASSISTANT, "b".repeat(10)),
        )

        val kept = HistoryCompactor.summarizerInput(turns, maxChars = 30)

        assertEquals(listOf(turns[0], turns[3]), kept)
    }

    @Test
    fun givenNoToolResultsLeft_whenStillOverTheBudget_thenWholeExchangesGoAfterTheFirst() {
        val turns = listOf(
            ChatMessage(Role.USER, "goal".repeat(3)),
            ChatMessage(Role.ASSISTANT, "a".repeat(10)),
            ChatMessage(Role.USER, "u".repeat(10)),
            ChatMessage(Role.ASSISTANT, "b".repeat(10)),
            ChatMessage(Role.USER, "latest"),
        )

        val kept = HistoryCompactor.summarizerInput(turns, maxChars = 30)

        assertEquals(listOf(turns[0], turns[1], turns[4]), kept)
    }

    @Test
    fun givenOnlyTheFirstAndLastExchangesLeft_whenStillOverTheBudget_thenTheFirstPromptStaysAndItsRepliesGo() {
        val turns = listOf(
            ChatMessage(Role.USER, "u".repeat(10)),
            ChatMessage(Role.ASSISTANT, "a".repeat(10)),
            ChatMessage(Role.USER, "v".repeat(10)),
        )

        assertEquals(listOf(turns[0], turns[2]), HistoryCompactor.summarizerInput(turns, maxChars = 20))
    }

    @Test
    fun givenOneExchangeOverTheBudget_whenCut_thenItStillOpensOnTheFirstPromptAndEndsOnTheLatestTurn() {
        val turns = listOf(
            ChatMessage(Role.USER, "the goal " + "g".repeat(40)),
            ChatMessage(Role.ASSISTANT, "a".repeat(40)),
            ChatMessage(Role.ASSISTANT, "b".repeat(40) + " latest"),
        )

        val kept = HistoryCompactor.summarizerInput(turns, maxChars = 60)

        assertEquals(listOf(Role.USER, Role.ASSISTANT), kept.map { it.role })
        assertTrue(kept.first().content.startsWith("the goal"))
        assertTrue(kept.last().content.endsWith(" latest"))
        assertTrue(kept.sumOf { it.content.length } <= 60)
    }

    @Test
    fun givenAShortFirstPrompt_whenCutToFirstAndLast_thenItIsKeptWholeAndTheLastTurnGetsTheRest() {
        val turns = listOf(ChatMessage(Role.USER, "goal"), ChatMessage(Role.ASSISTANT, "x".repeat(100)))

        val kept = HistoryCompactor.summarizerInput(turns, maxChars = 50)

        assertEquals("goal", kept.first().content)
        assertEquals(46, kept.last().content.length)
    }

    @Test
    fun givenToolResultsWithoutACallTurn_whenCut_thenOnlyTheResultsGo() {
        val turns = listOf(
            ChatMessage(Role.USER, "u".repeat(10)),
            ChatMessage(Role.USER, "<tool_response>" + "r".repeat(20)),
            ChatMessage(Role.ASSISTANT, "a".repeat(10)),
        )

        assertEquals(listOf(turns[0], turns[2]), HistoryCompactor.summarizerInput(turns, maxChars = 30))
    }

    @Test
    fun givenOneTurnOverTheBudget_whenPreparingTheSummarizerInput_thenItKeepsItsEnd() {
        val turns = listOf(ChatMessage(Role.USER, "old start " + "x".repeat(50) + " latest request"))

        val kept = HistoryCompactor.summarizerInput(turns, maxChars = 20).single()

        assertEquals(20, kept.content.length)
        assertTrue(kept.content.endsWith("latest request"))
    }

    @Test
    fun givenAHistoryThatFits_whenPreparingTheSummarizerInput_thenItIsSentWhole() {
        assertEquals(history, HistoryCompactor.summarizerInput(history, maxChars = 1_000))
    }

    @Test
    fun givenAReplyCapAboveTheBudget_whenCompacting_thenTheSummarizerStillGetsTheLatestTurn() {
        var sent = emptyList<ChatMessage>()

        runBlocking {
            HistoryCompactor.compact(shippedConfig, history, budgetTokens = 1_000, replyTokens = 4_096) { turns, _, _ ->
                sent = turns
                "summary"
            }
        }

        assertTrue(sent.isNotEmpty())
        assertTrue(sent.last().content.endsWith("I created LoginActivity.kt"))
    }

    @Test
    fun givenALargeHistory_whenCompacting_thenTheSummarizerInputStaysUnderTheBudget() {
        val large = List(40) { ChatMessage(Role.USER, "<tool_response>" + "r".repeat(3_000)) } + history
        var sentChars = 0

        runBlocking {
            HistoryCompactor.compact(shippedConfig, large, budgetTokens = 12_000, replyTokens = 4_096) { turns, request, systemPrompt ->
                sentChars = turns.sumOf { it.content.length } + request.length + systemPrompt.length
                "summary"
            }
        }

        assertTrue(sentChars > 0)
        assertTrue(sentChars / HistoryCompactor.CHARS_PER_TOKEN <= 12_000 - 4_096)
    }

    @Test
    fun givenAHistoryTooLongForOneRequest_whenCompacted_thenItIsSummarizedInOrderOnTopOfTheSummarySoFar() {
        // 12,000 tokens less the 4,096 reserved leaves ~23K characters a request.
        val goal = ChatMessage(Role.USER, "the goal: build a weather app")
        val long = listOf(goal) + List(6) { i ->
            ChatMessage(if (i % 2 == 0) Role.ASSISTANT else Role.USER, "$i".repeat(9_000))
        }
        val calls = mutableListOf<List<ChatMessage>>()

        val outcome = runBlocking {
            HistoryCompactor.compact(shippedConfig, long, budgetTokens = 12_000, replyTokens = 4_096) { turns, _, _ ->
                calls += turns
                "summary ${calls.size}"
            }
        }

        assertTrue(calls.size in 2..HistoryCompactor.MAX_SUMMARIZE_CALLS)
        assertEquals(goal, calls.first().first())
        calls.drop(1).forEach { assertEquals("Summary of the earlier conversation:\nsummary ${calls.indexOf(it)}", it.first().content) }
        assertTrue(calls.last().last().content.startsWith("5"))
        assertEquals("Summary of the earlier conversation:\nsummary ${calls.size}", (outcome as HistoryCompactor.Outcome.Summarized).turn.content)
    }

    @Test
    fun givenAChunkThatFails_whenCompactingInOrder_thenTheOutcomeIsFailed() {
        val long = List(6) { i -> ChatMessage(if (i % 2 == 0) Role.USER else Role.ASSISTANT, "$i".repeat(9_000)) }

        val outcome = runBlocking {
            HistoryCompactor.compact(shippedConfig, long, budgetTokens = 12_000, replyTokens = 4_096) { _, _, _ -> null }
        }

        assertEquals(HistoryCompactor.Outcome.Failed, outcome)
    }
}
