package qrtest

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.appdevforall.localstore.server.QrCodes

var failures = 0
val failedNames = mutableListOf<String>()
fun check(name: String, cond: Boolean) {
    if (!cond) failedNames += name
    println((if (cond) "PASS  " else "FAIL  ") + name); if (!cond) failures++ }

fun decode(text: String, size: Int = 400): String {
    val px = QrCodes.pixels(text, size)
    return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, px)))).text
}

/** Parses a WIFI: payload back into its fields, undoing the escaping. */
fun parseWifi(s: String): Map<String, String> {
    val body = s.removePrefix("WIFI:").removeSuffix(";")
    val out = mutableMapOf<String, String>(); val cur = StringBuilder(); var key: String? = null; var i = 0
    while (i < body.length) {
        val c = body[i]
        when {
            c == '\\' && i + 1 < body.length -> { cur.append(body[i + 1]); i++ }
            c == ':' && key == null -> { key = cur.toString(); cur.clear() }
            c == ';' -> { if (key != null) out[key] = cur.toString(); key = null; cur.clear() }
            else -> cur.append(c)
        }
        i++
    }
    return out
}

/** Runs every check; returns the names of those that failed. */
fun runChecks(): List<String> {
    failures = 0; failedNames.clear()
    val url = "http://192.168.49.1:6175/"
    check("store address decodes exactly", decode(url) == url)
    val w = QrCodes.wifi("DIRECT-LS-ABCD", "Hq7xKm2pRt9wZb4n")
    check("Wi-Fi code decodes exactly", decode(w) == w)
    check("Wi-Fi code uses the standard format", w == "WIFI:T:WPA;S:DIRECT-LS-ABCD;P:Hq7xKm2pRt9wZb4n;;")
    val tricky = QrCodes.wifi("Café; \"Ñandú\", a:b\\c", "p;a,s:s\"w\\d")
    val back = parseWifi(decode(tricky))
    check("special characters survive escaping and decoding", back["S"] == "Café; \"Ñandú\", a:b\\c" && back["P"] == "p;a,s:s\"w\\d" && back["T"] == "WPA")
    check("a long address still decodes at a small size", decode("http://192.168.237.18:65535/", 180) == "http://192.168.237.18:65535/")
    run {
        // The quiet zone: white from the edge to the first dark module, about four modules wide. A finder
        // pattern's outer square is seven modules across, which gives the module's size.
        val n = 300; val p = QrCodes.pixels(url, n); val dark = { x: Int, y: Int -> p[y * n + x] == 0xFF000000.toInt() }
        val first = (0 until n).first { i -> (0 until n).any { dark(it, i) } }        // the first row with a dark pixel
        var w = 0; while (dark(first + w, first)) w++                                  // the top finder's top edge
        val module = w / 7.0
        check("QR codes have a four-module quiet zone (${"%.1f".format(first / module)} modules)", first / module in 3.5..4.6)
    }
    val px = QrCodes.pixels(url, 300)
    check("pixels are only black and white", px.all { it == 0xFF000000.toInt() || it == 0xFFFFFFFF.toInt() } && px.size == 300 * 300)
    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED"); return failedNames.toList()
}

/** The JUnit test: runs the checks above, and fails naming them if any fail. */
class QrChecks {
    @org.junit.Test
    fun everyCheckPasses() {
        val failed = runChecks()
        org.junit.Assert.assertTrue("Failed checks: " + failed.joinToString("; "), failed.isEmpty())
    }
}
