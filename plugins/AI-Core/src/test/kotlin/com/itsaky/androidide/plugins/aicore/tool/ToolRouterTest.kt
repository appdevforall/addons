package com.itsaky.androidide.plugins.aicore.tool

import com.itsaky.androidide.plugins.aicore.models.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Covers how loosely a model may name a tool and still reach it, and what [ToolRouter.dispatch]
 * does with what it finds.
 *
 * A model shown `test_add` writes `add` often enough that treating it as unknown costs a turn every
 * time; the rule is that one match resolves and two match nothing. On the dispatch side the thing
 * worth guarding is the difference between a tool that failed and a run the user stopped.
 */
class ToolRouterTest {

    private fun handler(name: String) = object : ToolHandler {
        override val toolName = name
        override val description = "does $name"
        override suspend fun execute(args: Map<String, Any?>) = ToolResult.success("ran $name")
    }

    private class ThrowingHandler(
        override val toolName: String,
        private val error: Throwable,
    ) : ToolHandler {
        override val description = "throws"
        override suspend fun execute(args: Map<String, Any?>): ToolResult = throw error
    }

    private val router = ToolRouter(
        listOf(
            handler("edit_file"),
            handler("read_file"),
            handler("test_add"),
            handler("test_greet"),
        )
    )

    @Test
    fun givenTheRegisteredName_whenResolving_thenItMatchesExactly() {
        assertEquals("test_add", router.getHandler("test_add")?.toolName)
    }

    @Test
    fun givenTheToolsOwnNameWithoutItsPrefix_whenResolving_thenItStillMatches() {
        assertEquals("test_add", router.getHandler("add")?.toolName)
        assertEquals("test_greet", router.getHandler("greet")?.toolName)
    }

    @Test
    fun givenADifferentCase_whenResolving_thenItStillMatches() {
        assertEquals("edit_file", router.getHandler("Edit_File")?.toolName)
    }

    @Test
    fun givenSurroundingWhitespace_whenResolving_thenItStillMatches() {
        assertEquals("read_file", router.getHandler("  read_file ")?.toolName)
    }

    @Test
    fun givenASuffixSharedByTwoTools_whenResolving_thenNothingMatches() {
        // Dispatching a write to the wrong tool is worse than making the model be specific.
        val ambiguous = ToolRouter(listOf(handler("a_run"), handler("b_run")))

        assertNull(ambiguous.getHandler("run"))
    }

    @Test
    fun givenAnExactNameThatIsAlsoASuffixOfAnother_whenResolving_thenTheExactOneWins() {
        val overlapping = ToolRouter(listOf(handler("file"), handler("read_file")))

        assertEquals("file", overlapping.getHandler("file")?.toolName)
    }

    @Test
    fun givenAnUnknownName_whenResolving_thenNothingMatches() {
        assertNull(router.getHandler("definitely_not_a_tool"))
        assertNull(router.getHandler(""))
        assertNull(router.getHandler("   "))
    }

    @Test
    fun givenAnAmbiguousName_whenAskingForSuggestions_thenBothAreOffered() {
        val ambiguous = ToolRouter(listOf(handler("a_run"), handler("b_run")))

        assertEquals(listOf("a_run", "b_run"), ambiguous.suggestionsFor("run"))
    }

    @Test
    fun givenAPartialName_whenAskingForSuggestions_thenTheNearestAreOffered() {
        val suggestions = router.suggestionsFor("test")

        assertTrue(suggestions.contains("test_add"))
        assertTrue(suggestions.contains("test_greet"))
    }

    @Test
    fun givenNothingResembling_whenAskingForSuggestions_thenThereAreNone() {
        assertTrue(router.suggestionsFor("zzzz").isEmpty())
        assertTrue(router.suggestionsFor("").isEmpty())
    }

    @Test
    fun givenACancelledTool_whenDispatched_thenCancellationPropagatesInsteadOfBecomingAFailure() {
        // It extends Exception, so a broad catch would turn Stop into an ordinary tool failure.
        val cancelling = ToolRouter(listOf(ThrowingHandler("boom", CancellationException("stopped"))))

        try {
            runBlocking { cancelling.dispatch("boom", emptyMap()) }
            fail("dispatch should have rethrown CancellationException")
        } catch (ce: CancellationException) {
            assertTrue(true)
        }
    }

    @Test
    fun givenAFailingTool_whenDispatched_thenTheErrorIsReportedAsAFailureResult() {
        val failing = ToolRouter(listOf(ThrowingHandler("boom", IllegalStateException("bad"))))

        val result = runBlocking { failing.dispatch("boom", emptyMap()) }

        assertFalse(result.success)
        assertTrue(result.message.contains("bad"))
    }

    @Test
    fun givenAnUnknownTool_whenDispatched_thenItFailsCleanly() {
        val empty = ToolRouter(emptyList())

        val result = runBlocking { empty.dispatch("nope", emptyMap()) }

        assertFalse(result.success)
        assertTrue(result.message.contains("Unknown tool"))
    }
}
