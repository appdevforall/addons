package com.itsaky.androidide.plugins.aiagentgemini.backend

import org.json.JSONArray
import org.json.JSONObject

/**
 * Words the line that makes a resend after an empty `STOP` reply a new input.
 *
 * Gemini tends to answer an identical request empty again, while a typed "try again" works because
 * it changes the input; this does that for the backend's own retry of one request.
 */
internal object EmptyReplyNudge {

    /** Model-facing, one per resend so no two resends are the same input; the last repeats. */
    internal val LINES = listOf(
        "(Your previous reply to this was empty. Answer the request above, with text or a tool call.)",
        "(Your reply came back empty again. Give the user a short answer to their last message.)",
    )

    /**
     * [contents] with the line for [empties] added to its last user turn.
     *
     * @param empties empty replies so far; 0 returns [contents] itself, unchanged
     * @return a copy ending in the nudge, so the caller's history never carries it
     */
    fun nudged(contents: JSONArray, empties: Int): JSONArray {
        if (empties <= 0) return contents
        val line = JSONObject().put("text", LINES[minOf(empties, LINES.size) - 1])
        // Shallow: only the last turn is changed, so only it is copied.
        val copy = JSONArray()
        for (i in 0 until contents.length() - 1) copy.put(contents.get(i))
        val last = contents.optJSONObject(contents.length() - 1)
        if (last?.optString("role") == "user") {
            val parts = JSONArray()
            last.optJSONArray("parts")?.let { for (i in 0 until it.length()) parts.put(it.get(i)) }
            val turn = JSONObject()
            last.keys().forEach { turn.put(it, last.get(it)) }
            copy.put(turn.put("parts", parts.put(line)))
        } else {
            last?.let(copy::put)
            // Roles must alternate, and a request ending on a model turn has no user turn to extend.
            copy.put(JSONObject().put("role", "user").put("parts", JSONArray().put(line)))
        }
        return copy
    }
}
