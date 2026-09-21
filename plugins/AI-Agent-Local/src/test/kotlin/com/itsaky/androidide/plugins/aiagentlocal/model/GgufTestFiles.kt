package com.itsaky.androidide.plugins.aiagentlocal.model

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Builds the smallest GGUF files [GgufModelInspector] can be asked to classify: magic, version,
 * zero tensors and a single metadata entry. Shared by the inspector's own tests and by the
 * backend's load-path tests, which need a real file behind an [OpenModelFile].
 */
internal object GgufTestFiles {

    private const val MAGIC = "GGUF"

    /** GGUF v3: version >= 2 is what makes counts and lengths 64-bit. */
    private const val VERSION = 3

    private const val ARCHITECTURE_KEY = "general.architecture"

    /** The GGUF metadata value type for a string. */
    private const val TYPE_STRING = 8

    /**
     * @param architecture the value stored under `general.architecture`, e.g. "bert" or "qwen2"
     * @return a temp file holding a complete, minimal GGUF header
     */
    fun withArchitecture(architecture: String): File {
        val out = ByteArrayOutputStream()
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        out.writeU32(VERSION)
        out.writeU64(0) // tensor_count
        out.writeU64(1) // metadata_kv_count
        out.writeString(ARCHITECTURE_KEY)
        out.writeU32(TYPE_STRING)
        out.writeString(architecture)
        return tempFile(out.toByteArray())
    }

    /** Valid magic, then nothing — the inspector must fail open rather than throw. */
    fun truncated(): File = tempFile(MAGIC.toByteArray(Charsets.US_ASCII))

    /** A file that is not a GGUF at all. */
    fun notGguf(sizeBytes: Int): File = tempFile(ByteArray(sizeBytes))

    private fun tempFile(bytes: ByteArray): File =
        File.createTempFile("model", ".gguf").apply {
            deleteOnExit()
            writeBytes(bytes)
        }

    private fun ByteArrayOutputStream.writeU32(value: Int) {
        for (i in 0 until 4) write((value shr (8 * i)) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU64(value: Long) {
        for (i in 0 until 8) write(((value shr (8 * i)) and 0xFF).toInt())
    }

    private fun ByteArrayOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeU64(bytes.size.toLong())
        write(bytes)
    }
}
