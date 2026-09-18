package com.itsaky.androidide.plugins.aiagentlocal.model

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The GGUF metadata the memory estimate needs. Every field is nullable because a file may omit any
 * key; the estimator then falls back to a heuristic instead of guessing.
 *
 * New properties are appended rather than inserted: every one of them is `Long?`, so a positional
 * construction that shifted would still compile and silently bind the wrong value to each name.
 *
 * @property architecture `general.architecture`; also the prefix every other key here is read under
 * @property blockCount transformer layers, `{arch}.block_count`
 * @property embeddingLength model width, `{arch}.embedding_length`
 * @property headCount attention heads, `{arch}.attention.head_count`
 * @property headCountKv key/value heads under grouped-query attention, absent for plain MHA
 * @property keyLength per-head key width, `{arch}.attention.key_length`; absent means it is the
 *   model width divided by the head count, which is only the default and not always the truth
 * @property valueLength per-head value width, `{arch}.attention.value_length`; as [keyLength]
 * @property contextLength the context the model was trained for, `{arch}.context_length`; the
 *   ceiling [ContextSizePolicy] sizes the KV cache against, and absent on files that omit it
 */
data class GgufHeader(
    val architecture: String?,
    val blockCount: Long?,
    val embeddingLength: Long?,
    val headCount: Long?,
    val headCountKv: Long?,
    val keyLength: Long? = null,
    val valueLength: Long? = null,
    val contextLength: Long? = null,
)

/**
 * Reads the metadata block at the front of a `.gguf` file — the shape values the KV-cache estimate
 * needs, plus the architecture [GgufModelInspector] classifies. Never reads the weights, and fails
 * closed to null: an unreadable header must mean "no estimate", never a wrong one. The one parser
 * for this block — a well-formed file is walked once, [readArchitecture] retries only after a null.
 */
internal object GgufHeaderReader {

    private const val GGUF_MAGIC = GgufFormat.MAGIC_LE_INT

    // GGUF metadata value types.
    private const val T_UINT8 = 0
    private const val T_INT8 = 1
    private const val T_UINT16 = 2
    private const val T_INT16 = 3
    private const val T_UINT32 = 4
    private const val T_INT32 = 5
    private const val T_FLOAT32 = 6
    private const val T_BOOL = 7
    private const val T_STRING = 8
    private const val T_ARRAY = 9
    private const val T_UINT64 = 10
    private const val T_INT64 = 11
    private const val T_FLOAT64 = 12

    private const val KEY_ARCHITECTURE = "general.architecture"

    // Matched by suffix, then attributed to the "{arch}." prefix they carry — see [readHeader].
    private const val SUFFIX_BLOCK_COUNT = ".block_count"
    private const val SUFFIX_CONTEXT_LENGTH = ".context_length"
    private const val SUFFIX_EMBEDDING_LENGTH = ".embedding_length"
    private const val SUFFIX_HEAD_COUNT = ".attention.head_count"
    private const val SUFFIX_HEAD_COUNT_KV = ".attention.head_count_kv"
    private const val SUFFIX_KEY_LENGTH = ".attention.key_length"
    private const val SUFFIX_VALUE_LENGTH = ".attention.value_length"

    /** Backstop against a corrupt count claiming millions of entries; real files hold dozens. */
    private const val MAX_METADATA_ENTRIES = 4096L

    /**
     * Backstop on one array's declared length. The byte budget alone bounds the data read but not
     * the iteration count: an array of 1-byte elements would spin ~67M times before tripping it.
     * Far above the largest real value here, a ~256k-token tokenizer vocabulary.
     */
    private const val MAX_ARRAY_ELEMENTS = 1L shl 22

    /** Keys and architecture names are tiny; anything longer means a corrupt length field. */
    private const val MAX_STRING_BYTES = 1L shl 20

    /**
     * Ceiling on the bytes one parse may consume. Real metadata blocks run to a few MB, so this is
     * generous — it exists because an array's element count is otherwise unbounded, and a corrupt
     * one would have the parse skip its way through a multi-gigabyte model file before giving up.
     */
    private const val MAX_METADATA_BYTES = 64L shl 20

    /**
     * Blocking I/O, and reads at most [MAX_METADATA_BYTES] — never call this on the main thread.
     *
     * @param openStream opens the candidate model, or returns null when it can't be opened
     * @return the metadata values that were present, or null if the header could not be parsed
     */
    fun read(openStream: () -> InputStream?): GgufHeader? = try {
        openStream()?.use { stream ->
            val budgeted = BudgetedInputStream(stream, MAX_METADATA_BYTES)
            readHeader(DataInputStream(BufferedInputStream(budgeted, 1 shl 16)))
        }
    } catch (e: Throwable) {
        // Throwable, so a StackOverflowError is a null header rather than a crash.
        null
    }

