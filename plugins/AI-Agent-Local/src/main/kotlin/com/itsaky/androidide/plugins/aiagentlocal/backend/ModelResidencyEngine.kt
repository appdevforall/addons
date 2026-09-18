package com.itsaky.androidide.plugins.aiagentlocal.backend

/**
 * The slice of the native engine that owns model residency: making a model resident, and giving
 * it back. Generation itself still goes straight to `LLamaAndroid`.
 *
 * A seam rather than a wrapper: it exists so the residency rules that matter most (a model whose
 * file went away is unloaded, and its descriptor released) can be exercised off a device, where
 * loading real weights is not an option. See ADFA-5253.
 */
interface ModelResidencyEngine {

    /**
     * Every part of the load's shape is an argument rather than engine state, so nothing can drift
     * between being sized here and being allocated natively. See ADFA-5188.
     *
     * @param nativePath the path handed to the loader; a procfs entry for a picked document
     * @param contextTokens the KV-cache size to create the context with, as sized per model and device
     * @param quantizeKv true to store the KV cache as q8_0; the engine may still refuse it, in
     *   which case it falls back to f16 at [fallbackContextTokens]
     * @param fallbackContextTokens the context that f16 fallback gets, sized against f16's own
     *   per-token cost
     */
    suspend fun load(
        nativePath: String,
        contextTokens: Int,
        quantizeKv: Boolean,
        fallbackContextTokens: Int,
    )

    /** Frees the model's mapped pages and the buffers around them. */
    suspend fun unload()

    /**
     * @return the size of the context actually created, which the loader may clamp below the
     *   requested one
     */
    suspend fun contextSize(): Int
}
