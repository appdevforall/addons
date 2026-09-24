package com.itsaky.androidide.plugins.aiagentgemini.backend

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * The `models/{model}:batchEmbedContents` wire format, as pure functions.
 *
 * Kept apart from [GeminiBackend] so the two rules that are easy to get silently wrong are
 * unit-testable without a key: the API answers **positionally**, with no `index` field of the kind
 * OpenAI sends, and it caps how many requests one call may carry.
 */
internal object GeminiEmbeddingProtocol {

    /**
     * Requests per call, as the API's documented cap.
     *
     * A call over the cap is rejected whole, so a batch the caller asked for is split here rather
     * than handed to the server and hoped for.
     */
    const val MAX_REQUESTS_PER_CALL = 100

    /**
     * Splits [texts] into calls of at most [MAX_REQUESTS_PER_CALL], preserving order.
     *
     * @param texts the batch as the caller asked for it
     * @return the calls to issue, in order; empty when [texts] is empty
     */
    fun batches(texts: List<String>): List<List<String>> = texts.chunked(MAX_REQUESTS_PER_CALL)

    /**
     * Builds the request body for one call.
     *
     * Each entry repeats the model because the API requires it per request, even though every
     * request in a call must name the same one.
     *
     * @param model the embedding model id, without the `models/` prefix
     * @param texts the call's texts, in order
     * @return the request JSON
     */
    fun body(model: String, texts: List<String>): JSONObject {
        val requests = JSONArray()
        for (text in texts) {
            requests.put(
                JSONObject()
                    .put("model", "models/$model")
                    .put(
                        "content",
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))
                    )
            )
        }
        return JSONObject().put("requests", requests)
    }

    /**
     * Reads a response back into the caller's order.
     *
     * The answer carries no index, so position *is* the contract: a response of the wrong length
     * cannot be realigned and has to fail here, rather than shift every later vector onto the
     * wrong chunk.
     *
     * @param response the parsed response body
     * @param expected how many texts the call asked for
     * @return the vectors, in request order
     * @throws IOException when the answer is the wrong length or carries an unusable vector
     */
    fun vectors(response: JSONObject, expected: Int): List<FloatArray> {
        val embeddings = response.optJSONArray("embeddings")
            ?: throw IOException("Embeddings response carried no embeddings array")
        if (embeddings.length() != expected) {
            throw IOException(
                "Embeddings response carried ${embeddings.length()} vectors for $expected inputs"
            )
        }

        return (0 until expected).map { position ->
            val values = embeddings.optJSONObject(position)?.optJSONArray("values")
            if (values == null || values.length() == 0) {
                throw IOException("Embeddings response element $position carried no vector")
            }
            FloatArray(values.length()) { index -> values.getDouble(index).toFloat() }
        }
    }
}
