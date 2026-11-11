package soundpayloadtest

import java.net.Inet4Address
import java.net.InetAddress
import org.appdevforall.localstore.server.ShareCodes
import org.appdevforall.localstore.server.SoundPayload

var failures = 0
val failedNames = mutableListOf<String>()
fun check(name: String, cond: Boolean) {
    if (!cond) failedNames += name
    println((if (cond) "PASS  " else "FAIL  ") + name); if (!cond) failures++ }

/** True if building the payload is refused. */
fun refuses(block: () -> Unit): Boolean = try { block(); false } catch (e: IllegalArgumentException) { true }

fun ip(s: String) = InetAddress.getByName(s) as Inet4Address

/** A hand-made payload body with its CRC appended, to test what decode refuses even when the CRC is right. */
fun withCrc(body: ByteArray): ByteArray {
    val c = SoundPayload.crc16(body, body.size)
    return body + byteArrayOf((c shr 8).toByte(), c.toByte())
}

fun roundTrips(p: SoundPayload) = SoundPayload.decode(p.encode()) == p

/** Runs every check; returns the names of those that failed. */
fun runChecks(): List<String> {
    failures = 0; failedNames.clear()
    val code = "ABCD1234"
    val home = ip("192.168.49.1")

    // The CRC: CRC-16/CCITT-FALSE's standard check value.
    check("CRC-16 of \"123456789\" is 0x29B1", SoundPayload.crc16("123456789".toByteArray(), 9) == 0x29B1)

    // Typical payloads and their sizes.
    val own = SoundPayload(code, home, 6175, ownHotspot = true, ssid = "DIRECT-LS-ABCD", password = "Hq7xKm2pRt9wZb4n")
    check("own hotspot: round trip", roundTrips(own))
    check("own hotspot: 46 bytes with a 14-character name and 16-character password", own.encode().size == 46)
    val ext = SoundPayload(code, ip("10.0.0.23"), 6175, ssid = "Shop Wi-Fi")
    check("other Wi-Fi with name: round trip", roundTrips(ext))
    check("other Wi-Fi with name: 15 bytes plus the name", ext.encode().size == 15 + 10)
    val bare = SoundPayload(code, home, 6175)
    check("other Wi-Fi without name: 14 bytes", bare.encode().size == 14 && roundTrips(bare))
    val codeOnly = SoundPayload(code)
    check("code only: the smallest payload", codeOnly.encode().size == SoundPayload.MIN_SIZE && roundTrips(codeOnly))

    // Every flag combination: the valid ones round trip with exactly their flags; the others are refused.
    var combos = true
    for (mask in 0..15) {
        val o = mask and 1 != 0; val a = mask and 2 != 0; val s = mask and 4 != 0; val pw = mask and 8 != 0
        val build = { SoundPayload(code, if (a) home else null, if (a) 6175 else 0, o, if (s) "Net" else null, if (pw) "secret12" else null) }
        val valid = !pw || (o && s)
        if (valid) {
            val p = build()
            if (!roundTrips(p) || (p.encode()[0].toInt() and 0x0F) != mask || (p.encode()[0].toInt() and 0xFF) shr 4 != SoundPayload.VERSION) combos = false
        } else if (!refuses { build() }) combos = false
    }
    check("every flag combination: valid ones round trip, others are refused", combos)

    // Every character of the code's alphabet, in every position.
    val all = (0 until 32).all { k -> roundTrips(SoundPayload((0 until 8).map { SoundPayload.ALPHABET[(k + it * 5) % 32] }.joinToString(""))) }
    check("every code character in every position round trips", all)
    check("codes 00000000 and ZZZZZZZZ round trip", roundTrips(SoundPayload("00000000")) && roundTrips(SoundPayload("ZZZZZZZZ")))
    val normalized = ShareCodes.normalize("LS-7K3M-Q9XZ")
    check("a code from ShareCodes.normalize is accepted", normalized != null && roundTrips(SoundPayload(normalized)))

    // Addresses and ports.
    check("address 0.0.0.0 and 255.255.255.255 round trip",
        roundTrips(SoundPayload(code, ip("0.0.0.0"), 1)) && roundTrips(SoundPayload(code, ip("255.255.255.255"), 65535)))

    // The rules.
    check("refused: a password for other Wi-Fi", refuses { SoundPayload(code, home, 6175, ownHotspot = false, ssid = "Shop", password = "secret12") })
    check("refused: a password without the network's name", refuses { SoundPayload(code, home, 6175, ownHotspot = true, password = "secret12") })
    check("refused: a code that isn't 8 characters", refuses { SoundPayload("ABCD123") } && refuses { SoundPayload("ABCD12345") })
    check("refused: letters outside the alphabet, or lower case", refuses { SoundPayload("ABCDI234") } && refuses { SoundPayload("abcd1234") })
    check("refused: an address with port 0 or 65536", refuses { SoundPayload(code, home, 0) } && refuses { SoundPayload(code, home, 65536) })
    check("refused: a port without an address", refuses { SoundPayload(code, null, 6175) })
    check("refused: an empty name or password",
        refuses { SoundPayload(code, ssid = "") } && refuses { SoundPayload(code, ownHotspot = true, ssid = "Net", password = "") })

    // Sizes: one length byte, so 255 bytes each, counted in UTF-8.
    check("a 255-byte name and password round trip",
        roundTrips(SoundPayload(code, home, 6175, true, "n".repeat(255), "p".repeat(255))))
    check("refused: a 256-byte name", refuses { SoundPayload(code, ssid = "n".repeat(256)) })
    check("length counts UTF-8 bytes: 127 × é fits, 128 × é doesn't",
        roundTrips(SoundPayload(code, ssid = "é".repeat(127))) && refuses { SoundPayload(code, ssid = "é".repeat(128)) })

    // Odd characters.
    val odd = SoundPayload(code, home, 6175, true, "Café; \"Ñandú\" a:b\\c 🚀", "p;a,s:s\"w\\d €")
    check("odd characters in name and password round trip", roundTrips(odd))

    // Damage: every single bit flipped, and every cut, is caught.
    val good = own.encode()
    var flips = true
    for (i in good.indices) for (bit in 0 until 8) {
        val b = good.copyOf(); b[i] = (b[i].toInt() xor (1 shl bit)).toByte()
        if (SoundPayload.decode(b) != null) flips = false
    }
    check("every single flipped bit is caught", flips)
    check("every cut-off payload is refused", (0 until good.size).all { SoundPayload.decode(good.copyOf(it)) == null })
    check("an extra byte is refused", SoundPayload.decode(good + 0) == null)

    // Bytes with a correct CRC that still aren't a payload.
    val body = good.copyOf(good.size - 2)
    check("refused with a correct CRC: extra bytes", SoundPayload.decode(withCrc(body + 0)) == null)
    check("refused with a correct CRC: another version",
        SoundPayload.decode(withCrc(body.copyOf().also { it[0] = ((2 shl 4) or (it[0].toInt() and 0x0F)).toByte() })) == null)
    check("refused with a correct CRC: a password for other Wi-Fi",
        SoundPayload.decode(withCrc(body.copyOf().also { it[0] = (it[0].toInt() and SoundPayload.OWN_HOTSPOT.inv()).toByte() })) == null)
    val codeBytes = codeOnly.encode().copyOfRange(1, 1 + SoundPayload.CODE_BYTES)
    val ssidHead = byteArrayOf(((SoundPayload.VERSION shl 4) or SoundPayload.HAS_SSID).toByte())
    check("refused with a correct CRC: a zero-length name", SoundPayload.decode(withCrc(ssidHead + codeBytes + byteArrayOf(0))) == null)
    check("refused with a correct CRC: a name that isn't UTF-8",
        SoundPayload.decode(withCrc(ssidHead + codeBytes + byteArrayOf(2, 0xC3.toByte(), 0x28))) == null)
    check("a hand-made payload with a valid name is read", SoundPayload.decode(withCrc(ssidHead + codeBytes + byteArrayOf(1, 'N'.code.toByte())))
        == SoundPayload(code, ssid = "N"))

    // Logging.
    val shown = own.toString()
    check("toString hides the password", !shown.contains("Hq7xKm2pRt9wZb4n") && shown.contains("hidden"))

    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED"); return failedNames.toList()
}

/** The JUnit test: runs the checks above, and fails naming them if any fail. */
class SoundPayloadChecks {
    @org.junit.Test
    fun everyCheckPasses() {
        val failed = runChecks()
        org.junit.Assert.assertTrue("Failed checks: " + failed.joinToString("; "), failed.isEmpty())
    }
}
