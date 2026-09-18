package com.itsaky.androidide.plugins.aiagentlocal.model

/**
 * The parts of the GGUF format more than one reader in this package needs.
 *
 * Three readers screen the same four bytes — one from a SAF stream, two from a file — and each used
 * to spell the magic out again behind a "keep in sync" comment nothing checked.
 */
internal object GgufFormat {

    /** The magic as the four ASCII bytes a `.gguf` file starts with. */
    val MAGIC_BYTES: ByteArray = "GGUF".toByteArray(Charsets.US_ASCII)

    /** The same magic as the little-endian 32-bit word a `readU32` yields. */
    const val MAGIC_LE_INT = 0x46554747
}

/**
 * Memory allowances shared by the pre-flight warning and the refusal that gates the load itself, so
 * the two cannot contradict each other about how much headroom a model needs.
 */
internal object ModelMemory {

    /**
     * Graph and compute buffers every load allocates outright, whatever the model's shape (the
     * 2048-token batch `LLamaAndroid.load` sets up). Not derivable from the header, so it is a flat
     * allowance — and the floor below which a load is refused before it is attempted.
     */
    const val RUN_BUFFER_BYTES = 256L * 1024 * 1024
}
