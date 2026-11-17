package org.appdevforall.shareyourapps.server

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.stream.Collectors
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

/**
 * Gzips the pages at the Store directory's top and every source zip, in the background, so
 * publishing doesn't wait for it. Each copy is written to `<file>.gz.tmp` and renamed to
 * `<file>.gz` only when finished, so a partial copy is never served. A copy that isn't smaller
 * than the original is deleted instead of renamed. On any error the temporary file is deleted
 * and that file is skipped, and the uncompressed file keeps being served. Once a source zip's
 * copy is complete, the zip itself is deleted, and HttpServer serves it from the copy.
 *
 * Nothing coordinates with this thread: a relaunch kills the whole server process.
 */
class Compressor(root: File, private val log: StoreLog) {

    private val rootDir = root.canonicalFile

    /** The files to compress, relative to the Store directory: the pages at its top — index.html and accessible.html — and every .zip, without a .gz. */
    fun pending(): List<String> {
        val base = rootDir.toPath()
        return Files.walk(base).use { paths -> // doesn't follow symlinks, so it stays inside
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .map { base.relativize(it).toString().replace(File.separatorChar, '/') }
                .filter { (it.endsWith(".html") && '/' !in it) || it.endsWith(".zip") }
                .filter { !File(rootDir, "$it.gz").exists() }
                .sorted()
                .collect(Collectors.toList()) // not Stream.toList(): that's API 34+ on Android
        }
    }

    fun start(): Thread = thread(name = "gzip", isDaemon = true, priority = Thread.MIN_PRIORITY) { run() }

    fun run() {
        val todo = try { pending() } catch (e: Exception) {
            log.status("gzip", "failed: couldn't list files: ${e.message}")
            return
        }
        log.status("gzip", "started: ${todo.size} files")
        var ok = 0
        var skipped = 0
        var failed = 0
        for (rel in todo) {
            when (compress(rel)) {
                Outcome.COMPRESSED -> ok++
                Outcome.NOT_SMALLER -> skipped++
                Outcome.FAILED -> failed++
            }
        }
        log.status("gzip", "finished: $ok compressed, $skipped not smaller, $failed failed")
    }

    private enum class Outcome { COMPRESSED, NOT_SMALLER, FAILED }

    private fun compress(rel: String): Outcome {
        val src = File(rootDir, rel)
        val tmp = File(rootDir, "$rel.gz.tmp")
        val dst = File(rootDir, "$rel.gz")
        val started = System.currentTimeMillis()
        return try {
            FileInputStream(src).use { input ->
                BestGzip(FileOutputStream(tmp)).use { out -> input.copyTo(out, 64 * 1024) }
            }
            val secs = (System.currentTimeMillis() - started) / 1000
            if (tmp.length() >= src.length()) {
                // Serving it would save nothing, so it never becomes a .gz.
                log.status("gzip", "not smaller: $rel ${src.length()} -> ${tmp.length()} bytes; deleted")
                tmp.delete()
                return Outcome.NOT_SMALLER
            }
            if (!tmp.renameTo(dst)) throw IOException("couldn't rename ${tmp.name}")
            log.status("gzip", "done: $rel ${src.length()} -> ${dst.length()} bytes in $secs s")
            // A source zip keeps only its copy, to save space: the server decompresses it for the rare client that
            // doesn't take gzip, and for ranges. The copy takes the zip's date, so its ETag and Last-Modified don't
            // change. Pages keep both, being tiny; so does a zip of 4 GB or more, since gzip records sizes modulo 4 GB.
            if (rel.endsWith(".zip") && src.length() < GZ_SIZE_LIMIT) {
                dst.setLastModified(src.lastModified())
                if (src.delete()) log.status("gzip", "kept only the copy: $rel") else log.status("gzip", "couldn't delete $rel; serving both")
            }
            Outcome.COMPRESSED
        } catch (e: Exception) {
            tmp.delete()
            log.status("gzip", "failed: $rel: ${e.message ?: e.toString()}")
            Outcome.FAILED
        }
    }

    companion object {
        /** gzip records the original size modulo 2^32, so a larger zip keeps its original. */
        const val GZ_SIZE_LIMIT = 1L shl 32
    }

    private class BestGzip(out: OutputStream) : GZIPOutputStream(out, 64 * 1024) {
        init { def.setLevel(Deflater.BEST_COMPRESSION) }
    }
}
