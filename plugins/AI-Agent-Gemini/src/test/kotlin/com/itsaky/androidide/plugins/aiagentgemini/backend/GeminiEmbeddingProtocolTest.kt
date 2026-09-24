package com.itsaky.androidide.plugins.aiagentgemini.backend

import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiEmbeddingProtocolTest {

    @Test
    fun givenABatch_whenBuildingTheBody_thenEveryRequestNamesTheModelAndCarriesItsText() {
        val body = GeminiEmbeddingProtocol.body("gemini-embedding-001", listOf("alpha", "beta"))

        val requests = body.getJSONArray("requests")
        assertEquals(2, requests.length())
        assertEquals("models/gemini-embedding-001", requests.getJSONObject(0).getString("model"))
        assertEquals("alpha", textOf(requests.getJSONObject(0)))
        assertEquals("beta", textOf(requests.getJSONObject(1)))
    }

    @Test
    fun givenAResponse_whenParsed_thenPositionDecidesTheOrder() {
        // batchEmbedContents carries no index field, unlike OpenAI's answer, so position is the
        // only contract there is — and the one thing a parser can silently get wrong.
        val response = responseOf("[1.0,0.0]", "[0.0,1.0]")

        val vectors = GeminiEmbeddingProtocol.vectors(response, expected = 2)

        assertArrayEquals(floatArrayOf(1.0f, 0.0f), vectors[0], 0f)
        assertArrayEquals(floatArrayOf(0.0f, 1.0f), vectors[1], 0f)
    }

    @Test
    fun givenAShortResponse_whenParsed_thenItFails() {
        // With no index there is nothing to realign against: a short answer would shift every
        // later vector onto the wrong chunk, so it has to fail here.
        assertFails { GeminiEmbeddingProtocol.vectors(responseOf("[1.0]"), expected = 2) }
    }

    @Test
    fun givenALongResponse_whenParsed_thenItFails() {
        assertFails { GeminiEmbeddingProtocol.vectors(responseOf("[1.0]", "[0.0]"), expected = 1) }
    }

    @Test
    fun givenAnEmptyVector_whenParsed_thenItFails() {
        assertFails { GeminiEmbeddingProtocol.vectors(responseOf("[]"), expected = 1) }
    }

    @Test
    fun givenNoEmbeddingsArray_whenParsed_thenItFails() {
        assertFails { GeminiEmbeddingProtocol.vectors(JSONObject("""{"error":"nope"}"""), 1) }
    }

    @Test
    fun givenMoreTextsThanOneCallAllows_whenBatching_thenOrderIsPreservedAcrossCalls() {
        val texts = (0 until GeminiEmbeddingProtocol.MAX_REQUESTS_PER_CALL + 5).map { "chunk $it" }

        val batches = GeminiEmbeddingProtocol.batches(texts)

        assertEquals(2, batches.size)
        assertEquals(GeminiEmbeddingProtocol.MAX_REQUESTS_PER_CALL, batches[0].size)
        assertEquals(texts, batches.flatten())
    }

    @Test
    fun givenNoTexts_whenBatching_thenThereIsNoCallToMake() {
        assertTrue(GeminiEmbeddingProtocol.batches(emptyList()).isEmpty())
    }

    /** The text part of one built request. */
    private fun textOf(request: JSONObject): String = request
        .getJSONObject("content")
        .getJSONArray("parts")
        .getJSONObject(0)
        .getString("text")

    /** Wraps [values] arrays as an `{"embeddings":[{"values":…}]}` response body. */
    private fun responseOf(vararg values: String): JSONObject = JSONObject(
        """{"embeddings":[${values.joinToString(",") { """{"values":$it}""" }}]}"""
    )

    /** Asserts [block] reports a protocol failure rather than returning a misaligned batch. */
    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            return
        }
        throw AssertionError("expected an IOException")
    }
}
