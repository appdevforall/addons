package org.appdevforall.localstore.server

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cryptography for sharing a store with another seller (design, Appendix E.3), kept in this
 * one file so it can be reviewed on its own. P-256 throughout, since Android has it built in back
 * to Android 9: ECDSA signs a copy's description with the store's key; ECDH, then HKDF-SHA256,
 * derives a key only one pair of phones can know; AES-256-GCM, in numbered chunks, encrypts the
 * copy with it.
 *
 * The exchange, whose check code the owner compares by eye (screen 3 with screens 5 and 6):
 *  1. seller → owner: the share code, and a commitment — the hash of the seller's public key;
 *  2. owner → seller: the owner's one-time public key;
 *  3. seller → owner: the seller's public key, which the owner checks against the commitment.
 * Each side's key is fixed before it sees the other's, so someone in the middle can't try keys
 * until the four digits match: their chance stays 1 in 10,000.
 */
object ShareCrypto {
    private val random = SecureRandom()

    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    fun publicKey(encoded: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
    fun privateKey(encoded: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encoded))

    fun sign(key: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(key); update(data); sign() }

    fun verify(key: PublicKey, data: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(data); verify(signature) }
    } catch (e: Exception) { false }

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }

    /** A store's identity: its public key's SHA-256, first 10 bytes, in base32 — 16 characters. */
    fun storeId(key: PublicKey): String = Base32.encode(sha256(key.encoded).copyOf(10))

    /** Step 1's commitment to the seller's public key. */
    fun commitment(sellerPublic: ByteArray): ByteArray = sha256("localstore-commit v1".toByteArray(), sellerPublic)

    fun commitmentHolds(commitment: ByteArray, sellerPublic: ByteArray): Boolean =
        MessageDigest.isEqual(commitment, commitment(sellerPublic))

    /** The four digits both phones show. They match only if both hold the same two public keys and code. */
    fun checkCode(ownerPublic: ByteArray, sellerPublic: ByteArray, code: String): String {
        val h = sha256("localstore-check v1".toByteArray(), ownerPublic, sellerPublic, code.toByteArray())
        val n = ByteBuffer.wrap(h, 0, 4).int.toLong() and 0xffffffffL
        return "%04d".format(n % 10_000)
    }

    /** The key for one pair of phones: ECDH, then HKDF bound to the share code and both public keys. */
    fun sessionKey(own: PrivateKey, theirs: PublicKey, code: String, ownerPublic: ByteArray, sellerPublic: ByteArray): ByteArray {
        val secret = KeyAgreement.getInstance("ECDH").run { init(own); doPhase(theirs, true); generateSecret() }
        return hkdf(secret, code.toByteArray(), "localstore-share v1".toByteArray() + ownerPublic + sellerPublic, 32)
    }

    /** HKDF-SHA256, RFC 5869. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256")); doFinal(ikm) }
        val out = java.io.ByteArrayOutputStream(); var t = ByteArray(0); var i = 1
        while (out.size() < length) {
            t = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(prk, "HmacSHA256")); update(t); update(info); update(i.toByte()); doFinal() }
            out.write(t); i++
        }
        return out.toByteArray().copyOf(length)
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }
}

/**
 * AES-256-GCM over a stream, in chunks of up to 64 KB, so a copy of any size is encrypted without
 * holding it in memory. A header of magic bytes and an 8-byte random nonce prefix; then each chunk
 * as its length and ciphertext, with nonce = prefix + chunk number, and the number and a "last"
 * flag authenticated with it. So altering, reordering, or cutting off the stream all fail.
 */
class SealedOutputStream(private val out: OutputStream, key: ByteArray) : OutputStream() {
    private val cipherKey = SecretKeySpec(key, "AES")
    private val prefix = ShareCrypto.randomBytes(8)
    private val buf = ByteArray(CHUNK); private var n = 0; private var counter = 0; private var closed = false

    init { out.write(MAGIC); out.write(prefix) }

