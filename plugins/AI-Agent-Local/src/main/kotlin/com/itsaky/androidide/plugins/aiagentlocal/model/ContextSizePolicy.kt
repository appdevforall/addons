package com.itsaky.androidide.plugins.aiagentlocal.model

/**
 * Picks what one model load gets: the context size (`n_ctx`) and the type the KV cache is stored as,
 * from what the model advertises and what the device can spare. Pure and Android-free, so every
 * boundary is unit-testable off-device. See ADFA-5187 and ADFA-5188.
 */
object ContextSizePolicy {

    /**
     * The context every load got before this policy existed, and now both the fallback for any
     * unreadable input and the floor. Below it the native prompt check starts rejecting
     * conversations that fit today, so a smaller context costs working prompts rather than saving.
     */
    const val DEFAULT_CONTEXT_TOKENS = 4096

    /**
     * Ceiling, whatever the model advertises and the device can afford. Four times the old fixed
     * context and already past the point of diminishing returns, since prefill cost grows with the
     * prompt. Models advertising 32k+ are capped here rather than taken at their word.
     */
    const val MAX_CONTEXT_TOKENS = 16384

    /**
     * Contexts are rounded down to a multiple of this. Purely cosmetic — it keeps the chosen value
     * and the llama.cpp context dump readable instead of reporting a number like 11417.
     */
    private const val GRANULARITY_TOKENS = 256

    /**
     * The share of what the weights and compute buffers leave that the KV cache may claim. The IDE
     * and the app being edited draw on the same pool, and `availMem` is a snapshot taken before a
     * load that then takes seconds, so half is left alone rather than sized to the last free byte.
     */
    private const val KV_BUDGET_DIVISOR = 2L

    /**
     * The cache type a load should ask for. Quantized wherever the model allows it: it halves the
     * bytes one cached token costs, which is what lets [choose] return a longer context on the same
     * device. Falls back to [KvCacheType.F16] rather than risking a refused context. See ADFA-5188.
     *
     * @param header the model's GGUF metadata, or null when it could not be read
     * @return the type to configure natively, and to size the context against
     */
    fun chooseKvCache(header: GgufHeader?): KvCacheType =
        if (KvCacheType.Q8_0.supports(header)) KvCacheType.Q8_0 else KvCacheType.F16

    /**
     * The weights are charged against free RAM even though they are mmap'd: this reading is taken
     * before a load that then pages them in from the same pool. So a model whose file approaches
     * free RAM gets the floor — deliberate, since the alternative is sizing a cache it must fight.
     *
     * @param header the model's GGUF metadata, or null when it could not be read
     * @param availableBytes free RAM right now, or null when it could not be read; a negative
     *   reading is treated as unreadable too
     * @param modelSizeBytes the model file's size, or null when it could not be read; the weights
     *   are charged against free RAM before the cache gets a budget
     * @param kvType the cache type this load will ask for, from [chooseKvCache]; the budget buys
     *   about twice the context under [KvCacheType.Q8_0], so the two have to be decided together
     * @return the context to configure, always between [DEFAULT_CONTEXT_TOKENS] and
     *   [MAX_CONTEXT_TOKENS] inclusive
     */
    fun choose(
        header: GgufHeader?,
        availableBytes: Long?,
        modelSizeBytes: Long?,
        kvType: KvCacheType = KvCacheType.F16,
    ): Int {
        // Each null is a distinct "we don't know"; all of them mean the same fallback.
        if (header == null) return DEFAULT_CONTEXT_TOKENS
        // A negative reading is not free RAM this can reason about, so treat it as unreadable.
        val freeBytes = availableBytes?.takeIf { it >= 0L } ?: return DEFAULT_CONTEXT_TOKENS
        val weightBytes = modelSizeBytes?.takeIf { it > 0L } ?: return DEFAULT_CONTEXT_TOKENS
        val modelTokens = header.contextLength?.takeIf { it > 0L } ?: return DEFAULT_CONTEXT_TOKENS
        // Nothing to weigh below the floor, and no reason to price a cache we would not shrink.
        if (modelTokens <= DEFAULT_CONTEXT_TOKENS) return DEFAULT_CONTEXT_TOKENS

        val perToken = ModelMemoryEstimator.kvBytesPerToken(header, kvType)?.takeIf { it > 0L }
            ?: return DEFAULT_CONTEXT_TOKENS

        // Each clamped at zero: an unclamped Long underflows on an absurd size and wraps positive.
        val afterWeights = (freeBytes - weightBytes).coerceAtLeast(0L)
        val spareBytes = (afterWeights - ModelMemory.RUN_BUFFER_BYTES).coerceAtLeast(0L)
        val budgetBytes = spareBytes / KV_BUDGET_DIVISOR
        val affordableTokens = budgetBytes / perToken

        val ceiling = minOf(modelTokens, affordableTokens, MAX_CONTEXT_TOKENS.toLong())
        val rounded = (ceiling / GRANULARITY_TOKENS) * GRANULARITY_TOKENS
        return rounded.coerceIn(DEFAULT_CONTEXT_TOKENS.toLong(), MAX_CONTEXT_TOKENS.toLong()).toInt()
    }
}
