package org.appdevforall.shareyourapps

import io.pebbletemplates.pebble.PebbleEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

class PublishException(message: String) : Exception(message)

/** What a publish built: the new Store directory's name, and every file in it except index.html. */
/** A finished publish: its Store directory, every file written, and warnings for the owner, such as unsigned APKs. */
class Built(val storeDir: String, val files: List<String>, val warnings: List<String> = emptyList())

/** The accessible version of the store page, at the Store directory's top, served as /f/accessible.html. */
const val ACCESSIBLE_PAGE = "accessible.html"

/**
 * Builds a complete new Store directory next to the one being served (steps 1–5 of the
 * design's publish). If anything fails — including running out of space — the new directory
 * is deleted and the old one is untouched.
 */
/**
 * [posters], if given, writes a poster image for a video: a JPEG of an early frame, shown
 * until the buyer taps play, so the page never has to preload videos. It returns false if it
 * couldn't; the video then simply has no poster. The Android side supplies it; tests may not.
 */
class Publisher(
    private val sdcard: File,
    private val posters: ((video: File, poster: File) -> Boolean)? = null,
    /** Reads an APK's version and minimum Android version; the Android side supplies it, tests may not. */
    private val apkFacts: ((apk: File) -> ApkFacts?)? = null,
    /** The plugin's own version, for a "Made with Share Your Apps" line; null leaves the number out. */
    private val version: String? = null,
    /** Counts the files handled as the store is built, for the Share Your Apps tab; null counts nothing. */
    private val progress: PublishProgress? = null,
) {
    /** The owner's template library (design, Appendix D.1): what templates can extend, include and import. */
    private val library = File(sdcard, TemplateLibrary.FOLDER)
    /** What the owner should know, but that doesn't stop a publish; cleared by each [build]. */
    private val warnings = mutableListOf<String>()


    companion object {
        const val INLINE_LIMIT = 10_240L
        private val STORE_NAME = Regex("""Store_[0-9]{8}""")
    }

    val projects = File(sdcard, "CodeOnTheGoProjects")

    fun build(c: Catalog): Built {
        warnings.clear()
        check(c)
        // Spare the directory being served, and one handed to the server by a publish that never finished.
        removeOthers(setOfNotNull(c.storeDir, c.pendingDir))
        val name = newStoreName()
        val root = File(projects, name)
        try {
            if (!root.mkdirs()) throw IOException("couldn't create ${root.path}")
            val written = mutableListOf<String>()
            val products = c.products.filter { it.published }
                .sortedWith(compareBy<Product>({ it.ordinal }, { it.dateAdded ?: "" }, { it.name }))
            val slugs = uniqueSlugs(products.map { it.name })
            // Every project's source files, listed before anything is copied — the same work as listing each just
            // before its zip, done earlier — so the progress line has its totals from the start.
            val sources = products.filter { it.sourceZipPublished }.associateWith { SourceFiles.collect(File(sdcard, it.project!!)) }
            progress?.let { pr ->
                val files = mutableListOf<File>()
                c.headerImage?.let { files += File(sdcard, it) }
                for (p in products) {
                    if (p.apkPublished) p.apk?.let { files += File(sdcard, it) }
                    for (rel in p.carousel) {
                        files += File(sdcard, rel)
                        if (Media.kind(File(rel).name) == "video") p.mediaText[rel]?.captions?.let { files += File(sdcard, it) }
                    }
                }
                // Source files' sizes come from the listing; asking again would cost a call per file.
                pr.filesTotal = files.size + sources.values.sumOf { it.size }
                pr.bytesTotal = files.sumOf { it.length() } + sources.values.sumOf { l -> l.sumOf { it.size } }
            }
            val productCtx = products.mapIndexed { i, p -> product(p, slugs[i], root, written, sources[p]) }
            progress?.doing = "making the store page"
            val now = java.time.Instant.now().toString()
            val ctx = mapOf(
                // The page's language: Extra JSON's "lang" if the owner set one, else the phone's.
                "lang" to ((c.extra.opt("lang") as? String)?.takeIf { it.isNotBlank() } ?: Locale.getDefault().toLanguageTag()),
                "publishedAt" to now,
                "publishedLabel" to dateLabel(now),
                "shareYourAppsVersion" to version,
                // Documented Extra JSON keys, checked here so templates can use them safely.
                "contact" to contactOf(c.extra.optJSONObject("contact")),
                "announcement" to plainText(c.extra.opt("announcement"), 300),
                "accent" to accentOf(c.extra.opt("accent")),
                "accentInk" to accentOf(c.extra.opt("accent"))?.let(::inkFor),
                "storeName" to c.storeName,
                "headerImage" to header(c.headerImage, root, written),
                "storeDescriptionHtml" to c.storeDescriptionHtml,
                "extra" to toPlain(c.extra),
                "products" to productCtx,
            )
            // The store's page, and — unless its template is already the accessible one — the
            // accessible version beside it, each linking to the other.
            val template = c.templateOverride ?: Templates.DEFAULT_CATALOG
            val accessibleToo = Templates.ACCESSIBLE_MARKER !in template
            File(root, "index.html").writeText(render(template, ctx + ("accessibleHref" to if (accessibleToo) "/f/$ACCESSIBLE_PAGE" else null), library))
            if (accessibleToo) {
                File(root, ACCESSIBLE_PAGE).writeText(render(Templates.ACCESSIBLE, ctx + ("standardHref" to "/"), library))
                written += ACCESSIBLE_PAGE
            }
            File(root, "README.txt").writeText(Templates.README)
            written += "README.txt"
            return Built(name, written, warnings.toList())
        } catch (e: Exception) {
            deleteTree(root)
            val why = if (e is PublishException) e.message else (e.message ?: e.toString())
            throw PublishException("Couldn't publish: $why. Nothing was changed; the store is still serving the previous version.")
        }
    }

    /** Step 1: everything the publish will read must exist. */
    private fun check(c: Catalog) {
        val problems = mutableListOf<String>()
        fun needFile(label: String, rel: String?) {
            if (rel == null) { problems += "$label isn't set"; return }
            if (rel.startsWith("/")) { problems += "$label must be relative to shared storage: $rel"; return }
            if (!File(sdcard, rel).isFile) problems += "$label not found: $rel"
        }
        for (p in c.products.filter { it.published }) {
            // An unchosen APK means no APK, as everywhere else: a source-only product with the tick left on is fine.
            if (p.apkPublished && p.apk != null) {
                needFile("\"${p.name}\" APK", p.apk)
                val f = File(sdcard, p.apk)
                // A warning, not a refusal: the store still publishes, as the demo's placeholder APKs need.
                if (f.isFile && !apkSigned(f)) warnings += "\"${p.name}\" APK isn't signed, so buyers can't install it: ${f.name}."
            }
            if (p.sourceZipPublished) {
                val proj = p.project
                if (proj == null) problems += "\"${p.name}\" project isn't set"
                else if (!File(sdcard, proj).isDirectory) problems += "\"${p.name}\" project not found: $proj"
            }
            p.carousel.forEach { needFile("\"${p.name}\" carousel file", it) }
            p.mediaText.values.mapNotNull { it.captions }.forEach { c ->
                needFile("\"${p.name}\" captions file", c)
                if (!c.lowercase().endsWith(".vtt")) problems += "\"${p.name}\" captions must be a WebVTT file, ending .vtt: $c"
            }
        }
        c.headerImage?.let { needFile("header image", it) }
        if (problems.isNotEmpty()) throw PublishException(problems.joinToString("\n"))
    }

    /** Step 2: Store directories left by an interrupted publish. */
    /** Deletes every Store directory except those in [keep]: leftovers from interrupted publishes, and old stores. */
    fun removeOthers(keep: Set<String>) {
        projects.listFiles()?.filter { it.isDirectory && STORE_NAME.matches(it.name) && it.name !in keep }
            ?.forEach { deleteTree(it) }
    }

    private fun newStoreName(): String {
        val rnd = SecureRandom()
        while (true) {
            val n = "Store_%08d".format(rnd.nextInt(100_000_000))
            if (!File(projects, n).exists()) return n
        }
    }

    private fun product(p: Product, slug: String, root: File, written: MutableList<String>, source: List<SourceEntry>?): Map<String, Any?> {
        progress?.doing = "copying ${p.name}'s files"
        val dir = "products/$slug"
        File(root, dir).mkdirs()
        val used = mutableSetOf<String>()
        fun place(src: File, preferred: String): String {
            val n = uniqueName(preferred, used)
            used += n
            src.copyTo(File(root, "$dir/$n"))
            progress?.handled(src.length())
            written += "$dir/$n"
            return "$dir/$n"
        }
        val apkFile = p.apk?.let { File(sdcard, it) }
        val apk = if (p.apkPublished && apkFile != null) {
            // Named after the product, so a buyer's downloads don't all arrive as app-release.apk.
            val src = link(place(apkFile, "$slug.apk"))
            val facts = try { apkFacts?.invoke(apkFile) } catch (e: Exception) { null }
            val abis = apkAbis(apkFile)
            mapOf(
                "src" to src, "sizeLabel" to sizeLabel(apkFile.length()), "sizeBytes" to apkFile.length(),
                "versionName" to facts?.versionName, "versionCode" to facts?.versionCode, "packageName" to facts?.packageName,
                "minSdk" to facts?.minSdk, "androidLabel" to facts?.minSdk?.let(::androidLabel),
                "abis" to abis, "abiLabel" to abiLabel(abis),
            )
        } else null
        val zip = if (p.sourceZipPublished) {
            val project = File(sdcard, p.project!!)
            val zipName = uniqueName("$slug-source.zip", used)
            used += zipName
            progress?.doing = "zipping ${p.name}'s source"
            val entries = source ?: SourceFiles.collect(project)
            SourceFiles.writeStoredZip(entries, File(root, "$dir/$zipName"), added = { progress?.handled(it) })
            progress?.doing = "copying ${p.name}'s files"
            written += "$dir/$zipName"
            val zipFile = File(root, "$dir/$zipName")
            mapOf("src" to link("$dir/$zipName"), "sizeLabel" to sizeLabel(zipFile.length()), "sizeBytes" to zipFile.length())
        } else null
        val kinds = p.carousel.map { Media.kind(File(it).name) }
        val carousel = p.carousel.mapIndexed { i, rel ->
            val f = File(sdcard, rel)
            val inline = f.length() <= INLINE_LIMIT
            val src = if (inline) dataUri(f).also { progress?.handled(f.length()) } else link(place(f, f.name))
            val type = Media.kind(f.name)
            val item = mutableMapOf<String, Any?>("src" to src, "type" to type)
            if (type == "image") imageSize(f)?.let { (w, h) -> item["width"] = w; item["height"] = h }
            // What it shows, for buyers who can't see it: the owner's description, or which one it is.
            val text = p.mediaText[rel]
            val noun = when (type) { "video" -> "video"; "audio" -> "sound"; else -> "picture" }
            item["description"] = text?.description?.trim()?.takeIf { it.isNotEmpty() }
            item["alt"] = item["description"] ?: "${p.name}, $noun ${i + 1} of ${kinds.size}"
            // A video's captions, beside it.
            text?.captions?.let { c -> if (type == "video") item["captions"] = link(place(File(sdcard, c), File(c).name)) }
            if (type == "video" && !inline && posters != null) poster(f, dir, root, used, written)?.let {
                item["poster"] = link(it)
                // A video's size is its poster's, which is a frame of it.
                imageSize(File(root, it))?.let { (w, h) -> item["width"] = w; item["height"] = h }
            }
            item
        }
        return mapOf(
            "name" to p.name, "ordinal" to p.ordinal, "slug" to slug,
            "dateAddedLabel" to dateLabel(p.dateAdded), "dateAddedIso" to p.dateAdded,
            "descriptionHtml" to p.descriptionHtml, "carousel" to carousel, "apk" to apk, "sourceZip" to zip,
            // The product's own Extra JSON, raw, and its documented keys, checked.
            "extra" to toPlain(p.extra),
            "whatsNew" to plainText(p.extra.opt("whatsNew"), 500),
            "license" to plainText(p.extra.opt("license"), 80),
            "author" to plainText(p.extra.opt("author"), 80),
            "website" to plainText(p.extra.opt("website"), 300),
            "websiteHref" to webHref(p.extra.opt("website")),
        )
    }

    /** Writes [video]'s poster beside it and returns its path, or null if none could be made. */
    private fun poster(video: File, dir: String, root: File, used: MutableSet<String>, written: MutableList<String>): String? {
        val n = uniqueName(video.nameWithoutExtension + "-poster.jpg", used)
        val out = File(root, "$dir/$n")
        val made = try { posters!!(video, out) && out.isFile && out.length() > 0 } catch (e: Exception) { false }
        if (!made) { out.delete(); return null }
        used += n
        written += "$dir/$n"
        return "$dir/$n"
    }

    private fun header(rel: String?, root: File, written: MutableList<String>): Map<String, Any?> {
        if (rel == null) {
            val b64 = Base64.getEncoder().encodeToString(Templates.DEFAULT_ICON_SVG.toByteArray())
            return mapOf("src" to "data:image/svg+xml;base64,$b64", "type" to "image")
        }
        val f = File(sdcard, rel)
        val src = if (f.length() <= INLINE_LIMIT) dataUri(f).also { progress?.handled(f.length()) } else {
            val name = "header." + f.name.substringAfterLast('.', "img").lowercase()
            f.copyTo(File(root, name))
            progress?.handled(f.length())
            written += name
            link(name)
        }
        val type = Media.kind(f.name)
        // A header video shows its poster, and plays on tap, so buyers don't each download it on every visit.
        var poster: String? = null
        if (type == "video" && f.length() > INLINE_LIMIT && posters != null) {
            val out = File(root, "header-poster.jpg")
            val made = try { posters(f, out) && out.isFile && out.length() > 0 } catch (e: Exception) { false }
            if (made) { written += "header-poster.jpg"; poster = link("header-poster.jpg") } else out.delete()
        }
        return mapOf("src" to src, "type" to type, "poster" to poster)
    }
}

