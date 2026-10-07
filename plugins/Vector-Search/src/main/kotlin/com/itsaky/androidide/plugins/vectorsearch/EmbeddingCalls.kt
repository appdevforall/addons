package com.itsaky.androidide.plugins.vectorsearch

import com.itsaky.androidide.plugins.services.LlmInferenceService.EmbeddingBackend
import java.io.IOException
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible

/**
 * Embeds [texts] and waits for the vectors, unwrapping the future's failure so the caller sees the
 * backend's own message. Interruptible and cancels the future with it, so an abandoned index build
 * does not leave its HTTP requests running for vectors nobody will store.
 *
 * @return one vector per text, in order
 * @throws IOException when the backend failed or answered with the wrong number of vectors
 */
internal suspend fun EmbeddingBackend.awaitVectors(texts: List<String>): List<FloatArray> {
    val future = embed(texts)
    val vectors = try {
        runInterruptible { future.get() }
    } catch (e: CancellationException) {
        future.cancel(true)
        throw e
    } catch (e: ExecutionException) {
        throw IOException(e.cause?.message ?: "The embedder failed", e.cause ?: e)
    }
    if (vectors == null || vectors.size != texts.size) {
        throw IOException(
            "The embedder answered with ${vectors?.size ?: 0} vectors for ${texts.size} texts"
        )
    }
    return vectors
}
