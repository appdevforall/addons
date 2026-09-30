package com.itsaky.androidide.plugins.aicore.tool.web

import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmBackend
import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [BackendWebSearch], the one-off request behind the web_search tool. */
class BackendWebSearchTest {

    private val sent = slot<LlmConfig>()

    private fun backendAnswering(response: LlmResponse): LlmBackend = mockk {
        every { generate(any(), capture(sent)) } returns CompletableFuture.completedFuture(response)
    }

    private fun search(backend: LlmBackend?) = BackendWebSearch(
        config = { shippedConfig },
        backendId = { "gemini" },
        backend = { backend },
        currentTime = { NOW },
    )

    @Test
    fun givenABackend_whenSearching_thenItIsAskedForAWebSearchByExtraParam() {
        val backend = backendAnswering(LlmResponse.success("Kotlin 2.3 is current.", 5, 10))

        runBlocking { search(backend).search("latest kotlin version") }

        assertEquals(true, sent.captured.extraParams?.get(WebAccess.EXTRA_PARAM_WEB_SEARCH))
        assertEquals("gemini", sent.captured.backendId)
    }

    @Test
    fun givenTheShippedConfig_whenSearching_thenTheReportingInstructionIsTheSystemPrompt() {
        val backend = backendAnswering(LlmResponse.success("answer", 1, 1))

        runBlocking { search(backend).search("q") }

        assertTrue(sent.captured.systemPrompt.orEmpty().startsWith("Search the web to answer the query."))
    }

    @Test
    fun givenTheShippedConfig_whenSearching_thenLatestIsReadAsOfTheDevicesDate() {
        val backend = backendAnswering(LlmResponse.success("answer", 1, 1))

        runBlocking { search(backend).search("latest ktor version") }

        assertTrue(sent.captured.systemPrompt.orEmpty().contains("Today is $NOW;"))
    }

    @Test
    fun givenTheShippedConfig_whenChecked_thenTheInstructionRenders() {
        assertEquals(emptyList<String>(), BackendWebSearch.problems(shippedConfig))
    }

    @Test
    fun givenAnAnswer_whenSearching_thenItComesBackAsTheResultData() {
        val backend = backendAnswering(LlmResponse.success("Kotlin 2.3 is current.\n\nSources:\n- kotlinlang.org", 5, 10))

        val result = runBlocking { search(backend).search("latest kotlin version") }

        assertTrue(result.success)
        assertTrue(result.data!!.contains("Sources:"))
    }

    @Test
    fun givenABackendThatCannotSearch_whenSearching_thenItsRefusalIsTheFailure() {
        val backend = backendAnswering(LlmResponse.failure("Web search is not available with the on-device model."))

        val result = runBlocking { search(backend).search("q") }

        assertFalse(result.success)
        assertEquals("Web search is not available with the on-device model.", result.message)
    }

    @Test
    fun givenNoBackend_whenSearching_thenItFailsWithoutThrowing() {
        val result = runBlocking { search(null).search("q") }

        assertFalse(result.success)
    }

    private companion object {
        const val NOW = "Tuesday, 29 September 2026, 10:00 (UTC, UTC+00:00)"
    }
}
