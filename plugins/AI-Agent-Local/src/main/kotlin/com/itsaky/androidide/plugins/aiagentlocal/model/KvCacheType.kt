package com.itsaky.androidide.plugins.aiagentlocal.model

/**
 * How llama.cpp stores one element of the KV cache. The cache is the largest allocation a load
 * makes and the one this plugin sizes, so what it costs per element lives only here — the native
 * side is told a size per type rather than deriving one. Pure arithmetic, so it is unit-testable.
 *
 * @property bytesPerBlock what one block of [blockSize] elements occupies once stored
 * @property blockSize elements per stored block; 1 for a type that is not quantized
 */
enum class KvCacheType(private val bytesPerBlock: Long, private val blockSize: Long) {

    /** Two bytes per element, and llama.cpp's own default. Works for every model. */
    F16(bytesPerBlock = 2L, blockSize = 1L),

    /**
     * 32 elements in 34 bytes — 32 quantized bytes plus one f16 scale — so a shade over half of
     * [F16] for the same context. Usable only where [supports] holds, and only with flash
     * attention, which llama.cpp requires for a quantized value cache. See ADFA-5188.
     */
    Q8_0(bytesPerBlock = 34L, blockSize = 32L);

    /**
     * @param elements cached elements, at most 2^43 for the shapes [ModelMemoryEstimator] admits
     * @return what they occupy, exact whenever [elements] is a whole number of blocks
     */
    fun bytesFor(elements: Long): Long = elements * bytesPerBlock / blockSize

    /**
     * Whether a model's cached rows divide into whole blocks. llama.cpp refuses a quantized cache
     * whose head width does not, so asking anyway costs a failed context creation and a retry.
     *
     * @param header the model's metadata, or null when it could not be read
     * @return true when this type can hold that model's cache
     */
    fun supports(header: GgufHeader?): Boolean {
        if (blockSize == 1L) return true
        val widths = header?.let { ModelMemoryEstimator.headWidths(it) } ?: return false
        return widths.first % blockSize == 0L && widths.second % blockSize == 0L
    }
}
