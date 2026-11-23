package com.itsaky.androidide.plugins.aicodesuggestions

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ServiceRegistry
import com.itsaky.androidide.plugins.services.EditorContentChangeListener
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.LlmInferenceService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Drives the plugin through the editor's content-change listener, as the IDE does, with a fake
 * editor and a fake LLM. The plugin waits 800 ms before asking the LLM, so a test that expects
 * no request waits 2 s for one.
 */
class CodeSuggestionsPluginTest {

    private val prompts = LinkedBlockingQueue<String>()
    private val shown = LinkedBlockingQueue<String>()
    private val plugin = CodeSuggestionsPlugin()
    private lateinit var listener: EditorContentChangeListener

    @Before
    fun setUp() {
        val captured = slot<EditorContentChangeListener>()
        val editor = mockk<IdeEditorService>(relaxed = true)
        every { editor.addContentChangeListener(capture(captured)) } just Runs
        every { editor.showInlineSuggestion(any()) } answers { shown.add(firstArg()) }

        val llm = mockk<LlmInferenceService>()
        every { llm.generateCompletion(any(), any()) } answers {
            prompts.add(firstArg())
            CompletableFuture.completedFuture(LlmInferenceService.LlmResponse.success(COMPLETION, 1, 1L))
        }

        val services = mockk<ServiceRegistry>()
        every { services.get(IdeEditorService::class.java) } returns editor
        every { services.get(LlmInferenceService::class.java) } returns llm
        val context = mockk<PluginContext>()
        every { context.services } returns services

        assertTrue(plugin.initialize(context))
        assertTrue(plugin.activate())
        listener = captured.captured
    }

    @After
    fun tearDown() {
        plugin.deactivate()
        plugin.dispose()
    }

    @Test
    fun aWordOfTwoLettersOrMoreGetsASuggestion() {
        type("fun")

        assertEquals(COMPLETION, shown.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun aSpaceAfterAWordCancelsThatWordsPendingSuggestion() {
        type("fun")
        type("fun ")

        assertNull("the LLM was asked to complete the word left behind", prompts.poll(2, TimeUnit.SECONDS))
        assertTrue("a suggestion appeared after the space: $shown", shown.isEmpty())
    }

    @Test
    fun aOneLetterWordCancelsThePreviousWordsPendingSuggestion() {
        type("fun")
        type("fun x")

        assertNull("the LLM was asked to complete the word left behind", prompts.poll(2, TimeUnit.SECONDS))
        assertTrue("a suggestion appeared after the one-letter word: $shown", shown.isEmpty())
    }

    /** Reports [text] as a one-line Kotlin file with the cursor at its end. */
    private fun type(text: String) = listener.onContentChanged(text, 0, text.length, "kotlin")

    private companion object {
        const val COMPLETION = "ction main() {"
    }
}
