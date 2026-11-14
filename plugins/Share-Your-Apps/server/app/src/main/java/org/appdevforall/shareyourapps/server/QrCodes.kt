package org.appdevforall.shareyourapps.server

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The Serve screen's QR codes: joining the network, and opening the store. */
object QrCodes {

    /**
     * The standard Wi-Fi QR format that phone cameras recognise, for a WPA network. Backslash,
     * semicolon, comma, colon and double quote are escaped with a backslash, as the format
     * requires, so a name or password containing them still joins correctly.
     */
    fun wifi(ssid: String, password: String): String = "WIFI:T:WPA;S:${esc(ssid)};P:${esc(password)};;"

    private fun esc(s: String) = buildString { for (c in s) { if (c in "\\;,:\"") append('\\'); append(c) } }

    /** Black-on-white ARGB pixels, [size] × [size], with the standard four-module quiet zone, so a code sits on
     *  clear white, whatever the panel behind it, and scans readily. One module let the panel's grey show close around it. */
    fun pixels(text: String, size: Int): IntArray {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 4,
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        return IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    }
}
