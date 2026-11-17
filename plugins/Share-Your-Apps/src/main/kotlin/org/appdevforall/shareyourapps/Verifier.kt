package org.appdevforall.shareyourapps

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Checks the running server serves exactly what the publish wrote: fetches index.html and
 * every other file over localhost and compares length and SHA-256. The token gets past the PIN.
 */
class Verifier(private val port: Int, private val token: String) {

    /** Problems found, one line each; empty means everything matched. */
    fun verify(root: File, files: List<String>): List<String> {
        val problems = mutableListOf<String>()
        check("/", File(root, "index.html"), "index.html", problems)
        for (rel in files) check(link(rel), File(root, rel), rel, problems)
        return problems
    }

    private fun check(path: String, file: File, label: String, problems: MutableList<String>) {
        try {
            val url = URI("http://127.0.0.1:$port$path?token=" + URLEncoder.encode(token, "UTF-8")).toURL()
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 5_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            // Set explicitly: HttpURLConnection otherwise asks for gzip itself and silently decompresses.
            c.setRequestProperty("Accept-Encoding", "identity")
            try {
                if (c.responseCode != 200) { problems += "$label: server answered ${c.responseCode}"; return }
                val (length, digest) = c.inputStream.use { digest(it) }
                val (expectedLength, expected) = expected(file).use { digest(it) }
                if (length != expectedLength) problems += "$label: served $length bytes, expected $expectedLength"
                else if (!digest.contentEquals(expected)) problems += "$label: contents differ"
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            problems += "$label: ${e.message ?: e.toString()}"
        }
    }

    /**
     * The file's contents, as published. The server replaces a source zip by its .gz copy once that's made — perhaps
     * mid-check — so a zip that's gone is read from its copy, decompressed: the same bytes. Opened once, so its length
     * and contents can't come from different copies.
     */
    private fun expected(file: File): InputStream = try {
        file.inputStream()
    } catch (e: java.io.FileNotFoundException) {
        val gz = File(file.path + ".gz")
        if (file.name.endsWith(".zip") && gz.isFile) java.util.zip.GZIPInputStream(gz.inputStream(), 64 * 1024) else throw e
    }

    private fun digest(input: InputStream): Pair<Long, ByteArray> {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            total += n
        }
        return total to md.digest()
    }
}
