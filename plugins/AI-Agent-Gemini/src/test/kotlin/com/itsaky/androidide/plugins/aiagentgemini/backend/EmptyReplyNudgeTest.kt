package com.itsaky.androidide.plugins.aiagentgemini.backend

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EmptyReplyNudgeTest {

    private fun turn(role: String, text: String) =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun request() = JSONArray().put(turn("user", "Fix the build")).put(turn("model", "Done.")).put(turn("user", "Now add a test"))

    private fun JSONArray.lastTurnTexts(): List<String> {
        val parts = getJSONObject(length() - 1).getJSONArray("parts")
        return (0 until parts.length()).map { parts.getJSONObject(it).getString("text") }
    }

    @Test
    fun givenNoEmptyReplies_whenNudged_thenTheRequestGoesOutUnchanged() {
        val contents = request()

        assertSame(contents, EmptyReplyNudge.nudged(contents, 0))
    }

    @Test
    fun givenOneEmptyReply_whenNudged_thenTheLastUserTurnGainsTheFirstLineAndTheOriginalIsUntouched() {
        val contents = request()

        val nudged = EmptyReplyNudge.nudged(contents, 1)

        assertEquals(listOf("Now add a test", EmptyReplyNudge.LINES[0]), nudged.lastTurnTexts())
        assertEquals(listOf("Now add a test"), contents.lastTurnTexts())
        assertEquals(contents.length(), nudged.length())
    }

    @Test
    fun givenMoreEmptiesThanLines_whenNudged_thenEachResendDiffersAndTheLastLineRepeats() {
        val second = EmptyReplyNudge.nudged(request(), 2).lastTurnTexts().last()
        val beyond = EmptyReplyNudge.nudged(request(), 9).lastTurnTexts().last()

        assertNotEquals(EmptyReplyNudge.LINES[0], second)
        assertEquals(EmptyReplyNudge.LINES.last(), beyond)
    }

    @Test
    fun givenARequestEndingOnAModelTurn_whenNudged_thenTheLineGoesInANewUserTurn() {
        val contents = JSONArray().put(turn("user", "Hi")).put(turn("model", "Hello"))

        val nudged = EmptyReplyNudge.nudged(contents, 1)

        assertEquals("user", nudged.getJSONObject(2).getString("role"))
        assertEquals(listOf(EmptyReplyNudge.LINES[0]), nudged.lastTurnTexts())
        assertEquals(2, contents.length())
    }
}
