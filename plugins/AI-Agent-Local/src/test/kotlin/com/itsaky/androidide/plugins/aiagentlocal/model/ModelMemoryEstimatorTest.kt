package com.itsaky.androidide.plugins.aiagentlocal.model

import com.itsaky.androidide.plugins.aiagentlocal.model.ContextSizePolicy.DEFAULT_CONTEXT_TOKENS
import com.itsaky.androidide.plugins.aiagentlocal.model.ContextSizePolicy.MAX_CONTEXT_TOKENS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two properties the pre-flight warning depends on: that it prices the KV cache at the
 * floor context and at f16 — an upper bound over both cache types, never a context derived from the
 * free RAM it is then compared against (ADFA-5187/5188) — and that no context can wrap the product
 * and turn "does not fit" into "fits".
 */
class ModelMemoryEstimatorTest {

    @Test
    fun givenASelectedModel_whenEstimating_thenTheCacheIsPricedAtTheFloorContext() {
        val selection = ModelMemoryEstimator.estimateForSelection(MODEL_SIZE, header())!!

        assertEquals(F16_PER_TOKEN * DEFAULT_CONTEXT_TOKENS + ModelMemory.RUN_BUFFER_BYTES, selection.runBytes)
    }

    @Test
    fun givenAQuantizableModel_whenEstimatingForSelection_thenItIsStillPricedAtF16() {
        // Pricing a quantizable model at q8_0 would halve the cache term and go quiet on exactly the
        // load the native f16 retry exists for, which allocates the figure nobody was warned about.
        val header = header()
        assertEquals(KvCacheType.Q8_0, ContextSizePolicy.chooseKvCache(header))

        val selection = ModelMemoryEstimator.estimateForSelection(MODEL_SIZE, header)!!
        val atF16 = ModelMemoryEstimator.estimate(MODEL_SIZE, header, DEFAULT_CONTEXT_TOKENS, KvCacheType.F16)!!
        val atQ8 = ModelMemoryEstimator.estimate(MODEL_SIZE, header, DEFAULT_CONTEXT_TOKENS, KvCacheType.Q8_0)!!

        assertEquals(atF16.runBytes, selection.runBytes)
        assertTrue(atQ8.runBytes < selection.runBytes)
    }

    @Test
    fun givenARoomyDevice_whenEstimatingForSelection_thenTheGrantedContextDoesNotChangeThePrice() {
        // What the circularity looked like: the policy's answer fed back into the estimate that is
        // then judged against the same free RAM, so a larger granted context trips its own warning.
        val header = header()
        val kvType = ContextSizePolicy.chooseKvCache(header)
        val granted = ContextSizePolicy.choose(header, ROOMY_DEVICE_BYTES, MODEL_SIZE, kvType)
        val ramDerived = ModelMemoryEstimator.estimate(MODEL_SIZE, header, granted, kvType)!!

        val selection = ModelMemoryEstimator.estimateForSelection(MODEL_SIZE, header)!!

        assertEquals(MAX_CONTEXT_TOKENS, granted)
        assertNotEquals(ramDerived.runBytes, selection.runBytes)
        assertEquals(F16_PER_TOKEN * DEFAULT_CONTEXT_TOKENS + ModelMemory.RUN_BUFFER_BYTES, selection.runBytes)
    }

    @Test
    fun givenAContextAboveTheCeiling_whenEstimating_thenItIsClampedRatherThanWrapped() {
        // Shape values at their ceilings put f16's per-token figure at 2^44, so an unclamped Int
        // multiplier overflows to a negative cache and MemoryEstimate.totalBytes waves the model
        // through. f16 is named explicitly because it is the dearer of the two types.
        val widest = header(blockCount = 1L shl 10, headCount = 1L shl 12, headCountKv = 1L shl 12, width = 1L shl 20)
        val atCeiling = ModelMemoryEstimator.estimate(MODEL_SIZE, widest, MAX_CONTEXT_TOKENS, KvCacheType.F16)!!

        val absurd = ModelMemoryEstimator.estimate(MODEL_SIZE, widest, Int.MAX_VALUE, KvCacheType.F16)!!

        assertTrue(absurd.runBytes > 0L)
        assertTrue(absurd.totalBytes > 0L)
        assertEquals(atCeiling.runBytes, absurd.runBytes)
    }

    @Test
    fun givenNoHeader_whenEstimatingForSelection_thenItFallsBackToTheSizeHeuristic() {
        val selection = ModelMemoryEstimator.estimateForSelection(MODEL_SIZE, null)!!

        assertEquals(MODEL_SIZE, selection.loadBytes)
        assertTrue(!selection.fromHeader)
    }

    private fun header(
        blockCount: Long = 24L,
        headCount: Long = 16L,
        headCountKv: Long = 8L,
        width: Long = 64L,
    ) = GgufHeader(
        architecture = "llama",
        blockCount = blockCount,
        embeddingLength = 1024L,
        headCount = headCount,
        headCountKv = headCountKv,
        keyLength = width,
        valueLength = width,
        contextLength = 32768L,
    )

    private companion object {
        const val MODEL_SIZE = 64L * 1024 * 1024

        /** Cached elements per position for the default header: 24 layers x 8 kv heads x 128. */
        const val ELEMENTS_PER_TOKEN = 24L * 8 * (64 + 64)

        /** Those elements stored as f16, the type the selection estimate prices at: 2 bytes each. */
        const val F16_PER_TOKEN = ELEMENTS_PER_TOKEN * 2

        /** Enough free RAM that the policy lands on its ceiling rather than on the affordable term. */
        const val ROOMY_DEVICE_BYTES = 8L * 1024 * 1024 * 1024
    }
}
