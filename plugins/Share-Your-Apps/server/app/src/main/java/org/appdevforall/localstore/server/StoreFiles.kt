package org.appdevforall.localstore.server

import java.io.File
import java.io.IOException

/**
 * The only way the server finds files to serve. Every request path is resolved against the
 * Store directory's canonical path — which follows symlinks — and anything that doesn't
 * land inside it is refused.
 */
class StoreFiles(root: File) {

    private val rootDir: File = root.canonicalFile
    private val rootPrefix: String = rootDir.path + File.separator

    fun hasIndex(): Boolean = resolve("index.html") != null

    /** The regular file at [relative] inside the Store directory, or null. */
    fun resolve(relative: String): File? {
        if (relative.isEmpty() || relative.startsWith("/") || relative.contains('\u0000')) return null
        if (relative.split('/').any { it == ".." }) return null
        val f = try { File(rootDir, relative).canonicalFile } catch (e: IOException) { return null }
        if (!f.path.startsWith(rootPrefix)) return null
        return if (f.isFile) f else null
    }

    companion object {
        /** A .gz file's original size, from its last four bytes — modulo 4 GB, so only for smaller files. */
        fun gzipSize(gz: File): Long = java.io.RandomAccessFile(gz, "r").use { f ->
            f.seek(f.length() - 4); val b = ByteArray(4); f.readFully(b)
            (b[0].toLong() and 0xff) or ((b[1].toLong() and 0xff) shl 8) or ((b[2].toLong() and 0xff) shl 16) or ((b[3].toLong() and 0xff) shl 24)
        }

        /** Writes [count] bytes of [gz]'s original contents, from [offset], decompressing it on the way. */
        fun gunzipSlice(gz: File, offset: Long, count: Long, out: java.io.OutputStream) {
            java.util.zip.GZIPInputStream(java.io.BufferedInputStream(java.io.FileInputStream(gz), 64 * 1024), 64 * 1024).use { input ->
                var skip = offset
                while (skip > 0) { val n = input.skip(skip); if (n <= 0) { if (input.read() < 0) return; skip-- } else skip -= n }
                val buf = ByteArray(64 * 1024); var left = count
                while (left > 0) { val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; out.write(buf, 0, n); left -= n }
            }
        }

        private val TYPES = mapOf(
            "apk" to "application/vnd.android.package-archive",
            "zip" to "application/zip",
            "html" to "text/html; charset=utf-8", "htm" to "text/html; charset=utf-8",
            "txt" to "text/plain; charset=utf-8",
            "css" to "text/css", "js" to "text/javascript", "json" to "application/json",
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
            "gif" to "image/gif", "webp" to "image/webp", "svg" to "image/svg+xml",
            "ico" to "image/x-icon",
            "mp4" to "video/mp4", "webm" to "video/webm",
            "mp3" to "audio/mpeg", "ogg" to "audio/ogg", "wav" to "audio/wav", "m4a" to "audio/mp4",
        )

        fun contentType(name: String): String =
            TYPES[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

        /** APKs and zips download under their real name; everything else displays inline. */
        fun disposition(name: String): String? {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext != "apk" && ext != "zip") return null
            val ascii = name.map { if (it.code in 0x20..0x7e && it != '"' && it != '\\') it else '_' }
                .joinToString("")
            return "attachment; filename=\"$ascii\"; filename*=UTF-8''${pctEncode(name)}"
        }

        private fun pctEncode(s: String): String = buildString {
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xff
                if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-._~") append(c.toChar())
                else append('%').append("%02X".format(c))
            }
        }
    }
}
