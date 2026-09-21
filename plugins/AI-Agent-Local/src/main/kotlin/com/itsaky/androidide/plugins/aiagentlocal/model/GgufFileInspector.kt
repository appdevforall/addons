package com.itsaky.androidide.plugins.aiagentlocal.model

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.itsaky.androidide.plugins.aiagentlocal.logging.LOG_PREFIX
import java.io.InputStream

/**
 * Best-effort GGUF validation for a SAF-selected document.
 *
 * Separate from `GgufModelInspector` because the two answer different questions: this screens a
 * `content://` document the user just picked, before any path is stored, so it must work from a
 * stream and never throw. The magic itself comes from [GgufFormat], shared with the other readers.
 */
internal object GgufFileInspector {

    private const val TAG = "$LOG_PREFIX.GgufFileInspector"

    private val GGUF_MAGIC = GgufFormat.MAGIC_BYTES

    /**
     * @param header first bytes of a candidate file
     * @return true if [header] starts with the GGUF magic ("GGUF")
     */
    fun bytesAreGgufMagic(header: ByteArray): Boolean =
        header.size >= GGUF_MAGIC.size && GGUF_MAGIC.indices.all { header[it] == GGUF_MAGIC[it] }

    /**
     * Best-effort check that the picked document is a GGUF model, reading only its first four
     * bytes. Fails OPEN (returns true) on any read error or short file so a real model is never
     * wrongly rejected; SAF can't filter the picker by a .gguf extension. Do NOT call on the main
     * thread.
     * @param resolver content resolver used to open [uriString]
     * @param uriString content URI (or path) of the selected document
     * @return false only when four bytes were read and are not the GGUF magic; true otherwise
     */
    fun looksLikeGguf(resolver: ContentResolver, uriString: String): Boolean =
        looksLikeGguf(
            openStream = { resolver.openInputStream(Uri.parse(uriString)) },
            onReadError = { e ->
                Log.w(TAG, "Could not read model header for $uriString; skipping pre-check", e)
            },
        )

    /**
     * Android-free core of [looksLikeGguf], so every fail-open path (document that can't be
     * opened, stream that throws mid-read, truncated file) is unit-testable without a device.
     * @param openStream opens the candidate document, or returns null if it can't be opened
     * @param onReadError invoked with the failure when the stream can't be opened or read
     * @return false only when four bytes were read and are not the GGUF magic; true otherwise
     */
    fun looksLikeGguf(openStream: () -> InputStream?, onReadError: (Exception) -> Unit): Boolean =
        try {
            openStream()?.use { readsGgufMagic(it) } ?: true
        } catch (e: Exception) {
            onReadError(e)
            true
        }

    /**
     * @param input stream positioned at the start of the candidate document
     * @return true if the first four bytes are the GGUF magic, or if the stream ended early
     */
    private fun readsGgufMagic(input: InputStream): Boolean {
        val header = ByteArray(GGUF_MAGIC.size)
        var off = 0
        // Read may return fewer than 4 bytes at a time, so loop until full or EOF.
        while (off < header.size) {
            val n = input.read(header, off, header.size - off)
            if (n < 0) break
            off += n
        }
        return if (off < header.size) true else bytesAreGgufMagic(header)
    }
}
