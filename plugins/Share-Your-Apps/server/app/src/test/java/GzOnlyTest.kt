package gzonlytest

import org.appdevforall.shareyourapps.server.*
import java.io.*
import java.net.*

/**
 * A source zip kept only as its .gz copy (Compressor, from server step 70): served whole and in ranges, with or
 * without gzip, with its ETag and dates unchanged, and shared after it's gone — each against a real server.
 */
private fun req(port: Int, path: String, headers: Map<String, String> = emptyMap(), method: String = "GET"): Triple<Int, Map<String, String>, ByteArray> {
    val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
    c.requestMethod = method; c.setRequestProperty("Accept-Encoding", "identity"); headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    val code = c.responseCode; val h = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.first() }
    val body = try { (if (code >= 400) c.errorStream else c.inputStream)?.readBytes() ?: ByteArray(0) } catch (e: Exception) { ByteArray(0) }
    c.disconnect(); return Triple(code, h, body)
}

fun runChecks(): List<String> {
    val failed = mutableListOf<String>()
    fun check(what: String, ok: Boolean) { println((if (ok) "PASS  " else "FAIL  ") + what); if (!ok) failed += what }

    val dir = File(System.getProperty("java.io.tmpdir"), "gzonly-store"); dir.deleteRecursively(); File(dir, "products/a").mkdirs()
    File(dir, "index.html").writeText("<html><body>" + "store page ".repeat(500) + "</body></html>")
    val zip = File(dir, "products/a/a-source.zip"); val original = ByteArray(300_000) { (it % 251 + it / 1000).toByte() }; zip.writeBytes(original)
    val log = StoreLog(File(System.getProperty("java.io.tmpdir"), "gzonly-log"), 1_000_000)
    val server = HttpServer(dir, listOf(InetAddress.getByName("127.0.0.1")), 0, log); server.start(); val port = server.boundPort
    val path = "/f/products/a/a-source.zip"
    val before = req(port, path)
    Compressor(dir, log).run()
    val gz = File(dir, "products/a/a-source.zip.gz")
    check("the zip is deleted, and only its .gz copy kept", !zip.exists() && gz.isFile)
    check("the page keeps both copies", File(dir, "index.html").isFile && File(dir, "index.html.gz").isFile)
    val plain = req(port, path)
    check("a client without gzip gets the zip's exact bytes", plain.first == 200 && plain.third.contentEquals(original) && plain.second["content-length"] == original.size.toString())
    check("its ETag and Last-Modified are unchanged", plain.second["etag"] == before.second["etag"] && plain.second["last-modified"] == before.second["last-modified"])
    val zipped = req(port, path, mapOf("Accept-Encoding" to "gzip"))
    check("a gzip client gets the copy as-is, marked gzip", zipped.second["content-encoding"] == "gzip" && zipped.third.contentEquals(gz.readBytes()))
    val r = req(port, path, mapOf("Range" to "bytes=100000-100999"))
    check("a range gets exactly that slice, decompressed", r.first == 206 && r.third.contentEquals(original.copyOfRange(100000, 101000)) && r.second["content-range"] == "bytes 100000-100999/${original.size}")
    val tail = req(port, path, mapOf("Range" to "bytes=-500"))
    check("a range at the end gets the last bytes", tail.first == 206 && tail.third.contentEquals(original.copyOfRange(original.size - 500, original.size)))
    val head = req(port, path, method = "HEAD")
    check("HEAD gives the zip's own length", head.first == 200 && head.second["content-length"] == original.size.toString())
    val again = req(port, path, mapOf("If-None-Match" to (before.second["etag"] ?: "")))
    check("the old ETag still gets 304, not modified", again.first == 304)
    check("past the end gets 416", req(port, path, mapOf("Range" to "bytes=900000-")).first == 416)
    check("a missing zip is still not found", req(port, "/f/products/a/nothing.zip").first == 404)
    // Sharing: listed while the zip existed, then sent after it was deleted.
    zip.writeBytes(original); gz.delete(); gz.parentFile.listFiles()!!.forEach { if (it.name.endsWith(".gz")) it.delete() }
    val key = ShareCrypto.newKeyPair()
    val desc = SharePackage.describe(dir, "Store", key, java.time.Instant.now(), null)
    Compressor(dir, log).run()
    val out = ByteArrayOutputStream(); SharePackage.write(out, dir, desc, key)
    val bytes = out.toByteArray()
    check("sharing sends a zip deleted after listing, from its copy", !zip.exists() && bytes.size > original.size && String(bytes, Charsets.ISO_8859_1).contains(String(original.copyOfRange(0, 64), Charsets.ISO_8859_1)))
    server.stop()
    println(if (failed.isEmpty()) "\nALL PASSED" else "\n${failed.size} FAILED")
    return failed
}

/** The JUnit test: runs the checks above, and fails naming them if any fail. */
class GzOnlyChecks {
    @org.junit.Test
    fun everyCheckPasses() {
        val failed = runChecks()
        org.junit.Assert.assertTrue("Failed checks: " + failed.joinToString("; "), failed.isEmpty())
    }
}