    /**
     * The architecture and nothing else, for the crash guard when [read] already returned null.
     * Stops at the first `general.architecture` — conventionally the first entry — so nothing later
     * in the block can defeat it. Blocking I/O, bounded by [MAX_METADATA_BYTES].
     *
     * @param openStream opens the candidate model, or returns null when it can't be opened
     * @return the declared architecture, or null if the parse never reached it
     */
    fun readArchitecture(openStream: () -> InputStream?): String? = try {
        openStream()?.use { stream ->
            val budgeted = BudgetedInputStream(stream, MAX_METADATA_BYTES)
            readArchitectureOnly(DataInputStream(BufferedInputStream(budgeted, 1 shl 16)))
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Aborts the parse once [limit] bytes have been consumed, so no declared count can make the
     * read run on past the metadata. Throwing is deliberate: [read] treats it like any other parse
     * failure and returns null, which puts the estimate on its size-based fallback.
     */
    private class BudgetedInputStream(source: InputStream, private val limit: Long) :
        FilterInputStream(source) {

        private var consumed = 0L

        override fun read(): Int = super.read().also { if (it >= 0) charge(1L) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) charge(it.toLong()) }

        /**
         * Clamped to the remaining budget *before* delegating, since [charge] only runs once the
         * delegate returns: one string declaring a length of 2^62 would otherwise read its way
         * through the whole model file, 2 KB at a time, with the budget never consulted.
         */
        override fun skip(n: Long): Long {
            if (n <= 0L) return 0L
            // +1 so the clamp itself can still push consumed past the limit and trip charge().
            val allowed = minOf(n, limit - consumed + 1)
            if (allowed <= 0L) throw IOException("GGUF metadata exceeded $limit bytes")
            return super.skip(allowed).also { if (it > 0) charge(it) }
        }

        private fun charge(bytes: Long) {
            consumed += bytes
            if (consumed > limit) throw IOException("GGUF metadata exceeded $limit bytes")
        }
    }

    /** The shape values seen under one `{arch}.` prefix. A file may carry more than one. */
    private class ArchShape {
        var blockCount: Long? = null
        var contextLength: Long? = null
        var embeddingLength: Long? = null
        var headCount: Long? = null
        var headCountKv: Long? = null
        var keyLength: Long? = null
        var valueLength: Long? = null
    }

    private fun readHeader(input: DataInputStream): GgufHeader? {
        if (readU32(input) != GGUF_MAGIC) return null

        // v1 used 32-bit counts and string lengths; v2+ use 64-bit.
        val wide = readU32(input) >= 2
        readCount(input, wide) // tensor count, unused here
        val entryCount = readCount(input, wide)
        // Give up rather than truncate: a head_count_kv never reached reads as plain MHA.
        if (entryCount < 0L || entryCount > MAX_METADATA_ENTRIES) return null

        var architecture: String? = null
        // Keyed by each key's own "{arch}", so a multimodal file's `clip.…` values stay out.
        val shapes = HashMap<String, ArchShape>()

        // Walked to the end: an absent key is meaningful, and only the end of the block proves it.
        var index = 0L
        while (index < entryCount) {
            val key = readString(input, wide)
            val type = readU32(input)
            when {
                key == KEY_ARCHITECTURE && type == T_STRING -> architecture = readString(input, wide)

                key.endsWith(SUFFIX_BLOCK_COUNT) ->
                    shapeFor(shapes, key, SUFFIX_BLOCK_COUNT).blockCount = readInteger(input, type, wide)

                key.endsWith(SUFFIX_CONTEXT_LENGTH) ->
                    shapeFor(shapes, key, SUFFIX_CONTEXT_LENGTH).contextLength = readInteger(input, type, wide)

                key.endsWith(SUFFIX_EMBEDDING_LENGTH) ->
                    shapeFor(shapes, key, SUFFIX_EMBEDDING_LENGTH).embeddingLength = readInteger(input, type, wide)

                // Before the head_count suffix, so the more specific key always wins.
                key.endsWith(SUFFIX_HEAD_COUNT_KV) ->
                    shapeFor(shapes, key, SUFFIX_HEAD_COUNT_KV).headCountKv = readInteger(input, type, wide)

                key.endsWith(SUFFIX_HEAD_COUNT) ->
                    shapeFor(shapes, key, SUFFIX_HEAD_COUNT).headCount = readInteger(input, type, wide)

                key.endsWith(SUFFIX_KEY_LENGTH) ->
                    shapeFor(shapes, key, SUFFIX_KEY_LENGTH).keyLength = readInteger(input, type, wide)

                key.endsWith(SUFFIX_VALUE_LENGTH) ->
                    shapeFor(shapes, key, SUFFIX_VALUE_LENGTH).valueLength = readInteger(input, type, wide)

                else -> skipValue(input, type, wide)
            }
            index++
        }

        val shape = architecture?.let(shapes::get)
        return GgufHeader(
            architecture = architecture,
            blockCount = shape?.blockCount,
            embeddingLength = shape?.embeddingLength,
            headCount = shape?.headCount,
            headCountKv = shape?.headCountKv,
            keyLength = shape?.keyLength,
            valueLength = shape?.valueLength,
            contextLength = shape?.contextLength,
        )
    }

    /**
     * Deliberately laxer than [readHeader]: no entry-count ceiling, and it returns before reading
     * whatever follows the architecture, so the constructs that make a full parse fail cannot make
     * an embedding model look chat-capable. See ADFA-4388.
     */
    private fun readArchitectureOnly(input: DataInputStream): String? {
        if (readU32(input) != GGUF_MAGIC) return null

        val wide = readU32(input) >= 2
        readCount(input, wide) // tensor count, unused here
        val entryCount = readCount(input, wide)
        if (entryCount < 0L) return null

        var index = 0L
        while (index < entryCount) {
            val key = readString(input, wide)
            val type = readU32(input)
            if (key == KEY_ARCHITECTURE && type == T_STRING) return readString(input, wide)
            skipValue(input, type, wide)
            index++
        }
        return null
    }

    /** The shape values for the architecture [key] belongs to, created on first sight. */
    private fun shapeFor(shapes: HashMap<String, ArchShape>, key: String, suffix: String): ArchShape =
        shapes.getOrPut(key.dropLast(suffix.length)) { ArchShape() }

    /**
     * Consumes one value, returning it when it is an integer and null otherwise. The value is
     * always consumed either way, or the next key would be read from the middle of it.
     */
    private fun readInteger(input: DataInputStream, type: Int, wide: Boolean): Long? = when (type) {
        T_UINT8, T_INT8 -> input.readUnsignedByte().toLong()
        T_UINT16, T_INT16 -> readU16(input)
        T_UINT32, T_INT32 -> readU32(input).toLong() and 0xFFFFFFFFL
        T_UINT64, T_INT64 -> readU64(input)
        else -> {
            skipValue(input, type, wide)
            null
        }
    }

    private fun skipValue(input: DataInputStream, type: Int, wide: Boolean) {
        when (type) {
            T_UINT8, T_INT8, T_BOOL -> skipFully(input, 1)
            T_UINT16, T_INT16 -> skipFully(input, 2)
            T_UINT32, T_INT32, T_FLOAT32 -> skipFully(input, 4)
            T_UINT64, T_INT64, T_FLOAT64 -> skipFully(input, 8)
            T_STRING -> skipFully(input, readCount(input, wide))
            T_ARRAY -> {
                val elementType = readU32(input)
                // The format forbids nesting, and following one recurses until the stack goes.
                if (elementType == T_ARRAY) throw IllegalStateException("Nested GGUF array")
                val elements = readCount(input, wide)
                if (elements < 0L || elements > MAX_ARRAY_ELEMENTS) {
                    throw IllegalStateException("Unreasonable GGUF array length: $elements")
                }
                var i = 0L
                while (i < elements) {
                    skipValue(input, elementType, wide)
                    i++
                }
            }
            else -> throw IllegalStateException("Unknown GGUF value type: $type")
        }
    }

    // --- little-endian primitives ---

    private fun readU16(input: DataInputStream): Long {
        val b0 = input.read()
        val b1 = input.read()
        if (b1 < 0) throw EOFException()
        return ((b0 and 0xFF) or ((b1 and 0xFF) shl 8)).toLong()
    }

    private fun readU32(input: DataInputStream): Int {
        val b0 = input.read()
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        if (b3 < 0) throw EOFException()
        return (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or ((b2 and 0xFF) shl 16) or ((b3 and 0xFF) shl 24)
    }

    private fun readU64(input: DataInputStream): Long {
        var value = 0L
        for (i in 0 until 8) {
            val b = input.read()
            if (b < 0) throw EOFException()
            value = value or ((b.toLong() and 0xFF) shl (8 * i))
        }
        return value
    }

    /** A length or count field: 64-bit on GGUF v2+, 32-bit on v1. */
    private fun readCount(input: DataInputStream, wide: Boolean): Long =
        if (wide) readU64(input) else readU32(input).toLong() and 0xFFFFFFFFL

    private fun readString(input: DataInputStream, wide: Boolean): String {
        val length = readCount(input, wide)
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw IllegalStateException("Unreasonable GGUF string length: $length")
        }
        val bytes = ByteArray(length.toInt())
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun skipFully(input: DataInputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                // skip() can return 0 near buffer boundaries; fall back to a read.
                if (input.read() < 0) throw EOFException()
                remaining--
            }
        }
    }
}
