package com.itsaky.androidide.plugins.aiagentopenai.backend

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * The `POST /v1/embeddings` wire format, as pure functions.
 *
 * Kept apart from [OpenAiBackend] so the one rule that is easy to get silently wrong — the answer
 * is ordered by each element's own `index`, not by arrival — is unit-testable without a server.
 */
internal object OpenAiEmbeddingProtocol {

    /**
     * Inputs per request. The API takes an array but bills and rate-limits by token, and a batch
     * large enough to exceed the model's context window is rejected whole, so the batch the caller
     * asked for is split rather than gambled on.
     */
    const val MAX_INPUTS_PER_REQUEST = 96

    /**
     * Characters per request, as the cheap stand-in for a token budget.
     *
     * Roughly four characters to a token, kept well under the 8 192-token window the current
     * embedding models share so a batch of long code chunks still fits.
     */
    const val MAX_CHARS_PER_REQUEST = 24_000

    /**
     * Splits [texts] into requests that respect [MAX_INPUTS_PER_REQUEST] and
     * [MAX_CHARS_PER_REQUEST], preserving order.
     *
     * A single text over the character budget is sent alone rather than dropped: only the server
     * knows its real token count, and refusing it here would silently lose a chunk from the index.
     *
     * @param texts the batch as the caller asked for it
     * @return the requests to issue, in order; empty when [texts] is empty
     */
    fun batches(texts: List<String>): List<List<String>> {
        val batches = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var currentChars = 0

        for (text in texts) {
            val wouldOverflow = current.isNotEmpty() &&
                (current.size >= MAX_INPUTS_PER_REQUEST ||
                    currentChars + text.length > MAX_CHARS_PER_REQUEST)
            if (wouldOverflow) {
                batches.add(current)
                current = mutableListOf()
                currentChars = 0
            }
            current.add(text)
            currentChars += text.length
        }

        if (current.isNotEmpty()) batches.add(current)
        return batches
    }

    /**
     * Builds the request body for one batch.
     *
     * @param model the embedding model id to request
     * @param texts the batch to embed, in the caller's order
     * @return the request JSON
     */
    fun body(model: String, texts: List<String>): JSONObject = JSONObject()
        .put("model", model)
        .put("input", JSONArray().apply { texts.forEach(::put) })

    /**
     * Reads a response back into the caller's order.
     *
     * The API documents `data[].index` precisely because the array may not arrive in request
     * order, and a batch reordered by accident attaches every vector to the wrong chunk — an
     * index that is silently wrong rather than visibly broken.
     *
     * @param response the parsed response body
     * @param expected how many vectors the request asked for
     * @return the vectors, in request order
     * @throws IOException when the answer is short, long, or carries an index it cannot place
     */
    fun vectors(response: JSONObject, expected: Int): List<FloatArray> {
        val data = response.optJSONArray("data")
            ?: throw IOException("Embeddings response carried no data array")
        if (data.length() != expected) {
            throw IOException(
                "Embeddings response carried ${data.length()} vectors for $expected inputs"
            )
        }

        val ordered = arrayOfNulls<FloatArray>(expected)
        for (position in 0 until data.length()) {
            val element = data.optJSONObject(position)
                ?: throw IOException("Embeddings response element $position is not an object")
            // Defaulting to the arrival position keeps a server that omits `index` working; only a
            // value that is present and out of range is a protocol error.
            val index = element.optInt("index", position)
            if (index !in 0 until expected || ordered[index] != null) {
                throw IOException("Embeddings response carried an unusable index: $index")
            }
            ordered[index] = floats(element.optJSONArray("embedding"), index)
        }
        // Every slot is filled: `expected` distinct in-range indices over `expected` slots.
        return ordered.map { requireNotNull(it) }
    }

    /**
     * Reads one `embedding` array.
     *
     * @param values the raw array, or null when the element carried none
     * @param index the element's index, named in the failure so a bad row is identifiable
     * @throws IOException when the vector is missing or empty
     */
    private fun floats(values: JSONArray?, index: Int): FloatArray {
        if (values == null || values.length() == 0) {
            throw IOException("Embeddings response element $index carried no vector")
        }
        return FloatArray(values.length()) { position -> values.getDouble(position).toFloat() }
    }
}
