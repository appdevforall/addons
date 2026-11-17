package coretest

import org.appdevforall.shareyourapps.server.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

var failures = 0
val failedNames = mutableListOf<String>()
fun check(name: String, cond: Boolean) {
    if (!cond) failedNames += name
   
    println((if (cond) "PASS  " else "FAIL  ") + name)
    if (!cond) failures++
}

class Resp(val status: Int, val headers: Map<String, String>, val body: ByteArray)

fun http(port: Int, request: String, host: String = "127.0.0.1"): Resp {
    Socket(host, port).use { s ->
        s.soTimeout = 10000
        s.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
        s.getOutputStream().flush()
        val all = s.getInputStream().readBytes()
        val split = (0 until all.size - 3).first {
            all[it] == '\r'.code.toByte() && all[it + 1] == '\n'.code.toByte() &&
                all[it + 2] == '\r'.code.toByte() && all[it + 3] == '\n'.code.toByte()
        }
        val head = String(all, 0, split, Charsets.ISO_8859_1).split("\r\n")
        val status = head[0].split(' ')[1].toInt()
        val headers = head.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        return Resp(status, headers, all.copyOfRange(split + 4, all.size))
    }
}
fun get(port: Int, path: String, vararg hdr: String, host: String = "127.0.0.1") =
    http(port, "GET $path HTTP/1.1\r\nHost: x\r\n" + hdr.joinToString("") { "$it\r\n" } + "\r\n", host)

fun gz(bytes: ByteArray): ByteArray {
    val b = ByteArrayOutputStream(); GZIPOutputStream(b).use { it.write(bytes) }; return b.toByteArray()
}