/**
 * Renders with Pebble — CodeOnTheGo's own copy, found through the plugin's parent class loader. The
 * template itself is text; anything it extends, includes or imports comes from [library].
 */
fun render(template: String, context: Map<String, Any?>, library: File? = null): String {
    val engine = PebbleEngine.Builder().loader(TemplateLibrary(library)).extension(TemplateFunctions()).cacheActive(false).build()
    val w = StringWriter()
    engine.getLiteralTemplate(template).evaluate(w, context)
    return w.toString()
}

/** Plain JDK maps and lists, which Pebble reads without reflection. */
fun toPlain(v: Any?): Any? = when (v) {
    is JSONObject -> LinkedHashMap<String, Any?>().also { m -> v.keys().forEach { k -> m[k] = toPlain(v.get(k)) } }
    is JSONArray -> (0 until v.length()).map { toPlain(v.get(it)) }
    JSONObject.NULL -> null
    else -> v
}

object Media {
    private val MIME = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
        "webp" to "image/webp", "svg" to "image/svg+xml",
        "mp4" to "video/mp4", "webm" to "video/webm",
        "mp3" to "audio/mpeg", "ogg" to "audio/ogg", "wav" to "audio/wav", "m4a" to "audio/mp4",
    )
    fun mime(name: String) = MIME[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
    fun kind(name: String): String = mime(name).substringBefore('/').let { if (it == "video" || it == "audio") it else "image" }
}

