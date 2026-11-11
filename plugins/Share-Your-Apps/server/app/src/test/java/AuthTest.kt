package authtest

import org.appdevforall.shareyourapps.server.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.util.zip.GZIPInputStream

var failures = 0
val failedNames = mutableListOf<String>()
fun check(name: String, cond: Boolean) {
    if (!cond) failedNames += name
   
    println((if (cond) "PASS  " else "FAIL  ") + name)
    if (!cond) failures++
}

class Resp(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    val text get() = String(body, Charsets.UTF_8)
}

fun http(port: Int, request: String): Resp {
    Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 10000
        s.getOutputStream().write(request.toByteArray(Charsets.UTF_8)); s.getOutputStream().flush()
        val all = s.getInputStream().readBytes()
        val split = (0 until all.size - 3).first {
            all[it] == 13.toByte() && all[it + 1] == 10.toByte() && all[it + 2] == 13.toByte() && all[it + 3] == 10.toByte()
        }
        val head = String(all, 0, split, Charsets.ISO_8859_1).split("\r\n")
        return Resp(head[0].split(' ')[1].toInt(),
            head.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() },
            all.copyOfRange(split + 4, all.size))
    }
}
fun get(port: Int, path: String, vararg hdr: String) =
    http(port, "GET $path HTTP/1.1\r\nHost: x\r\n" + hdr.joinToString("") { "$it\r\n" } + "\r\n")
fun postPin(port: Int, pin: String, vararg hdr: String): Resp {
    val body = "pin=$pin"
    return http(port, "POST /pin HTTP/1.1\r\nHost: x\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
        "Content-Length: ${body.toByteArray().size}\r\n" + hdr.joinToString("") { "$it\r\n" } + "\r\n$body")
}
fun gunzip(f: File) = GZIPInputStream(f.inputStream()).use { it.readBytes() }

