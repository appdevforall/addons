package com.itsaky.androidide.plugins.aiagentlocal.model

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream

/**
 * Tells whether a `.gguf` file is a chat/generation model or an embedding (encoder-only) model,
 * from the architecture [GgufHeaderReader] parsed out of its metadata block.
 *
 * WHY: the native chat path runs a causal `llama_decode`. Handed an encoder-only model (BERT
 * family, e.g. all-MiniLM), llama.cpp hits a `GGML_ASSERT` and calls `abort()` — a SIGABRT that
 * no Kotlin `try/catch` can intercept, taking the whole IDE process down. We classify the file
 * up front so the backend can refuse chat gracefully instead of crashing. See ADFA-4388.
 *
 * It deliberately **fails open**: an unreadable header or a missing architecture is reported as
 * [ModelKind.UNKNOWN] and treated as chat-capable, so a genuine chat model is never wrongly
 * blocked by a header quirk.
 *
 * Both entry points take a stream *factory* rather than a path, because since ADFA-5253 the model
 * is read in place through a `content://` grant and has no stable filesystem path. Each call opens
 * its own stream and closes it, so inspection never disturbs the native loader's file offset.
 */
object GgufModelInspector {

    private const val GGUF_MAGIC = GgufFormat.MAGIC_LE_INT

    /**
     * Architectures that are encoder-only embedding models and cannot do causal generation.
     * The `contains("bert")` catch below covers the whole BERT family (bert, nomic-bert,
     * jina-bert-*, roberta, xlm-roberta, …); the explicit set covers the rest.
     */
    private val EMBEDDING_ARCHS = setOf("mpnet", "gte", "t5encoder")

    enum class ModelKind { CHAT, EMBEDDING, UNKNOWN }

    data class Result(val kind: ModelKind, val architecture: String?) {
        val isEmbeddingOnly: Boolean get() = kind == ModelKind.EMBEDDING
    }

    /**
     * Cheap magic-only check that never throws; reads just the first 4 bytes.
     *
     * @param openStream opens a fresh read stream over the candidate model, or returns null when
     *   it cannot be reached
     * @return true if the model begins with the GGUF magic; false on any read error or mismatch
     */
    fun isGguf(openStream: () -> InputStream?): Boolean = try {
        openStream()?.use { stream ->
            readU32(DataInputStream(BufferedInputStream(stream, 16))) == GGUF_MAGIC
        } ?: false
    } catch (_: Exception) {
        false
    }

    /**
     * Classifies the header [GgufHeaderReader] read, so a caller that also needs the rest of it —
     * the context sizing does — pays for one metadata parse rather than two. Never throws.
     *
     * A header with no architecture retries via [GgufHeaderReader.readArchitecture]: a full parse
     * rejects far more files than an architecture-only scan, and each rejection is a model this
     * guard would otherwise wave through (ADFA-4388). There is deliberately no header-only overload
     * — it would fail open on exactly those files.
     *
     * @param header the model's metadata, or null when it could not be read
     * @param openStream reopens the same model for the fallback scan; blocking I/O
     */
    fun classify(header: GgufHeader?, openStream: () -> InputStream?): Result =
        header?.architecture?.let(::classifyArchitecture)
            ?: classifyArchitecture(GgufHeaderReader.readArchitecture(openStream))

    private fun classifyArchitecture(architecture: String?): Result {
        val arch = architecture ?: return Result(ModelKind.UNKNOWN, null)
        val a = arch.lowercase()
        val isEmbedding = a.contains("bert") || a in EMBEDDING_ARCHS
        return Result(if (isEmbedding) ModelKind.EMBEDDING else ModelKind.CHAT, arch)
    }

    private fun readU32(input: DataInputStream): Int {
        val b0 = input.read(); val b1 = input.read(); val b2 = input.read(); val b3 = input.read()
        if (b3 < 0) throw java.io.EOFException()
        return (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or ((b2 and 0xFF) shl 16) or ((b3 and 0xFF) shl 24)
    }
}
