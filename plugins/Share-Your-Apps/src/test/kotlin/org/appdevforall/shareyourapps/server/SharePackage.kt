package org.appdevforall.shareyourapps.server

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair
import java.time.Instant
import java.util.Base64

/**
 * A copy of a published store (design, Appendix E): its signed description, then its files. The
 * whole copy travels inside a [SealedOutputStream], so this layer only has to describe and order
 * the files, and refuse anything unsafe when importing.
 *
 * The description is JSON: format, storeId, storeName, storeKey (the store's public key, X.509,
 * base64), created, expires (null for never), and files, each with its path and size.
 */
object SharePackage {
    const val FORMAT = 1
    const val MAX_DESCRIPTION = 4 * 1024 * 1024

    class Description(val json: JSONObject) {
        val storeId: String get() = json.getString("storeId")
        val storeName: String get() = json.getString("storeName")
        val storeKey: ByteArray get() = Base64.getDecoder().decode(json.getString("storeKey"))
        val created: Instant get() = Instant.parse(json.getString("created"))
        val expires: Instant? get() = if (json.isNull("expires")) null else Instant.parse(json.getString("expires"))
        val files: List<Pair<String, Long>> get() = json.getJSONArray("files").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { f -> f.getString("path") to f.getLong("size") } } }
        val totalBytes: Long get() = files.sumOf { it.second }
    }

    /** Every file of a published store, by path under it, in a fixed order. */
    fun filesOf(storeDir: File): List<Pair<String, File>> =
        // Never a temporary file, such as the compressor's .gz.tmp, which may vanish while it's being shared.
        storeDir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.map { it.relativeTo(storeDir).invariantSeparatorsPath to it }.sortedBy { it.first }.toList()

    fun describe(storeDir: File, storeName: String, storeKey: KeyPair, created: Instant, expires: Instant?): Description {
        val files = JSONArray(); filesOf(storeDir).forEach { (p, f) -> files.put(JSONObject().put("path", p).put("size", f.length())) }
        return Description(JSONObject()
            .put("format", FORMAT).put("storeId", ShareCrypto.storeId(storeKey.public)).put("storeName", storeName)
            .put("storeKey", Base64.getEncoder().encodeToString(storeKey.public.encoded))
            .put("created", created.toString()).put("expires", expires?.toString() ?: JSONObject.NULL).put("files", files))
    }

    /** Writes the signed description, then every file it lists, to [out] — normally a SealedOutputStream. */
    fun write(out: OutputStream, storeDir: File, description: Description, storeKey: KeyPair) {
        val d = DataOutputStream(out)
        val bytes = description.json.toString().toByteArray(Charsets.UTF_8)
        val signature = ShareCrypto.sign(storeKey.private, bytes)
        d.writeInt(bytes.size); d.write(bytes); d.writeInt(signature.size); d.write(signature)
        val buf = ByteArray(64 * 1024)
        for ((path, size) in description.files) {
            // A source zip the compressor replaced by its .gz copy after the listing: the same bytes, decompressed.
            val f = File(storeDir, path); val gz = File(storeDir, "$path.gz")
            val source: InputStream = if (!f.exists() && path.endsWith(".zip") && gz.isFile) java.util.zip.GZIPInputStream(FileInputStream(gz), 64 * 1024) else FileInputStream(f)
            source.use { input ->
                var left = size
                while (left > 0) { val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) throw IOException("$path shrank while being shared"); d.write(buf, 0, n); left -= n }
            }
        }
        d.flush()
    }

    /** Reads just the signed description, checking its signature; throws IOException if it's wrong. */
    fun readDescription(input: InputStream): Description {
        val d = DataInputStream(input)
        val len = d.readInt(); if (len <= 0 || len > MAX_DESCRIPTION) throw IOException("the copy's description is damaged")
        val bytes = ByteArray(len).also { d.readFully(it) }
        val sigLen = d.readInt(); if (sigLen <= 0 || sigLen > 256) throw IOException("the copy's signature is damaged")
        val signature = ByteArray(sigLen).also { d.readFully(it) }
        val desc = try { Description(JSONObject(String(bytes, Charsets.UTF_8))) } catch (e: Exception) { throw IOException("the copy's description can't be read") }
        if (desc.json.optInt("format") != FORMAT) throw IOException("this copy was made by a newer Share Your Apps Server")
        val key = try { ShareCrypto.publicKey(desc.storeKey) } catch (e: Exception) { throw IOException("the copy's store key can't be read") }
        if (ShareCrypto.storeId(key) != desc.storeId) throw IOException("the copy's store identity doesn't match its key")
        if (!ShareCrypto.verify(key, bytes, signature)) throw IOException("the copy's signature doesn't match")
        desc.files.forEach { (p, s) -> if (!safePath(p) || s < 0) throw IOException("the copy lists an unsafe file: $p") }
        if (desc.files.map { it.first }.toSet().size != desc.files.size) throw IOException("the copy lists a file twice")
        return desc
    }

    /** A path that stays inside the store: relative, with no empty, "." or ".." parts, and no backslashes. */
    fun safePath(p: String): Boolean =
        p.isNotEmpty() && !p.startsWith("/") && '\\' !in p && '\u0000' !in p && p.split('/').none { it.isEmpty() || it == "." || it == ".." }

    /**
     * Imports a copy from [input], already decrypted, into [destParent]/<storeId>. Everything goes
     * into a temporary folder first, renamed into place only once the last file is saved, so a store
     * is either complete or absent. The free space — [usableSpace], if given, else the phone's — is
     * checked against the description's total first.
     * [accept] sees the description before any file is written, and can refuse it by throwing.
     */
    fun import(input: InputStream, destParent: File, usableSpace: Long? = null, accept: (Description) -> Unit = {},
               progress: (written: Long, total: Long) -> Unit = { _, _ -> }): Pair<Description, File> {
        val desc = readDescription(input)
        accept(desc)
        // A folder that doesn't exist yet reports no space, so measure the nearest one that does.
        val space = usableSpace ?: generateSequence(destParent.absoluteFile) { it.parentFile }.first { it.exists() }.usableSpace
        if (desc.totalBytes + 1024 * 1024 > space) throw IOException("there isn't enough space on this phone for the copy")
        destParent.mkdirs()
        val tmp = File(destParent, ".incoming-" + Base32.encode(ShareCrypto.randomBytes(5)))
        try {
            val buf = ByteArray(64 * 1024)
            var written = 0L
            progress(0, desc.totalBytes)
            for ((path, size) in desc.files) {
                val f = File(tmp, path); f.parentFile?.mkdirs()
                if (!f.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) throw IOException("the copy lists an unsafe file: $path")
                FileOutputStream(f).use { out ->
                    var left = size
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) throw IOException("the copy was cut off")
                        out.write(buf, 0, n); left -= n; written += n; progress(written, desc.totalBytes)
                    }
                }
            }
            if (input.read() != -1) throw IOException("the copy has more data than it lists")
            if (input is SealedInputStream && !input.complete) throw IOException("the copy was cut off")
            val dest = File(destParent, desc.storeId)
            val old = File(destParent, ".replaced-" + desc.storeId)
            if (dest.exists()) { old.deleteRecursively(); if (!dest.renameTo(old)) throw IOException("the previous copy couldn't be moved aside") }
            if (!tmp.renameTo(dest)) { old.renameTo(dest); throw IOException("the copy couldn't be put in place") }
            old.deleteRecursively()
            return desc to dest
        } catch (e: Exception) {
            tmp.deleteRecursively(); throw e
        }
    }
}