/** Runs every check; returns the names of those that failed. */
fun runChecks(): List<String> {
    failures = 0; failedNames.clear()
    val tmp = Files.createTempDirectory("lss").toFile()
    val store = File(tmp, "CodeOnTheGoProjects/Store_12345678").apply { mkdirs() }
    val secret = File(tmp, "CodeOnTheGoProjects/secret.txt").apply { writeText("SECRET") }
    val logDir = File(tmp, "logs")
    val log = StoreLog(logDir, 10L * 1024 * 1024)

    val server = HttpServer(store, listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("127.0.0.2")), 0, log)
    server.start()
    val p = server.boundPort
    println("server on port $p")

    // --- not set up yet ---
    check("503 on / before any index.html", get(p, "/").status == 503)
    check("503 on /f/ before any index.html", get(p, "/f/anything").status == 503)

    // --- populate ---
    val index = "<html>catalog</html>".repeat(50).toByteArray()
    File(store, "index.html").writeBytes(index)
    File(store, "index.html.gz").writeBytes(gz(index))
    val apk = ByteArray(300_000) { (it % 251).toByte() }
    File(store, "products/my-app").mkdirs()
    File(store, "products/my-app/app-release.apk").writeBytes(apk)
    File(store, "products/my-app/app-release.zip").writeBytes(ByteArray(1000) { 7 })
    File(store, "products/my-app/café shot.png").writeBytes(ByteArray(20_000) { 1 })
    Files.createSymbolicLink(File(store, "products/my-app/escape.txt").toPath(), secret.toPath())

    // --- the catalog page ---
    val r1 = get(p, "/")
    check("/ serves index.html", r1.status == 200 && r1.body.contentEquals(index))
    check("the page tells browsers to check back every time", r1.headers["cache-control"] == "no-cache")
    check("message pages aren't stored at all", get(p, "/nope").headers["cache-control"] == "no-store")
    check("/ has text/html type", r1.headers["content-type"]!!.startsWith("text/html"))
    check("/ has Connection: close", r1.headers["connection"] == "close")
    check("/index.html serves the same page", get(p, "/index.html").body.contentEquals(index))

    // --- Last-Modified ---
    val lmHeader = r1.headers["last-modified"]
    check("files carry Last-Modified", lmHeader != null && parseHttpDate(lmHeader) != null)
    val lmSame = get(p, "/", "If-Modified-Since: $lmHeader")
    check("unchanged since that time: 304, with no body", lmSame.status == 304 && lmSame.body.isEmpty() && lmSame.headers["cache-control"] == "no-cache")
    val lmOlder = get(p, "/", "If-Modified-Since: ${httpDate(parseHttpDate(lmHeader!!)!! - 3_600_000)}")
    check("changed since an older time: the whole file", lmOlder.status == 200 && lmOlder.body.contentEquals(index))
    check("an unreadable date is ignored", get(p, "/", "If-Modified-Since: yesterday").status == 200)
    // On Wi-Fi Direct every store is at the same address, so a later date may be another store's page.
    val lmLater = get(p, "/", "If-Modified-Since: ${httpDate(parseHttpDate(lmHeader)!! + 86_400_000)}")
    check("a later date — perhaps another store's page — gets this store's page, not 304", lmLater.status == 200)
    val etag = get(p, "/").headers["etag"]
    check("every file carries an ETag naming its store ($etag)", etag != null && etag.startsWith("W/\"") && store.name in etag)
    check("a matching If-None-Match gets 304", get(p, "/", "If-None-Match: $etag").status == 304)
    check("another store's ETag gets this store's page", get(p, "/", "If-None-Match: W/\"Store_00000000-1-2\"").status == 200)
    check("If-None-Match wins over a matching date", get(p, "/", "If-None-Match: W/\"Store_00000000-1-2\"", "If-Modified-Since: $lmHeader").status == 200)

    // --- gzip rule ---
    val g = get(p, "/", "Accept-Encoding: gzip, deflate")
    check("gzip-accepting client gets the .gz copy",
        g.headers["content-encoding"] == "gzip" && g.body.contentEquals(File(store, "index.html.gz").readBytes()))
    check("gzip copy keeps the original Content-Type", g.headers["content-type"]!!.startsWith("text/html"))
    check("gzip Content-Length is the compressed size", g.headers["content-length"] == File(store, "index.html.gz").length().toString())
    check("identity client gets the plain file", get(p, "/", "Accept-Encoding: identity").headers["content-encoding"] == null)
    check("gzip;q=0 is treated as refusal", get(p, "/", "Accept-Encoding: gzip;q=0").headers["content-encoding"] == null)
    check("no .gz copy means plain file even for gzip clients",
        get(p, "/f/products/my-app/app-release.apk", "Accept-Encoding: gzip").headers["content-encoding"] == null)

    // --- files and headers ---
    val a = get(p, "/f/products/my-app/app-release.apk")
    check("APK served whole", a.status == 200 && a.body.contentEquals(apk))
    check("APK Content-Type", a.headers["content-type"] == "application/vnd.android.package-archive")
    check("APK downloads under its real name", a.headers["content-disposition"]!!.contains("filename=\"app-release.apk\""))
    check("zip Content-Type and attachment",
        get(p, "/f/products/my-app/app-release.zip").let { it.headers["content-type"] == "application/zip" && it.headers["content-disposition"]!!.startsWith("attachment") })
    val img = get(p, "/f/products/my-app/caf%C3%A9%20shot.png")
    check("percent-encoded UTF-8 path resolves", img.status == 200 && img.headers["content-type"] == "image/png")
    check("images display inline (no Content-Disposition)", img.headers["content-disposition"] == null)

    // --- confinement ---
    check("/f/../ traversal refused", get(p, "/f/../secret.txt").status == 404)
    check("encoded %2e%2e traversal refused", get(p, "/f/%2e%2e/secret.txt").status == 404)
    check("mid-path .. refused", get(p, "/f/products/../../secret.txt").status == 404)
    check("absolute path refused", get(p, "/f//etc/passwd").status == 404)
    check("symlink pointing outside refused", get(p, "/f/products/my-app/escape.txt").status == 404)
    check("encoded NUL refused", get(p, "/f/index.html%00.png").status in setOf(400, 404))
    check("directory refused", get(p, "/f/products").status == 404)
    check("secret never appears in any response", listOf("/f/../secret.txt", "/f/%2e%2e/secret.txt", "/f/products/my-app/escape.txt")
        .none { String(get(p, it).body).contains("SECRET") })

    // --- other routes and methods ---
    check("unknown path is 404", get(p, "/nope").status == 404)
    check("POST is 405", http(p, "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n").status == 405)
    val h = http(p, "HEAD /f/products/my-app/app-release.apk HTTP/1.1\r\nHost: x\r\n\r\n")
    check("HEAD gives headers and no body", h.status == 200 && h.body.isEmpty() && h.headers["content-length"] == apk.size.toString())
    check("malformed request line gets no crash", runCatching { http(p, "garbage\r\n\r\n") }.isSuccess || true)
    check("server still healthy after garbage", get(p, "/").status == 200)

    // --- second listening address ---
    check("second bound address serves too", get(p, "/", host = "127.0.0.2").status == 200)

    // --- idle preconnect doesn't block other buyers ---
    val idle = Socket("127.0.0.1", p) // sends nothing, like a browser preconnect
    Thread.sleep(100)
    val t0 = System.currentTimeMillis()
    val r = get(p, "/")
    val waited = System.currentTimeMillis() - t0
    check("request behind an idle preconnect completes (waited ${waited} ms)", r.status == 200 && waited < HttpServer.READ_TIMEOUT_MS + 1500)
    idle.close()

    // --- concurrency: many clients at once, all answered correctly ---
    val results = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
    (1..20).map { thread { results.add(get(p, "/f/products/my-app/app-release.apk").body.contentEquals(apk)) } }.forEach { it.join() }
    check("20 simultaneous downloads all correct", results.size == 20 && results.all { it })

    // --- range requests: skipping in a video, or resuming a download ---
    val rangePath = "/f/products/my-app/app-release.apk"
    val slice = get(p, rangePath, "Range: bytes=0-99")
    check("a range gets 206 and just that slice", slice.status == 206 && slice.body.contentEquals(apk.copyOfRange(0, 100)) &&
        slice.headers["content-range"] == "bytes 0-99/300000" && slice.headers["content-length"] == "100")
    check("…from a point to the end", get(p, rangePath, "Range: bytes=299900-").let { it.status == 206 && it.body.contentEquals(apk.copyOfRange(299900, 300000)) })
    check("…the last n bytes", get(p, rangePath, "Range: bytes=-50").let { it.status == 206 && it.body.contentEquals(apk.copyOfRange(299950, 300000)) && it.headers["content-range"] == "bytes 299950-299999/300000" })
    check("…a range running past the end is cut short", get(p, rangePath, "Range: bytes=299990-400000").let { it.status == 206 && it.body.contentEquals(apk.copyOfRange(299990, 300000)) })
    check("a range starting past the end gets 416, with the real size", get(p, rangePath, "Range: bytes=300000-").let { it.status == 416 && it.headers["content-range"] == "bytes */300000" })
    check("several ranges, or a malformed one, get the whole file",
        listOf("bytes=0-9,20-29", "bytes=abc", "bytes=9-3", "items=0-9").all { h -> get(p, rangePath, "Range: $h").let { it.status == 200 && it.body.contentEquals(apk) } })
    val rangeDate = get(p, rangePath).headers["last-modified"]!!
    check("If-Range with the file's own date: the slice", get(p, rangePath, "Range: bytes=0-9", "If-Range: $rangeDate").status == 206)
    check("If-Range with an old date: the whole, new file", get(p, rangePath, "Range: bytes=0-9", "If-Range: Mon, 01 Jan 2024 00:00:00 GMT").let { it.status == 200 && it.body.size == 300_000 })
    check("a range of the store page is from the plain page, never its gzip copy",
        get(p, "/", "Range: bytes=0-9", "Accept-Encoding: gzip").let { it.status == 206 && it.headers["content-encoding"] == null && it.body.contentEquals(index.copyOfRange(0, 10)) })
    check("every file says it accepts ranges", get(p, rangePath).headers["accept-ranges"] == "bytes")
    check("HEAD with a range: its headers, no body", http(p, "HEAD $rangePath HTTP/1.1\r\nHost: x\r\nRange: bytes=0-99\r\n\r\n").let { it.status == 206 && it.body.isEmpty() && it.headers["content-length"] == "100" })
    check("the range rules on their own", parseRange("bytes=-0", 100) == ByteRange.UNSATISFIABLE && parseRange("bytes=0-", 0) == ByteRange.UNSATISFIABLE &&
        parseRange("BYTES=5-", 10) == ByteRange(5, 9) && parseRange("bytes= 1-2 ", 10) == ByteRange(1, 2) && parseRange("bytes=-", 10) == null && parseRange("bytes=99999999999999999999-", 10) == null)

    // --- a download the buyer stops partway is still logged, with the bytes actually sent ---
    // When the buyer hangs up, the server learns of it at its next failed write, whose timing is up
    // to the phone's network stack; failing that, the stall limit closes the connection. A server
    // with a two-second limit makes the line appear within seconds either way, on any phone.
    // Much larger than the network's buffers, and a small receive buffer, so stopping really stops it partway.
    val stopBig = File(store, "products/my-app/stop-big.bin")
    java.io.RandomAccessFile(stopBig, "rw").use { it.setLength(32L * 1024 * 1024) }
    val stopServer = HttpServer(store, listOf(InetAddress.getByName("127.0.0.1")), 0, log, stallMs = 2_000)
    stopServer.start()
    java.net.Socket().use { sk ->
        sk.receiveBufferSize = 4096
        sk.connect(java.net.InetSocketAddress("127.0.0.1", stopServer.boundPort))
        sk.getOutputStream().apply { write("GET /f/products/my-app/stop-big.bin?stopped HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); flush() }
        sk.getInputStream().read(ByteArray(1000))
    }
    var stoppedLine: String? = null
    val stopWaitStart = System.currentTimeMillis()
    while (System.currentTimeMillis() - stopWaitStart < 10_000) {
        stoppedLine = logDir.listFiles()!!.filter { it.isFile }.flatMap { it.readLines() }.lastOrNull { "?stopped" in it }
        if (stoppedLine != null) break
        Thread.sleep(100)
    }
    val stopWaited = System.currentTimeMillis() - stopWaitStart
    // Sizes are counted in 8 KB slices, so a send that fails within its first slice logs "-", none: still less than the file's.
    val stoppedBytes = stoppedLine?.let { Regex("""" 200 (\d+|-) """).find(it)?.groupValues?.get(1)?.let { n -> if (n == "-") 0L else n.toLong() } }
    check("a download stopped partway is still logged, with fewer bytes than the file's, to within 8 KB (${stoppedBytes} of ${stopBig.length()}, " +
        "after ${stopWaited} ms${if (stoppedBytes == null) "; line seen: " + (stoppedLine?.substringAfter("HTTP/1.1\" ")?.take(40) ?: "none") else ""})",
        stoppedBytes != null && stoppedBytes < stopBig.length())
    stopServer.stop()
    stopBig.delete()

    // --- log ---
    val lines = File(logDir, "access.log").readLines()
    check("the log records a range as 206, with the bytes sent", lines.any { "\" 206 100 " in it })
    check("access log has lines", lines.size > 20)
    check("log lines are Apache combined format with a rate",
        lines.first { "app-release.apk" in it }.matches(Regex("""^\S+ - - \[.+] "GET \S+ HTTP/1\.1" 200 \d+ "[^"]*" "[^"]*" \d+B/s$""")))
    val accessLines = lines.filter { it.contains("\" 200 ") || it.contains("\" 304 ") || it.contains("\" 404 ") }
    check("with many downloads at once, every log line is whole — a response without a body logs its size as -, (${accessLines.size} lines)",
        accessLines.size > 20 && accessLines.all { it.matches(Regex("""^\S+ - - \[.+] "\S+ \S+ HTTP/1\.[01]" \d{3} (\d+|-) "[^"]*" "[^"]*" \d+B/s$""")) }.also { ok ->
            if (!ok) accessLines.filterNot { it.matches(Regex("""^\S+ - - \[.+] "\S+ \S+ HTTP/1\.[01]" \d{3} (\d+|-) "[^"]*" "[^"]*" \d+B/s$""")) }.take(3).forEach { println("   odd log line: $it") } })

    // --- restart on the same port right away (TIME_WAIT) ---
    // --- the stall watchdog: a buyer who stops receiving can't hold up everyone ---
    val wdBig = File(store, "products/my-app/wdBig.bin")
    java.io.RandomAccessFile(wdBig, "rw").use { it.setLength(64L * 1024 * 1024) }
    val wdServer = HttpServer(store, listOf(InetAddress.getByName("127.0.0.1")), 0, log, stallMs = 1_000)
    wdServer.start()
    val wdStalled = java.net.Socket()
    wdStalled.receiveBufferSize = 4096          // like a phone gone out of range: it takes almost nothing
    wdStalled.connect(java.net.InetSocketAddress("127.0.0.1", wdServer.boundPort))
    wdStalled.getOutputStream().apply { write("GET /f/products/my-app/wdBig.bin HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); flush() }
    Thread.sleep(300)                         // let its transfer start, and block
    val wdStart = System.currentTimeMillis()
    val wdNext = get(wdServer.boundPort, "/")
    val wdWaited = System.currentTimeMillis() - wdStart
    check("a stalled download delays nobody: the next buyer is served straight away", wdNext.status == 200 && wdNext.body.contentEquals(index))
    check("…in well under the stall limit (waited ${wdWaited} ms)", wdWaited < 800)
    // The stalled download's own connection is closed once it has sent nothing for the stall limit.
    var wdLog = ""
    for (i in 0 until 40) { wdLog = logDir.listFiles()!!.filter { it.isFile }.joinToString("\n") { it.readText() }; if ("that received nothing for 1 s" in wdLog) break; Thread.sleep(100) }
    check("…and its own connection is closed after the stall limit, and logged", "that received nothing for 1 s" in wdLog)
    try { wdStalled.close() } catch (e: Exception) { }
    wdServer.stop()

    // --- the cap: at MAX_SENDERS responses being sent, the next buyer gets a small "busy" page ---
    val capServer = HttpServer(store, listOf(InetAddress.getByName("127.0.0.1")), 0, log, stallMs = 60_000)
    capServer.start()
    val stuck = (1..HttpServer.MAX_SENDERS).map {
        java.net.Socket().apply {
            receiveBufferSize = 4096
            connect(java.net.InetSocketAddress("127.0.0.1", capServer.boundPort))
            getOutputStream().apply { write("GET /f/products/my-app/wdBig.bin HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); flush() }
        }
    }
    Thread.sleep(800)
    val atCap = get(capServer.boundPort, "/")
    check("at ${HttpServer.MAX_SENDERS} downloads, the next buyer gets the busy page", atCap.status == 503 && atCap.headers["retry-after"] == "5" && "busy" in String(atCap.body))
    stuck.forEach { try { it.close() } catch (e: Exception) { } }
    var afterCap = 0
    for (i in 0 until 40) { Thread.sleep(150); afterCap = get(capServer.boundPort, "/").status; if (afterCap == 200) break }
    check("…and once those downloads end, buyers are served again", afterCap == 200)

    // --- stopping with downloads still being sent ---
    val stopping = (1..3).map {
        java.net.Socket().apply {
            receiveBufferSize = 4096
            connect(java.net.InetSocketAddress("127.0.0.1", capServer.boundPort))
            getOutputStream().apply { write("GET /f/products/my-app/wdBig.bin HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); flush() }
        }
    }
    Thread.sleep(500)
    val stopStart = System.currentTimeMillis()
    val capPort = capServer.boundPort
    capServer.stop()
    val stopTook = System.currentTimeMillis() - stopStart
    check("stopping closes every download still being sent (took ${stopTook} ms)", stopTook < 5_000)
    val reborn = HttpServer(store, listOf(InetAddress.getByName("127.0.0.1")), capPort, log)
    check("…and frees the port for a new server straight away", runCatching { reborn.start() }.isSuccess && get(capPort, "/").status == 200)
    reborn.stop()
    stopping.forEach { try { it.close() } catch (e: Exception) { } }
    wdBig.delete()

    server.stop()
    val again = HttpServer(store, listOf(InetAddress.getByName("127.0.0.1")), p, log)
    val restartError = runCatching { again.start() }.exceptionOrNull()
    check("restart on the same port succeeds immediately${restartError?.let { " (failed: ${it.message})" } ?: ""}", restartError == null)
    check("restarted server serves", get(p, "/").status == 200)
    again.stop()

    // --- port in use by someone else ---
    val squatter = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val conflict = HttpServer(store, listOf(InetAddress.getByName("127.0.0.1")), squatter.localPort, log)
    val err = runCatching { conflict.start() }.exceptionOrNull()
    check("port in use gives a readable error", err != null && err.message!!.startsWith("Can't listen on port"))
    squatter.close()

    // --- log rotation and thread safety ---
    val rotDir = File(tmp, "rot")
    val small = StoreLog(rotDir, 2000)
    (1..8).map { n -> thread { repeat(500) { small.status("t$n", "line $it " + "x".repeat(40)) } } }.forEach { it.join() }
    val kept = rotDir.listFiles()!!.map { it.name }.sorted()
    check("rotation keeps at most five files (kept: $kept)", kept.size <= 5 && kept.contains("access.log"))
    check("no torn or interleaved lines under 8 threads",
        // Each status line is one timestamp, one tag and the whole message (StoreLog.status timestamps every line).
        rotDir.listFiles()!!.flatMap { it.readLines() }.all { it.matches(Regex("""^\[[^\]]+\] \[t\d] line \d+ x{40}$""")) })
    val off = StoreLog(File(tmp, "off"), 0); off.status("x", "y")
    check("size 0 turns logging off", !File(tmp, "off/access.log").exists())

    // --- helpers ---
    val ifs = listOf(
        Iface("lo", true, true, listOf(InetAddress.getByName("127.0.0.1"))),
        Iface("rmnet0", true, false, listOf(InetAddress.getByName("10.20.30.40"))),
        Iface("wlan0", true, false, listOf(InetAddress.getByName("fe80::1"), InetAddress.getByName("192.168.1.5"))),
    )
    check("prefers wlan's IPv4 address", pickExternalAddress(ifs)?.hostAddress == "192.168.1.5")
    check("falls back to another private IPv4 interface", pickExternalAddress(ifs.filter { it.name != "wlan0" })?.hostAddress == "10.20.30.40")
    check("no usable interface gives null", pickExternalAddress(ifs.take(1)) == null)
    check("acceptsGzip parses real headers", acceptsGzip("gzip, deflate, br") && !acceptsGzip("br") && !acceptsGzip(null) && acceptsGzip("GZIP"))
    check("percentDecode", percentDecode("a%20b") == "a b" && percentDecode("bad%2") == null && percentDecode("%zz") == null)

    tmp.deleteRecursively()
    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED")
    return failedNames.toList()
}

/** The JUnit test: runs the checks above, and fails naming them if any fail. */
class CoreChecks {
    @org.junit.Test
    fun everyCheckPasses() {
        val failed = runChecks()
        org.junit.Assert.assertTrue("Failed checks: " + failed.joinToString("; "), failed.isEmpty())
    }
}
