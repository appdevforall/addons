package com.itsaky.androidide.plugins.aiagentlocal.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KvCacheTypeTest {

    /** Widths default to 64, a multiple of the q8_0 block, as most models' heads are. */
    private fun header(
        embeddingLength: Long? = 1024L,
        headCount: Long? = 16L,
        keyLength: Long? = 64L,
        valueLength: Long? = 64L,
    ) = GgufHeader(
        architecture = "llama",
        blockCount = 24L,
        contextLength = 8192L,
        embeddingLength = embeddingLength,
        headCount = headCount,
        headCountKv = 8L,
        keyLength = keyLength,
        valueLength = valueLength,
    )

    @Test
    fun givenF16_whenSizingElements_thenChargesTwoBytesEach() {
        assertEquals(64L, KvCacheType.F16.bytesFor(32L))
        assertEquals(2L, KvCacheType.F16.bytesFor(1L))
    }

    @Test
    fun givenQ8_0_whenSizingOneBlock_thenChargesTheBlockPlusItsScale() {
        assertEquals(34L, KvCacheType.Q8_0.bytesFor(32L))
    }

    @Test
    fun givenQ8_0_whenSizingATypicalToken_thenCostsJustOverHalfOfF16() {
        val elements = 24L * 8L * (64L + 64L)
        assertEquals(26112L, KvCacheType.Q8_0.bytesFor(elements))
        assertEquals(49152L, KvCacheType.F16.bytesFor(elements))
    }

    @Test
    fun givenNoHeader_whenAskingF16_thenStillSupported() {
        // f16 has no shape constraint, so an unreadable header cannot rule it out.
        assertTrue(KvCacheType.F16.supports(null))
    }

    @Test
    fun givenNoHeader_whenAskingQ8_0_thenNotSupported() {
        assertFalse(KvCacheType.Q8_0.supports(null))
    }

    @Test
    fun givenDeclaredWidthsInWholeBlocks_whenAskingQ8_0_thenSupported() {
        assertTrue(KvCacheType.Q8_0.supports(header()))
    }

    @Test
    fun givenKeyWidthNotInWholeBlocks_whenAskingQ8_0_thenNotSupported() {
        assertFalse(KvCacheType.Q8_0.supports(header(keyLength = 80L)))
    }

    @Test
    fun givenValueWidthNotInWholeBlocks_whenAskingQ8_0_thenNotSupported() {
        assertFalse(KvCacheType.Q8_0.supports(header(valueLength = 48L)))
    }

    @Test
    fun givenUndeclaredWidths_whenAskingQ8_0_thenJudgesTheDerivedWidth() {
        // 1024 / 16 = 64, a whole number of blocks; 1200 / 16 = 75 is not.
        assertTrue(KvCacheType.Q8_0.supports(header(keyLength = null, valueLength = null)))
        assertFalse(
            KvCacheType.Q8_0.supports(
                header(embeddingLength = 1200L, keyLength = null, valueLength = null)
            )
        )
    }

    @Test
    fun givenHeaderWithoutShapeValues_whenAskingQ8_0_thenNotSupported() {
        val result = KvCacheType.Q8_0.supports(header(embeddingLength = null, keyLength = null, valueLength = null))
        assertFalse(result)
    }
}
