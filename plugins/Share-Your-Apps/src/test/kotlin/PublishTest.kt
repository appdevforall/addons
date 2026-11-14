package pubtest

import org.appdevforall.shareyourapps.*
import org.appdevforall.shareyourapps.server.Auth
import org.appdevforall.shareyourapps.server.Compressor
import org.appdevforall.shareyourapps.server.HttpServer
import org.appdevforall.shareyourapps.server.StoreLog
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

var failures = 0
val failedNames = mutableListOf<String>()
fun check(name: String, cond: Boolean) {
    if (!cond) failedNames += name
   
    println((if (cond) "PASS  " else "FAIL  ") + name)
    if (!cond) failures++
}

/**
 * A Unix-domain server socket at [path], or null where Java has none. Made through reflection
 * because unit tests compile against Android's library, which has no Unix-domain sockets, while
 * they run on the computer's or phone's full Java, which has them from Java 16.
 */
/** Why the last [unixSocket] call made none, for the SKIP line. */
var unixSocketProblem = ""

fun unixSocket(path: File): java.nio.channels.ServerSocketChannel? = try {
    val unix = Class.forName("java.net.StandardProtocolFamily").getField("UNIX").get(null)
    val address = Class.forName("java.net.UnixDomainSocketAddress")
        .getMethod("of", java.nio.file.Path::class.java).invoke(null, path.toPath()) as java.net.SocketAddress
    val channel = java.nio.channels.ServerSocketChannel::class.java
        .getMethod("open", java.net.ProtocolFamily::class.java).invoke(null, unix) as java.nio.channels.ServerSocketChannel
    try { channel.bind(address) } catch (e: Exception) { channel.close(); throw e }
} catch (e: Exception) {
    // Missing classes (Java before 16), a filesystem that won't hold a socket, or a path over
    // the 108-byte limit for socket paths.
    val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
    unixSocketProblem = "${cause.javaClass.simpleName}: ${cause.message}; path is ${path.path.length} characters"
    null
}

fun unixSocketsAvailable() = try { Class.forName("java.net.UnixDomainSocketAddress"); true } catch (e: ClassNotFoundException) { false }

/** A check this machine can't run: reported, but not a failure. */
fun skip(name: String, why: String) = println("SKIP  $name ($why)")

