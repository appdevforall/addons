package org.appdevforall.shareyourapps.server

import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * What a sharing phone plays as sound for the handshake: the share code, where its server is, and, for the
 * sender's own hotspot only, how to join it. Sound is for the handshake only; the copy itself still goes over
 * Wi-Fi, and a person still taps Allow. Plain Kotlin, with no Android code, so it's unit-tested on its own.
 *
 * Layout, in bytes:
 * - 1: version (high 4 bits, now 1) and flags (low 4 bits: own hotspot, has address, has SSID, has password)
 * - 5: the share code, 8 characters of 5 bits each
 * - 6: IPv4 address and port, if it has an address
 * - 1 + n: SSID length and SSID (UTF-8), if it has one
 * - 1 + n: password length and password (UTF-8), if it has one
 * - 2: CRC-16/CCITT-FALSE of everything before it
 *
 * Typically about 46 bytes for the sender's own hotspot, and 14 to 30 for other Wi-Fi. Lengths are fields, so
 * the only limit is what one length byte holds: 255 bytes each.
 *
 * Rules: a password is sent only for the sender's own hotspot, never for other Wi-Fi, and only with the
 * network's name. [toString] never shows the password, so a payload can be logged.
 */
data class SoundPayload(
    /** The normalized share code: 8 characters of [ALPHABET], as ShareCodes.normalize returns it. */
    val code: String,
    val address: Inet4Address? = null,
    /** The store server's port; 0 when there's no address. */
    val port: Int = 0,
    /** The sender serves on its own hotspot (Wi-Fi Direct or local-only), not on other Wi-Fi. */
    val ownHotspot: Boolean = false,
    val ssid: String? = null,
    val password: String? = null,
) {
    init {
        require(code.length == CODE_CHARS && code.all { it in ALPHABET }) { "not a normalized share code" }
        if (address != null) require(port in 1..65535) { "the port must be 1 to 65535" }
        else require(port == 0) { "a port needs an address" }
        if (ssid != null) require(ssid.isNotEmpty() && utf8(ssid).size <= MAX_FIELD) { "the network name must be 1 to $MAX_FIELD bytes" }
        if (password != null) {
            require(ownHotspot) { "a password is only sent for the sender's own hotspot" }
            require(ssid != null) { "a password needs the network's name" }
            require(password.isNotEmpty() && utf8(password).size <= MAX_FIELD) { "the password must be 1 to $MAX_FIELD bytes" }
        }
    }

    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        var flags = 0
        if (ownHotspot) flags = flags or OWN_HOTSPOT
        if (address != null) flags = flags or HAS_ADDRESS
        if (ssid != null) flags = flags or HAS_SSID
        if (password != null) flags = flags or HAS_PASSWORD
        out.write((VERSION shl 4) or flags)
        packCode(code).let { out.write(it, 0, it.size) }
        if (address != null) {
            val a = address.address
            out.write(a, 0, a.size)
            out.write(port shr 8); out.write(port and 0xFF)
        }
        ssid?.let { field(out, it) }
        password?.let { field(out, it) }
        val body = out.toByteArray()
        val crc = crc16(body, body.size)
        out.write(crc shr 8); out.write(crc and 0xFF)
        return out.toByteArray()
    }

    override fun toString(): String =
        "SoundPayload(code=$code, address=${address?.hostAddress}, port=$port, ownHotspot=$ownHotspot, " +
            "ssid=$ssid, password=${if (password != null) "hidden" else "none"})"

    companion object {
        const val VERSION = 1
        const val OWN_HOTSPOT = 1
        const val HAS_ADDRESS = 2
        const val HAS_SSID = 4
        const val HAS_PASSWORD = 8
        /** The share code's characters, Crockford base32, as in ShareCodes. */
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        const val CODE_CHARS = 8
        const val CODE_BYTES = 5
        /** The most one length byte holds. */
        const val MAX_FIELD = 255
        /** Version and flags, the code, and the CRC: a payload with nothing else. */
        const val MIN_SIZE = 1 + CODE_BYTES + 2

        /**
         * Reads a payload, or returns null if it isn't one exactly: a wrong CRC or version, cut-off or extra
         * bytes, text that isn't UTF-8, or fields that break the rules (such as a password for other Wi-Fi).
         */
        fun decode(bytes: ByteArray): SoundPayload? {
            if (bytes.size < MIN_SIZE) return null
            val body = bytes.size - 2
            val crc = ((bytes[body].toInt() and 0xFF) shl 8) or (bytes[body + 1].toInt() and 0xFF)
            if (crc16(bytes, body) != crc) return null
            val head = bytes[0].toInt() and 0xFF
            if ((head shr 4) != VERSION) return null
            val flags = head and 0x0F
            var i = 1
            val code = unpackCode(bytes, i); i += CODE_BYTES
            var address: Inet4Address? = null
            var port = 0
            if ((flags and HAS_ADDRESS) != 0) {
                if (i + 6 > body) return null
                address = InetAddress.getByAddress(bytes.copyOfRange(i, i + 4)) as Inet4Address
                port = ((bytes[i + 4].toInt() and 0xFF) shl 8) or (bytes[i + 5].toInt() and 0xFF)
                i += 6
            }
            fun field(): String? {
                if (i >= body) return null
                val n = bytes[i].toInt() and 0xFF
                i++
                if (n == 0 || i + n > body) return null
                val s = utf8Strict(bytes, i, n) ?: return null
                i += n
                return s
            }
            val ssid = if ((flags and HAS_SSID) != 0) (field() ?: return null) else null
            val password = if ((flags and HAS_PASSWORD) != 0) (field() ?: return null) else null
            if (i != body) return null
            return try {
                SoundPayload(code, address, port, (flags and OWN_HOTSPOT) != 0, ssid, password)
            } catch (e: IllegalArgumentException) { null }
        }

        /** CRC-16/CCITT-FALSE (polynomial 0x1021, starting at 0xFFFF) of the first [length] bytes. */
        fun crc16(b: ByteArray, length: Int): Int {
            var crc = 0xFFFF
            for (k in 0 until length) {
                crc = crc xor ((b[k].toInt() and 0xFF) shl 8)
                repeat(8) {
                    crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
                }
            }
            return crc
        }

        private fun packCode(code: String): ByteArray {
            var v = 0L
            for (c in code) v = (v shl 5) or ALPHABET.indexOf(c).toLong()
            return ByteArray(CODE_BYTES) { k -> (v shr (8 * (CODE_BYTES - 1 - k))).toByte() }
        }

        private fun unpackCode(b: ByteArray, at: Int): String {
            var v = 0L
            for (k in 0 until CODE_BYTES) v = (v shl 8) or (b[at + k].toLong() and 0xFF)
            return (0 until CODE_CHARS).map { k -> ALPHABET[((v shr (5 * (CODE_CHARS - 1 - k))) and 31).toInt()] }.joinToString("")
        }

        private fun utf8(s: String) = s.toByteArray(StandardCharsets.UTF_8)

        private fun field(out: ByteArrayOutputStream, s: String) {
            val b = utf8(s)
            out.write(b.size)
            out.write(b, 0, b.size)
        }

        /** Decodes UTF-8, refusing malformed bytes rather than replacing them. */
        private fun utf8Strict(b: ByteArray, at: Int, n: Int): String? = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, at, n)).toString()
        } catch (e: CharacterCodingException) { null }
    }
}
