package org.appdevforall.maps.util

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Atomic file write/copy helpers shared by the data layer (RegionDownloader,
 * RegionInstaller, ActiveRegionStore) and the JVM-pure templates emitter
 * (ProjectMapEmitter).
 *
 * Each operation writes to a sibling `.tmp` file first, then renames it onto the
 * destination — so a process kill mid-write leaves either the old file intact or
 * a stray `.tmp`, never a torn destination. `ATOMIC_MOVE` is preferred; on
 * filesystems that don't support it (some FAT/exFAT SD cards) the code falls back
 * to a plain `REPLACE_EXISTING` move.
 *
 * JVM-pure on purpose — only `java.io` / `java.nio` / `kotlin.io`, no Android
 * imports — so [ProjectMapEmitter] (which has no Android dependencies and is
 * unit-tested without Robolectric) can depend on it.
 */
internal object AtomicFiles {

    /**
     * Atomically write [text] to [dest]. Creates [dest]'s parent directory if it
     * doesn't already exist.
     *
     * @throws IllegalStateException if [dest] has no parent directory.
     */
    fun writeText(dest: File, text: String) {
        val parent = dest.parentFile
            ?: error("atomicWriteText: destination has no parent dir: $dest")
        if (!parent.exists()) parent.mkdirs()
        val tmp = File(parent, dest.name + ".tmp")
        if (tmp.exists()) tmp.delete()
        tmp.writeText(text)
        move(tmp, dest)
    }

    /**
     * Atomically copy [src] to [dest] via a temp file. Creates [dest]'s parent
     * directory if it doesn't already exist.
     *
     * The copy runs as a chunked read/write loop that invokes [onChunk] between
     * chunks, giving the caller a cooperative-cancellation seam: a coroutine
     * caller passes `{ coroutineContext.ensureActive() }`, so closing the bottom
     * sheet (or switching projects) mid-copy of a 100+ MB `tiles.pmtiles`
     * actually aborts the copy instead of blocking until it finishes. [onChunk]
     * defaults to a no-op so non-coroutine callers (and the JVM unit tests) are
     * unaffected. If [onChunk] throws (e.g. `CancellationException`), the
     * half-written temp file is deleted and the exception propagates — the old
     * destination is left untouched because the atomic move never ran.
     *
     * @throws IllegalStateException if [dest] has no parent directory.
     */
    fun copy(src: File, dest: File, onChunk: () -> Unit = {}) {
        val parent = dest.parentFile
            ?: error("atomicCopy: destination has no parent dir: $dest")
        if (!parent.exists()) parent.mkdirs()
        val tmp = File(parent, dest.name + ".tmp")
        if (tmp.exists()) tmp.delete()
        try {
            src.inputStream().use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        onChunk()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (t: Throwable) {
            // Cancelled or failed mid-copy: drop the partial temp so we never
            // promote a torn file, then rethrow so the caller sees the failure.
            runCatching { tmp.delete() }
            throw t
        }
        move(tmp, dest)
    }

    /** Read/write chunk size for [copy]; also the cancellation-check granularity. */
    private const val COPY_BUFFER_BYTES = 512 * 1024

    /** Rename [tmp] onto [dest], preferring an atomic move with a plain-move fallback. */
    private fun move(tmp: File, dest: File) {
        try {
            Files.move(
                tmp.toPath(),
                dest.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
