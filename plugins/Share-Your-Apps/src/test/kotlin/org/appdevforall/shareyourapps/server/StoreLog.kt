package org.appdevforall.shareyourapps.server

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The access log. Thread-safe: every write and every rotation happen under one lock,
 * so no line can land in a file that is being closed or renamed.
 *
 * Keeps at most five files: access.log plus access.log.1 … access.log.4.
 * maxBytes = 0 turns logging off.
 */
class StoreLog(private val dir: File, private val maxBytes: Long) {

    companion object {
        const val FILE_NAME = "access.log"
        const val KEEP = 5
    }

    private val file = File(dir, FILE_NAME)
    private var out: FileOutputStream? = null
    private val dateFormat = SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z", Locale.US)

    @Synchronized
    fun write(line: String) {
        if (maxBytes <= 0) return
        try {
            val o = out ?: open().also { out = it }
            o.write((line + "\n").toByteArray(Charsets.UTF_8))
            o.flush()
            if (file.length() >= maxBytes) rotate()
        } catch (e: IOException) {
            // Logging must never break serving.
        }
    }

    /** A plain-text status line, e.g. `[server] serving Store_12345678 on 192.168.1.5:6175`. */
    fun status(tag: String, message: String) = write("[$tag] $message")

    /** One Apache combined-format line, plus the transfer rate. */
    @Synchronized
    fun access(
        client: String, requestLine: String, status: Int, bytes: Long,
        referer: String?, userAgent: String?, millis: Long,
    ) {
        val rate = if (millis > 0) bytes * 1000 / millis else bytes
        val size = if (bytes > 0) bytes.toString() else "-"
        write(
            "$client - - [${dateFormat.format(Date())}] \"${q(requestLine)}\" $status $size " +
                "\"${q(referer ?: "-")}\" \"${q(userAgent ?: "-")}\" ${rate}B/s"
        )
    }

    @Synchronized
    fun close() {
        try { out?.close() } catch (e: IOException) { }
        out = null
    }

    private fun open(): FileOutputStream {
        dir.mkdirs()
        return FileOutputStream(file, true)
    }

    private fun rotate() {
        try { out?.close() } catch (e: IOException) { }
        out = null
        File(dir, "$FILE_NAME.${KEEP - 1}").delete()
        for (i in KEEP - 2 downTo 1) {
            val f = File(dir, "$FILE_NAME.$i")
            if (f.exists()) f.renameTo(File(dir, "$FILE_NAME.${i + 1}"))
        }
        file.renameTo(File(dir, "$FILE_NAME.1"))
    }

    private fun q(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
