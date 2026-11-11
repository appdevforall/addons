package org.appdevforall.localstore.server

import java.io.File
import org.json.JSONObject
import java.time.Instant
import java.security.KeyPair

/**
 * Share codes, as the owner's screen shows them and a seller types them: LS-XXXX-XXXX, from
 * Crockford's base32, which leaves out I, L, O and U. Reading one forgives case, spaces, dashes,
 * the LS prefix, and the look-alikes O for 0 and I or L for 1.
 */
object ShareCodes {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun newCode(): String {
        val b = ShareCrypto.randomBytes(8)
        val chars = b.map { ALPHABET[(it.toInt() and 0xff) % 32] }.joinToString("")
        return "LS-${chars.take(4)}-${chars.drop(4)}"
    }

    /** The code's 8 characters, or null if the input isn't one. */
    fun normalize(input: String): String? {
        var s = input.uppercase().filter { it.isLetterOrDigit() }
        if (s.startsWith("LS") && s.length == 10) s = s.drop(2)
        s = s.map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }.joinToString("")
        return s.takeIf { it.length == 8 && it.all { c -> c in ALPHABET } }
    }

    fun display(normalized: String): String = "LS-${normalized.take(4)}-${normalized.drop(4)}"
}

/**
 * A copy's expiry, against a phone clock that can be set back (design, Appendix E.5). The latest
 * time ever seen is kept in [stateFile]. A clock up to five minutes behind it is an ordinary
 * correction; further behind, the copy expires at once, and the caller deletes it.
 */
class ExpiryClock(private val stateFile: File) {
    enum class Status { OK, EXPIRED, CLOCK_SET_BACK }

    /** The latest time this phone's clock has shown, in epoch milliseconds, or 0. */
    fun latestSeen(): Long = try { stateFile.readText().trim().toLong() } catch (e: Exception) { 0L }

    fun check(nowMs: Long, expiresMs: Long?): Status {
        val latest = latestSeen()
        if (latest > 0 && nowMs < latest - ALLOWED_BACK_MS) return Status.CLOCK_SET_BACK
        if (nowMs > latest) {
            stateFile.parentFile?.mkdirs()
            val tmp = File(stateFile.path + ".tmp"); tmp.writeText(nowMs.toString()); tmp.renameTo(stateFile)
        }
        return if (expiresMs != null && nowMs >= expiresMs) Status.EXPIRED else Status.OK
    }

    /**
     * Starts again from [nowMs], for a fresh copy: the rule stops a seller extending a copy they
     * already have, and a clock once set too far ahead mustn't condemn every copy imported later.
     */
    fun restart(nowMs: Long) {
        stateFile.parentFile?.mkdirs()
        val tmp = File(stateFile.path + ".tmp"); tmp.writeText(nowMs.toString()); tmp.renameTo(stateFile)
    }

    companion object { const val ALLOWED_BACK_MS = 5 * 60 * 1000L }
}

/**
 * The store's key pair (design, Appendix E.3): made once, and kept in the app's private storage.
 * Each file is written to a temporary name, then renamed, so a crash can't leave half a key.
 * Losing it needs no recovery: a new key simply makes a new store identity.
 */
object StoreKeyFile {
    fun loadOrCreate(dir: File): KeyPair {
        val pub = File(dir, "store-key.pub"); val priv = File(dir, "store-key.p8")
        if (pub.isFile && priv.isFile) try {
            return KeyPair(ShareCrypto.publicKey(pub.readBytes()), ShareCrypto.privateKey(priv.readBytes()))
        } catch (e: Exception) { }
        val k = ShareCrypto.newKeyPair()
        dir.mkdirs()
        write(priv, k.private.encoded); write(pub, k.public.encoded)
        return k
    }

    private fun write(f: File, bytes: ByteArray) {
        val tmp = File(f.path + ".tmp"); tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}

/**
 * The link a share's QR code holds: localstore-share://<address>:<port>/<code>. A phone's own camera
 * app recognises it, and opening it takes the seller straight to importing, with the code filled in.
 */
object ShareLink {
    const val SCHEME = "localstore-share"

