package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [AnswerReview], the second pass over an answer holding code. */
class AnswerReviewTest {

    private val draft = "Use this:\n```kotlin\nval x = 1\n```"

    @Test
    fun givenTheShippedConfig_whenChecked_thenEveryTextRenders() {
        assertEquals(emptyList<String>(), AnswerReview.problems(shippedConfig))
    }

    @Test
    fun givenAReplyWithACodeBlock_whenChecked_thenItIsReviewed() {
        assertTrue(AnswerReview.holdsCode(draft))
        assertFalse(AnswerReview.holdsCode("A ViewModel survives configuration changes."))
    }

    @Test
    fun givenTheShippedConfig_whenBuildingTheSystemPrompt_thenItCarriesTheDateAndTheEndMarker() {
        val prompt = AnswerReview.systemPrompt(shippedConfig, "Tuesday, 29 September 2026, 10:00 (UTC, UTC+00:00)")

        assertTrue(prompt.contains("Today is Tuesday, 29 September 2026"))
        assertTrue(prompt.endsWith("a line holding only ${AnswerReview.END_MARKER}."))
    }

    @Test
    fun givenEvidence_whenBuildingTheTurn_thenRequestEvidenceAndDraftAreSentVerbatim() {
        val turn = AnswerReview.prompt(shippedConfig, "Review {{this}}", "✓ web_search(query=q)\nKtor 3.6.0", draft)

        assertEquals(
            "REQUEST:\nReview {{this}}\n\nEVIDENCE:\n✓ web_search(query=q)\nKtor 3.6.0\n\nDRAFT:\n$draft",
            turn,
        )
    }

    @Test
    fun givenNoEvidence_whenBuildingTheTurn_thenTheReviewIsToldNothingWasChecked() {
        val turn = AnswerReview.prompt(shippedConfig, "q", "  ", draft)

        assertTrue(turn.contains("EVIDENCE:\nNone. No tool ran"))
    }

    @Test
    fun givenAReplyEndingOnTheMarker_whenRead_thenTheCorrectedAnswerIsReturnedWithoutIt() {
        val raw = "<think>checking</think>Fixed:\n```kotlin\nval x = 2\n```\n${AnswerReview.END_MARKER}\n"

        assertEquals("Fixed:\n```kotlin\nval x = 2\n```", AnswerReview.corrected(raw, draft))
    }

    @Test
    fun givenAReplyCutOffBeforeTheMarker_whenRead_thenTheDraftStands() {
        assertNull(AnswerReview.corrected("Fixed:\n```kotlin\nval x =", draft))
    }

    @Test
    fun givenAReplyThatChangedNothing_whenRead_thenTheDraftStands() {
        assertNull(AnswerReview.corrected("$draft\n${AnswerReview.END_MARKER}", draft))
        assertNull(AnswerReview.corrected(AnswerReview.END_MARKER, draft))
    }

    @Test
    fun givenATypoInTheLayout_whenChecked_thenItIsReportedByItsFileAndPath() {
        val config = shippedWith("layout.yml") { it.replace("{{DRAFT}}", "{{DRAFTT}}") }

        assertEquals(listOf("layout.yml: layout.answer_review: unknown name {{DRAFTT}}"), AnswerReview.problems(config))
    }
}