    override fun write(b: Int) { if (n == CHUNK) flushChunk(false); buf[n++] = b.toByte() }
    override fun write(b: ByteArray, off: Int, len: Int) {
        var i = off; val end = off + len
        while (i < end) {
            if (n == CHUNK) flushChunk(false)
            val k = minOf(CHUNK - n, end - i); System.arraycopy(b, i, buf, n, k); n += k; i += k
        }
    }

    private fun flushChunk(last: Boolean) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, cipherKey, GCMParameterSpec(128, nonce(prefix, counter)))
        c.updateAAD(aad(counter, last))
        val sealed = c.doFinal(buf, 0, n)
        out.write(ByteBuffer.allocate(4).putInt(sealed.size).array()); out.write(sealed)
        counter++; n = 0
    }

    /** Writes the last chunk — possibly empty — so the reader knows the stream really ended. */
    override fun close() { if (closed) return; closed = true; flushChunk(true); out.flush(); out.close() }

    companion object {
        const val CHUNK = 64 * 1024
        val MAGIC = "LSC1".toByteArray()
        fun nonce(prefix: ByteArray, counter: Int): ByteArray = prefix + ByteBuffer.allocate(4).putInt(counter).array()
        fun aad(counter: Int, last: Boolean): ByteArray = ByteBuffer.allocate(5).putInt(counter).put(if (last) 1 else 0).array()
    }
}

/** Reads a [SealedOutputStream]'s stream; any altered, reordered or missing chunk throws IOException. */
class SealedInputStream(input: InputStream, key: ByteArray) : InputStream() {
    private val src = DataInputStream(input)
    private val cipherKey = SecretKeySpec(key, "AES")
    private val prefix: ByteArray
    private var plain = ByteArray(0); private var pos = 0; private var counter = 0; private var ended = false

    init {
        val magic = ByteArray(4); src.readFully(magic)
        if (!magic.contentEquals(SealedOutputStream.MAGIC)) throw IOException("not a sealed Local Store copy")
        prefix = ByteArray(8); src.readFully(prefix)
    }

    private fun nextChunk(): Boolean {
        if (ended) return false
        val len = try { src.readInt() } catch (e: EOFException) { throw IOException("the copy was cut off") }
        if (len < 16 || len > SealedOutputStream.CHUNK + 16) throw IOException("a chunk of the copy is damaged")
        val sealed = ByteArray(len); try { src.readFully(sealed) } catch (e: EOFException) { throw IOException("the copy was cut off") }
        // A chunk authenticates as the last, or not; try the ordinary case first.
        plain = open(sealed, false) ?: open(sealed, true)?.also { ended = true } ?: throw IOException("a chunk of the copy failed its check")
        pos = 0; counter++
        if (ended && src.read() != -1) throw IOException("the copy has data after its end")
        return true
    }

    private fun open(sealed: ByteArray, last: Boolean): ByteArray? = try {
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, cipherKey, GCMParameterSpec(128, SealedOutputStream.nonce(prefix, counter)))
            updateAAD(SealedOutputStream.aad(counter, last)); doFinal(sealed)
        }
    } catch (e: javax.crypto.AEADBadTagException) { null }

    override fun read(): Int {
        while (pos >= plain.size) if (!nextChunk()) return -1
        return plain[pos++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (pos >= plain.size) if (!nextChunk()) return -1
        val k = minOf(len, plain.size - pos); System.arraycopy(plain, pos, b, off, k); pos += k; return k
    }

    /** True once the last chunk has been read and fully consumed: the copy arrived whole. */
    val complete: Boolean get() = ended && pos >= plain.size
}

/** RFC 4648 base32, upper case, without padding. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    fun encode(b: ByteArray): String {
        val sb = StringBuilder(); var buffer = 0; var bits = 0
        for (x in b) { buffer = (buffer shl 8) or (x.toInt() and 0xff); bits += 8
            while (bits >= 5) { sb.append(ALPHABET[(buffer shr (bits - 5)) and 31]); bits -= 5 } }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }
}
