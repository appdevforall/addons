package org.appdevforall.shareyourapps.server

import android.app.Activity
import android.app.DatePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import org.appdevforall.shareyourapps.server.HelpUi.help

/**
 * Sharing a copy of the store with another seller (design, Appendix E.2; screens 2 and 3). Binds
 * to the server process, where the share lives beside the HTTP server, asks it what's happening
 * twice a second, and shows: the code and QR while waiting; "Allow a copy?" once a phone has asked,
 * with the check code and the copy's expiry; then the outcome.
 *
 * A share already under way when the screen opens — one an MCP client started, opened from its
 * "asks for a copy" notification, or one this screen showed before — is joined, not replaced, so its
 * code keeps working. Otherwise the screen opens a share of its own. Leaving the screen closes only a
 * share it opened itself; Cancel closes either kind. Allow and Deny are only ever sent from here, by a person.
 * The screen stays on while it's open, since a server paused for a dark screen can't share. Play sound plays the
 * code as tones, for a phone that taps Listen; they stop when the share moves on or the screen closes.
 */
class ShareActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var server: Messenger? = null
    private var bound = false
    /** Whether this screen has chosen its share: joined one already under way, or opened its own. Kept across rotation. */
    private var opened = false
    /**
     * This screen opened the share, so leaving it closes the share. A share it joined — one an MCP client
     * started, opened from the "asks for a copy" notification — stays open when the screen is left;
     * only Cancel, a deliberate choice, closes it.
     */
    private var ownShare = false
    private var lastState = ""
    /** Play sound: the player while the code plays, and its button, which reads Stop sound meanwhile. */
    private var player: SoundIo.Player? = null
    private var playButton: Button? = null
    private lateinit var body: LinearLayout
    private val pad by lazy { (16 * resources.displayMetrics.density).toInt() }

    // The expiry choice, kept while the owner decides.
    private var choice = MONTH
    private var chosenDate: LocalDate = LocalDate.now().plusMonths(1)

    private val replies = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == Ipc.MSG_SHARE_STATE) show(msg.data); true
    })

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            server = Messenger(service)
            // Not opened yet: the first poll's answer decides, in show(), whether to join a share
            // already under way or open a new one.
            main.post(poll)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            server = null
            showMessage("The server stopped", "Sharing stopped with it. Start the server, then share again.")
        }
    }

    private val poll = object : Runnable {
        override fun run() { if (server != null) { send(Ipc.MSG_SHARE_POLL); main.postDelayed(this, 500) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A rotation recreates this screen: the share it opened or joined carries on, rather than a new one opening.
        opened = savedInstanceState?.getBoolean("opened") == true
        ownShare = savedInstanceState?.getBoolean("ownShare") == true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        setContentView(ScrollView(this).apply { setBackgroundColor(pal.screen); addView(body) }.also { SystemBars.fit(it) })
        title = "Share this store"
        showMessage("Share this store", "Connecting to the server…")
        // No BIND_AUTO_CREATE: if the server isn't running, it isn't started just for this.
        bound = bindService(Intent(this, ServerService::class.java), conn, 0)
        main.postDelayed({
            if (server == null) showMessage("The server isn't running", "Start the server in Manage server, then share again.")
        }, 1500)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out); out.putBoolean("opened", opened); out.putBoolean("ownShare", ownShare)
    }

    override fun onDestroy() {
        // Nothing plays off the screen: leaving it, or rotating, stops the tones. The "played" line is sent now,
        // while the connection to the server is still open, rather than when the player thread ends.
        val wasPlaying = player != null
        stopSound()
        if (wasPlaying) logPlayedNow?.invoke()
        logPlayedNow = null
        main.removeCallbacksAndMessages(null)
        // Only leaving the screen closes the share, and only one this screen opened; a rotation, or another
        // app on top, doesn't. A share it joined (an MCP client's) stays open: Cancel closes that one.
        if (isFinishing && !isChangingConfigurations && ownShare) send(Ipc.MSG_SHARE_CLOSE)
        if (bound) try { unbindService(conn) } catch (e: IllegalArgumentException) { }
        super.onDestroy()
    }

    private fun send(what: Int, data: Bundle? = null) {
        val m = server ?: return
        try { m.send(Message.obtain(null, what).apply { replyTo = replies; if (data != null) this.data = data }) } catch (e: RemoteException) { }
    }

    /** Redraws only when the state changes, so a choice being made isn't reset by the next poll. */
    private fun show(b: Bundle) {
        val state = b.getString(Ipc.KEY_SHARE_STATE) ?: return
        // The first answer decides: a share still under way (an MCP client's, say, opened from its
        // "asks for a copy" notification) is joined, not replaced, so its code keeps working. Anything
        // else gets a new share of this screen's own. Not serving: nothing to open.
        if (!opened && state != "NONE") {
            opened = true
            if (state !in UNDER_WAY) {
                ownShare = true; lastState = ""
                send(Ipc.MSG_SHARE_OPEN)
                return
            }
        }
        val key = state + "|" + (b.getString(Ipc.KEY_SHARE_CHECK) ?: "") + "|" + b.getInt(Ipc.KEY_SHARE_FAILURES)
        if (state == "OPEN") refreshCountdown(b.getLong(Ipc.KEY_SHARE_CODE_EXPIRES))
        if (key == lastState) return
        lastState = key
        // The other phone has connected, or the share ended: the tones have done their job.
        if (state != "OPEN") stopSound()
        val store = b.getString(Ipc.KEY_SHARE_STORE).orEmpty()
        val phone = b.getString(Ipc.KEY_SHARE_PHONE).orEmpty()
        when (state) {
            "OPEN" -> showCode(b.getString(Ipc.KEY_SHARE_CODE).orEmpty(), b.getString(Ipc.KEY_SHARE_LINK).orEmpty(), b.getLong(Ipc.KEY_SHARE_CODE_EXPIRES),
                b.getString(Ipc.KEY_SHARE_APP_URL))
            "PREPARING" -> showMessage("Share this store", "Getting the store ready to share… This takes a moment after publishing.")
            "ASKED" -> showMessage("A phone is connecting", "Waiting for the other phone to finish connecting…")
            "REVEALED" -> showAllow(store, phone, b.getString(Ipc.KEY_SHARE_CHECK).orEmpty())
            "ALLOWED" -> if (b.getInt(Ipc.KEY_SHARE_FAILURES) > 0)
                showMessage("The copy didn't arrive", "The transfer to $phone didn't finish. $phone can try again while this screen stays open.")
                else showMessage("Sending the copy", "Sending $store to $phone. Keep this screen open until it's done.")
            "DONE" -> showMessage("Copy sent", "$phone has a copy of $store.", done = true)
            "DENIED" -> showMessage("Request denied", "$phone didn't get a copy.", done = true, again = true)
            "CLOSED" -> showMessage("This share has ended", "Its code expired, or was used wrongly too often. Share again for a new code.", done = true, again = true)
            "NONE" -> showMessage("The server isn't serving", "Start the server in Manage server, then share again.", done = true)
            else -> showMessage("Can't share", b.getString(Ipc.KEY_SHARE_ERROR) ?: "Something went wrong.", done = true, again = true)
        }
    }

    // ---- screen 2: the code and QR ----
    private var countdown: TextView? = null

    private fun showCode(code: String, link: String, expiresAt: Long, appUrl: String?) {
        body.removeAllViews()
        body.addView(card(
            heading("Share this store").help("share.code"),
            text("On the other phone, open Share Your Apps Server and tap Import a store. Then scan this code with the other phone's camera, or type the code below into it."),
            qr(link),
            text("Or type this code", 13f, center = true),
            codeRow(code),
            soundButton(code, link),
            text("Both phones must be on the same Wi-Fi network or hotspot. You'll be asked to allow the request before anything is sent.", 14f),
            text("", 13f).apply { visibility = View.GONE }.also { countdown = it },
        ))
        // A phone that doesn't have the app yet can get it from this one first.
        if (appUrl != null) body.addView(card(
            text("No Share Your Apps Server on the other phone?", 15f, bold = true).help("share.app"),
            text("Scan this first with the other phone's camera, to download Share Your Apps Server from this phone and install it on the other phone. Then scan the code above.", 14f),
            qr(appUrl, 140),
            text("Or open this link in the other phone's browser:", 13f, center = true),
            copyRow(appUrl, 18f, clipLabel = "Share Your Apps Server download link", description = "Copy the link",
                copied = "Link copied", sensitive = false, lines = 2),
        ))
        // Cancel always ends the share, a joined one too: it's the person's clear choice. Leaving by Back doesn't, for a joined share.
        body.addView(button("Cancel") { send(Ipc.MSG_SHARE_CLOSE); finish() }.help("share.cancel"))
        refreshCountdown(expiresAt)
    }

    /** The code, with a button beside it that copies it for pasting into a message. */
    private fun codeRow(code: String) = copyRow(code, 30f, clipLabel = "Share code", description = "Copy the code",
        copied = "Code copied", sensitive = true)

    // ---- Play sound ----
    /** Counts players started and stopped, so a finished player's callback can't reset a newer one's button. */
    private var soundRun = 0

    /** Play sound, or Stop sound while the tones play. */
    private fun soundButton(code: String, link: String): Button =
        button(if (player != null) "Stop sound" else "Play sound") { if (player != null) stopSound() else playSound(code, link) }
            .help("share.sound").also { playButton = it }

    /**
     * Logs the playing player's "played" line at once, as stopped early. onDestroy calls it while the connection to
     * the server is still open; the player's own callback, coming later, then logs nothing.
     */
    private var logPlayedNow: (() -> Unit)? = null

    private fun playSound(code: String, link: String) {
        val payload = soundPayload(code, link) ?: return toast("Couldn't turn this code into sound.")
        val low = SoundIo.volumeLow(this)
        if (low) toast("The media volume is low: turn it up so the other phone hears the tones.")
        val run = ++soundRun
        var logged = false
        lateinit var p: SoundIo.Player
        p = SoundIo.Player(payload.encode(), { error ->
            if (run == soundRun) { player = null; playButton?.text = "Play sound" }
            if (error != null) toast(error)
            if (!logged) { logged = true; logSound(SoundLog.played(p.burstsPlayed, p.bursts, p.stoppedEarly, low, payload.ownHotspot, error)) }
            main.post { countdown?.text = ""; countdown?.visibility = View.GONE } // Clear and hide countdown when sound ends
        }, { played ->
            // Update the countdown on the main thread
            main.post {
                val remainingAttempts = p.bursts - played
                if (remainingAttempts > 0) {
                    countdown?.text = attemptsText(remainingAttempts)
                    countdown?.visibility = View.VISIBLE
                } else {
                    countdown?.text = ""; countdown?.visibility = View.GONE // Clear and hide when done
                }
            }
        })
        logPlayedNow = { if (!logged) { logged = true; logSound(SoundLog.played(p.burstsPlayed, p.bursts, true, low, payload.ownHotspot, null)) } }
        player = p.start()
        playButton?.text = "Stop sound"
        // Initial countdown display
        countdown?.text = attemptsText(p.bursts)
        countdown?.visibility = View.VISIBLE
    }

    /** The countdown line: each attempt is one burst of the code. */
    private fun attemptsText(n: Int) = "Playing sound... ($n ${if (n == 1) "attempt" else "attempts"} remaining)"

    /** The server process owns the log file while it serves, so the line goes to it, over the share's connection. */
    private fun logSound(message: String) {
        val line = SoundLog.line(message)
        android.util.Log.i("ShareYourAppsSound", line)
        send(Ipc.MSG_LOG, Bundle().apply { putString(Ipc.KEY_LINE, line) })
    }

    private fun stopSound() {
        soundRun++
        player?.stop(); player = null
        playButton?.text = "Play sound"
        countdown?.visibility = View.GONE
    }

    /**
     * What the tones carry: the code and the server's address from the share link; on this phone's own hotspot,
     * also its name and password. On other Wi-Fi, neither name nor password (Hotspot.info is null there).
     */
    private fun soundPayload(code: String, link: String): SoundPayload? {
        val normalized = ShareCodes.normalize(code) ?: return null
        val parsed = ShareLink.parse(link)
        val address = parsed?.let { try { java.net.InetAddress.getByName(it.first) as? java.net.Inet4Address } catch (e: Exception) { null } }
        val port = if (address != null && parsed != null) parsed.second else 0
        val hotspot = Hotspot.info
        fun fits(s: String) = s.isNotEmpty() && s.toByteArray(Charsets.UTF_8).size <= SoundPayload.MAX_FIELD
        val ssid = hotspot?.ssid?.takeIf { it != "?" && fits(it) }
        val password = hotspot?.password?.takeIf { ssid != null && fits(it) }
        return try { SoundPayload(normalized, if (port > 0) address else null, port, hotspot != null, ssid, password) }
        catch (e: IllegalArgumentException) { null }
    }

    private fun toast(t: String) { android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show() }

    /**
     * A QR code's text, with a button beside it that copies it. [sensitive] keeps it out of the clipboard preview,
     * for the share code; the app's download link is fine to show. [lines] is how many lines the text may take: one for
     * the share code; two for the download link, which on a narrow phone doesn't fit on one even at the smallest size,
     * and was cut off, showing only the tops of its second line's characters as dots.
     */
    private fun copyRow(value: String, maxSp: Float, clipLabel: String, description: String, copied: String, sensitive: Boolean, lines: Int = 1) = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        // The text takes the width the copy button leaves, shrinking to fit rather than overflowing.
        addView(text(value, maxSp, bold = true, center = true).apply {
            setTextIsSelectable(true); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); maxLines = lines
            setAutoSizeTextTypeUniformWithConfiguration(minOf(12, maxSp.toInt()), maxSp.toInt(), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val size = (48 * resources.displayMetrics.density).toInt()
        addView(android.widget.ImageButton(this@ShareActivity).apply {
            setImageDrawable(CopyIcon(pal.ink))
            val inset = (12 * resources.displayMetrics.density).toInt(); setPadding(inset, inset, inset, inset)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val ripple = android.util.TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
            setBackgroundResource(ripple.resourceId)
            contentDescription = description
            setOnClickListener {
                // Sensitive: Android 13+ hides it from the clipboard preview, and sync services and keyboards
                // generally don't pass it on. The share code is what lets a phone ask for a copy, so it shouldn't travel far.
                val clip = android.content.ClipData.newPlainText(clipLabel, value)
                if (sensitive) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                // Android 13 and later confirm a copy themselves.
                if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(this@ShareActivity, copied, android.widget.Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(size, size).apply { marginStart = pad / 2 })
    }

    private fun refreshCountdown(expiresAt: Long) {
        val minutes = ((expiresAt - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(0)
        if (player == null) {
            countdown?.text = "This code works for one phone, for ${if (minutes == 1L) "1 more minute" else "$minutes more minutes"}."
            countdown?.visibility = View.VISIBLE
        }
    }

    // ---- screen 3: Allow a copy? ----
    private fun showAllow(store: String, phone: String, check: String) {
        countdown = null
        body.removeAllViews()
        val month = LocalDate.now().plusMonths(1)
        val dateButton = Button(this).help("share.expiry").apply { text = shortDate(chosenDate); isEnabled = choice == DATE }
        val group = RadioGroup(this).apply {
            fun option(id: Int, label: String) = addView(RadioButton(this@ShareActivity).apply { this.id = id; text = label; textSize = 16f; minHeight = (44 * resources.displayMetrics.density).toInt() })
            option(WEEK, "In a week"); option(MONTH, "In a month · ${shortDate(month)}"); option(DATE, "On a date"); option(NEVER, "Never")
            check(choice)
            setOnCheckedChangeListener { _, id -> choice = id; dateButton.isEnabled = id == DATE }
        }.help("share.expiry")
        dateButton.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d -> chosenDate = LocalDate.of(y, m + 1, d); dateButton.text = shortDate(chosenDate) },
                chosenDate.year, chosenDate.monthValue - 1, chosenDate.dayOfMonth).apply {
                datePicker.minDate = System.currentTimeMillis() + 86_400_000L
            }.show()
        }
        body.addView(card(
            heading("Allow a copy?").help("share.allow"),
            text("$phone is asking for a copy of $store.", 16f),
            checkBox(check),
            text("The copy expires", 14f, bold = true),
            group,
            dateButton,
            text("On that date it's deleted from their phone. Until then they serve it with their own PIN. They can't change it or pass it on.", 14f),
        ))
        body.addView(LinearLayout(this).apply {
            addView(button("Deny") { send(Ipc.MSG_SHARE_DENY) }.help("share.deny"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("Allow") { send(Ipc.MSG_SHARE_ALLOW, Bundle().apply { putLong(Ipc.KEY_SHARE_COPY_EXPIRES, expiryMs()) }) }.help("share.allow"),
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = pad / 2 })
        })
    }

    /** The chosen expiry, as the end of that day on this phone, or -1 for never. */
    private fun expiryMs(): Long {
        val zone = ZoneId.systemDefault()
        return when (choice) {
            NEVER -> -1
            WEEK -> ZonedDateTime.now(zone).plusWeeks(1).toInstant().toEpochMilli()
            DATE -> chosenDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            else -> ZonedDateTime.now(zone).plusMonths(1).toInstant().toEpochMilli()
        }
    }

    private fun shortDate(d: LocalDate): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant()))

    // ---- messages and outcomes ----
    private fun showMessage(title: String, message: String, done: Boolean = false, again: Boolean = false) {
        countdown = null
        body.removeAllViews()
        body.addView(card(heading(title), text(message, 16f)))
        // A new share from this screen is its own: leaving the screen closes it.
        if (again) body.addView(button("Share again") { ownShare = true; lastState = ""; send(Ipc.MSG_SHARE_OPEN) }.help("share.again"))
        if (done) body.addView(button("Done") { finish() }.help("share.done"))
    }

    // ---- building blocks, in the app's style ----
    private fun card(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val dp = resources.displayMetrics.density
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(pal.panelTop, pal.cardBottom)).apply {
            cornerRadius = 12 * dp; setStroke((1 * dp).toInt(), pal.stroke)
        }
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pad }
        views.forEach { v -> addView(v, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pad / 2 }) }
    }

    private fun heading(t: String) = TextView(this).apply { text = t; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(pal.ink) }

    private fun text(t: String, size: Float = 15f, bold: Boolean = false, center: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(pal.ink)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (center) gravity = Gravity.CENTER
    }

    private fun checkBox(check: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        val dp = resources.displayMetrics.density
        background = GradientDrawable().apply { setColor(pal.digits); cornerRadius = 10 * dp; setStroke((2 * dp).toInt(), pal.ink) }
        setPadding(pad, pad / 2, pad, pad / 2)
        addView(text("Check that the other phone's screen shows", 13f, center = true))
        addView(text(check.chunked(2).joinToString(" "), 34f, bold = true, center = true).apply { letterSpacing = 0.15f })
    }

    private fun qr(content: String, dp: Int = 220) = ImageView(this).apply {
        val size = (dp * resources.displayMetrics.density).toInt()
        setImageBitmap(Bitmap.createBitmap(QrCodes.pixels(content, size), size, size, Bitmap.Config.ARGB_8888))
        layoutParams = LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL }
        contentDescription = if (dp == 220) "QR code for importing this store" else "QR code for downloading Share Your Apps Server"
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }

    private val pal by lazy { Palette.of(this) }

    companion object {
        private val WEEK = View.generateViewId(); private val MONTH = View.generateViewId()
        private val DATE = View.generateViewId(); private val NEVER = View.generateViewId()
        /** A share in these states is still under way: this screen joins it rather than opening a new one. */
        private val UNDER_WAY = setOf("OPEN", "PREPARING", "ASKED", "REVEALED", "ALLOWED")

        fun open(context: Context) = context.startActivity(Intent(context, ShareActivity::class.java))
    }
}