    fun make(address: String, port: Int, code: String): String = "$SCHEME://$address:$port/${ShareCodes.display(code)}"

    /** The address, port and code, or null if the text isn't a share link. */
    fun parse(link: String): Triple<String, Int, String>? {
        val m = Regex("^$SCHEME://([0-9.]{7,15}):(\\d{1,5})/([A-Za-z0-9-]+)/?$").matchEntire(link.trim()) ?: return null
        val (address, port, code) = m.destructured
        val p = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val c = ShareCodes.normalize(code) ?: return null
        if (address.split('.').let { it.size != 4 || it.any { o -> (o.toIntOrNull() ?: 256) > 255 } }) return null
        return Triple(address, p, c)
    }
}

/**
 * A copy's expiry, applied (design, Appendix E.5). A copy is due on its date, or at once if the
 * phone's clock has been set back more than five minutes. Then it's deleted, and a notice left for
 * the seller — screen 8, "A store has expired" — until they dismiss it. The two cases look the same
 * to the seller and in the log: nothing says which it was, so nothing teaches how the rule works.
 *
 * Copies live in the imported folder as <storeId>/, with their description beside them as
 * <storeId>.json. The notice is [NOTICE], in the same folder.
 */
object CopyExpiry {
    const val NOTICE = "expired-notice.json"

    class Expired(val storeName: String, val expires: Instant?, val clockSetBack: Boolean)

    /** Every copy's description in [importedDir]: every <storeId>.json except the notice. */
    fun copies(importedDir: File): List<SharePackage.Description> =
        importedDir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != NOTICE }.orEmpty().mapNotNull { f ->
            try { SharePackage.Description(JSONObject(f.readText())).also { it.storeId; it.storeName } } catch (e: Exception) { null }
        }

    /** Whether [copy] is still fine, due, or on a phone whose clock was set back. Updates the clock's latest time. */
    fun status(copy: SharePackage.Description, clock: ExpiryClock, nowMs: Long = System.currentTimeMillis()): ExpiryClock.Status {
        val s = clock.check(nowMs, copy.expires?.toEpochMilli())
        // A copy that never expires has no date to extend, so the clock rule spares it.
        return if (copy.expires == null && s == ExpiryClock.Status.CLOCK_SET_BACK) ExpiryClock.Status.OK else s
    }

    /** Deletes [copy] and leaves the notice. */
    fun delete(importedDir: File, copy: SharePackage.Description, clockSetBack: Boolean): Expired {
        File(importedDir, copy.storeId).deleteRecursively()
        File(importedDir, "${copy.storeId}.json").delete()
        val e = Expired(copy.storeName, copy.expires, clockSetBack)
        val tmp = File(importedDir, "$NOTICE.tmp")
        tmp.writeText(JSONObject().put("storeName", e.storeName).toString())   // just the name: nothing says why
        tmp.renameTo(File(importedDir, NOTICE))
        return e
    }

    /** Checks every copy, deleting any that's due; returns the last one deleted, or null. */
    fun enforce(importedDir: File, clock: ExpiryClock, nowMs: Long = System.currentTimeMillis()): Expired? {
        var last: Expired? = null
        for (c in copies(importedDir)) {
            val s = status(c, clock, nowMs)
            if (s != ExpiryClock.Status.OK) last = delete(importedDir, c, s == ExpiryClock.Status.CLOCK_SET_BACK)
        }
        return last
    }

    /** The notice left by a deleted copy, until the seller dismisses it. */
    fun notice(importedDir: File): Expired? = try {
        val j = JSONObject(File(importedDir, NOTICE).readText())
        Expired(j.getString("storeName"), null, false)
    } catch (e: Exception) { null }

    fun dismiss(importedDir: File) { File(importedDir, NOTICE).delete() }

    /** Where the expiry clock keeps the latest time it has seen: outside the imported folder, so it outlives copies. */
    fun clockFile(filesDir: File) = File(filesDir, "share/latest-time")
}
