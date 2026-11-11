package org.appdevforall.localstore.server

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * The seller's side of importing a store (design, Appendix E.2–E.3): the three-step exchange with
 * the owner's server, asking whether the owner has allowed the copy, then downloading it straight
 * into [SharePackage.import]. [base] is the owner's server, such as http://192.168.1.5:6175.
 */
class ShareClient(
    private val base: String, code: String, private val phoneName: String,
    /** Opens each connection; on Android, through the Wi-Fi network, which a network without internet isn't by default. */
    private val opener: (URL) -> java.net.URLConnection = { it.openConnection() },
) {
    private val code = ShareCodes.normalize(code) ?: throw IOException("That isn't a share code.")
    private val sellerKey = ShareCrypto.newKeyPair()
    private var requestId: String? = null
    private var sessionKey: ByteArray? = null
    @Volatile private var cancelled = false
    @Volatile private var connection: HttpURLConnection? = null

    /** Stops whatever this client is doing, from any thread: a download in progress fails, leaving nothing. */
    fun cancel() { cancelled = true; connection?.disconnect() }

    /** Steps 1 to 3. Returns the check code, for the seller's screen. */
    fun ask(): String {
        val first = post("/share/request", JSONObject()
            .put("code", code).put("name", phoneName)
            .put("commitment", b64(ShareCrypto.commitment(sellerKey.public.encoded))).put("protocol", ShareHost.PROTOCOL))
        val id = first.getString("request")
        val ownerPublic = Base64.getDecoder().decode(first.getString("ownerKey"))
        val owner = try { ShareCrypto.publicKey(ownerPublic) } catch (e: Exception) { throw IOException("The owner's phone sent a key that can't be read.") }
        post("/share/reveal", JSONObject().put("request", id).put("sellerKey", b64(sellerKey.public.encoded)))
        requestId = id
        sessionKey = ShareCrypto.sessionKey(sellerKey.private, owner, code, ownerPublic, sellerKey.public.encoded)
        return ShareCrypto.checkCode(ownerPublic, sellerKey.public.encoded, code)
    }

    /** waiting, allowed, denied or expired. */
    fun status(): String = get("/share/status?request=${requestId ?: throw IOException("Ask first.")}").getString("state")

    /** Downloads the allowed copy into [destParent], all or nothing. */
    fun download(destParent: File, usableSpace: Long? = null, accept: (SharePackage.Description) -> Unit = {},
                 progress: (Long, Long) -> Unit = { _, _ -> }): Pair<SharePackage.Description, File> {
        val c = open("/share/copy?request=${requestId ?: throw IOException("Ask first.")}")
        if (c.responseCode != 200) throw IOException(errorOf(c))
        return c.inputStream.use { SharePackage.import(SealedInputStream(it, sessionKey!!), destParent, usableSpace, accept, progress) }
    }

    private fun open(path: String): HttpURLConnection {
        if (cancelled) throw IOException("Cancelled.")
        return (opener(URL(base + path)) as HttpURLConnection).apply {
            connectTimeout = 5_000; readTimeout = 30_000
            connection = this
        }
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val c = open(path).apply { requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/json") }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        return reply(c)
    }

    private fun get(path: String): JSONObject = reply(open(path))

    private fun reply(c: HttpURLConnection): JSONObject {
        if (c.responseCode != 200) throw IOException(errorOf(c))
        return JSONObject(c.inputStream.use { String(it.readBytes()) })
    }

    private fun errorOf(c: HttpURLConnection): String = try {
        JSONObject(c.errorStream.use { String(it.readBytes()) }).getString("error")
    } catch (e: Exception) { "The owner's phone answered ${c.responseCode}." }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
}
