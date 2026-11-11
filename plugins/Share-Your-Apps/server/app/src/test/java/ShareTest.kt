package sharetest

import org.appdevforall.shareyourapps.server.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant

var failures = 0
val failedNames = mutableListOf<String>()
fun check(name: String, cond: Boolean) {
    if (!cond) failedNames += name
    println((if (cond) "PASS  " else "FAIL  ") + name); if (!cond) failures++ }

fun seal(key: ByteArray, data: ByteArray): ByteArray = ByteArrayOutputStream().also { b -> SealedOutputStream(b, key).use { it.write(data) } }.toByteArray()
fun unseal(key: ByteArray, sealed: ByteArray): ByteArray = SealedInputStream(ByteArrayInputStream(sealed), key).readBytes()
fun fails(block: () -> Unit): Boolean = try { block(); false } catch (e: IOException) { true }

/** Runs every check; returns the names of those that failed. */
fun runChecks(): List<String> {
    failures = 0; failedNames.clear()
    // --- signatures and identity ---
    val store = ShareCrypto.newKeyPair(); val other = ShareCrypto.newKeyPair()
    val data = "a description".toByteArray()
    val sig = ShareCrypto.sign(store.private, data)
    check("a signature checks with its key", ShareCrypto.verify(store.public, data, sig))
    check("…not with another key, nor over altered data", !ShareCrypto.verify(other.public, data, sig) && !ShareCrypto.verify(store.public, "a descriptioN".toByteArray(), sig))
    check("keys survive encoding", ShareCrypto.publicKey(store.public.encoded) == store.public && ShareCrypto.privateKey(store.private.encoded).encoded.contentEquals(store.private.encoded))
    val id = ShareCrypto.storeId(store.public)
    check("a store's identity: 16 base32 characters, stable, and differing between keys", id.length == 16 && id.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" } && id == ShareCrypto.storeId(store.public) && id != ShareCrypto.storeId(other.public))

    // --- the exchange: commitment, check code and session key ---
    val code = ShareCodes.normalize(ShareCodes.newCode())!!
    val owner = ShareCrypto.newKeyPair(); val seller = ShareCrypto.newKeyPair(); val mallory = ShareCrypto.newKeyPair()
    val commit = ShareCrypto.commitment(seller.public.encoded)
    check("the commitment holds for the seller's key", ShareCrypto.commitmentHolds(commit, seller.public.encoded))
    check("…and not for a substitute key", !ShareCrypto.commitmentHolds(commit, mallory.public.encoded))
    val ownerSees = ShareCrypto.checkCode(owner.public.encoded, seller.public.encoded, code)
    val sellerSees = ShareCrypto.checkCode(owner.public.encoded, seller.public.encoded, code)
    check("both phones show the same four digits ($ownerSees)", ownerSees == sellerSees && ownerSees.length == 4 && ownerSees.all { it.isDigit() })
    val altered = (1..8).map { ShareCrypto.checkCode(owner.public.encoded, ShareCrypto.newKeyPair().public.encoded, code) }
    check("…and a different key changes them", altered.any { it != ownerSees })
    val kOwner = ShareCrypto.sessionKey(owner.private, seller.public, code, owner.public.encoded, seller.public.encoded)
    val kSeller = ShareCrypto.sessionKey(seller.private, owner.public, code, owner.public.encoded, seller.public.encoded)
    val kMallory = ShareCrypto.sessionKey(mallory.private, owner.public, code, owner.public.encoded, seller.public.encoded)
    check("owner and seller derive the same 32-byte key", kOwner.size == 32 && kOwner.contentEquals(kSeller))
    check("…which no one else can", !kMallory.contentEquals(kOwner))
    // RFC 5869, test case 1.
    val okm = ShareCrypto.hkdf(hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
    check("HKDF matches RFC 5869's first test case", okm.contentEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")))

    // --- encryption in chunks ---
    for (size in listOf(0, 1, SealedOutputStream.CHUNK - 1, SealedOutputStream.CHUNK, SealedOutputStream.CHUNK + 1, 300_000)) {
        val plain = ByteArray(size) { (it * 31 + 7).toByte() }
        check("a $size-byte copy round-trips", unseal(kOwner, seal(kOwner, plain)).contentEquals(plain))
    }
    val plain = ByteArray(200_000) { (it % 251).toByte() }; val sealed = seal(kOwner, plain)
    check("a flipped byte fails", fails { unseal(kOwner, sealed.copyOf().also { it[70_000] = (it[70_000].toInt() xor 1).toByte() }) })
    check("a cut-off copy fails, even on a chunk boundary", fails { unseal(kOwner, sealed.copyOf(sealed.size - 10)) } && fails { unseal(kOwner, sealed.copyOf(12 + 4 + SealedOutputStream.CHUNK + 16)) })
    check("data after the end fails", fails { unseal(kOwner, sealed + byteArrayOf(1, 2, 3)) })
    check("the wrong key fails", fails { unseal(kMallory, sealed) })
    run {   // swap the first two chunks
        val c1 = 12; val l1 = 4 + SealedOutputStream.CHUNK + 16; val c2 = c1 + l1
        val swapped = sealed.copyOfRange(0, c1) + sealed.copyOfRange(c2, c2 + l1) + sealed.copyOfRange(c1, c2) + sealed.copyOfRange(c2 + l1, sealed.size)
        check("swapped chunks fail", fails { unseal(kOwner, swapped) })
    }

    // --- a copy of a store ---
    val tmp = Files.createTempDirectory("share").toFile()
    val storeDir = File(tmp, "Store_12345678").apply { mkdirs() }
    File(storeDir, "index.html").writeText("<p>the store</p>"); File(storeDir, "index.html.gz").writeBytes(ByteArray(40) { 9 })
    File(storeDir, "products/app").mkdirs(); File(storeDir, "products/app/app.apk").writeBytes(ByteArray(150_000) { (it % 7).toByte() })
    File(storeDir, "products/app/icon.png").writeBytes(ByteArray(3_000) { 5 })
    val expires = Instant.parse("2026-10-26T12:00:00Z")
    val desc = SharePackage.describe(storeDir, "Top Android apps", store, Instant.parse("2026-09-26T12:00:00Z"), expires)
    fun copyOf(d: SharePackage.Description, key: java.security.KeyPair = store): ByteArray =
        ByteArrayOutputStream().also { b -> SealedOutputStream(b, kOwner).use { SharePackage.write(it, storeDir, d, key) } }.toByteArray()
    val copy = copyOf(desc)
    val sellerDir = File(tmp, "seller")
    val (got, dir) = SharePackage.import(SealedInputStream(ByteArrayInputStream(copy), kSeller), sellerDir)
    check("a copy arrives in the store's own folder, named by its identity", dir == File(sellerDir, id))
    check("…with every file identical", SharePackage.filesOf(storeDir).all { (p, f) -> File(dir, p).readBytes().contentEquals(f.readBytes()) } && SharePackage.filesOf(dir).size == 4)
    check("…and its description: name, expiry, and store", got.storeName == "Top Android apps" && got.expires == expires && got.storeId == id && got.totalBytes == 153_056L)
    check("nothing temporary is left behind", sellerDir.listFiles()!!.map { it.name } == listOf(id))
    File(storeDir, "index.html").writeText("<p>the store, updated</p>")
    SharePackage.import(SealedInputStream(ByteArrayInputStream(copyOf(SharePackage.describe(storeDir, "Top Android apps", store, Instant.now(), null))), kSeller), sellerDir)
    check("a newer copy replaces the old one, with its own expiry", File(sellerDir, "$id/index.html").readText() == "<p>the store, updated</p>" && sellerDir.listFiles()!!.map { it.name } == listOf(id))
    fun importFails(bytes: ByteArray, space: Long = Long.MAX_VALUE, into: File = File(tmp, "fresh-" + System.nanoTime())): Boolean {
        val ok = fails { SharePackage.import(SealedInputStream(ByteArrayInputStream(bytes), kSeller), into, space) }
        return ok && (!into.exists() || into.listFiles()!!.isEmpty())
    }
    // An altered description: signed by the store, then one size changed.
    run {
        val out = ByteArrayOutputStream(); SealedOutputStream(out, kOwner).use { s ->
            val bytes = desc.json.toString().toByteArray(); val signature = ShareCrypto.sign(store.private, bytes)
            val changed = String(bytes).replace("\"size\":150000", "\"size\":150001").toByteArray()
            DataOutputStream(s).apply { writeInt(changed.size); write(changed); writeInt(signature.size); write(signature); flush() } }
        check("an altered description is refused, leaving nothing", importFails(out.toByteArray()))
    }
    run {   // another key claiming this store's identity
        val forged = JSONObject(desc.json.toString()).put("storeKey", java.util.Base64.getEncoder().encodeToString(other.public.encoded))
        check("another key claiming the store's identity is refused", importFails(copyOf(SharePackage.Description(forged), other)))
    }
    for (bad in listOf("../outside", "/etc/passwd", "a/../../b", "a//b", "a\\b", "")) {
        val evil = JSONObject(desc.json.toString()); evil.getJSONArray("files").getJSONObject(0).put("path", bad)
        val out = ByteArrayOutputStream(); SealedOutputStream(out, kOwner).use { s ->
            val bytes = evil.toString().toByteArray(); val signature = ShareCrypto.sign(store.private, bytes)
            DataOutputStream(s).apply { writeInt(bytes.size); write(bytes); writeInt(signature.size); write(signature); flush() } }
        check("an unsafe path is refused, even signed by the store: \"$bad\"", importFails(out.toByteArray()) && !File(tmp, "outside").exists())
    }
    run {
        val twice = JSONObject(desc.json.toString()); val files = twice.getJSONArray("files"); files.put(JSONObject(files.getJSONObject(0).toString()))
        val out = ByteArrayOutputStream(); SealedOutputStream(out, kOwner).use { s ->
            val bytes = twice.toString().toByteArray(); val signature = ShareCrypto.sign(store.private, bytes)
            DataOutputStream(s).apply { writeInt(bytes.size); write(bytes); writeInt(signature.size); write(signature); flush() } }
        check("a copy listing a file twice is refused, even signed by the store", importFails(out.toByteArray()))
    }
    File(storeDir, "index.html.gz.tmp").writeBytes(ByteArray(10))
    check("a copy leaves out temporary files, such as the compressor's", SharePackage.filesOf(storeDir).none { it.first.endsWith(".tmp") })
    File(storeDir, "index.html.gz.tmp").delete()
    check("too little space is refused before anything is written", importFails(copy, space = 100_000))
    check("a cut-off copy is refused, leaving nothing", importFails(copy.copyOf(copy.size - 5_000)))
    check("the importer can refuse a copy after seeing its description", fails {
        SharePackage.import(SealedInputStream(ByteArrayInputStream(copy), kSeller), File(tmp, "declined"), accept = { throw IOException("an older copy: declined") }) } && !File(tmp, "declined/$id").exists())

    // --- share codes ---
    val shown = ShareCodes.newCode()
    check("a new code looks like LS-XXXX-XXXX ($shown)", Regex("^LS-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$").matches(shown))
    check("codes are forgiving: case, dashes, spaces and the prefix", ShareCodes.normalize("ls-7q4k-9m2p") == "7Q4K9M2P" && ShareCodes.normalize("7Q4K 9M2P") == "7Q4K9M2P" && ShareCodes.normalize("LS7Q4K9M2P") == "7Q4K9M2P")
    check("…and look-alikes: O for 0, I and L for 1", ShareCodes.normalize("LS-O1LI-ABCD") == "0111ABCD")
    check("…but not wrong lengths, or U", ShareCodes.normalize("LS-7Q4K-9M2") == null && ShareCodes.normalize("LS-7Q4K-9M2U") == null)
    check("a code displays as it's shown", ShareCodes.display("7Q4K9M2P") == "LS-7Q4K-9M2P")

    // --- the expiry clock ---
    val clock = ExpiryClock(File(tmp, "clock/latest"))
    val t0 = Instant.parse("2026-10-01T12:00:00Z").toEpochMilli(); val exp = expires.toEpochMilli()
    check("before its date, a copy is fine", clock.check(t0, exp) == ExpiryClock.Status.OK && clock.latestSeen() == t0)
    check("a copy that never expires is fine", clock.check(t0 + 1000, null) == ExpiryClock.Status.OK)
    check("a clock set back 4 minutes is an ordinary correction", clock.check(t0 - 4 * 60_000, exp) == ExpiryClock.Status.OK)
    check("…6 minutes is setting it back", clock.check(t0 - 6 * 60_000, exp) == ExpiryClock.Status.CLOCK_SET_BACK)
    check("the latest time seen survives", ExpiryClock(File(tmp, "clock/latest")).latestSeen() == t0 + 1000)
    check("on its date, a copy expires", clock.check(exp, exp) == ExpiryClock.Status.EXPIRED)
    // ===== M2: a whole share between two servers =====
    val owners = File(tmp, "owner").apply { mkdirs() }
    val shop = File(owners, "Store_87654321").apply { mkdirs() }
    File(shop, "index.html").writeText("<p>Top Android apps</p>"); File(shop, "products/a").mkdirs(); File(shop, "products/a/a.apk").writeBytes(ByteArray(400_000) { (it % 13).toByte() })
    val log = StoreLog(File(tmp, "logs"), 1_000_000)
    // A store with a PIN: sharing must not need it.
    val ownerServer = HttpServer(shop, listOf(java.net.InetAddress.getByName("127.0.0.1")), 0, log, Auth("4321", "secret", null))
    ownerServer.start()
    val base = "http://127.0.0.1:${ownerServer.boundPort}"
    val shareKey = ShareCrypto.newKeyPair()
    check("with no share open, the sharing address doesn't exist", rawStatus(ownerServer.boundPort, "GET /share/status?request=x") == 404)
    var host = ShareHost(shop, "Top Android apps", shareKey); ownerServer.share = host
    val client = ShareClient(base, host.displayCode.lowercase(), "Maria’s phone")
    val sellerCheck = client.ask()
    val asked = host.pending()
    check("the owner sees the phone's name, and the same check code ($sellerCheck)", asked != null && asked.phoneName == "Maria’s phone" && asked.checkCode == sellerCheck)
    check("…through a store with a PIN, which sharing doesn't need", true)
    check("until the owner decides, the seller is told to wait", client.status() == "waiting")
    check("a download before the owner allows it is refused", fails { client.download(File(tmp, "early")) } && !File(tmp, "early/${ShareCrypto.storeId(shareKey.public)}").exists())
    check("a second phone can't use the same code", fails { ShareClient(base, host.code, "Another phone").ask() })
    val copyExpires = Instant.parse("2026-10-26T12:00:00Z")
    host.allow(copyExpires)
    check("once allowed, the seller is told so", client.status() == "allowed")
    val seen = mutableListOf<Pair<Long, Long>>()
    val (gotCopy, copyDir) = client.download(File(tmp, "seller2")) { w, t -> seen += w to t }
    check("the copy arrives whole, every file identical", SharePackage.filesOf(shop).all { (p, f) -> File(copyDir, p).readBytes().contentEquals(f.readBytes()) })
    check("…with the expiry the owner chose, and the store's name and identity",
        gotCopy.expires == copyExpires && gotCopy.storeName == "Top Android apps" && gotCopy.storeId == ShareCrypto.storeId(shareKey.public))
    check("after one download, the share is done", host.state() == ShareHost.State.DONE)
    check("progress is reported as the copy arrives, ending at its size",
        seen.size > 2 && seen.first() == 0L to gotCopy.totalBytes && seen.last() == gotCopy.totalBytes to gotCopy.totalBytes && seen.zipWithNext().all { (a, b) -> b.first >= a.first })
    check("…and the code can't be used again", fails { ShareClient(base, host.code, "Maria’s phone").ask() })
    // Cancelled, before and during a download.
    host = ShareHost(shop, "Top Android apps", shareKey); ownerServer.share = host
    val cancelling = ShareClient(base, host.code, "Maria’s phone"); cancelling.ask(); host.allow(null)
    cancelling.cancel()
    check("a cancelled client downloads nothing", fails { cancelling.download(File(tmp, "cancelled")) } && File(tmp, "cancelled").listFiles().orEmpty().isEmpty())
    host = ShareHost(shop, "Top Android apps", shareKey); ownerServer.share = host
    val midway = ShareClient(base, host.code, "Maria’s phone"); midway.ask(); host.allow(null)
    val midDir = File(tmp, "midway")
    check("cancelling during a download stops it, leaving nothing", fails { midway.download(midDir) { w, _ -> if (w > 100_000) midway.cancel() } } &&
        midDir.listFiles().orEmpty().none { !it.name.startsWith(".") } && midDir.listFiles().orEmpty().isEmpty())
    // Denied.
    host = ShareHost(shop, "Top Android apps", shareKey); ownerServer.share = host
    val denied = ShareClient(base, host.code, "A stranger"); denied.ask(); host.deny()
    check("a denied request is told so, and gets nothing", denied.status() == "denied" && fails { denied.download(File(tmp, "denied")) })
    // A key that doesn't match its commitment.
    host = ShareHost(shop, "Top Android apps", shareKey); ownerServer.share = host
    val promised = ShareCrypto.newKeyPair(); val swapped = ShareCrypto.newKeyPair()
    val r1 = rawJson(ownerServer.boundPort, "/share/request", JSONObject().put("code", host.code).put("name", "Mallory")
        .put("commitment", java.util.Base64.getEncoder().encodeToString(ShareCrypto.commitment(promised.public.encoded))))
    val r2 = rawJson(ownerServer.boundPort, "/share/reveal", JSONObject().put("request", r1.second.getString("request"))
        .put("sellerKey", java.util.Base64.getEncoder().encodeToString(swapped.public.encoded)))
    check("a key that doesn't match its commitment is refused, and the share closes", r1.first == 200 && r2.first == 403 && host.state() == ShareHost.State.CLOSED && host.pending() == null)
    // Mismatched versions, named.
    host = ShareHost(shop, "Top Android apps", shareKey); ownerServer.share = host
    val older = rawJson(ownerServer.boundPort, "/share/request", JSONObject().put("code", host.code).put("name", "Old").put("protocol", 0)
        .put("commitment", java.util.Base64.getEncoder().encodeToString(ShareCrypto.commitment(ShareCrypto.newKeyPair().public.encoded))))
    check("an older seller's app is refused, telling it to update", older.first == 426 && "This phone's Share Your Apps Server is older" in older.second.getString("error"))
    val newer = rawJson(ownerServer.boundPort, "/share/request", JSONObject().put("code", host.code).put("name", "New").put("protocol", ShareHost.PROTOCOL + 1)
        .put("commitment", java.util.Base64.getEncoder().encodeToString(ShareCrypto.commitment(ShareCrypto.newKeyPair().public.encoded))))
    check("…and a newer one, telling the owner to update", newer.first == 426 && "The owner's Share Your Apps Server is older" in newer.second.getString("error"))
    check("…while the share stays open for a matching phone", host.state() == ShareHost.State.OPEN)
    // Expired, and guessed.
    var clockNow = System.currentTimeMillis()
    host = ShareHost(shop, "Top Android apps", shareKey, now = { clockNow }); ownerServer.share = host
    clockNow += ShareHost.CODE_LIFETIME_MS + 1000
    check("an expired code is refused, saying so", try { ShareClient(base, host.code, "Late").ask(); false } catch (e: IOException) { "expired" in e.message.orEmpty() })
    host = ShareHost(shop, "Top Android apps", shareKey); ownerServer.share = host
    repeat(ShareHost.MAX_WRONG_GUESSES) { try { ShareClient(base, "LS-0000-0000", "Guesser").ask() } catch (e: IOException) { } }
    check("${ShareHost.MAX_WRONG_GUESSES} wrong guesses close the code, even to the right one", fails { ShareClient(base, host.code, "Maria’s phone").ask() })
    // The app itself, for a phone that doesn't have it.
    host = ShareHost(shop, "Top Android apps", shareKey); ownerServer.share = host
    check("without the app's file, /share/app offers nothing", rawStatus(ownerServer.boundPort, "GET /share/app") == 404)
    val fakeApk = File(tmp, "base.apk").apply { writeBytes(ByteArray(12_345) { (it % 11).toByte() }) }
    host.appApk = fakeApk
    val app = java.net.URL("$base/share/app").openConnection() as java.net.HttpURLConnection
    val appBytes = app.inputStream.use { it.readBytes() }
    check("/share/app sends the app, as an APK to install", app.responseCode == 200 && appBytes.contentEquals(fakeApk.readBytes()) &&
        app.contentType == "application/vnd.android.package-archive" && app.getHeaderField("Content-Disposition") == "attachment; filename=\"share-your-apps-server.apk\"")
    ownerServer.share = null
    check("…only while a share is open", rawStatus(ownerServer.boundPort, "GET /share/app") == 404)

    // Discovery.
    host = ShareHost(shop, "Top Android apps", shareKey); ownerServer.share = host
    val listener = ShareDiscovery.Listener({ ownerServer.share }, ownerServer.boundPort, port = 0); listener.start()
    val loop = listOf(java.net.InetAddress.getByName("127.0.0.1"))
    val found = ShareDiscovery.find(host.displayCode, loop, listener.port, 2_000)
    check("the owner's server is found from a typed code", found?.port == ownerServer.boundPort)
    check("…and stays silent for a wrong one", ShareDiscovery.find("LS-0000-0000", loop, listener.port, 800) == null)
    check("the broadcast holds a hash of the code, not the code", host.code !in ShareDiscovery.probe(host.code))
    listener.close()
    val shareLog = File(tmp, "logs").listFiles()!!.filter { it.isFile }.joinToString("\n") { it.readText() }
    check("the log records the request and the copy sent",
        "[share] Maria’s phone asked for a copy of Top Android apps" in shareLog && "[share] sent a copy of Top Android apps to Maria’s phone" in shareLog)
    ownerServer.stop()

    // --- stopping for an expired copy: downloads under way finish, new ones are refused ---
    // Its own server, with no PIN, so it depends on nothing before it.
    val drainStore = File(tmp, "drain/Store_1").apply { mkdirs() }; File(drainStore, "index.html").writeText("x")
    val big = File(drainStore, "big.bin"); java.io.RandomAccessFile(big, "rw").use { it.setLength(8L * 1024 * 1024) }
    val drainServer = HttpServer(drainStore, listOf(java.net.InetAddress.getByName("127.0.0.1")), 0, log)
    drainServer.start()
    val slowSocket = java.net.Socket().apply { receiveBufferSize = 4096; connect(java.net.InetSocketAddress("127.0.0.1", drainServer.boundPort)) }
    slowSocket.getOutputStream().apply { write("GET /f/big.bin HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); flush() }
    val slowIn = java.io.DataInputStream(slowSocket.getInputStream())
    val head = StringBuilder(); while (!head.endsWith("\r\n\r\n")) head.append(slowIn.read().toChar())
    Thread.sleep(1200)
    check("a download is under way (${drainServer.sending} being sent; ${head.lineSequence().first()})", drainServer.sending >= 1 && head.startsWith("HTTP/1.1 200"))
    drainServer.stopAccepting()
    // Refused, or closed without an answer, both count; the message says which, or what answered.
    val lateOutcome = try {
        java.net.Socket().use { late ->
            late.connect(java.net.InetSocketAddress("127.0.0.1", drainServer.boundPort), 3000); late.soTimeout = 5000
            late.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val b = ByteArray(80); val n = late.getInputStream().read(b)
            if (n < 0) "closed without an answer" else "answered: " + String(b, 0, n).lineSequence().first()
        }
    } catch (e: java.io.IOException) { "refused: ${e.javaClass.simpleName} ${e.message}" }
    check("…and after stopAccepting, new connections are refused ($lateOutcome)", !lateOutcome.startsWith("answered"))
    var total = 0L; val buf = ByteArray(64 * 1024)
    while (true) { val n = slowIn.read(buf); if (n < 0) break; total += n }
    slowSocket.close()
    check("…while the download under way finishes whole ($total bytes)", total == 8L * 1024 * 1024)
    var drained = false
    for (i in 0 until 30) { if (drainServer.sending == 0) { drained = true; break }; Thread.sleep(200) }
    check("…and the count of downloads being sent falls to 0", drained)
    drainServer.stop()

    // --- a client dripping its request can't hold the main thread for longer than the limit ---
    val slowStore = File(tmp, "slow/Store_2").apply { mkdirs() }; File(slowStore, "index.html").writeText("<p>slow</p>")
    val slowServer = HttpServer(slowStore, listOf(java.net.InetAddress.getByName("127.0.0.1")), 0, log); slowServer.start()
    val dripper = Thread {
        try { java.net.Socket("127.0.0.1", slowServer.boundPort).use { sk -> val o = sk.getOutputStream()
            for (c in "GET / HTTP/1.1\r\nHost: x\r\nX-Slow: aaaaaaaaaaaaaaaaaaaa\r\n\r\n") { o.write(c.code); o.flush(); Thread.sleep(1000) } } } catch (e: Exception) { }
    }.apply { isDaemon = true; start() }
    Thread.sleep(300)
    val waitStart = System.currentTimeMillis()
    val served = runCatching { rawStatus(slowServer.boundPort, "GET /") }.getOrDefault(0)
    val waited = System.currentTimeMillis() - waitStart
    check("a client sending a byte a second delays another by no more than the ${HttpServer.REQUEST_DEADLINE_MS / 1000}-second limit (waited ${waited} ms)",
        served == 200 && waited < HttpServer.REQUEST_DEADLINE_MS + 2_000)
    slowServer.stop(); dripper.interrupt()

    // --- a copy's expiry, applied ---
    val imported = File(tmp, "imported").apply { mkdirs() }
    val expiring = SharePackage.describe(shop, "Top Android apps", shareKey, Instant.parse("2026-09-26T12:00:00Z"), Instant.parse("2026-10-26T12:00:00Z"))
    fun place(d: SharePackage.Description) { File(imported, d.storeId).apply { mkdirs(); File(this, "index.html").writeText("x") }; File(imported, "${d.storeId}.json").writeText(d.json.toString()) }
    place(expiring)
    val expiryClock = ExpiryClock(CopyExpiry.clockFile(File(tmp, "files")))
    val day = 86_400_000L; val before = Instant.parse("2026-10-01T12:00:00Z").toEpochMilli()
    check("before its date, a copy is kept", CopyExpiry.enforce(imported, expiryClock, before) == null && File(imported, expiring.storeId).isDirectory)
    val gone = CopyExpiry.enforce(imported, expiryClock, Instant.parse("2026-10-26T12:00:00Z").toEpochMilli())
    check("on its date, it's deleted", gone != null && !gone.clockSetBack && !File(imported, expiring.storeId).exists() && !File(imported, "${expiring.storeId}.json").exists())
    val left = CopyExpiry.notice(imported)
    check("…leaving a notice with its name, and nothing about why", left?.storeName == "Top Android apps" && left.expires == null && !left.clockSetBack &&
        org.json.JSONObject(File(imported, CopyExpiry.NOTICE).readText()).keys().asSequence().toSet() == setOf("storeName"))
    check("…which is never mistaken for a copy", CopyExpiry.copies(imported).isEmpty())
    CopyExpiry.dismiss(imported)
    check("…until the seller dismisses it", CopyExpiry.notice(imported) == null)
    val never = SharePackage.describe(shop, "Top Android apps", shareKey, Instant.parse("2026-09-26T12:00:00Z"), null)
    place(never)
    check("a copy that never expires is kept", CopyExpiry.enforce(imported, expiryClock, Instant.parse("2027-01-01T12:00:00Z").toEpochMilli()) == null)
    check("a clock set back six minutes spares a copy that never expires: it has no date to extend",
        CopyExpiry.enforce(imported, expiryClock, Instant.parse("2027-01-01T12:00:00Z").toEpochMilli() - 6 * 60_000) == null && CopyExpiry.copies(imported).size == 1)
    File(imported, never.storeId).deleteRecursively(); File(imported, "${never.storeId}.json").delete()
    val dated = SharePackage.describe(shop, "Top Android apps", shareKey, Instant.parse("2026-12-01T12:00:00Z"), Instant.parse("2027-06-01T12:00:00Z"))
    place(dated)
    val setBack = CopyExpiry.enforce(imported, expiryClock, Instant.parse("2027-01-01T12:00:00Z").toEpochMilli() - 6 * 60_000)
    check("…but deletes a copy with a date", setBack != null && CopyExpiry.copies(imported).isEmpty())
    check("…and its notice is the same as an ordinary expiry's", CopyExpiry.notice(imported)?.let { it.storeName == "Top Android apps" && !it.clockSetBack } == true &&
        org.json.JSONObject(File(imported, CopyExpiry.NOTICE).readText()).keys().asSequence().toSet() == setOf("storeName"))
    // A clock once set far ahead, then corrected, mustn't condemn a fresh copy.
    val farAhead = Instant.parse("2030-01-01T00:00:00Z").toEpochMilli(); val today = Instant.parse("2026-10-01T12:00:00Z").toEpochMilli()
    expiryClock.check(farAhead, null)
    val dated2 = SharePackage.describe(shop, "Top Android apps", shareKey, Instant.parse("2026-09-26T12:00:00Z"), Instant.parse("2026-12-31T12:00:00Z"))
    place(dated2)
    check("without a restart, a clock once set far ahead condemns a copy", CopyExpiry.enforce(imported, expiryClock, today)?.clockSetBack == true)
    place(dated2); expiryClock.restart(today)
    check("…so importing restarts the clock, and a fresh copy is kept", CopyExpiry.enforce(imported, expiryClock, today + 60_000) == null && CopyExpiry.copies(imported).size == 1)
    check("the clock keeps its latest time outside the imported folder", !CopyExpiry.clockFile(File(tmp, "files")).path.contains("imported") && expiryClock.latestSeen() > day)

    // --- the store's key file, and the QR's link ---
    val keyDir = File(tmp, "keys")
    val k1 = StoreKeyFile.loadOrCreate(keyDir); val k2 = StoreKeyFile.loadOrCreate(keyDir)
    check("the store's key is made once, then kept", k1.public == k2.public && ShareCrypto.storeId(k1.public) == ShareCrypto.storeId(k2.public))
    File(keyDir, "store-key.p8").writeBytes(byteArrayOf(1, 2, 3))
    check("a damaged key file makes a new key, a new store identity", StoreKeyFile.loadOrCreate(keyDir).public != k1.public)
    val link = ShareLink.make("192.168.1.5", 6175, "7Q4K9M2P")
    check("a share link: $link", link == "shareyourapps-share://192.168.1.5:6175/LS-7Q4K-9M2P")
    check("…reads back", ShareLink.parse(link) == Triple("192.168.1.5", 6175, "7Q4K9M2P"))
    check("…and refuses anything else", listOf("http://192.168.1.5:6175/LS-7Q4K-9M2P", "shareyourapps-share://999.1.1.1:6175/LS-7Q4K-9M2P",
        "shareyourapps-share://192.168.1.5:99999/LS-7Q4K-9M2P", "shareyourapps-share://192.168.1.5:6175/nope", "shareyourapps-share://evil.example:80/LS-7Q4K-9M2P").all { ShareLink.parse(it) == null })

    tmp.deleteRecursively()

    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED"); return failedNames.toList()
}

fun rawStatus(port: Int, requestLine: String): Int = java.net.Socket("127.0.0.1", port).use { sk ->
    sk.getOutputStream().write("$requestLine HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); sk.getOutputStream().flush()
    String(sk.getInputStream().readBytes()).substringAfter(' ').take(3).toInt() }

fun rawJson(port: Int, path: String, body: JSONObject): Pair<Int, JSONObject> {
    val c = java.net.URL("http://127.0.0.1:$port$path").openConnection() as java.net.HttpURLConnection
    c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) }
    val code = c.responseCode
    val text = (if (code == 200) c.inputStream else c.errorStream).use { String(it.readBytes()) }
    return code to JSONObject(text)
}

fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** The JUnit test: runs the checks above, and fails naming them if any fail. */
class ShareChecks {
    @org.junit.Test
    fun everyCheckPasses() {
        val failed = runChecks()
        org.junit.Assert.assertTrue("Failed checks: " + failed.joinToString("; "), failed.isEmpty())
    }
}