fun dataUri(f: File): String = "data:${Media.mime(f.name)};base64," + Base64.getEncoder().encodeToString(f.readBytes())

/** `/f/<path>`, each segment percent-encoded as UTF-8. */
fun link(rel: String): String = "/f/" + rel.split('/').joinToString("/") { seg ->
    buildString {
        for (b in seg.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if ((c in 0x30..0x39) || (c in 0x41..0x5a) || (c in 0x61..0x7a) || c.toChar() in "-._~") append(c.toChar())
            else append('%').append("%02X".format(c))
        }
    }
}

fun slug(name: String): String =
    name.lowercase().replace(Regex("[^a-z0-9-]"), "-").replace(Regex("-+"), "-").trim('-').ifEmpty { "product" }

fun uniqueSlugs(names: List<String>): List<String> {
    val used = mutableSetOf<String>()
    return names.map { uniqueName(slug(it), used, extension = false).also { used += it } }
}

/** [preferred], or `name-2.ext`, `name-3.ext`… if it's taken. */
fun uniqueName(preferred: String, used: Set<String>, extension: Boolean = true): String {
    if (preferred !in used) return preferred
    val dot = if (extension) preferred.lastIndexOf('.').takeIf { it > 0 } ?: preferred.length else preferred.length
    val base = preferred.substring(0, dot)
    val ext = preferred.substring(dot)
    var n = 2
    while ("$base-$n$ext" in used) n++
    return "$base-$n$ext"
}

