package com.itsaky.androidide.plugins.aiagentlocal.model

/**
 * What one model load should get — context size and KV cache type — and the header behind them.
 *
 * @property contextTokens the context to load with; always a value [ContextSizePolicy] returned
 * @property kvType the type the KV cache will be stored as, which is what that context was sized
 *   against; the two are decided together or they describe different allocations
 * @property fallbackContextTokens the context the same RAM buys under [KvCacheType.F16], for the
 *   native fallback when llama.cpp refuses a quantized cache; equals [contextTokens] when [kvType]
 *   is already [KvCacheType.F16]
 * @property header the model's parsed metadata, or null when it could not be read
 */
internal data class ModelContextSize(
    val contextTokens: Int,
    val kvType: KvCacheType,
    val fallbackContextTokens: Int,
    val header: GgufHeader?,
) {

    /** The context the model claims to support, or null when the header did not say. */
    val advertisedTokens: Long? get() = header?.contextLength
}

/**
 * Decides what one model load gets on this device — context size and KV cache type — from the header
 * someone else already read. Pure, so the load path can take its free-RAM reading after an unload
 * and still price the same header the embedding-model guard used. See ADFA-5187 and ADFA-5188.
 */
internal object ModelContextResolver {

    /**
     * Fails open by construction, with no error path of its own: a null header is what
     * [GgufHeaderReader.read] returns for anything it could not open or parse, and
     * [ContextSizePolicy.choose] answers its default for one.
     *
     * @param header the model's parsed metadata, or null when it could not be read
     * @param availableBytes free RAM in bytes, or null when it could not be read
     * @param modelSizeBytes the model file's size in bytes, or null when it could not be read; the
     *   weights are charged against free RAM before the KV cache gets a budget
     * @return the context and cache type to load with, and the header behind them
     */
    fun resolve(
        header: GgufHeader?,
        availableBytes: Long?,
        modelSizeBytes: Long?,
    ): ModelContextSize {
        // A quantized cache buys about twice the context, so the type is picked before the size.
        val kvType = ContextSizePolicy.chooseKvCache(header)
        val contextTokens = ContextSizePolicy.choose(header, availableBytes, modelSizeBytes, kvType)
        return ModelContextSize(
            contextTokens = contextTokens,
            kvType = kvType,
            // Sized by the one policy that knows the floor, the ceiling and the rounding rather
            // than scaled natively, and reused outright where the load is already f16: that is the
            // documented invariant, and asking twice would only make it hold by coincidence.
            fallbackContextTokens = if (kvType == KvCacheType.F16) {
                contextTokens
            } else {
                ContextSizePolicy.choose(header, availableBytes, modelSizeBytes, KvCacheType.F16)
            },
            header = header,
        )
    }
}