/** Runs every check; returns the names of those that failed. */
fun runChecks(): List<String> {
    failures = 0; failedNames.clear()
    val tmp = Files.createTempDirectory("lss2").toFile()
    val store = File(tmp, "Store_12345678").apply { mkdirs() }
    val index = "<html>".repeat(2000).toByteArray()
    File(store, "index.html").writeBytes(index)
    File(store, "products/a").mkdirs()
    File(store, "products/a/app.apk").writeBytes(ByteArray(5000) { 3 })
    val zip = ByteArray(200_000) { (it % 7).toByte() }
    File(store, "products/a/app.zip").writeBytes(zip)
    File(store, "products/b").mkdirs()
    File(store, "products/b/src.zip").writeBytes(ByteArray(50_000) { (it % 13).toByte() })
    val log = StoreLog(File(tmp, "logs"), 10_000_000)

    // ---------------- PIN ----------------
    val secret = "s3cr3t-abc"
    val token = "tok-987"
    val srv = HttpServer(store, listOf(InetAddress.getByName("127.0.0.1")), 0, log,
        Auth("012345", secret, token), "Anna's <Store>")
    srv.start(); val p = srv.boundPort

    val r = get(p, "/")
    check("unauthenticated catalog request redirects to /pin", r.status == 302 && r.headers["location"] == "/pin")
    check("unauthenticated file request redirects to /pin", get(p, "/f/products/a/app.apk").let { it.status == 302 && it.headers["location"] == "/pin" })
    val page = get(p, "/pin")
    check("PIN page shows the store name, escaped", page.status == 200 && page.text.contains("Anna's &lt;Store&gt;"))
    check("PIN page has a form posting to /pin", page.text.contains("method=post action=/pin"))

    val wrong = postPin(p, "12345")
    check("wrong PIN shows the page again with an error and no cookie",
        wrong.status == 200 && wrong.text.contains("isn't right") && wrong.headers["set-cookie"] == null)
    check("empty PIN is refused", postPin(p, "").headers["set-cookie"] == null)
    val right = postPin(p, "012345")
    val setCookie = right.headers["set-cookie"] ?: ""
    check("right PIN (leading zero kept) redirects to the catalog", right.status == 302 && right.headers["location"] == "/")
    val issued = setCookie.substringAfter("ls_auth=").substringBefore(';')
    check("cookie is an issue time and a signature, 24 h, HttpOnly, Path=/",
        Regex("""\d+\.[0-9a-f]{64}""").matches(issued) && "Max-Age=86400" in setCookie && "HttpOnly" in setCookie && "Path=/" in setCookie)
    check("cookie doesn't reveal the secret", secret !in setCookie)

    val authed = "Cookie: ls_auth=$issued"
    check("cookie opens the catalog", get(p, "/", authed).let { it.status == 200 && it.body.contentEquals(index) })
    check("cookie opens files", get(p, "/f/products/a/app.apk", authed).status == 200)
    check("cookie among other cookies still works", get(p, "/", "Cookie: a=1; ls_auth=$issued; b=2").status == 200)
    check("the old-style cookie, the bare secret, no longer works", get(p, "/", "Cookie: ls_auth=$secret").headers["location"] == "/pin")

    // The cookie's rules, with a controlled clock.
    val t0 = 1_800_000_000L
    val c0 = Auth("1", secret, null, now = { t0 }).issueCookie()
    check("a cookie still works one second short of 24 hours", Auth("1", secret, null, now = { t0 + 86_399 }).cookieValid(c0))
    check("a cookie is refused at 24 hours, whatever the browser keeps", !Auth("1", secret, null, now = { t0 + 86_400 }).cookieValid(c0))
    check("a cookie from another store's secret is refused", !Auth("1", secret, null, now = { t0 }).cookieValid(Auth("1", "another-store", null, now = { t0 }).issueCookie()))
    check("a cookie with its time changed is refused", !Auth("1", secret, null, now = { t0 }).cookieValid((t0 + 5).toString() + "." + c0.substringAfter('.')))
    check("a cookie survives a PIN change, as the secret doesn't change", Auth("9", secret, null, now = { t0 + 10 }).cookieValid(c0))
    check("garbage is refused", listOf("", ".", "abc", "123.", ".abc", "12.zz").none { Auth("1", secret, null, now = { t0 }).cookieValid(it) })
    check("authenticated /pin redirects to the catalog", get(p, "/pin", authed).let { it.status == 302 && it.headers["location"] == "/" })
    check("made-up cookie value is sent to the PIN page", get(p, "/", "Cookie: ls_auth=1").headers["location"] == "/pin")
    check("empty cookie value is sent to the PIN page", get(p, "/", "Cookie: ls_auth=").headers["location"] == "/pin")

    check("token opens the catalog", get(p, "/?token=$token").status == 200)
    check("token opens files", get(p, "/f/products/a/app.apk?token=$token").status == 200)
    check("wrong token is unauthenticated", get(p, "/?token=nope").headers["location"] == "/pin")
    check("empty token is unauthenticated", get(p, "/?token=").headers["location"] == "/pin")
    Thread.sleep(300)
    val logged = File(tmp, "logs").listFiles()!!.filter { it.isFile }.joinToString("\n") { it.readText() }
    check("the token is blanked in the log", token !in logged && "?token=-" in logged)
    check("oversized form is refused, server survives",
        http(p, "POST /pin HTTP/1.1\r\nHost: x\r\nContent-Length: 999999\r\n\r\nx").status == 400 && get(p, "/pin").status == 200)
    check("POST elsewhere is 405", http(p, "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n").status == 405)
    srv.stop()

    // A server with no token configured must not accept an empty or any token.
    val noTok = HttpServer(store, listOf(InetAddress.getByName("127.0.0.1")), 0, log, Auth("1", secret, null))
    noTok.start()
    check("no token configured: ?token= never authenticates", get(noTok.boundPort, "/?token=").headers["location"] == "/pin")
    noTok.stop()

    // No PIN: everything open, /pin redirects home.
    val open = HttpServer(store, listOf(InetAddress.getByName("127.0.0.1")), 0, log, Auth(null, secret, token))
    open.start()
    check("no PIN: catalog opens without a cookie", get(open.boundPort, "/").status == 200)
    check("no PIN: /pin redirects to the catalog", get(open.boundPort, "/pin").headers["location"] == "/")
    open.stop()

    // 503 comes before the PIN.
    val empty = File(tmp, "Store_00000000").apply { mkdirs() }
    val e = HttpServer(empty, listOf(InetAddress.getByName("127.0.0.1")), 0, log, Auth("1", secret, token))
    e.start()
    check("not set up yet: 503 even with a PIN set", get(e.boundPort, "/").status == 503 && get(e.boundPort, "/pin").status == 503)
    e.stop()

    // ---------------- background compression ----------------
    // The accessible page is compressed too; an HTML file inside a product's folder isn't.
    File(store, "accessible.html").writeText("<p>" + "accessible ".repeat(400) + "</p>")
    File(store, "products/a/notes.html").writeText("<p>owner's file</p>")
    val c = Compressor(store, log)
    check("pending lists the top-level pages and every zip, not HTML inside products",
        c.pending() == listOf("accessible.html", "index.html", "products/a/app.zip", "products/b/src.zip"))
    c.start().join()
    check("index.html.gz decompresses to index.html", gunzip(File(store, "index.html.gz")).contentEquals(index))
    check("app.zip.gz decompresses to app.zip", gunzip(File(store, "products/a/app.zip.gz")).contentEquals(zip))
    check("no .gz.tmp left behind", store.walk().none { it.name.endsWith(".gz.tmp") })
    check("APKs are not compressed", !File(store, "products/a/app.apk.gz").exists())
    check("nothing pending after a pass", c.pending().isEmpty())

    val logText = File(tmp, "logs/access.log").readText()
    check("log: started line", logText.contains("[gzip] started: 4 files"))
    // "37 ms" under a second, else "5.2 s" (Compressor.duration).
    check("log: done line with sizes and time", Regex("""\[gzip] done: products/a/app\.zip 200000 -> \d+ bytes in \d+(?: ms|\.\d s)""").containsMatchIn(logText))
    check("log: finished line", logText.contains("[gzip] finished: 4 compressed, 0 not smaller, 0 failed"))

    // Served compressed once it exists.
    val s2 = HttpServer(store, listOf(InetAddress.getByName("127.0.0.1")), 0, log)
    s2.start()
    val gz = get(s2.boundPort, "/f/products/a/app.zip", "Accept-Encoding: gzip")
    check("zip served gzip-encoded, keeping zip type and download name",
        gz.headers["content-encoding"] == "gzip" && gz.headers["content-type"] == "application/zip" &&
            gz.headers["content-disposition"]!!.contains("app.zip\"") && gunzip(File(store, "products/a/app.zip.gz")).contentEquals(zip))
    check("identity client still gets the plain zip", get(s2.boundPort, "/f/products/a/app.zip").body.contentEquals(zip))
    s2.stop()

    // Failure: the temp name is blocked, so writing it fails; the pass carries on.
    val store3 = File(tmp, "Store_33333333").apply { mkdirs() }
    File(store3, "index.html").writeText("<html>".repeat(500))
    File(store3, "bad.zip").writeBytes(ByteArray(100))
    File(store3, "good.zip").writeBytes(ByteArray(100))
    File(store3, "bad.zip.gz.tmp").mkdirs() // a directory where the temp file should go
    Compressor(store3, log).start().join()
    val t3 = File(tmp, "logs/access.log").readText()
    check("a failure is logged with the file and reason", t3.contains("[gzip] failed: bad.zip:"))
    check("the other files still get compressed", File(store3, "good.zip.gz").exists() && File(store3, "index.html.gz").exists())
    check("failed file has no .gz and keeps serving plain", !File(store3, "bad.zip.gz").exists())
    check("finished line counts the failure", t3.contains("[gzip] finished: 2 compressed, 0 not smaller, 1 failed"))

    // A copy that isn't smaller is deleted, never served.
    val store5 = File(tmp, "Store_55555555").apply { mkdirs() }
    File(store5, "index.html").writeText("<html>".repeat(500))
    File(store5, "random.zip").writeBytes(ByteArray(100_000).also { java.util.Random(1).nextBytes(it) })
    File(store5, "tiny.zip").writeBytes(ByteArray(0))
    Compressor(store5, log).start().join()
    val t5 = File(tmp, "logs/access.log").readText()
    check("incompressible zip gets no .gz", !File(store5, "random.zip.gz").exists())
    check("empty zip gets no .gz", !File(store5, "tiny.zip.gz").exists())
    check("its temporary file is gone too", store5.walk().none { it.name.endsWith(".gz.tmp") })
    check("compressible index still gets a .gz", File(store5, "index.html.gz").exists())
    // The time taken sits between the sizes and "deleted": "37 ms" under a second, else "5.2 s" (Compressor.duration).
    check("log: not-smaller line with both sizes", Regex("""\[gzip] not smaller: random\.zip 100000 -> \d+ bytes in \d+(?: ms|\.\d s); deleted""").containsMatchIn(t5))
    check("finished line counts it", t5.contains("[gzip] finished: 1 compressed, 2 not smaller, 0 failed"))
    val s5 = HttpServer(store5, listOf(InetAddress.getByName("127.0.0.1")), 0, log); s5.start()
    check("that zip is served uncompressed to a gzip client",
        get(s5.boundPort, "/f/random.zip", "Accept-Encoding: gzip").headers["content-encoding"] == null)
    s5.stop()

    // Confinement: a symlinked zip outside the store is not compressed.
    val outside = File(tmp, "outside.zip").apply { writeBytes(ByteArray(10)) }
    val store4 = File(tmp, "Store_44444444").apply { mkdirs() }
    File(store4, "index.html").writeText("x")
    Files.createSymbolicLink(File(store4, "link.zip").toPath(), outside.toPath())
    check("symlinked zip outside the store isn't touched", Compressor(store4, log).pending() == listOf("index.html"))

    tmp.deleteRecursively()
    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED")
    return failedNames.toList()
}

/** The JUnit test: runs the checks above, and fails naming them if any fail. */
class AuthChecks {
    @org.junit.Test
    fun everyCheckPasses() {
        val failed = runChecks()
        org.junit.Assert.assertTrue("Failed checks: " + failed.joinToString("; "), failed.isEmpty())
    }
}