/** Runs every check; returns the names of those that failed. */
fun runChecks(): List<String> {
    failures = 0; failedNames.clear()
    val sd = Files.createTempDirectory("sdcard").toFile()
    val projects = File(sd, "CodeOnTheGoProjects").apply { mkdirs() }

    // ---- a realistic project ----
    val proj = File(projects, "MyTodoApp")
    fun put(rel: String, text: String = "x") = File(proj, rel).apply { parentFile?.mkdirs(); writeText(text) }
    put("settings.gradle.kts"); put("app/build.gradle.kts"); put("app/src/main/java/Main.kt", "fun main() {}")
    put(".git/config"); put("build/out.txt"); put("app/build/intermediates/x.dex"); put("core/build/y.class")
    put(".gradle/cache"); put(".idea/workspace.xml"); put("app/app.iml"); put("local.properties", "sdk.dir=/x")
    put("keystore.properties"); put("release.jks"); put("debug.keystore")
    put("notes.log"); put("docs/draft.tmp"); put("docs/keep.tmp"); put("secrets/key.txt"); put("app/src/main/res/x.txt")
    put(".gitignore", "# comment\n*.log\n*.tmp\n!keep.tmp\n/secrets/\n")
    put("app/.gitignore", "src/main/res/\n")
    put(".kotlin/sessions/s.salive"); put(".externalNativeBuild/cmake/x"); put("captures/trace.trace")
    put(".cg/state.json"); put("release.properties", "storePassword=hunter2"); put("app/release.properties", "keyPassword=x")
    put("app/heap.hprof"); put(".DS_Store"); put("docs/Thumbs.db"); put("upload.p12"); put("cert.pfx")
    // Special files: a named pipe (reading it would block forever) and a Unix socket.
    // Some filesystems, such as a phone's shared storage, allow neither; the checks are skipped there.
    val pipeMade = try { ProcessBuilder("mkfifo", File(proj, "pipe").path).start().waitFor() == 0 } catch (e: Exception) { false }
    if (pipeMade) check("made a named pipe", true) else skip("made a named pipe", "this filesystem doesn't allow them")
    val sock = unixSocket(File(proj, "app/ipc.sock"))
    if (sock != null) check("made a Unix socket", true) else skip("made a Unix socket", if (unixSocketsAvailable()) "couldn't make one here — $unixSocketProblem" else "needs Java 16 or later")
    val outside = File(sd, "outside.txt").apply { writeText("OUTSIDE") }
    Files.createSymbolicLink(File(proj, "link.txt").toPath(), outside.toPath())

    // ---- media ----
    // A stand-in APK, signed the v1 way — a signature file in META-INF — so publishing accepts it.
    val apkBytes = java.io.ByteArrayOutputStream().also { b -> java.util.zip.ZipOutputStream(b).use { z ->
        z.putNextEntry(java.util.zip.ZipEntry("META-INF/CERT.RSA")); z.write(ByteArray(64) { 1 }); z.closeEntry()
        z.putNextEntry(java.util.zip.ZipEntry("classes.dex")); z.setLevel(0); z.write(ByteArray(250_000) { (it % 256).toByte() }); z.closeEntry() } }.toByteArray()
    File(proj, "app/build/outputs/apk/release").mkdirs()
    File(proj, "app/build/outputs/apk/release/app-release.apk").writeBytes(apkBytes)
    val pics = File(sd, "Pictures").apply { mkdirs() }
    File(pics, "small.png").writeBytes(ByteArray(10_240) { 1 })        // exactly the limit: inlined
    File(pics, "large shot.png").writeBytes(ByteArray(10_241) { 2 })   // one over: a file
    File(pics, "demo.mp4").writeBytes(ByteArray(50_000) { 3 })

    // ---- leftovers ----
    File(projects, "Store_11111111").mkdirs()                 // interrupted publish: removed
    File(projects, "Store_22222222/index.html").apply { parentFile?.mkdirs(); writeText("old") } // current: kept

    val catalogJson = """
    {
      "storeDir": "Store_22222222",
      "storeName": "Anna's <Store>",
      "storeDescriptionHtml": "<p>All <b>free</b>.</p>",
      "headerImage": null,
      "pin": "012345",
      "extra": {"motto": "hello"},
      "products": [
        {"name": "Todo <App>", "project": "CodeOnTheGoProjects/MyTodoApp",
         "apk": "CodeOnTheGoProjects/MyTodoApp/app/build/outputs/apk/release/app-release.apk",
         "apkPublished": true, "sourceZipPublished": true,
         "carousel": ["Pictures/small.png", "Pictures/large shot.png", "Pictures/demo.mp4"],
         "descriptionHtml": "<i>offline</i>", "dateAdded": "2026-09-19T12:00:00Z", "ordinal": 2},
        {"name": "todo app", "project": "CodeOnTheGoProjects/MyTodoApp", "apk": null,
         "apkPublished": false, "sourceZipPublished": true, "carousel": [], "ordinal": 1},
        {"name": "Draft", "apk": null, "apkPublished": false, "sourceZipPublished": false, "ordinal": 3}
      ]
    }"""
    val catalog = Catalog.parse(catalogJson)
    check("catalog parses; PIN keeps its leading zero", catalog.pin == "012345")
    val again = Catalog.parse(catalog.toJson().toString())
    check("catalog round-trips through toJson",
        // JSONObject doesn't compare by content, so the store's and products' Extra JSON are compared as plain values.
        again.copy(extra = catalog.extra, products = again.products.mapIndexed { i, pr -> pr.copy(extra = catalog.products[i].extra) }) == catalog &&
            toPlain(again.extra) == toPlain(catalog.extra) && again.products.indices.all { toPlain(again.products[it].extra) == toPlain(catalog.products[it].extra) })

    // ---- publish ----
    val built = Publisher(sd).build(catalog)
    val root = File(projects, built.storeDir)
    check("new Store directory named Store_ + 8 digits", Regex("Store_[0-9]{8}").matches(built.storeDir) && root.isDirectory)
    check("leftover Store directory removed", !File(projects, "Store_11111111").exists())
    check("current Store directory kept", File(projects, "Store_22222222/index.html").exists())
    check("README.txt written", File(root, "README.txt").readText().startsWith("SHARE YOUR APPS"))

    val html = File(root, "index.html").readText()
    check("names escaped, descriptions raw", "Anna&#39;s &lt;Store&gt;" in html && "Todo &lt;App&gt;" in html && "<p>All <b>free</b>.</p>" in html && "<i>offline</i>" in html)
    check("draft product left out", "Draft" !in html)
    check("ordinal order: source-only 'todo app' first", html.indexOf(">todo app<") < html.indexOf(">Todo &lt;App&gt;<"))
    check("slugs deduplicated", File(root, "products/todo-app").isDirectory && File(root, "products/todo-app-2").isDirectory)
    val p1 = "products/todo-app-2"   // "Todo <App>" sorts second, so it gets the -2 slug
    val slug1 = p1.substringAfter("products/")
    check("APK copied byte for byte, named after the product", File(root, "$p1/$slug1.apk").readBytes().contentEquals(apkBytes))
    check("APK link and size label", "href=\"/f/$p1/$slug1.apk\">Download APK<small>${sizeLabel(apkBytes.size.toLong())}</small>" in html)
    check("no download is named app-release any more", root.walkTopDown().none { it.name.startsWith("app-release") })
    check("10,240-byte image inlined, no file", !File(root, "$p1/small.png").exists() && "data:image/png;base64," in html)
    check("10,241-byte image copied, link percent-encoded", File(root, "$p1/large shot.png").exists() && "/f/$p1/large%20shot.png" in html)
    val manyPictures = catalog.products.count { it.published && it.carousel.size > 1 }
    val arrows = Regex("""class="ar l" aria-label="Previous picture" hidden>""").findAll(html).count()
    check("products with several pictures get arrows, the left one hidden at first ($arrows of $manyPictures)", manyPictures > 0 && arrows == manyPictures)
    check("…and a right arrow each", Regex("""class="ar r" aria-label="Next picture">""").findAll(html).count() == manyPictures)
    check("products with one picture get none", catalog.products.any { it.published && it.carousel.size == 1 }.let { !it || arrows < catalog.products.count { p -> p.published && p.carousel.isNotEmpty() } })
    check("the arrows' script is in the page", "querySelectorAll(\".cw\")" in html)
    check("video rendered as video, not preloaded, and labelled", Regex("""<video src="/f/$p1/demo\.mp4" controls preload="none" aria-label="[^"]+, video \d+ of \d+">""").containsMatchIn(html))
    check("default header icon inlined", "data:image/svg+xml;base64," in html)
    check("date label", "Sep 19, 2026" in html)

    // ---- source zip ----
    val zip = ZipFile(File(root, "$p1/$slug1-source.zip"))
    val names = zip.entries().toList().map { it.name }.toSet()
    check("zip entries are uncompressed (stored)", zip.entries().toList().all { it.method == ZipEntry.STORED })
    check("zip keeps real sources", setOf("settings.gradle.kts", "app/build.gradle.kts", "app/src/main/java/Main.kt", ".gitignore", "docs/keep.tmp").all { it in names })
    check("zip drops .git, every build/, .gradle, .idea, *.iml", names.none { it.startsWith(".git/") || it.contains("build/") || it.startsWith(".gradle/") || it.startsWith(".idea/") || it.endsWith(".iml") })
    check("zip drops local.properties, keystore.properties, *.jks, *.keystore", names.none { it in setOf("local.properties", "keystore.properties") || it.endsWith(".jks") || it.endsWith(".keystore") })
    check("zip honors .gitignore: *.log, *.tmp, /secrets/", "notes.log" !in names && "docs/draft.tmp" !in names && names.none { it.startsWith("secrets/") })
    check("zip honors a nested .gitignore", names.none { it.startsWith("app/src/main/res/") })
    check("zip doesn't follow symlinks", "link.txt" !in names)
    check("zip drops .kotlin, .externalNativeBuild, captures", names.none { it.startsWith(".kotlin/") || it.startsWith(".externalNativeBuild/") || it.startsWith("captures/") })
    check("zip drops *.hprof, .DS_Store, Thumbs.db", names.none { it.endsWith(".hprof") || it.endsWith(".DS_Store") || it.endsWith("Thumbs.db") })
    check("zip drops CodeOnTheGo's .cg folder", names.none { it.startsWith(".cg/") })
    check("zip drops release.properties at any depth (signing passwords)", names.none { it.endsWith("release.properties") })
    check("zip drops *.p12 and *.pfx keystores", names.none { it.endsWith(".p12") || it.endsWith(".pfx") })
    check("zip skips the named pipe and the socket (and the publish didn't hang)", "pipe" !in names && "app/ipc.sock" !in names)
    check("zip contents are intact", zip.getInputStream(zip.getEntry("app/src/main/java/Main.kt")).readBytes().decodeToString() == "fun main() {}")
    zip.close()
    check("source-only product's zip named after the product", File(root, "products/todo-app/todo-app-source.zip").exists())
    check("written list covers every file except index.html",
        root.walk().filter { it.isFile }.map { it.relativeTo(root).path }.toSet() == built.files.toSet() + "index.html")

    // ---- serve it with the real server, PIN set, then verify with the token ----
    val log = StoreLog(File(sd, "logs"), 10_000_000)
    val token = "tok-${System.nanoTime()}"
    val server = HttpServer(root, listOf(InetAddress.getByName("127.0.0.1")), 0, log, Auth(catalog.pin, "secret", token), catalog.storeName)
    server.start()
    Compressor(root, log).run() // as the server does in the background; verify must still pass
    val problems = Verifier(server.boundPort, token).verify(root, built.files)
    check("verify passes against the real server, through the PIN (${problems.size} problems)", problems.isEmpty())
    problems.forEach { println("      $it") }
    check("verify with a wrong token fails every file", Verifier(server.boundPort, "nope").verify(root, built.files).size == built.files.size + 1)
    // The case verification exists for: the server serving something other than what was just
    // published (for instance, it never switched). Verify against a copy that differs in one file.
    val expected = File(sd, "expected").also { root.copyRecursively(it) }
    File(expected, "$p1/$slug1.apk").writeBytes(ByteArray(5))
    val mismatch = Verifier(server.boundPort, token).verify(expected, built.files)
    check("verify catches a file the server serves differently", mismatch.size == 1 && "$slug1.apk" in mismatch[0])
    server.stop()
    sock?.close()

    // ---- failures roll back ----
    val before = projects.list()!!.toSet()
    val missing = catalog.copy(products = catalog.products.map { it.copy(apk = "CodeOnTheGoProjects/nope.apk") })
    val e1 = runCatching { Publisher(sd).build(missing) }.exceptionOrNull()
    check("missing APK fails before anything is written", e1 is PublishException && "not found" in e1.message!! && projects.list()!!.toSet() == before)
    val empty = File(projects, "Empty").apply { mkdirs() }
    val emptyZip = catalog.copy(storeDir = built.storeDir, products = listOf(Product("E", "CodeOnTheGoProjects/Empty", null, false, true, emptyList(), null, null, 1)))
    val e2 = runCatching { Publisher(sd).build(emptyZip) }.exceptionOrNull()
    check("a failure mid-build deletes the new directory", e2 is PublishException && "Nothing was changed" in e2.message!! &&
        projects.list()!!.filter { it.startsWith("Store_") }.toSet() == setOf(built.storeDir))
    check("catalog: extra must be an object", runCatching { Catalog.parse("""{"extra":[1]}""") }.exceptionOrNull() is CatalogException)
    check("catalog: PIN must be digits", runCatching { Catalog.parse("""{"pin":"12a"}""") }.exceptionOrNull() is CatalogException)
    check("catalog: empty PIN is no PIN", Catalog.parse("""{"pin":""}""").pin == null)
    fun strictOk(t: String) = runCatching { strictJsonObject(t) }.isSuccess
    check("strict JSON: trailing text rejected", !strictOk("{}ffg") && !strictOk("{\"a\":1} x") && !strictOk("{}{}"))
    check("strict JSON: arrays and scalars rejected", !strictOk("[1]") && !strictOk("3") && !strictOk("\"s\""))
    check("strict JSON: whitespace around is fine", strictOk("  {\"a\": [1, 2]}  \n") && strictOk("{}"))
    check("catalog.json with trailing text is rejected", runCatching { Catalog.parse("""{"storeName":"x"} junk""") }.exceptionOrNull() is CatalogException)

    // ---- small units ----
    check("slug", slug("AC/DC Player!") == "ac-dc-player" && slug("✨✨") == "product")
    check("uniqueName", uniqueName("a.png", setOf("a.png", "a-2.png")) == "a-3.png")
    check("deleteTree doesn't follow symlinks", run {
        val d = File(sd, "del").apply { mkdirs() }; Files.createSymbolicLink(File(d, "l").toPath(), outside.toPath())
        deleteTree(d); !d.exists() && outside.readText() == "OUTSIDE"
    })

    // --- the accessible version: a second page, unless the store's template is already it ---
    val accRoot = File(sd, "CodeOnTheGoProjects/${built.storeDir}")
    check("the accessible page is written, and listed so verify checks it", File(accRoot, ACCESSIBLE_PAGE).isFile && ACCESSIBLE_PAGE in built.files)
    check("the store page links to it, first, in its own region", Regex("""<body>\{?%?[^<]*<nav class=acc-nav aria-label="Page versions"><a class=acc href="/f/accessible.html">Accessible version</a></nav>""").containsMatchIn(html) || html.contains("<body><nav class=acc-nav aria-label=\"Page versions\"><a class=acc href=\"/f/accessible.html\">Accessible version</a></nav>"))
    val accHtml = File(accRoot, ACCESSIBLE_PAGE).readText()
    check("the accessible page links back, in its own region", "<nav aria-label=\"Page versions\"><a class=alt href=\"/\">Standard version</a></nav>" in accHtml)
    check("the store page's pictures are labelled, and its strips focusable", Regex("""alt="[^"]+, picture 1 of \d+"""").containsMatchIn(html) && "tabindex=0 role=region" in html)
    check("the products are the page's main region", "<main class=n>" in html && "</main>" in html)
    check("…and holds every product, with its downloads", catalog.products.filter { it.published }.all { "<h2 id=\"name-" in accHtml && it.name.replace("<", "&lt;").replace(">", "&gt;") in accHtml })
    val asOwn = Publisher(sd).build(catalog.copy(templateOverride = Templates.ACCESSIBLE))
    val ownRoot = File(sd, "CodeOnTheGoProjects/${asOwn.storeDir}")
    val ownHtml = File(ownRoot, "index.html").readText()
    check("with Clear Access as the store's own template, no second page", !File(ownRoot, ACCESSIBLE_PAGE).exists() && ACCESSIBLE_PAGE !in asOwn.files)
    check("…and no links between the two", "Accessible version" !in ownHtml && "Standard version" !in ownHtml)

    // --- what the page learns about each product and the store ---
    val abiApk = File(Files.createTempDirectory("abi").toFile(), "native.apk")
    java.util.zip.ZipOutputStream(abiApk.outputStream()).use { z ->
        for (e in listOf("AndroidManifest.xml", "lib/arm64-v8a/libx.so", "lib/armeabi-v7a/libx.so", "lib/arm64-v8a/liby.so")) { z.putNextEntry(java.util.zip.ZipEntry(e)); z.write(1); z.closeEntry() }
    }
    check("an APK's architectures come from its lib/ folders", apkAbis(abiApk) == listOf("arm64-v8a", "armeabi-v7a"))
    check("…and an APK without native code runs on all phones", apkAbis(File(root, "$p1/$slug1.apk")).isEmpty() && abiLabel(emptyList()) == "all phones")
    check("the buyer labels for each architecture",
        abiLabel(listOf("arm64-v8a", "armeabi-v7a")) == "32- and 64-bit phones" && abiLabel(listOf("arm64-v8a")) == "64-bit phones" && abiLabel(listOf("armeabi-v7a")) == "32-bit phones")
    check("Android versions as buyers know them", androidLabel(28) == "Android 9 or later" && androidLabel(36) == "Android 16 or later" && androidLabel(99) == null)
    abiApk.parentFile?.deleteRecursively()
    val probe = "{{lang}}|{{publishedLabel}}|{% for p in products %}{{p.slug}},{{p.dateAddedIso}},{% if p.apk is not empty %}{{p.apk.versionName}},{{p.apk.androidLabel}},{{p.apk.abiLabel}},{{p.apk.sizeBytes}}{% endif %},{% if p.sourceZip is not empty %}{{p.sourceZip.sizeLabel}}{% endif %};{% endfor %}"
    val facts = Publisher(sd, apkFacts = { ApkFacts("2.1", 21, "org.example.todo", 28) })
        .build(catalog.copy(templateOverride = probe, extra = org.json.JSONObject().put("lang", "sw")))
    val probed = File(sd, "CodeOnTheGoProjects/${facts.storeDir}/index.html").readText()
    check("the page's language comes from Extra JSON's lang (probe: ${probed.take(120)})", probed.startsWith("sw|"))
    check("each product has its slug, ISO date, and the APK's version and needs", "todo-app-2,20" in probed && ",2.1,Android 9 or later,all phones,${apkBytes.size}," in probed)
    check("…and the source zip's size", Regex(""",\d+(\.\d)? (B|KB|MB);""").containsMatchIn(probed))
    val noLang = File(sd, "CodeOnTheGoProjects/${Publisher(sd).build(catalog.copy(templateOverride = "{{lang}}")).storeDir}/index.html").readText()
    check("without it, the phone's language", noLang == java.util.Locale.getDefault().toLanguageTag())
    check("the default page shows the facts, the source's size, and dates as <time>",
        "<p class=facts>For all phones</p>" in html && Regex("""Download source<small>\d""").containsMatchIn(html) && "<time datetime=\"" in html)

    // --- picture sizes, read from each format's own header ---
    val hdrDir = Files.createTempDirectory("hdr").toFile()
    fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    fun be32(n: Int) = bytes(n ushr 24 and 255, n ushr 16 and 255, n ushr 8 and 255, n and 255)
    fun le16(n: Int) = bytes(n and 255, n ushr 8 and 255)
    fun le24(n: Int) = bytes(n and 255, n ushr 8 and 255, n ushr 16 and 255)
    fun file(name: String, vararg parts: ByteArray) = File(hdrDir, name).apply { writeBytes(parts.fold(ByteArray(0)) { a, b -> a + b } + ByteArray(64)) }
    val png = file("a.png", bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), be32(13), "IHDR".toByteArray(), be32(640), be32(480))
    val gif = file("a.gif", "GIF89a".toByteArray(), le16(320), le16(200))
    val jpg = file("a.jpg", bytes(0xff, 0xd8), bytes(0xff, 0xe0, 0, 16), ByteArray(14), bytes(0xff, 0xc0, 0, 17, 8), bytes(1080 ushr 8, 1080 and 255), bytes(1920 ushr 8, 1920 and 255))
    val vp8x = file("x.webp", "RIFF".toByteArray(), be32(0), "WEBPVP8X".toByteArray(), be32(10), ByteArray(4), le24(799), le24(599))
    val vp8l = file("l.webp", "RIFF".toByteArray(), be32(0), "WEBPVP8L".toByteArray(), be32(0), bytes(0x2f),
        run { val w = 100 - 1; val h = 50 - 1; bytes(w and 255, (w ushr 8 and 0x3f) or (h and 3 shl 6), h ushr 2 and 255, h ushr 10 and 0x0f) })
    val vp8 = file("s.webp", "RIFF".toByteArray(), be32(0), "WEBPVP8 ".toByteArray(), be32(0), ByteArray(6), le16(300), le16(150))
    check("sizes from PNG, GIF and JPEG headers", imageSize(png) == 640 to 480 && imageSize(gif) == 320 to 200 && imageSize(jpg) == 1920 to 1080)
    check("…and from all three kinds of WebP", imageSize(vp8x) == 800 to 600 && imageSize(vp8l) == 100 to 50 && imageSize(vp8) == 300 to 150)
    check("anything else has no size", imageSize(File(hdrDir, "n.txt").apply { writeText("not a picture") }) == null)

    // --- the documented Extra JSON keys, checked before any template sees them ---
    check("accents: #rgb and #rrggbb, nothing else", accentOf("#1E8") == "#11ee88" && accentOf("#1E88E5") == "#1e88e5" && accentOf("red") == null && accentOf("#fff;}body{display:none") == null && accentOf(42) == null)
    fun lum(h: String): Double { fun l(c: Int) = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * l(h.substring(1, 3).toInt(16)) + 0.7152 * l(h.substring(3, 5).toInt(16)) + 0.0722 * l(h.substring(5, 7).toInt(16)) }
    fun ratio(a: String, b: String) = (maxOf(lum(a), lum(b)) + 0.05) / (minOf(lum(a), lum(b)) + 0.05)
    val colours = (0 until 4096).map { "#%02x%02x%02x".format((it shr 8 and 15) * 17, (it shr 4 and 15) * 17, (it and 15) * 17) }
    val worst = colours.minOf { ratio(it, inkFor(it)) }
    check("text on any accent reaches WCAG's 4.5 to 1 (worst of 4,096: ${"%.2f".format(worst)})", worst >= 4.5)
    check("links: only http and https", webHref("https://example.org/a") == "https://example.org/a" && webHref("javascript:alert(1)") == null && webHref("http://x y") == null)
    val contact = contactOf(org.json.JSONObject("""{"name":"Ada","email":"ada@example.org","phone":"+1 (555) 010-0200","url":"javascript:alert(1)"}"""))
    check("contact: email and phone become links, a script address doesn't",
        contact?.get("emailHref") == "mailto:ada@example.org" && contact["phoneHref"] == "tel:+15550100200" && contact["urlHref"] == null && contact["name"] == "Ada")
    check("contact: nothing usable, no contact", contactOf(org.json.JSONObject("""{"email":"not an address"}""")) == null)

    // --- all of it on a published page ---
    val withExtras = catalog.copy(
        extra = org.json.JSONObject("""{"announcement":"New this week: version 2","contact":{"name":"Ada","email":"ada@example.org"},"accent":"#1e88e5"}"""),
        products = catalog.products.mapIndexed { i, pr -> if (i == 0) pr.copy(carousel = pr.carousel + "Pictures/size-test.png",
            extra = org.json.JSONObject("""{"whatsNew":"Faster sync.","license":"GPL-3.0-or-later","author":"Ada","website":"https://example.org"}""")) else pr })
    png.copyTo(File(sd, "Pictures/size-test.png").apply { parentFile?.mkdirs() }, overwrite = true)
    File(sd, "Pictures/size-test.png").appendBytes(ByteArray(12_000))   // over 10 KB, so it's copied, not inlined
    val extrasRoot = File(sd, "CodeOnTheGoProjects/${Publisher(sd, version = "1.0.0").build(withExtras).storeDir}")
    val extrasHtml = File(extrasRoot, "index.html").readText()
    check("the announcement, above the products, as a labelled region", "<p class=news role=region aria-label=\"Announcement\">New this week: version 2</p><main" in extrasHtml)
    check("the accent, with black or white text", "<style>:root{--accent:#1e88e5;--accent-ink:#000000}</style>" in extrasHtml)
    check("what's new, and the byline", "<b>What's new:</b> Faster sync.</p>" in extrasHtml && "By Ada &middot; License: GPL-3.0-or-later &middot; <a href=\"https://example.org\">Website</a>" in extrasHtml)
    check("the contact, and the plugin's version, linked to App Dev for All's plugins page, at the foot", "Questions? Ask Ada: <a href=\"mailto:ada@example.org\">ada@example.org</a>" in extrasHtml && "Made with <a href=\"https://www.appdevforall.org/plugins\" style=\"color:inherit\">Share Your Apps 1.0.0</a>" in extrasHtml)
    check("a picture's size on its tag", "width=\"640\" height=\"480\"" in extrasHtml)
    check("the accessible page has the rest, but keeps its own colours",
        File(extrasRoot, ACCESSIBLE_PAGE).readText().let { "New this week" in it && "Faster sync." in it && "--accent" !in it })
    check("a product's Extra JSON is kept in catalog.json", Catalog.parse(withExtras.toJson().toString()).products[0].extra.optString("license") == "GPL-3.0-or-later")
    hdrDir.deleteRecursively()

    // --- posters: a JPEG of an early frame, so the page never has to preload videos ---
    val withPoster = Publisher(sd, posters = { _, out -> out.writeBytes(ByteArray(2048) { 7 }); true }).build(catalog)
    val posterRoot = File(sd, "CodeOnTheGoProjects/${withPoster.storeDir}")
    val posterHtml = File(posterRoot, "index.html").readText()
    check("a video carries its poster", Regex("""<video src="/f/$p1/demo\.mp4" controls preload="none" poster="/f/$p1/demo-poster\.jpg" aria-label="[^"]+">""").containsMatchIn(posterHtml))
    check("…the poster is written, and listed so verify checks it", File(posterRoot, "$p1/demo-poster.jpg").length() == 2048L && "$p1/demo-poster.jpg" in withPoster.files)
    for ((label, maker) in listOf<Pair<String, (File, File) -> Boolean>>(
        "fails" to { _, _ -> false }, "throws" to { _, _ -> throw RuntimeException("no decoder") })) {
        val b = runCatching { Publisher(sd, maker).build(catalog) }
        val h = b.getOrNull()?.let { File(sd, "CodeOnTheGoProjects/${it.storeDir}/index.html").readText() } ?: ""
        check("a poster maker that $label doesn't stop the publish, and leaves no poster", b.isSuccess && "poster=" !in h && "demo-poster.jpg" !in (b.getOrNull()?.files ?: emptyList()))
    }

    // --- the header video: its poster, played on tap, never autoplayed ---
    File(sd, "Movies").mkdirs(); File(sd, "Movies/header.mp4").writeBytes(ByteArray(20_000) { 3 })
    val withHeader = Publisher(sd, posters = { _, out -> out.writeBytes(ByteArray(2048) { 7 }); true }).build(catalog.copy(headerImage = "Movies/header.mp4"))
    val headerHtml = File(sd, "CodeOnTheGoProjects/${withHeader.storeDir}/index.html").readText()
    val headerTag = Regex("""<video src="[^"]*header\.mp4"[^>]*>""").find(headerHtml)?.value.orEmpty()
    check("a header video shows its poster, and plays on tap ($headerTag)",
        "poster=\"/f/header-poster.jpg\"" in headerTag && "controls" in headerTag && "preload=\"none\"" in headerTag && "autoplay" !in headerTag)
    check("…its poster is written, and listed so verify checks it", "header-poster.jpg" in withHeader.files)

    // --- the template library (design, Appendix D.1): extends, blocks, macros and includes ---
    val lib = File(sd, TemplateLibrary.FOLDER).apply { mkdirs() }
    File(lib, "macros.peb").writeText("{% macro badge(t) %}<b class=badge>{{ t }}</b>{% endmacro %}")
    File(lib, "parts").mkdirs(); File(lib, "parts/note.peb").writeText("<p class=note>Made nearby</p>")
    fun page(t: String): String { val b = Publisher(sd).build(catalog.copy(templateOverride = t)); return File(sd, "CodeOnTheGoProjects/${b.storeDir}/index.html").readText() }
    val plain = page(Templates.DEFAULT_CATALOG)
    check("extending the default, unchanged, gives exactly the default page", page("{% extends \"default\" %}") == plain)
    val newCard = page("{% extends \"default\" %}{% block card %}<article class=mine>{{ p.name }}</article>{% endblock %}")
    check("replacing only the card changes only the cards", catalog.products.filter { it.apkPublished || it.sourceZipPublished }.all { "<article class=mine>${it.name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</article>" in newCard } &&
        plain.substringBefore("<main") == newCard.substringBefore("<main") && plain.substringAfter("</main>") == newCard.substringAfter("</main>"))
    val newDownloads = page("{% extends \"default\" %}{% block downloads %}<p class=dl>{{ p.name }}</p>{% endblock %}")
    check("…and replacing only the downloads keeps the rest of each card", "<p class=dl>" in newDownloads && "Download APK" !in newDownloads && "class=facts" in newDownloads)
    val moreStyle = page("{% extends \"default\" %}{% block styles %}{{ parent() }}<style>.x{color:red}</style>{% endblock %}")
    check("parent() adds to the stylesheet rather than replacing it", moreStyle.contains("<style>.x{color:red}</style>") && plain.substringBefore("</style>") in moreStyle)
    check("macros come from the library, by import", "<b class=badge>new</b>" in page("{% import \"macros\" %}{{ badge('new') }}"))
    check("…and by from … import", "<b class=badge>hi</b>" in page("{% from \"macros.peb\" import badge as b %}{{ b('hi') }}"))
    check("an include finds a library file in a folder", "<p class=note>Made nearby</p>" in page("{% include \"parts/note\" %}"))
    val noTemplate = runCatching { page("{% include \"nowhere\" %}") }.exceptionOrNull()
    check("a missing template stops the publish, naming it (${noTemplate?.message?.take(70)})", noTemplate != null && "nowhere" in noTemplate.message.orEmpty())
    File(sd, "CodeOnTheGoProjects/secret.peb").writeText("SECRET")
    for (bad in listOf("../secret", "/etc/hosts", "parts/../../secret", "parts\\note", "")) {
        val e = runCatching { page("{% include \"$bad\" %}") }
        check("a name that could leave the library is refused: \"$bad\"", e.isFailure && "SECRET" !in (e.getOrNull() ?: ""))
    }
    val withAcc = Publisher(sd).build(catalog.copy(templateOverride = "{% extends \"default\" %}{% block card %}<p>{{ p.name }}</p>{% endblock %}"))
    check("the accessible page is unaffected by the store's template", File(sd, "CodeOnTheGoProjects/${withAcc.storeDir}/accessible.html").readText().contains("Standard version"))
    lib.deleteRecursively(); File(sd, "CodeOnTheGoProjects/secret.peb").delete()

    // --- built-in filters and functions (design, Appendix D.2) ---
    val strings = mapOf("strings" to mapOf("sw" to mapOf("download" to "Pakua"), "en" to mapOf("download" to "Download", "html" to "<b>x</b>")))
    fun r(t: String, lang: String = "sw") = render(t, mapOf("lang" to lang, "extra" to strings))
    check("t() uses the store's language", r("{{ t('download') }}") == "Pakua")
    check("…its main language, for a regional one", r("{{ t('download') }}", "sw-TZ") == "Pakua")
    check("…then English", r("{{ t('download') }}", "fr") == "Download")
    check("…then the fallback, then the key", r("{{ t('buy', 'Get it') }}") == "Get it" && r("{{ t('buy') }}") == "buy")
    check("…and its text is escaped, like any other", r("{{ t('html') }}", "en") == "&lt;b&gt;x&lt;/b&gt;")
    check("plural(): one, and other, with # as the number", r("{{ plural(1, '# app', '# apps') }}") == "1 app" && r("{{ plural(3, '# app', '# apps') }}") == "3 apps" &&
        r("{{ plural(0, '# app', '# apps') }}") == "0 apps")
    check("markdown: bold, italic, code, and a paragraph each", r("{{ 'A **bold** and *it* and `x<y`\n\nNext'|markdown }}") ==
        "<p>A <strong>bold</strong> and <em>it</em> and <code>x&lt;y</code></p><p>Next</p>")
    check("…a list", r("{{ '- one\n- two'|markdown }}") == "<ul><li>one</li><li>two</li></ul>")
    check("…links to https and mailto only", r("{{ '[site](https://example.org) [mail](mailto:a@b.c)'|markdown }}") ==
        "<p><a href=\"https://example.org\">site</a> <a href=\"mailto:a@b.c\">mail</a></p>")
    val evil = r("{{ '<script>alert(1)</script> [x](javascript:alert(1)) [y](https://e.org\"onmouseover=alert(1))'|markdown }}")
    val hrefs = Regex("href=\"([^\"]*)\"").findAll(evil).map { it.groupValues[1] }.toList()
    check("…and no HTML, script or javascript: link survives, nor a quote in any link ($evil)",
        "<script" !in evil && hrefs.none { it.startsWith("javascript") || "&quot;" in it || "&#39;" in it })
    val code1 = r("{{ qr('http://192.168.1.5:6175/') }}"); val code2 = r("{{ qr('http://192.168.1.5:6175/', 'Scan to open the store') }}")
    check("qr(): an inline SVG image, labelled", code1.startsWith("<svg class=qr") && "role=\"img\"" in code1 && "aria-label=\"QR code\"" in code1 && "aria-label=\"Scan to open the store\"" in code2)
    check("…differing with its text, and nothing for none", r("{{ qr('other') }}") != code1 && r("{{ qr('') }}") == "")
    check("bytes: like the store's size labels", r("{{ 5242880|bytes }}") == "5.0 MB" && r("{{ 532|bytes }}") == "532 B")
    check("slugify: the store's slug rule", r("{{ 'Great Scott 2'|slugify }}") == slug("Great Scott 2"))
    check("Pebble's own abbreviate still works, for truncating", r("{{ 'Hello there, world'|abbreviate(10) }}") == "Hello t...")

    // --- picture descriptions and video captions ---
    val withMedia = catalog.products.indexOfFirst { pr -> pr.carousel.any { it.endsWith("demo.mp4") } && pr.carousel.any { it.endsWith("large shot.png") } }
    check("the test catalog has a product with a picture and a video", withMedia >= 0)
    val mp = catalog.products[withMedia]
    val shot = mp.carousel.first { it.endsWith("large shot.png") }; val vid = mp.carousel.first { it.endsWith("demo.mp4") }
    File(sd, "Movies").mkdirs(); File(sd, "Movies/demo.vtt").writeText("WEBVTT\n\n00:00.000 --> 00:02.000\nHello\n")
    val described = catalog.copy(products = catalog.products.mapIndexed { k, pr -> if (k != withMedia) pr else pr.copy(mediaText = mapOf(
        shot to MediaText("The chat screen, with <b>two</b> messages"), vid to MediaText(null, "Movies/demo.vtt"))) })
    val dBuild = Publisher(sd).build(described)
    val dHtml = File(sd, "CodeOnTheGoProjects/${dBuild.storeDir}/index.html").readText()
    check("a description becomes the picture's alt text, escaped", "alt=\"The chat screen, with &lt;b&gt;two&lt;/b&gt; messages\"" in dHtml)
    val others = mp.carousel.withIndex().filter { it.value != shot && !it.value.endsWith(".mp4") && Media.kind(File(it.value).name) == "image" }
    val mpName = mp.name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    check("…while an undescribed picture keeps its place: \"${mp.name}, picture n of ${mp.carousel.size}\"",
        others.all { (i, _) -> "alt=\"$mpName, picture ${i + 1} of ${mp.carousel.size}\"" in dHtml })
    val track = Regex("""<track kind="captions" src="([^"]+)" srclang="[^"]*" label="Captions" default>""").find(dHtml)
    check("a video's captions become a captions track", track != null)
    val trackFile = track?.groupValues?.get(1)?.removePrefix("/f/").orEmpty()
    check("…with the captions file copied beside the video, and listed for verifying", File(sd, "CodeOnTheGoProjects/${dBuild.storeDir}/$trackFile").readText().startsWith("WEBVTT") && trackFile in dBuild.files)
    val rt = Catalog.parse(described.toJson().toString()).products[withMedia].mediaText
    check("descriptions and captions survive saving and loading", rt[shot]?.description == "The chat screen, with <b>two</b> messages" && rt[vid]?.captions == "Movies/demo.vtt")
    val noVtt = runCatching { Publisher(sd).build(described.copy(products = described.products.mapIndexed { k, pr -> if (k != withMedia) pr else pr.copy(mediaText = mapOf(vid to MediaText(null, "Movies/nope.vtt"))) })) }.exceptionOrNull()
    check("a missing captions file stops the publish", noVtt is PublishException && "captions file not found" in noVtt.message.orEmpty())
    File(sd, "Movies/demo.srt").writeText("1\n")
    val srt = runCatching { Publisher(sd).build(described.copy(products = described.products.mapIndexed { k, pr -> if (k != withMedia) pr else pr.copy(mediaText = mapOf(vid to MediaText(null, "Movies/demo.srt"))) })) }.exceptionOrNull()
    check("…and so does one that isn't WebVTT", srt is PublishException && "WebVTT" in srt.message.orEmpty())

    // --- tier-3 help: every button's page and section exist, offline ---
    val docs = listOf(File("src/main/assets/docs"), File("../../lsp/assets/docs")).firstOrNull { it.isDirectory }
    check("the plugin's tier-3 pages are there (${docs?.path})", docs != null && File(docs, "index.html").isFile)
    if (docs != null) {
        val broken = HelpPages.buttons.values.map { it.second }.distinct().filter { uri ->
            val f = File(docs, uri.substringBefore('#')); val frag = uri.substringAfter('#', "")
            !f.isFile || (frag.isNotEmpty() && "id=\"$frag\"" !in f.readText()) }
        check("every help button's page and section exist (broken: $broken)", broken.isEmpty() && HelpPages.buttons.size >= 21)
        val remote = docs.listFiles()!!.filter { Regex("""(src|href)="(https?:)?//""").containsMatchIn(it.readText()) }.map { it.name }
        check("…and the pages work offline, loading nothing from the internet (loading: $remote)", remote.isEmpty())
    }

    // --- slugs (design, Files): two products can never share a folder ---
    check("slugs: the design's example", uniqueSlugs(listOf("Great Scott 2", "Great Scott", "Great Scott")) == listOf("great-scott-2", "great-scott", "great-scott-3"))
    check("…and the review's, where only earlier products count", uniqueSlugs(listOf("Great Scott", "Great Scott", "Great Scott 2")) == listOf("great-scott", "great-scott-2", "great-scott-2-2"))
    check("…never two the same, however many clash", uniqueSlugs(List(12) { i -> if (i % 3 == 0) "App" else "App ${i / 3 + 1}" }).let { it.toSet().size == it.size })

    // --- unsigned APKs: buyers can't install them ---
    val signedDir = Files.createTempDirectory("sig").toFile()
    fun zipOf(vararg entries: String) = java.io.ByteArrayOutputStream().also { b -> java.util.zip.ZipOutputStream(b).use { z ->
        entries.forEach { z.putNextEntry(java.util.zip.ZipEntry(it)); z.write(ByteArray(100) { 7 }); z.closeEntry() } } }.toByteArray()
    val unsignedApk = File(signedDir, "app-release-unsigned.apk").apply { writeBytes(zipOf("classes.dex", "AndroidManifest.xml")) }
    val v1Apk = File(signedDir, "v1.apk").apply { writeBytes(zipOf("classes.dex", "META-INF/MANIFEST.MF", "META-INF/CERT.RSA")) }
    // v2 or later: the APK Signing Block's marker ends just before the central directory.
    val plainZip = zipOf("classes.dex", "AndroidManifest.xml")
    val eocd = plainZip.size - 22
    val cdOffset = java.nio.ByteBuffer.wrap(plainZip, eocd + 16, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
    val magic = "APK Sig Block 42".toByteArray()
    val v2Bytes = plainZip.copyOfRange(0, cdOffset) + magic + plainZip.copyOfRange(cdOffset, plainZip.size)
    java.nio.ByteBuffer.wrap(v2Bytes, v2Bytes.size - 22 + 16, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(cdOffset + 16)
    val v2Apk = File(signedDir, "v2.apk").apply { writeBytes(v2Bytes) }
    check("an APK with no signature is unsigned", !apkSigned(unsignedApk))
    check("…one signed the v1 way is signed", apkSigned(v1Apk))
    check("…and one signed the v2 way, by its signing block", apkSigned(v2Apk))
    check("…and a file that isn't a zip at all isn't", !apkSigned(File(signedDir, "junk.apk").apply { writeBytes(ByteArray(500) { 3 }) }))
    val projUnsigned = File(sd, "CodeOnTheGoProjects/Unsigned/app/build/outputs/apk/release").apply { mkdirs() }
    unsignedApk.copyTo(File(projUnsigned, "app-release-unsigned.apk"), overwrite = true)
    val withUnsigned = catalog.copy(products = catalog.products.map { if (it.apk != null) it.copy(apk = "CodeOnTheGoProjects/Unsigned/app/build/outputs/apk/release/app-release-unsigned.apk") else it })
    val unsignedBuild = runCatching { Publisher(sd).build(withUnsigned) }
    check("an unsigned APK still publishes — as the demo's placeholders must — with a warning (${unsignedBuild.getOrNull()?.warnings?.firstOrNull()?.take(70)})",
        unsignedBuild.isSuccess && unsignedBuild.getOrNull()!!.warnings.any { "isn't signed" in it })
    check("…and a signed one publishes without", Publisher(sd).build(catalog).warnings.isEmpty())
    signedDir.deleteRecursively()

    // --- a source-only product with "Publish the APK" left ticked, and no APK chosen ---
    val srcOnly = catalog.products.first { it.apk == null && it.sourceZipPublished }
    val ticked = catalog.copy(products = catalog.products.map { if (it === srcOnly) it.copy(apkPublished = true) else it })
    val tickedBuild = runCatching { Publisher(sd).build(ticked) }
    check("a source-only product with Publish the APK left ticked still publishes (${tickedBuild.exceptionOrNull()?.message ?: "ok"})", tickedBuild.isSuccess)

    // --- an unreadable catalog.json is kept, not overwritten ---
    val dataDir = Files.createTempDirectory("data").toFile()
    val cf = File(dataDir, "catalog.json")
    check("no catalog.json: start empty, no problem", loadCatalogFile(cf).let { it.catalog == null && it.problem == null })
    cf.writeText(catalog.toJson().toString())
    check("a good catalog.json loads", loadCatalogFile(cf).catalog?.storeName == catalog.storeName)
    File(dataDir, "catalog.json.bad").writeText("an older bad file")
    cf.writeText("{ this isn't json")
    val badLoad = loadCatalogFile(cf)
    check("an unreadable one starts empty, and says where the file went",
        badLoad.catalog == null && badLoad.problem?.contains("catalog.json.bad") == true)
    check("…it's kept as catalog.json.bad, replacing an older one, and catalog.json is gone",
        File(dataDir, "catalog.json.bad").readText() == "{ this isn't json" && !cf.exists())
    dataDir.deleteRecursively()

    // --- noticing a catalog.json changed by something else ---
    val watched = File(Files.createTempDirectory("watch").toFile(), "catalog.json")
    watched.writeText(catalog.toJson().toString())
    val atLoad = FileStamp.of(watched)
    check("an unchanged file needs nothing, even with unsaved edits",
        reloadDecision(atLoad, FileStamp.of(watched), unsaved = false) == Reload.NONE && reloadDecision(atLoad, FileStamp.of(watched), unsaved = true) == Reload.NONE)
    watched.writeText(catalog.copy(storeName = "Replaced by hand").toJson().toString())
    check("a replaced file is noticed", FileStamp.of(watched) != atLoad)
    check("…and reloaded", reloadDecision(atLoad, FileStamp.of(watched), unsaved = false) == Reload.RELOAD)
    check("…unless there are unsaved edits, which are kept", reloadDecision(atLoad, FileStamp.of(watched), unsaved = true) == Reload.CONFLICT)
    watched.delete()
    check("a deleted file counts as changed", FileStamp.of(watched) == null && reloadDecision(atLoad, null, unsaved = false) == Reload.RELOAD)
    watched.parentFile?.deleteRecursively()

    // --- a publish that never finished: its directory is spared, not deleted as a leftover ---
    val projectsDir = File(sd, "CodeOnTheGoProjects")
    projectsDir.listFiles()!!.filter { it.name.startsWith("Store_") }.forEach { deleteTree(it) }
    for (n in listOf("Store_11111111", "Store_22222222", "Store_33333333")) File(projectsDir, "$n/index.html").apply { parentFile?.mkdirs(); writeText("x") }
    val interrupted = catalog.copy(storeDir = "Store_11111111", pendingDir = "Store_22222222")
    val rebuilt = Publisher(sd).build(interrupted)
    check("the directory being served survives the next publish", File(projectsDir, "Store_11111111").isDirectory)
    check("so does one a publish handed to the server but never finished", File(projectsDir, "Store_22222222").isDirectory)
    check("a true leftover is still removed", !File(projectsDir, "Store_33333333").exists())
    Publisher(sd).removeOthers(setOf(rebuilt.storeDir))
    check("once a publish succeeds, every other store directory is removed",
        projectsDir.listFiles()!!.filter { it.name.startsWith("Store_") }.map { it.name } == listOf(rebuilt.storeDir))
    check("catalog.json keeps pendingDir", Catalog.parse(interrupted.toJson().toString()).pendingDir == "Store_22222222")
    check("and it's null when absent", Catalog.parse(catalog.toJson().toString()).pendingDir == null)

    sd.deleteRecursively()
    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED")
    return failedNames.toList()
}

/** The JUnit test: runs the checks above, and fails naming them if any fail. */
class PublishChecks {
    @org.junit.Test
    fun everyCheckPasses() {
        val failed = runChecks()
        org.junit.Assert.assertTrue("Failed checks: " + failed.joinToString("; "), failed.isEmpty())
    }
}