/** Trimmed text from an Extra JSON value, if it's a non-empty string, cut to [max] characters. */
fun plainText(v: Any?, max: Int): String? = (v as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(max)

/** An http or https address, and nothing else — so an Extra JSON value can never become a javascript: link. */
fun webHref(v: Any?): String? = plainText(v, 300)?.takeIf { Regex("""^https?://[^\s"'<>]+$""", RegexOption.IGNORE_CASE).matches(it) }

private val EMAIL = Regex("""^[^\s@<>"']+@[^\s@<>"']+\.[^\s@<>"']+$""")

/**
 * Extra JSON's contact: its name, email, phone and website, each with a link when it's a
 * plausible address — mailto:, tel:, or http(s): only — or null when nothing usable is given.
 */
fun contactOf(o: org.json.JSONObject?): Map<String, String?>? {
    if (o == null) return null
    val name = plainText(o.opt("name"), 80)
    val email = plainText(o.opt("email"), 120)?.takeIf { EMAIL.matches(it) }
    val phone = plainText(o.opt("phone"), 40)?.takeIf { Regex("""^\+?[0-9 ().-]+$""").matches(it) && it.count(Char::isDigit) >= 3 }
    val url = webHref(o.opt("url"))
    if (name == null && email == null && phone == null && url == null) return null
    return mapOf("name" to name, "email" to email, "emailHref" to email?.let { "mailto:$it" },
        "phone" to phone, "phoneHref" to phone?.let { p -> "tel:" + p.filter { it.isDigit() || it == '+' } }, "url" to url, "urlHref" to url)
}

/** Extra JSON's accent: a colour, accepted only as #rgb or #rrggbb, and returned as #rrggbb. */
fun accentOf(v: Any?): String? {
    val s = plainText(v, 7)?.lowercase() ?: return null
    return when {
        Regex("^#[0-9a-f]{6}$").matches(s) -> s
        Regex("^#[0-9a-f]{3}$").matches(s) -> "#" + s.drop(1).map { "$it$it" }.joinToString("")
        else -> null
    }
}

/**
 * Black or white, whichever contrasts more with [hex]. For any colour, one of them reaches at
 * least 4.58 to 1, so text in this colour on the accent always meets WCAG's 4.5 to 1.
 */
fun inkFor(hex: String): String {
    fun lin(c: Int) = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
    val l = 0.2126 * lin(hex.substring(1, 3).toInt(16)) + 0.7152 * lin(hex.substring(3, 5).toInt(16)) + 0.0722 * lin(hex.substring(5, 7).toInt(16))
    return if ((l + 0.05) / 0.05 >= 1.05 / (l + 0.05)) "#000000" else "#ffffff"
}

/**
 * A picture's width and height, read from its own header — PNG, GIF, JPEG or WebP — or null.
 * Android lacks Java's desktop image library, so the headers are read directly.
 */
fun imageSize(f: File): Pair<Int, Int>? = try {
    // A plain read loop: InputStream.readNBytes only arrived in Android 13.
    val b = f.inputStream().use { s ->
        val buf = ByteArray(minOf(f.length(), 256L * 1024).toInt()); var n = 0
        while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
        buf.copyOf(n)
    }
    fun u8(i: Int) = b[i].toInt() and 0xff
    fun be16(i: Int) = (u8(i) shl 8) or u8(i + 1)
    fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
    fun be32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
    fun le24(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)
    fun ascii(i: Int, n: Int) = String(b, i, n, Charsets.ISO_8859_1)
    when {
        b.size >= 24 && u8(0) == 0x89 && ascii(1, 3) == "PNG" -> be32(16) to be32(20)
        b.size >= 10 && ascii(0, 4) == "GIF8" -> le16(6) to le16(8)
        b.size >= 30 && ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> when (ascii(12, 4)) {
            "VP8 " -> (le16(26) and 0x3fff) to (le16(28) and 0x3fff)
            "VP8L" -> (1 + (u8(21) or ((u8(22) and 0x3f) shl 8))) to (1 + ((u8(22) shr 6) or (u8(23) shl 2) or ((u8(24) and 0x0f) shl 10)))
            "VP8X" -> (1 + le24(24)) to (1 + le24(27))
            else -> null
        }
        b.size >= 4 && u8(0) == 0xff && u8(1) == 0xd8 -> {
            // JPEG: walk the segments to the frame header, which holds the size.
            var i = 2; var size: Pair<Int, Int>? = null
            while (i + 9 < b.size && size == null) {
                if (u8(i) != 0xff) { i++; continue }
                val m = u8(i + 1)
                if (m == 0xd8 || m == 0x01 || m in 0xd0..0xd7 || m == 0xff) { i += if (m == 0xff) 1 else 2; continue }
                if (m in 0xc0..0xcf && m != 0xc4 && m != 0xc8 && m != 0xcc) size = be16(i + 7) to be16(i + 5)
                else i += 2 + be16(i + 2)
            }
            size
        }
        else -> null
    }?.takeIf { (w, h) -> w in 1..100_000 && h in 1..100_000 }
} catch (e: Exception) { null }

/** What Android can read from an APK file. Any part may be missing. */
class ApkFacts(val versionName: String?, val versionCode: Long?, val packageName: String?, val minSdk: Int?)

/**
 * The native-code architectures an APK includes, from its lib/<abi>/ folders, such as
 * arm64-v8a and armeabi-v7a. None means the app has no native code, so it runs on any phone.
 */
fun apkAbis(apk: File): List<String> = try {
    java.util.zip.ZipFile(apk).use { z ->
        z.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") }
            .mapNotNull { it.split('/').getOrNull(1)?.takeIf { abi -> abi.isNotEmpty() } }.distinct().sorted().toList()
    }
} catch (e: Exception) { emptyList() }

/** Which phones an APK with these architectures runs on, for buyers. */
fun abiLabel(abis: List<String>): String {
    if (abis.isEmpty()) return "all phones"
    val arm64 = "arm64-v8a" in abis
    val arm32 = "armeabi-v7a" in abis || "armeabi" in abis
    return when {
        arm64 && arm32 -> "32- and 64-bit phones"
        arm64 -> "64-bit phones"
        arm32 -> "32-bit phones"
        else -> abis.joinToString(", ")
    }
}

/** An Android API level as buyers know it: 28 is "Android 9 or later". */
fun androidLabel(api: Int): String? {
    val names = mapOf(21 to "5", 22 to "5.1", 23 to "6", 24 to "7", 25 to "7.1", 26 to "8", 27 to "8.1", 28 to "9", 29 to "10",
        30 to "11", 31 to "12", 32 to "12L", 33 to "13", 34 to "14", 35 to "15", 36 to "16")
    return names[api]?.let { "Android $it or later" }
}

fun sizeLabel(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
}

fun dateLabel(iso: String?): String {
    if (iso == null) return ""
    return try {
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).withZone(ZoneId.systemDefault()).format(Instant.parse(iso))
    } catch (e: Exception) { iso }
}

