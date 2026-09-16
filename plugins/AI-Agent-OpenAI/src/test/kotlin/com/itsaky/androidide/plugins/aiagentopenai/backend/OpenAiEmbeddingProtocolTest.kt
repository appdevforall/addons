package com.itsaky.androidide.plugins.aiagentopenai.backend

import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiEmbeddingProtocolTest {

    @Test
    fun givenABatch_whenBuildingTheBody_thenEveryInputIsSentUnderTheChosenModel() {
        val body = OpenAiEmbeddingProtocol.body("text-embedding-3-small", listOf("alpha", "beta"))

        assertEquals("text-embedding-3-small", body.getString("model"))
        assertEquals(2, body.getJSONArray("input").length())
        assertEquals("alpha", body.getJSONArray("input").getString(0))
        assertEquals("beta", body.getJSONArray("input").getString(1))
    }

    @Test
    fun givenAnOutOfOrderResponse_whenParsed_thenVectorsAreReturnedInRequestOrder() {
        // The whole reason `index` exists. Trusting arrival order attaches every vector to the
        // wrong chunk, which produces an index that is wrong rather than one that is broken.
        val response = responseOf(
            """{"index":1,"embedding":[0.5,0.5]}""",
            """{"index":0,"embedding":[1.0,0.0]}""",
        )

        val vectors = OpenAiEmbeddingProtocol.vectors(response, expected = 2)

        assertArrayEquals(floatArrayOf(1.0f, 0.0f), vectors[0], 0f)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), vectors[1], 0f)
    }

    @Test
    fun givenAResponseWithoutIndices_whenParsed_thenArrivalOrderIsUsed() {
        // Compatible servers are not all strict about the field; arrival order is the only thing
        // left to go on, and refusing the answer would make those servers unusable.
        val response = responseOf(
            """{"embedding":[1.0,0.0]}""",
            """{"embedding":[0.0,1.0]}""",
        )

        val vectors = OpenAiEmbeddingProtocol.vectors(response, expected = 2)

        assertArrayEquals(floatArrayOf(1.0f, 0.0f), vectors[0], 0f)
        assertArrayEquals(floatArrayOf(0.0f, 1.0f), vectors[1], 0f)
    }

    @Test
    fun givenAShortResponse_whenParsed_thenItFails() {
        val response = responseOf("""{"index":0,"embedding":[1.0]}""")

        assertFails { OpenAiEmbeddingProtocol.vectors(response, expected = 2) }
    }

    @Test
    fun givenADuplicateIndex_whenParsed_thenItFails() {
        // Two vectors claiming one slot leaves another chunk with none, so the batch is unusable.
        val response = responseOf(
            """{"index":0,"embedding":[1.0]}""",
            """{"index":0,"embedding":[0.0]}""",
        )

        assertFails { OpenAiEmbeddingProtocol.vectors(response, expected = 2) }
    }

    @Test
    fun givenAnElementWithNoVector_whenParsed_thenItFails() {
        val response = responseOf("""{"index":0,"embedding":[]}""")

        assertFails { OpenAiEmbeddingProtocol.vectors(response, expected = 1) }
    }

    @Test
    fun givenNoDataArray_whenParsed_thenItFails() {
        assertFails { OpenAiEmbeddingProtocol.vectors(JSONObject("""{"error":"nope"}"""), 1) }
    }

    @Test
    fun givenMoreTextsThanOneRequestAllows_whenBatching_thenOrderIsPreservedAcrossBatches() {
        val texts = (0 until OpenAiEmbeddingProtocol.MAX_INPUTS_PER_REQUEST + 3).map { "chunk $it" }

        val batches = OpenAiEmbeddingProtocol.batches(texts)

        assertEquals(2, batches.size)
        assertEquals(OpenAiEmbeddingProtocol.MAX_INPUTS_PER_REQUEST, batches[0].size)
        assertEquals(texts, batches.flatten())
    }

    @Test
    fun givenLongTexts_whenBatching_thenTheCharacterBudgetSplitsThem() {
        val long = "x".repeat(OpenAiEmbeddingProtocol.MAX_CHARS_PER_REQUEST / 2)

        val batches = OpenAiEmbeddingProtocol.batches(listOf(long, long, long))

        assertEquals(2, batches.size)
        assertEquals(2, batches[0].size)
    }

    @Test
    fun givenOneTextOverTheBudget_whenBatching_thenItIsSentAloneRatherThanDropped() {
        // Only the server knows the real token count, and dropping it would lose a chunk silently.
        val oversized = "x".repeat(OpenAiEmbeddingProtocol.MAX_CHARS_PER_REQUEST * 2)

        val batches = OpenAiEmbeddingProtocol.batches(listOf("small", oversized))

        assertEquals(listOf(listOf("small"), listOf(oversized)), batches)
    }

    @Test
    fun givenNoTexts_whenBatching_thenThereIsNoRequestToMake() {
        assertTrue(OpenAiEmbeddingProtocol.batches(emptyList()).isEmpty())
    }

    /** Wraps [elements] as a `{"data":[…]}` response body. */
    private fun responseOf(vararg elements: String): JSONObject =
        JSONObject("""{"data":[${elements.joinToString(",")}]}""")

    /** Asserts [block] reports a protocol failure rather than returning a half-read batch. */
    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            return
        }
        throw AssertionError("expected an IOException")
    }
}
