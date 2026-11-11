package org.appdevforall.localstore.server

import android.content.Context
import android.content.res.Configuration

/**
 * Every colour the server draws itself, in a light and a dark version, chosen from the phone's light or dark mode.
 * Android's own controls follow the same setting through the app's day-and-night theme (res/values-night).
 * Android recreates each screen when the mode changes, so each takes its palette once, when it starts.
 * The Serve screen is the exception: it's always light, since cameras read QR codes best black on white.
 */
class Palette private constructor(val night: Boolean) {
    val screen = if (night) 0xFF121418.toInt() else 0xFFE2E5EA.toInt()        // behind the panels
    val panelTop = if (night) 0xFF2B3038.toInt() else 0xFFFFFFFF.toInt()      // a panel's face, top to bottom
    val panelBottom = if (night) 0xFF1F232A.toInt() else 0xFFDCE0E6.toInt()
    val cardBottom = if (night) 0xFF23272E.toInt() else 0xFFF0F2F5.toInt()    // the Share and Import screens' cards
    val stroke = if (night) 0xFF3B414B.toInt() else 0xFFB9C0CA.toInt()        // panels' and cards' edges
    val rim = if (night) 0xFF0A0C0F.toInt() else 0xFF9AA3AE.toInt()           // the bevel's lower edge
    val shadow = if (night) 0x99000000.toInt() else 0x55000000
    val highlight = if (night) 0x22FFFFFF else 0xE6FFFFFF.toInt()             // the bevel's upper edge
    val ink = if (night) 0xFFE6E8EC.toInt() else 0xFF20242B.toInt()           // text
    val muted = if (night) 0xFFA3AAB5.toInt() else 0xFF5B6270.toInt()
    val link = if (night) 0xFFA9BCF5.toInt() else 0xFF485D92.toInt()
    val onLink = if (night) 0xFF121418.toInt() else 0xFFFFFFFF.toInt()        // text on a link-coloured button
    val good = if (night) 0xFF81C784.toInt() else 0xFF2E7D32.toInt()
    val bad = if (night) 0xFFF28B82.toInt() else 0xFFC2261A.toInt()
    val danger = if (night) 0xFFF28B82.toInt() else 0xFFA1261B.toInt()        // Remove this store, failures
    val startFill = 0xFF2E7D32.toInt() // Start button, enabled
    val stopFill = if (night) 0xFFB3261E.toInt() else 0xFFC2261A.toInt()      // Stop button, enabled
    val fillOff = if (night) 0xFF34383D.toInt() else 0xFFE6E8EB.toInt()       // a disabled Start or Stop: grey, like Share this store...
    val onFill = 0xFFFFFFFF.toInt()                                           // text on those fills
    val onFillOff = if (night) 0xFF8A9088.toInt() else 0xFF5B6270.toInt()
    val copied = if (night) 0xFF81C784.toInt() else 0xFF4CAF50.toInt()        // an address, just copied
    val digits = if (night) 0xFF4A3F22.toInt() else 0xFFFFF3D6.toInt()        // the four digits' box
    val help = if (night) 0xFF3A332A.toInt() else 0xFFF5ECD7.toInt()          // help: beige, or a warm dark brown
    val helpInk = if (night) 0xFFEDE6D8.toInt() else 0xFF222222.toInt()
    val helpEdge = if (night) 0x33FFFFFF else 0x33000000

    companion object {
        fun of(c: Context) = Palette((c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
    }
}
