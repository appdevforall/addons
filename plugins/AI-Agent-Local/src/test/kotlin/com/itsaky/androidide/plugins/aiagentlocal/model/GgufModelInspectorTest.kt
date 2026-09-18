package com.itsaky.androidide.plugins.aiagentlocal.model

import com.itsaky.androidide.plugins.aiagentlocal.model.GgufModelInspector.ModelKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ADFA-4388 guard. Running causal generation on an encoder-only model aborts natively and takes
 * the IDE down, so misclassifying one is not a wrong message — it is a crash. The parser-give-up
 * cases matter most: a file a full header parse rejects still has to be classified, or the guard
 * waves it through. Since ADFA-5253 the header arrives as a stream over a document read in place.
 */
class GgufModelInspectorTest {

    @Test
    fun givenAWellFormedEmbeddingModel_whenClassifying_thenReportsEmbedding() {
        val bytes = gguf(architectureEntry(EMBEDDING_ARCH))

        assertEquals(EMBEDDING_ARCH, GgufHeaderReader.read { bytes.inputStream() }?.architecture)
        assertEquals(ModelKind.EMBEDDING, classify(bytes).kind)
    }

    @Test
    fun givenAnUnknownValueTypeAfterTheArchitecture_whenClassifying_thenStillReportsEmbedding() {
        val bytes = gguf(architectureEntry(EMBEDDING_ARCH), unknownTypeEntry("quirk"))

        assertNull(GgufHeaderReader.read { bytes.inputStream() })
        assertEquals(ModelKind.EMBEDDING, classify(bytes).kind)
    }

    @Test
    fun givenMoreEntriesThanTheParserAccepts_whenClassifying_thenStillReportsEmbedding() {
        val bytes = gguf(architectureEntry(EMBEDDING_ARCH), declaredEntryCount = 5000L)

        assertNull(GgufHeaderReader.read { bytes.inputStream() })
        assertEquals(ModelKind.EMBEDDING, classify(bytes).kind)
    }

    @Test
    fun givenAnUnparseableChatModel_whenClassifying_thenReportsChat() {
        val bytes = gguf(architectureEntry("llama"), unknownTypeEntry("quirk"))

        assertEquals(ModelKind.CHAT, classify(bytes).kind)
    }

    @Test
    fun givenNoArchitectureAtAll_whenClassifying_thenFailsOpenAsUnknown() {
        val bytes = gguf(unknownTypeEntry("quirk"))

        assertEquals(ModelKind.UNKNOWN, classify(bytes).kind)
    }

    @Test
    fun givenABertModel_whenClassified_thenEmbeddingOnly() {
        val result = classify(GgufTestFiles.withArchitecture("bert"))

        assertEquals(ModelKind.EMBEDDING, result.kind)
        assertTrue(result.isEmbeddingOnly)
    }

    @Test
    fun givenABertFamilyArchitecture_whenClassified_thenStillEmbeddingOnly() {
        // The family is matched by substring, so the named variants must not need their own entry.
        for (arch in listOf("nomic-bert", "jina-bert-v2", "xlm-roberta")) {
            assertTrue(arch, classify(GgufTestFiles.withArchitecture(arch)).isEmbeddingOnly)
        }
    }

    @Test
    fun givenANonBertEmbeddingArchitecture_whenClassified_thenEmbeddingOnly() {
        for (arch in listOf("mpnet", "gte", "t5encoder")) {
            assertTrue(arch, classify(GgufTestFiles.withArchitecture(arch)).isEmbeddingOnly)
        }
    }

    @Test
    fun givenAChatArchitecture_whenClassified_thenChat() {
        val result = classify(GgufTestFiles.withArchitecture("qwen2"))

        assertEquals(ModelKind.CHAT, result.kind)
        assertEquals("qwen2", result.architecture)
        assertFalse(result.isEmbeddingOnly)
    }

    @Test
    fun givenATruncatedHeader_whenClassified_thenUnknownAndNotBlocked() {
        // Fails open: a header quirk must never block a model that would have run fine.
        val result = classify(GgufTestFiles.truncated())

        assertEquals(ModelKind.UNKNOWN, result.kind)
        assertFalse(result.isEmbeddingOnly)
    }

    @Test
    fun givenAnUnreachableSource_whenClassified_thenUnknownRatherThanThrowing() {
        // A null stream is what a revoked grant or a deleted file looks like here.
        val result = classify { null }

        assertEquals(ModelKind.UNKNOWN, result.kind)
        assertNull(result.architecture)
    }

    @Test
    fun givenAStreamThatThrows_whenClassified_thenUnknownRatherThanPropagating() {
        val result = classify { throw java.io.IOException("provider died") }

        assertEquals(ModelKind.UNKNOWN, result.kind)
    }

    @Test
    fun givenGgufMagic_whenIsGguf_thenTrue() {
        assertTrue(GgufModelInspector.isGguf(streamOf(GgufTestFiles.withArchitecture("qwen2"))))
    }

    @Test
    fun givenNonGgufContent_whenIsGguf_thenFalse() {
        assertFalse(GgufModelInspector.isGguf(streamOf(GgufTestFiles.notGguf(64))))
    }

    @Test
    fun givenAnUnreachableSource_whenIsGguf_thenFalse() {
        assertFalse(GgufModelInspector.isGguf { null })
    }

    private fun streamOf(file: File): () -> InputStream? =
        { if (file.isFile) file.inputStream() else null }

    /** Classifies the way the load path does: one full parse, then the architecture-only retry. */
    private fun classify(openStream: () -> InputStream?): GgufModelInspector.Result =
        GgufModelInspector.classify(GgufHeaderReader.read(openStream), openStream)

    private fun classify(bytes: ByteArray): GgufModelInspector.Result =
        classify { bytes.inputStream() }

    private fun classify(file: File): GgufModelInspector.Result = classify(streamOf(file))
}

private const val EMBEDDING_ARCH = "nomic-bert"
private const val T_STRING = 8
private const val UNKNOWN_VALUE_TYPE = 99
private const val GGUF_VERSION = 3

/**
 * @param entries the metadata key/value pairs, already encoded, in order
 * @param declaredEntryCount the count to write into the header, defaulting to the truth; a larger
 *   one is how a corrupt file trips the parser's entry ceiling
 */
private fun gguf(vararg entries: ByteArray, declaredEntryCount: Long? = null): ByteArray =
    ByteArrayOutputStream().apply {
        write("GGUF".toByteArray(Charsets.US_ASCII))
        writeU32(GGUF_VERSION)
        writeU64(0L) // tensor count
        writeU64(declaredEntryCount ?: entries.size.toLong())
        entries.forEach { write(it) }
    }.toByteArray()

private fun architectureEntry(value: String): ByteArray =
    ByteArrayOutputStream().apply {
        writeString("general.architecture")
        writeU32(T_STRING)
        writeString(value)
    }.toByteArray()

/** A value type no parser can skip past, so it ends any parse that reaches it. */
private fun unknownTypeEntry(key: String): ByteArray =
    ByteArrayOutputStream().apply {
        writeString(key)
        writeU32(UNKNOWN_VALUE_TYPE)
    }.toByteArray()

private fun ByteArrayOutputStream.writeU32(value: Int) {
    for (shift in 0 until 32 step 8) write((value ushr shift) and 0xFF)
}

private fun ByteArrayOutputStream.writeU64(value: Long) {
    for (shift in 0 until 64 step 8) write(((value ushr shift) and 0xFF).toInt())
}

private fun ByteArrayOutputStream.writeString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeU64(bytes.size.toLong())
    write(bytes)
}
