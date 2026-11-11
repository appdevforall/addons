package org.appdevforall.shareyourapps.server

import java.util.Base64

import org.json.JSONObject

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.io.FilterOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Who may see the store. [pin] null means no PIN. A request is authenticated by a valid `ls_auth`
 * cookie, or by a `?token=` equal to [token] (the plugin's verify step).
 *
 * The cookie is the time it was issued and an HMAC of that time made with [cookieSecret], so the
 * server itself rejects it after 24 hours — whatever a browser keeps — without a session list. It
 * still survives restarts and PIN changes, since the secret doesn't change.
 */
class Auth(
    val pin: String?, val cookieSecret: String, val token: String?,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    companion object {
        val NONE = Auth(null, "", null)
        const val LIFETIME_S = 86_400L
    }

    /** A fresh cookie value: `<issued, in seconds>.<signature>`. */
    fun issueCookie(): String { val t = now(); return "$t.${sign(t)}" }

    fun cookieValid(value: String): Boolean {
        if (cookieSecret.isEmpty()) return false
        val t = value.substringBefore('.', "").toLongOrNull() ?: return false
        val age = now() - t
        if (age < -60 || age >= LIFETIME_S) return false   // a minute's grace for clock steps
        return MessageDigest.isEqual(value.substringAfter('.', "").toByteArray(), sign(t).toByteArray())
    }

    private fun sign(t: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(cookieSecret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(t.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Serves one Store directory. **One main thread decides every response** — it reads the request,
 * checks the PIN, cookie and token, routes, and chooses the file — so none of that ever runs
 * concurrently. **Sending is left to a sender thread per response,** which writes the response,
 * streams any file, writes the log line and closes the connection, so a buyer who stops
 * receiving — a paused video, or a phone gone out of range — holds up nobody else.
 *
 * A sender shares only its last-progress time, which it writes and the main thread reads, and
 * the log, whose writes are synchronized. The main thread alone owns the list of senders: it
 * forgets finished ones and closes the connection of any that has sent nothing for [stallMs],
 * and it also runs [stop]'s shutdown. At [MAX_SENDERS], new requests get a small "busy" page.
 * Every response closes its connection, and each request's line and headers must arrive within
 * [READ_TIMEOUT_MS], so an idle browser preconnect can't hold the main thread.
 */
class HttpServer(
    private val root: File,
    private val addresses: List<InetAddress>,
    private val port: Int,
    private val log: StoreLog,
    private val auth: Auth = Auth.NONE,
    private val storeName: String = "Share Your Apps",
    private val stallMs: Long = STALL_MS,
) {
    companion object {
        const val READ_TIMEOUT_MS = 2000
        /** A whole request must arrive within this long of the main thread taking it. */
        const val REQUEST_DEADLINE_MS = 5000L
        /** A stalled sender is closed after this long: it holds up nobody, so this only frees resources. */
        const val STALL_MS = 120_000L
        /** At most this many responses being sent at once; beyond it, buyers get a "busy" page. */
        const val MAX_SENDERS = 16
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_FORM_BYTES = 1024
        const val COOKIE = "ls_auth"
        const val COOKIE_MAX_AGE = Auth.LIFETIME_S
        private val STATUS = mapOf(
            200 to "OK", 206 to "Partial Content", 302 to "Found", 304 to "Not Modified", 400 to "Bad Request", 403 to "Forbidden",
            404 to "Not Found", 405 to "Method Not Allowed", 409 to "Conflict", 410 to "Gone", 426 to "Upgrade Required", 416 to "Range Not Satisfiable", 503 to "Service Unavailable",
        )
    }

    private val files = StoreFiles(root)
    private val sockets = mutableListOf<ServerSocket>()
    /** New connections, and [Stop], for the main thread. */
    private val queue = LinkedBlockingQueue<Any>()
    private object Stop
    private val threads = mutableListOf<Thread>()
    @Volatile private var running = false
    /** False once [stopAccepting] has run: the main thread then closes any connection it's handed. */
    @Volatile private var accepting = true
    /** The share the owner is showing, if any (design, Appendix E): set by the owner's screen, read by the main thread. */
    @Volatile var share: ShareHost? = null

    /** The responses being sent. The main thread's alone: nothing else reads or changes it. */
    private val senders = mutableListOf<Sender>()
    /** How many responses are being sent, as of the main thread's last tidy-up; for [stopAccepting]'s caller. */
    @Volatile var sending = 0
        private set

    /** The port actually bound (useful when [port] is 0). */
    var boundPort: Int = port
        private set

    /** Binds every address. Throws IOException with a readable message if any bind fails. */
    fun start() {
        var p = port
        try {
            for (a in addresses) {
                val s = ServerSocket()
                sockets += s
                s.reuseAddress = true
                s.bind(InetSocketAddress(a, p), 50)
                if (p == 0) p = s.localPort
            }
        } catch (e: IOException) {
            stop()
            throw IOException("Can't listen on port $port: ${e.message}", e)
        }
        boundPort = p
        running = true
        for (s in sockets) {
            threads += thread(name = "accept-${s.inetAddress.hostAddress}", isDaemon = true) { acceptLoop(s) }
        }
        threads += thread(name = "main", isDaemon = true) { mainLoop() }
    }

    /**
     * Stops taking new connections, but lets responses already being sent finish (design, Appendix
     * E.5: a copy that expires lets downloads under way finish). Watch [sending] fall to 0, then [stop].
     */
    fun stopAccepting() {
        accepting = false
        running = false
        for (s in sockets) try { s.close() } catch (e: IOException) { }
    }

    /**
     * Stops listening, and asks the main thread to close every response being sent — it owns the
     * senders, so it does that itself — then waits, so the port is truly free when this returns:
     * on some platforms, Android's among them, a socket a thread is blocked in accept() on isn't
     * released until that thread returns.
     */
    fun stop() {
        running = false
        for (s in sockets) try { s.close() } catch (e: IOException) { }
        sockets.clear()
        queue.put(Stop)
        threads.forEach { try { it.join(3_000) } catch (e: InterruptedException) { } }
        threads.clear()
        while (true) {
            val c = queue.poll() ?: break
            if (c is Socket) try { c.close() } catch (e: IOException) { }
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            try {
                val c = s.accept()
                // On Android, a closed socket keeps listening until a blocked accept() returns, so one
                // more connection can arrive after stopAccepting: close it, and end, releasing the port.
                if (!running) { try { c.close() } catch (e: IOException) { }; return }
                queue.put(c)
            } catch (e: IOException) {
                if (!running || s.isClosed) return
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /** Takes each connection and decides its response, then hands it to a sender; tidies up at least once a second. */
    private fun mainLoop() {
        val tick = (stallMs / 4).coerceIn(50, 1000)
        while (true) {
            val item = try { queue.poll(tick, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { Stop }
            reap()
            if (item === Stop) break
            if (item is Socket) take(item)
        }
        // Stopping: close every response still being sent, and wait for its sender.
        for (s in senders) try { s.x.socket.close() } catch (e: IOException) { }
        for (s in senders) try { s.join(1_000) } catch (e: InterruptedException) { }
        senders.clear()
    }

    private fun take(c: Socket) {
        // However a connection got here after stopAccepting — queued just before, or accepted at the
        // last moment while Android was still closing the socket — it isn't served.
        if (!accepting) { try { c.close() } catch (e: IOException) { }; return }
        val x = Exchange(c, c.inetAddress?.hostAddress ?: "-")
        try { handle(x) } catch (e: Exception) { x.status = 0 }   // one bad request must never stop the server
        if (x.status == 0) { try { c.close() } catch (e: IOException) { }; return }
        if (senders.size >= MAX_SENDERS) return busy(x)
        val sender = Sender(x)
        senders += sender
        sender.start()
    }

    /**
     * At [MAX_SENDERS]: a small "busy" page instead, written here — it's small enough that
     * writing it can't block — so the main thread never waits for a sender to finish.
     */
    private fun busy(x: Exchange) {
        val req = x.req ?: return
        val reply = Exchange(x.socket, x.client).also { it.req = req }
        respondText(reply, req, 503, "The store is busy. Try again in a moment.", mapOf("Retry-After" to "5"))
        try {
            x.socket.getOutputStream().apply { write(reply.out.toByteArray()); flush() }
            log.access(x.client, req.line, 503, reply.inlineBytes, req.headers["referer"], req.headers["user-agent"], 0)
        } catch (e: IOException) {
        } finally {
            try { x.socket.close() } catch (e: IOException) { }
        }
    }

    /** Forgets finished senders, and closes the connection of any that has sent nothing for [stallMs]. */
    private fun reap() {
        senders.removeAll { !it.isAlive }
        sending = senders.size
        val now = System.currentTimeMillis()
        for (s in senders) {
            if (s.closing || now - s.lastProgress <= stallMs) continue
            s.closing = true
            log.status("server", "closed a connection from ${s.x.client} that received nothing for ${stallMs / 1000} s")
            try { s.x.socket.close() } catch (e: IOException) { }   // its blocked write then fails, and the sender ends
        }
    }

    /** A socket's input, for reading a request: each read waits no longer than what's left of [deadline]. */
    private class Deadline(private val socket: Socket, private val deadline: Long) : InputStream() {
        private val input = socket.getInputStream()
        private fun arm() {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw SocketTimeoutException("the request took too long to arrive")
            socket.soTimeout = minOf(READ_TIMEOUT_MS.toLong(), left).toInt().coerceAtLeast(1)
        }
        override fun read(): Int { arm(); return input.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { arm(); return input.read(b, off, len) }
    }

    /** One request's response, as the main thread decided it, for a sender to send. */
    private class Exchange(val socket: Socket, val client: String) {
        var req: Request? = null
        /** The head, and any small body. */
        val out = ByteArrayOutputStream()
        /** Content made as it's sent, such as an encrypted copy of the store; it writes to what it's given, which it mustn't close. */
        var producer: ((OutputStream) -> Unit)? = null
        /** A file to stream after [out], for a GET of a file: [fileCount] bytes from [fileOffset]. */
        var file: File? = null
        var fileOffset = 0L
        var fileCount = 0L
        /** For the log line; 0 means there's no response to send. */
        var status = 0
        var inlineBytes = 0L
    }

    /** Sends one response, streams its file, writes its log line and closes its connection. */
    private inner class Sender(val x: Exchange) : Thread("sender") {
        /** When it last handed anything to the network; the only thing it shares, read by the main thread. */
        @Volatile var lastProgress = System.currentTimeMillis()
        /** Set by the main thread only, once it has closed this sender's connection. */
        var closing = false
        /** Bytes handed to the network, headers included: the sender's own, for its log line. */
        var handedOver = 0L

        init { isDaemon = true }

        override fun run() {
            val started = System.currentTimeMillis()
            try {
                val out = BufferedOutputStream(Progress(x.socket.getOutputStream(), this), 64 * 1024)
                out.write(x.out.toByteArray())
                x.producer?.let { produce -> produce(object : FilterOutputStream(out) {
                    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
                    override fun close() { flush() }   // the sender closes the connection itself
                }) }
                x.file?.let { f ->
                    FileInputStream(f).use { input ->
                        input.channel.position(x.fileOffset)   // a range starts partway through
                        val buf = ByteArray(64 * 1024)
                        var left = x.fileCount
                        while (left > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) break
                            out.write(buf, 0, n); left -= n
                        }
                    }
                }
                out.flush()
            } catch (e: Exception) {
                // The buyer went away, or the main thread closed a stalled connection.
            } finally {
                // Logged even when the send failed partway, with the body bytes actually handed to
                // the network — so the log shows a download a buyer stopped, as a size smaller than the file's.
                val req = x.req
                val headLength = x.out.size() - x.inlineBytes
                val body = (handedOver - headLength).coerceAtLeast(0)
                if (req != null) log.access(x.client, req.line, x.status, body, req.headers["referer"],
                    req.headers["user-agent"], if (x.file != null || x.producer != null) System.currentTimeMillis() - started else 0)
                try { x.socket.close() } catch (e: IOException) { }
            }
        }
    }

    /**
     * Notes each chunk actually handed to the network: its time, for the main thread's check on
     * stalled senders, and its size, for the log. Writes go out in 8 KB slices, so a send that
     * fails partway is counted to within 8 KB.
     */
    private class Progress(out: OutputStream, private val sender: Sender) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) {
            var i = off
            while (i < off + len) {
                val n = minOf(8 * 1024, off + len - i)
                out.write(b, i, n); i += n
                sender.handedOver += n
                sender.lastProgress = System.currentTimeMillis()
            }
        }
        override fun write(b: Int) { out.write(b); sender.handedOver++; sender.lastProgress = System.currentTimeMillis() }
    }

    private class Request(
        val method: String, val target: String, val line: String,
        val headers: Map<String, String>,
    ) {
        val path: String get() = target.substringBefore('?')
        fun query(name: String): String? = target.substringAfter('?', "").split('&')
            .firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        fun cookie(name: String): String? = headers["cookie"]?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")
    }

    /** Reads the request and decides its response, into [x]; leaves x.status at 0 when there's nothing to send. */
    private fun handle(x: Exchange) {
        x.socket.soTimeout = READ_TIMEOUT_MS
        // The whole request — headers, and any body — must arrive within REQUEST_DEADLINE_MS, not just
        // each read within READ_TIMEOUT_MS: a client sending a byte a second can't hold the main thread.
        val input = BufferedInputStream(Deadline(x.socket, System.currentTimeMillis() + REQUEST_DEADLINE_MS))

        val req = try {
            readRequest(input)
        } catch (e: SocketTimeoutException) {
            return // an idle connection, such as a browser preconnect: close quietly
        } ?: return
        x.req = req

        if (!files.hasIndex()) {
            return respondText(x, req, 503, "This store hasn't been set up yet.")
        }

        val path = req.path
        // Sellers aren't buyers, so the sharing address comes before the PIN.
        if (path.startsWith("/share/")) return shareRoute(input, x, req)
        if (path == "/pin") return pinRoute(input, x, req)
        if (req.method != "GET" && req.method != "HEAD") {
            return respondText(x, req, 405, "Method not allowed.", mapOf("Allow" to "GET, HEAD"))
        }
        if (auth.pin != null && !authenticated(req)) return redirect(x, req, "/pin")

        val relative = when {
            path == "/" || path == "/index.html" -> "index.html"
            path.startsWith("/f/") -> percentDecode(path.removePrefix("/f/"))
                ?: return respondText(x, req, 400, "Bad request.")
            else -> return respondText(x, req, 404, "Not found.")
        }
        val file = files.resolve(relative)
        if (file != null) return serveFile(x, req, file, relative)
        // A source zip the compressor has replaced by its .gz copy, to save space.
        val gzOnly = if (relative.endsWith(".zip")) files.resolve("$relative.gz") else null
        if (gzOnly != null) return serveFile(x, req, null, relative, gzOnly)
        respondText(x, req, 404, "Not found.")
    }

    /**
     * The sharing address (design, Appendix E.3): request, reveal, status and copy. Only while the
     * owner's screen has a share; otherwise every path answers not found, as if it didn't exist.
     */
    private fun shareRoute(input: InputStream, x: Exchange, req: Request) {
        // While the owner's screen has a share, open or not, so a seller can still hear "denied" or
        // "expired"; each route refuses what it should. Leaving the sharing screen removes the share.
        val host = share ?: return respondText(x, req, 404, "Not found.")
        val route = req.path.removePrefix("/share/")
        // A phone that doesn't have the app yet gets it here: the genuine APK, signed with the server's key.
        if (route == "app") return shareApp(x, req, host)
        val post = route == "request" || route == "reveal"
        if (post && req.method != "POST") return respondText(x, req, 405, "Method not allowed.", mapOf("Allow" to "POST"))
        if (!post && req.method != "GET") return respondText(x, req, 405, "Method not allowed.", mapOf("Allow" to "GET"))
        try {
            when (route) {
                "request" -> {
                    val body = readJson(input, req) ?: return respondJson(x, req, 400, JSONObject().put("error", "Bad request."))
                    val (id, ownerKey) = host.request(body.optString("code"), body.optString("name"),
                        Base64.getDecoder().decode(body.optString("commitment")), body.optInt("protocol", 1))
                    log.status("share", "${host.phoneName()} asked for a copy of ${host.storeName}")
                    respondJson(x, req, 200, JSONObject().put("request", id).put("ownerKey", Base64.getEncoder().encodeToString(ownerKey))
                        .put("protocol", ShareHost.PROTOCOL))
                }
                "reveal" -> {
                    val body = readJson(input, req) ?: return respondJson(x, req, 400, JSONObject().put("error", "Bad request."))
                    host.reveal(body.optString("request"), Base64.getDecoder().decode(body.optString("sellerKey")))
                    respondJson(x, req, 200, JSONObject().put("ok", true))
                }
                "status" -> {
                    val state = host.status(req.query("request").orEmpty())
                    val reply = JSONObject().put("state", state).put("storeName", host.storeName)
                    if (state == "allowed") reply.put("expires", host.copyExpiry()?.toString() ?: JSONObject.NULL)
                    respondJson(x, req, 200, reply)
                }
                "copy" -> {
                    val copy = host.copyFor(req.query("request").orEmpty())
                    writeHead(x.out, 200, linkedMapOf("Content-Type" to "application/octet-stream", "Cache-Control" to "no-store"))
                    x.status = 200
                    // Encrypted as it's written, so the copy is never stored on this phone.
                    x.producer = { out ->
                        try {
                            SealedOutputStream(out, copy.key).use { SharePackage.write(it, copy.storeDir, copy.description, copy.storeKey) }
                        } catch (e: Exception) { host.transferFailed(); throw e }
                        host.sent()
                        log.status("share", "sent a copy of ${host.storeName} to ${host.phoneName()}")
                    }
                }
                else -> respondText(x, req, 404, "Not found.")
            }
        } catch (e: ShareHost.Refused) {
            respondJson(x, req, e.refusal.status, JSONObject().put("error", e.refusal.message))
        } catch (e: IllegalArgumentException) {   // bad base64
            respondJson(x, req, 400, JSONObject().put("error", "Bad request."))
        }
    }

    private fun shareApp(x: Exchange, req: Request, host: ShareHost) {
        if (req.method != "GET" && req.method != "HEAD") return respondText(x, req, 405, "Method not allowed.", mapOf("Allow" to "GET"))
        val apk = host.appApk?.takeIf { it.isFile } ?: return respondText(x, req, 404, "Not found.")
        writeHead(x.out, 200, linkedMapOf(
            "Content-Type" to "application/vnd.android.package-archive",
            "Content-Length" to apk.length().toString(),
            "Content-Disposition" to "attachment; filename=\"share-your-apps-server.apk\"",
            "Cache-Control" to "no-store",
        ))
        if (req.method == "GET") { x.file = apk; x.fileOffset = 0; x.fileCount = apk.length() }
        x.status = 200
    }

    private fun readJson(input: InputStream, req: Request): JSONObject? {
        val length = req.headers["content-length"]?.toIntOrNull() ?: return null
        if (length < 0 || length > MAX_FORM_BYTES) return null
        val body = ByteArray(length); var read = 0
        while (read < length) { val n = input.read(body, read, length - read); if (n < 0) return null; read += n }
        return try { JSONObject(String(body, Charsets.UTF_8)) } catch (e: Exception) { null }
    }

    private fun respondJson(x: Exchange, req: Request, status: Int, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        writeHead(x.out, status, linkedMapOf("Content-Type" to "application/json", "Content-Length" to bytes.size.toString(), "Cache-Control" to "no-store"))
        if (req.method != "HEAD") x.out.write(bytes)
        x.status = status
        x.inlineBytes = if (req.method == "HEAD") 0 else bytes.size.toLong()
    }

    private fun authenticated(req: Request): Boolean {
        val cookie = req.cookie(COOKIE)
        if (cookie != null && auth.cookieValid(cookie)) return true
        val token = req.query("token")
        return token != null && auth.token != null && same(token, auth.token)
    }

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /** GET shows the PIN page (or redirects home if there's nothing to enter); POST checks the PIN. */
    private fun pinRoute(input: InputStream, x: Exchange, req: Request) {
        val pin = auth.pin
        if (pin == null || authenticated(req)) return redirect(x, req, "/")
        when (req.method) {
            "GET", "HEAD" -> respondHtml(x, req, pinPage(null))
            "POST" -> {
                val entered = readForm(input, req)?.get("pin")
                    ?: return respondText(x, req, 400, "Bad request.")
                if (entered.isNotEmpty() && same(entered, pin)) {
                    redirect(x, req, "/", mapOf("Set-Cookie" to
                        "$COOKIE=${auth.issueCookie()}; Max-Age=$COOKIE_MAX_AGE; Path=/; HttpOnly; SameSite=Lax"))
                } else {
                    respondHtml(x, req, pinPage("That PIN isn't right."))
                }
            }
            else -> respondText(x, req, 405, "Method not allowed.", mapOf("Allow" to "GET, POST"))
        }
    }

    private fun readForm(input: InputStream, req: Request): Map<String, String>? {
        val length = req.headers["content-length"]?.toIntOrNull() ?: return null
        if (length < 0 || length > MAX_FORM_BYTES) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        return String(body, Charsets.UTF_8).split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            k to (runCatching { URLDecoder.decode(v, "UTF-8") }.getOrNull() ?: "")
        }
    }

    private fun pinPage(error: String?): String {
        val name = htmlEscape(storeName)
        val err = if (error != null) "<p class=er>${htmlEscape(error)}</p>" else ""
        return Brand.page(name, "<h1>$name</h1>$err" +
            "<form method=post action=/pin><label>PIN<input name=pin type=password inputmode=numeric " +
            "pattern=\"[0-9]*\" maxlength=6 autocomplete=off autofocus required></label>" +
            "<button>Enter</button></form>")
    }

    private fun redirect(x: Exchange, req: Request, to: String, extra: Map<String, String> = emptyMap()) {
        writeHead(x.out, 302, linkedMapOf("Location" to to, "Content-Length" to "0", "Cache-Control" to "no-store") + extra)
        x.status = 302
    }

    private fun respondHtml(x: Exchange, req: Request, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        writeHead(x.out, 200, linkedMapOf(
            "Content-Type" to "text/html; charset=utf-8",
            "Content-Length" to bytes.size.toString(),
            "Cache-Control" to "no-store",
        ))
        if (req.method != "HEAD") x.out.write(bytes)
        x.status = 200
        x.inlineBytes = bytes.size.toLong()
    }

    /**
     * Sends [file], or its `.gz` copy if the client accepts gzip and one exists. Or, with [file] null, a source zip
     * kept only as its copy, [gzOnly]: the copy as-is to a client that accepts gzip, and otherwise the zip's own bytes,
     * decompressed on the fly — whole, or a range, skipped to. Its size, date and ETag are the zip's, exactly as they
     * were before it was deleted, since the copy has its date, and gzip records its size.
     */
    private fun serveFile(x: Exchange, req: Request, file: File?, relative: String, gzOnly: File? = null) {
        val name = relative.substringAfterLast('/')
        val length = file?.length() ?: StoreFiles.gzipSize(gzOnly!!)
        // Each publish copies its files afresh, so a file's time changes whenever its content can have.
        val modified = (file ?: gzOnly!!).lastModified() / 1000 * 1000
        // 304 only for this very file of this very store. On Wi-Fi Direct every store is at the same
        // address, so a browser's cached page may be another store's: an exact date match, or an
        // ETag naming the store, rather than "not modified since", keeps one from confirming another.
        val etag = "W/\"${root.name}-$modified-$length\""
        val inm = req.headers["if-none-match"]
        val since = req.headers["if-modified-since"]?.let(::parseHttpDate)
        val unchanged = if (inm != null) inm.split(',').map { it.trim().removePrefix("W/") }.any { it == "*" || it == etag.removePrefix("W/") }
            else since != null && modified == since
        if (unchanged) {
            writeHead(x.out, 304, linkedMapOf("ETag" to etag, "Last-Modified" to httpDate(modified), "Vary" to "Accept-Encoding", "Cache-Control" to "no-cache"))
            x.status = 304
            return
        }
        // A range request — skipping in a video, or resuming a download — gets just that slice of
        // the plain file, never of its gzip copy, whose byte positions differ. If-Range with an old
        // date means the browser's copy is stale, so it gets the whole, new file instead.
        val range = req.headers["range"]?.takeIf { ifRangeHolds(req.headers["if-range"], modified) }?.let { parseRange(it, length) }
        if (range == ByteRange.UNSATISFIABLE) {
            writeHead(x.out, 416, linkedMapOf("Content-Range" to "bytes */$length", "Content-Length" to "0", "Accept-Ranges" to "bytes"))
            x.status = 416
            return
        }
        val gz = if (range == null && acceptsGzip(req.headers["accept-encoding"])) (gzOnly ?: files.resolve("$relative.gz")) else null
        val body = gz ?: file   // null: decompressed from gzOnly, below
        val offset = range?.start ?: 0L
        val count = range?.let { it.end - it.start + 1 } ?: (body?.length() ?: length)
        val headers = linkedMapOf(
            "Content-Type" to StoreFiles.contentType(name),
            "Content-Length" to count.toString(),
            "Accept-Ranges" to "bytes",
            "Vary" to "Accept-Encoding",
            // Every publish reuses the same addresses — "/" and each product's files — for new
            // content, so browsers must check back each time rather than reuse an old copy.
            "Cache-Control" to "no-cache",
            "Last-Modified" to httpDate(modified),
            "ETag" to etag,
        )
        if (range != null) headers["Content-Range"] = "bytes ${range.start}-${range.end}/$length"
        if (gz != null) headers["Content-Encoding"] = "gzip"
        StoreFiles.disposition(name)?.let { headers["Content-Disposition"] = it }

        val status = if (range != null) 206 else 200
        writeHead(x.out, status, headers)
        if (req.method == "GET") {
            if (body != null) { x.file = body; x.fileOffset = offset; x.fileCount = count }   // the sender streams it
            else x.producer = { out -> StoreFiles.gunzipSlice(gzOnly!!, offset, count, out) }   // the zip, decompressed
        }
        x.status = status
    }

    private fun respondText(
        x: Exchange, req: Request, status: Int, message: String,
        extra: Map<String, String> = emptyMap(),
    ) {
        // The same look as the PIN page: every page a buyer can reach is themed.
        val bytes = Brand.page(htmlEscape(storeName).ifEmpty { "Share Your Apps" }, "<p>$message</p>").toByteArray(Charsets.UTF_8)
        writeHead(x.out, status, linkedMapOf(
            "Content-Type" to "text/html; charset=utf-8",
            "Content-Length" to bytes.size.toString(),
            "Cache-Control" to "no-store",
        ) + extra)
        if (req.method != "HEAD") x.out.write(bytes)
        x.status = status
        x.inlineBytes = if (req.method == "HEAD") 0 else bytes.size.toLong()
    }

    private fun writeHead(out: OutputStream, status: Int, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $status ${STATUS[status] ?: ""}\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    /** Reads the request line and headers. Returns null if the request is malformed or too big. */
    private fun readRequest(input: InputStream): Request? {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0d0a0d0a || (last4 and 0xffff) == 0x0a0a) break
            if (buf.size() > MAX_HEADER_BYTES) return null
        }
        val lines = buf.toString("ISO-8859-1").split("\n").map { it.trimEnd('\r') }
        val requestLine = lines.firstOrNull() ?: return null
        val parts = requestLine.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) return null
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        // The verify token is never written to disk, so it's blanked in the line that gets logged.
        val logged = requestLine.replace(Regex("""([?&]token=)[^&\s]*"""), "\$1-")
        return Request(parts[0], parts[1], logged, headers)
    }
}

/** An HTTP date, such as "Thu, 24 Sep 2026 10:00:00 GMT". */
/** A single byte range, inclusive; or [UNSATISFIABLE], for a range that starts past the end. */
data class ByteRange(val start: Long, val end: Long) {
    companion object { val UNSATISFIABLE = ByteRange(-1, -1) }
}

/**
 * A Range header, for a file of [length] bytes, following RFC 9110: a single `bytes=a-b`, `bytes=a-`
 * or `bytes=-n`. Several ranges, another unit, or anything malformed returns null, meaning the
 * header is ignored and the whole file sent, which the standard allows. A range that starts at or
 * past the end is [ByteRange.UNSATISFIABLE]; one that runs past the end is cut short.
 */
fun parseRange(header: String, length: Long): ByteRange? {
    val h = header.trim()
    if (!h.startsWith("bytes=", ignoreCase = true)) return null
    val spec = h.substring(6).trim()
    if (',' in spec) return null
    val dash = spec.indexOf('-').takeIf { it >= 0 } ?: return null
    val a = spec.substring(0, dash).trim(); val b = spec.substring(dash + 1).trim()
    if (!a.all(Char::isDigit) || !b.all(Char::isDigit)) return null
    return when {
        a.isEmpty() && b.isEmpty() -> null
        a.isEmpty() -> {   // the last n bytes
            val n = b.toLongOrNull() ?: return null
            if (n == 0L || length == 0L) ByteRange.UNSATISFIABLE else ByteRange(maxOf(0, length - n), length - 1)
        }
        else -> {
            val start = a.toLongOrNull() ?: return null
            val end = if (b.isEmpty()) length - 1 else (b.toLongOrNull() ?: return null)
            when {
                b.isNotEmpty() && end < start -> null
                start >= length -> ByteRange.UNSATISFIABLE
                else -> ByteRange(start, minOf(end, length - 1))
            }
        }
    }
}

/** Whether a range request's If-Range still matches the file: absent, or the file's own date. We send no ETags. */
fun ifRangeHolds(ifRange: String?, modified: Long): Boolean = ifRange == null || parseHttpDate(ifRange.trim()) == modified

fun httpDate(millis: Long): String =
    DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC))

/** The time an HTTP date names, in milliseconds, or null if it isn't one. */
fun parseHttpDate(s: String): Long? =
    try { ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() } catch (e: Exception) { null }

/** True if an Accept-Encoding header accepts gzip (q=0 means refused). */
fun acceptsGzip(header: String?): Boolean {
    if (header == null) return false
    return header.split(',').any { part ->
        val bits = part.split(';').map { it.trim() }
        val coding = bits[0].lowercase()
        val q = bits.drop(1).firstOrNull { it.startsWith("q=") }?.substring(2)?.toDoubleOrNull() ?: 1.0
        (coding == "gzip" || coding == "x-gzip") && q > 0.0
    }
}

/** Decodes %XX escapes in a URL path as UTF-8. Returns null on a malformed escape or a NUL. */
fun percentDecode(s: String): String? {
    val out = ByteArrayOutputStream()
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        if (ch == '%') {
            if (i + 2 >= s.length) return null
            val v = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
            out.write(v)
            i += 3
        } else {
            out.write(ch.toString().toByteArray(Charsets.UTF_8))
            i++
        }
    }
    val r = out.toString("UTF-8")
    return if (r.contains('\u0000')) null else r
}

fun htmlEscape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
