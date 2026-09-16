package com.itsaky.androidide.plugins.vectorsearch

/**
 * Which embedder a vector came from, as far as it is known before anything has been embedded.
 *
 * The model id is part of it, not just the backend: `text-embedding-3-small` and
 * `text-embedding-ada-002` are both 1536-dimensional and mutually incomparable, so a swap between
 * them produces no dimension mismatch and would otherwise go undetected.
 *
 * @param backendId the id of the backend that produced the vector
 * @param modelId the embedding model that backend was configured with
 */
data class EmbedderKey(val backendId: String, val modelId: String)

/**
 * Which embedder a stored vector came from, in full.
 *
 * [dimensions] is the width actually observed, never a width a backend predicted: only the server
 * knows what a given model id produces, and a configured output dimensionality can differ from the
 * model's default. Two vectors are comparable only when their whole identity matches — comparing
 * across identities is not an error that surfaces, because cosine similarity answers `0.0f` for a
 * width mismatch and plausible nonsense for a same-width one.
 *
 * @param key the backend and model that produced the vector
 * @param dimensions the vector's width, as produced
 */
data class EmbedderIdentity(val key: EmbedderKey, val dimensions: Int) {

    val backendId: String get() = key.backendId

    val modelId: String get() = key.modelId

    override fun toString(): String = "${key.backendId}/${key.modelId}@${dimensions}d"
}
