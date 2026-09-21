package com.itsaky.androidide.plugins.aiagentlocal.model

import com.itsaky.androidide.plugins.aiagentlocal.model.ContextSizePolicy.DEFAULT_CONTEXT_TOKENS
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the fail-open contract of the read-then-resolve pair the load path runs: no way of failing
 * to read a header may propagate out of it, because the caller is a model load that should proceed
 * at the default context instead of aborting.
 */
class ModelContextResolverTest {

    @Test
    fun givenAnOpenerThatThrows_whenResolving_thenReturnsTheDefaultContext() {
        val resolved = resolve {
            throw IOException("permission denied")
        }
        assertEquals(DEFAULT_CONTEXT_TOKENS, resolved.contextTokens)
        assertNull(resolved.header)
    }

    @Test
    fun givenAnOpenerReturningNoStream_whenResolving_thenReturnsTheDefaultContext() {
        val resolved = resolve { null }
        assertEquals(DEFAULT_CONTEXT_TOKENS, resolved.contextTokens)
        assertNull(resolved.header)
    }

    @Test
    fun givenAStreamThatIsNotGguf_whenResolving_thenReturnsTheDefaultContext() {
        val resolved = resolve {
            "not a model file".byteInputStream()
        }
        assertEquals(DEFAULT_CONTEXT_TOKENS, resolved.contextTokens)
        assertNull(resolved.header)
    }

    @Test
    fun givenAStreamThatThrowsMidRead_whenResolving_thenReturnsTheDefaultContext() {
        val resolved = resolve { ThrowingStream() }
        assertEquals(DEFAULT_CONTEXT_TOKENS, resolved.contextTokens)
        assertNull(resolved.header)
    }

    @Test
    fun givenUnknownFreeMemory_whenResolving_thenReturnsTheDefaultContext() {
        val resolved = ModelContextResolver.resolve(header(), null, MODEL_SIZE)
        assertEquals(DEFAULT_CONTEXT_TOKENS, resolved.contextTokens)
    }

    @Test
    fun givenAnUnreadableHeader_whenResolving_thenTheFallbackSizeIsTheDefaultToo() {
        // No header means f16, so the native fallback has nothing shorter to drop to.
        val resolved = resolve { null }
        assertEquals(KvCacheType.F16, resolved.kvType)
        assertEquals(resolved.contextTokens, resolved.fallbackContextTokens)
    }

    @Test
    fun givenAQuantizableModel_whenResolving_thenTheFallbackSizeIsTheShorterOne() {
        // What the native f16 retry needs from this side: fewer tokens, since f16 costs nearly twice
        // as much per cached token out of the same budget.
        val resolved = ModelContextResolver.resolve(header(), ROOMY_DEVICE_BYTES, MODEL_SIZE)

        assertEquals(KvCacheType.Q8_0, resolved.kvType)
        assertTrue(resolved.fallbackContextTokens < resolved.contextTokens)
    }

    @Test
    fun givenUnknownModelSize_whenResolving_thenReturnsTheDefaultContext() {
        val resolved = ModelContextResolver.resolve(header(), Long.MAX_VALUE, null)
        assertEquals(DEFAULT_CONTEXT_TOKENS, resolved.contextTokens)
    }

    /** Reads then resolves the way the load path does, with room to spare so only the header decides. */
    private fun resolve(openStream: () -> InputStream?) =
        ModelContextResolver.resolve(GgufHeaderReader.read(openStream), Long.MAX_VALUE, MODEL_SIZE)

    /** A header that would earn more than the floor, so a null one is what the assertions catch. */
    private fun header() = GgufHeader(
        architecture = "llama",
        blockCount = 24L,
        embeddingLength = 1024L,
        headCount = 16L,
        headCountKv = 8L,
        keyLength = 64L,
        valueLength = 64L,
        contextLength = 32768L,
    )

    /** Opens fine and then fails, which is the case a null-check on the opener would not cover. */
    private class ThrowingStream : InputStream() {
        override fun read(): Int = throw IOException("device is gone")
    }

    private companion object {
        const val MODEL_SIZE = 64L * 1024 * 1024

        /** Free RAM that buys more than the floor under both types, so the two sizes can differ. */
        const val ROOMY_DEVICE_BYTES = 1024L * 1024 * 1024
    }
}