/** Deletes a directory tree without following symlinks, so a link can't lead it outside. */
fun deleteTree(dir: File) {
    val root = dir.toPath()
    if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
        override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult { Files.delete(d); return FileVisitResult.CONTINUE }
    })
}


/**
 * Whether [apk] is signed, so buyers can install it (design, Known open items: unsigned APKs). Signed
 * with scheme v2 or later, it has an APK Signing Block, whose marker ends 16 bytes before the zip's
 * central directory; signed only with v1, it has signature files in META-INF. With neither, Android
 * refuses to install it — as with a build's `*-unsigned.apk`.
 */
fun apkSigned(apk: File): Boolean = try {
    java.io.RandomAccessFile(apk, "r").use { f ->
        // The end-of-central-directory record: the last 22 bytes, or more, with a comment.
        val tail = minOf(f.length(), 65_557L).toInt()
        val buf = ByteArray(tail); f.seek(f.length() - tail); f.readFully(buf)
        var i = tail - 22
        while (i >= 0 && !(buf[i] == 0x50.toByte() && buf[i + 1] == 0x4b.toByte() && buf[i + 2] == 5.toByte() && buf[i + 3] == 6.toByte())) i--
        val v2 = i >= 0 && run {
            val cd = java.nio.ByteBuffer.wrap(buf, i + 16, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            cd >= 16 && ByteArray(16).also { f.seek(cd - 16); f.readFully(it) }.contentEquals("APK Sig Block 42".toByteArray())
        }
        v2 || java.util.zip.ZipFile(apk).use { z -> z.entries().asSequence().any { e ->
            val n = e.name.uppercase(); n.startsWith("META-INF/") && (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC")) } }
    }
} catch (e: Exception) { false }
