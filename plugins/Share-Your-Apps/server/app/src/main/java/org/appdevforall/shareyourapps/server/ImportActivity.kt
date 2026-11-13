package org.appdevforall.shareyourapps.server

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.net.InetSocketAddress
import java.text.DateFormat
import java.util.Date
import org.appdevforall.shareyourapps.server.HelpUi.help

/**
 * Importing a copy of someone else's store (design, Appendix E.2; screens 5 and 6). Opened from
 * Manage Server's Import panel, or from a share's QR link, shareyourapps-share://…, which a camera app
 * offers to open — then the owner's address and code are already filled in. The import itself runs
 * in [ImportJob], so this screen can rotate, or be left and reopened, without breaking it.
 */
class ImportActivity : Activity() {
    private lateinit var body: LinearLayout
    private val pad by lazy { (16 * resources.displayMetrics.density).toInt() }
    private var linkAddress: InetSocketAddress? = null
    private var linkCode: String? = null
    private var shownPhase: ImportJob.Phase? = null
    private var asked: ImportJob.Question? = null
    private var progress: ProgressBar? = null
    private var progressText: TextView? = null
    private val listener: () -> Unit = { render() }

    // ---- Listen: hearing the code from the owner's Play sound ----
    /** The listener while it listens, and the button, which reads Stop listening meanwhile. */
    private var soundListener: SoundIo.Listener? = null
    private var listenButton: Button? = null
    private var listenStatus: TextView? = null
    /** The name field, so a heard code imports with the name as shown. */
    private var nameField: EditText? = null
    /** Counts listeners started and stopped, so a finished one's callback can't reset a newer one. */
    private var listenRun = 0
    /** Taps on Listen within [TAP_MS] of each other: three run the self-test; one listens. */
    private var taps = 0
    private val tapDone = Runnable { val n = taps; taps = 0; if (n >= 3) selfTest() else toggleListen() }
    /** The self-test's player, while it plays. */
    private var testPlayer: SoundIo.Player? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    /** What to do once the microphone permission is answered: listen, or run the self-test. */
    private var afterPermission: (() -> Unit)? = null
    /** The line under Listen, kept so a redraw of the form shows it again. */
    private var listenMessage = ""
    /** The code was heard, not scanned: the form says so. */
    private var heard = false
    /** What the tones said about the owner's network, shown if the import from them fails: join it, then Continue. */
    private var heardNetwork: String? = null
    /** While listening: the microphone's loudness meter and the seconds left. Hidden otherwise. */
    private var meter: ProgressBar? = null
    private var meterText: TextView? = null
    private var countdownText: TextView? = null
    /** The last level reported, so a redraw of the form shows the meter and countdown as they were. */
    private var lastLevel: SoundIo.Level? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Import a store"
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        setContentView(ScrollView(this).apply { setBackgroundColor(pal.screen); addView(body) }.also { SystemBars.fit(it) })
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        intent?.data?.toString()?.let { ShareLink.parse(it) }?.let { (address, port, code) ->
            linkAddress = InetSocketAddress(address, port); linkCode = code
        }
    }

    override fun onResume() {
        super.onResume()
        ImportJob.observe(listener)
        shownScreen = this
        // A join that got no Connect tap while an earlier screen was showing (before a rotation): show its text here.
        pendingByHand?.let { b -> pendingByHand = null; if (!ImportJob.snapshot.busy) applyByHand(b) }
    }
    override fun onPause() {
        if (shownScreen === this) shownScreen = null
        ImportJob.remove(listener)
        super.onPause()
    }

    /** Back cancels an import in progress, as Cancel does; otherwise it leaves. Two ways, as elsewhere in the app. */
    private fun back() { if (ImportJob.snapshot.busy) ImportJob.cancel() else finish() }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT < 33 && keyCode == android.view.KeyEvent.KEYCODE_BACK) { back(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun render() {
        val s = ImportJob.snapshot
        ImportJob.question?.let { q -> if (q !== asked) { asked = q; ask(q) } }
        if (s.phase == ImportJob.Phase.RECEIVING && shownPhase == ImportJob.Phase.RECEIVING) return updateProgress(s)
        shownPhase = s.phase
        body.removeAllViews()
        // "Imported" only while that copy is still on this phone: it may have been removed, expired or replaced since.
        val done = s.phase == ImportJob.Phase.DONE && s.storeId != null && ImportJob.currentCopy(this)?.storeId == s.storeId
        when {
            // A phone serving its own store can't import one (Appendix E.1; screen 4b).
            !s.busy && !done && ServerStatus.url != null -> {
                body.addView(card(heading("Can't import a store here"),
                    text("This phone is serving a store. To import a copy: stop serving it, in Manage server, or import on another phone.")))
                body.addView(button("OK") { finish() }.help("import.cant"))
            }
            s.busy -> showProgress(s)
            done -> showDone(s)
            else -> showForm(s)
        }
    }

    // ---- screen 5: the code and the phone's name ----
    private fun showForm(s: ImportJob.Snapshot) {
        val code = EditText(this).apply {
            hint = "LS-XXXX-XXXX"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setText(linkCode?.let { ShareCodes.display(it) } ?: ""); textSize = 22f; typeface = Typeface.MONOSPACE
        }
        val name = EditText(this).apply { setText(defaultPhoneName()); inputType = InputType.TYPE_CLASS_TEXT; filters = arrayOf(android.text.InputFilter.LengthFilter(40)) }
        nameField = name
        val error = when (s.phase) {
            ImportJob.Phase.FAILED, ImportJob.Phase.DENIED, ImportJob.Phase.CANCELLED ->
                // A heard code whose import failed: the owner may be on a network this phone isn't on.
                if (heard && heardNetwork != null && s.phase == ImportJob.Phase.FAILED) "${s.message}\n\n$heardNetwork" else s.message
            else -> null
        }
        body.addView(card(
            heading("Import a store").help("import.code"),
            text(when {
                heard -> "The code was heard from the owner's phone."
                linkAddress != null -> "The code from the owner's QR code is filled in."
                else -> "Scan the owner's QR code with this phone's camera, type the code shown on their phone, or tap Listen while their phone plays it."
            }),
            label("Code").help("import.code"), code,
            label("Your phone's name").help("import.name"), name,
            text("The owner sees this when you ask for a copy. It starts as your phone's own name.", 13f),
            text("Both phones must be on the same Wi-Fi network or hotspot.", 13f),
            *(if (error != null) arrayOf(text(error, 15f).apply { setTextColor(pal.danger) }) else emptyArray()),
        ))
        // Listen: one tap listens for the owner's Play sound; three quick taps run the one-phone self-test.
        body.addView(button(if (soundListener != null || testPlayer != null) "Stop listening" else "Listen") { listenTapped() }
            .help("import.listen").also { listenButton = it })
        body.addView(text(listenMessage, 14f).also { listenStatus = it; it.visibility = if (listenMessage.isEmpty()) View.GONE else View.VISIBLE })
        // While listening: the seconds left, and a meter of how loud the microphone hears things. Hidden otherwise.
        body.addView(text("", 15f).also { countdownText = it })
        body.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; contentDescription = "Microphone level"
        }.also { meter = it })
        body.addView(text("", 13f).also { meterText = it })
        showLevel(if (soundListener != null) lastLevel else null)
        // A transfer that failed partway can be tried again, with the same exchange, while the owner's share is open.
        if (s.canRetry) body.addView(button("Try again") { ImportJob.retry(this) }.help("import.retry"))
        body.addView(button(if (s.canRetry) "Start over" else "Continue") {
            val normalized = ShareCodes.normalize(code.text.toString())
            if (normalized == null) { code.error = "That isn't a share code: LS, then two groups of four letters and digits."; return@button }
            val address = if (normalized == linkCode) linkAddress else null
            ImportJob.start(this, normalized, name.text.toString(), address)
        }.help("import.continue"))
    }

    /** The phone's own name, at most 40 characters: the companion's [defaultPhoneName], which import_start uses too. */
    private fun defaultPhoneName(): String = defaultPhoneName(this)

    // ---- Listen ----
    /** One tap listens, after a short wait in case more follow; three quick taps run the self-test. A tap while listening stops. */
    private fun listenTapped() {
        if (soundListener != null || testPlayer != null) {
            stopListening("Stopped.")
            SoundLog.write(this, SoundLog.stopped())
            return
        }
        taps++
        ui.removeCallbacks(tapDone)
        if (taps >= 3) tapDone.run() else ui.postDelayed(tapDone, TAP_MS)
    }

    private fun toggleListen() = withMicrophone { startListening(null) }

    /** Runs [action] once the app may use the microphone, asking first if needed. */
    private fun withMicrophone(action: () -> Unit) {
        if (SoundIo.canListen(this)) { action(); return }
        afterPermission = action
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC) return
        val then = afterPermission; afterPermission = null
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) then?.invoke()
        else {
            setListenMessage("Without the microphone, the app can't hear the code. Scan the owner's QR code, or type the code, instead.")
            SoundLog.write(this, SoundLog.microphoneRefused())
        }
    }

    /** Listens for the owner's tones; with [test], for this phone's own self-test tones instead. */
    private fun startListening(test: SoundPayload?) {
        if (ImportJob.snapshot.busy || soundListener != null) return
        val run = ++listenRun
        val doing = if (test != null) "Self-test: playing and listening on this phone…" else "Listening… Ask the owner to tap Play sound."
        setListenMessage(doing)
        var strongest = 0.0
        lastLevel = null
        soundListener = SoundIo.Listener(this,
            level = { v ->
                strongest = maxOf(strongest, v.signal)
                if (run == listenRun) { lastLevel = v; setListenMessage("$doing\n${signal(v.signal)}"); showLevel(v) }
            },
            heard = { p, how -> if (run == listenRun) { endListening(); heardCode(p, how, test) } },
            ended = { end, why ->
                if (run == listenRun) {
                    endListening(); setListenMessage(endMessage(end, why, test != null))
                    SoundLog.write(this, when {
                        test != null -> SoundLog.selfTest(false, if (end == SoundIo.End.NOTHING_HEARD) SoundLog.nothingHeard(SoundIo.WINDOW_MS / 1000, strongest) else why)
                        end == SoundIo.End.NOTHING_HEARD -> SoundLog.nothingHeard(SoundIo.WINDOW_MS / 1000, strongest)
                        else -> SoundLog.listenFailed(why)
                    })
                }
            },
        ).start()
        listenButton?.text = "Stop listening"
    }

    private fun signal(v: Double) = when {
        v < 0.15 -> "Nothing heard yet."
        v < SoundDecoder.THRESHOLD -> "Hearing something faint: hold the phones closer."
        else -> "Hearing the tones."
    }

    private fun endMessage(end: SoundIo.End, why: String, test: Boolean) = when (end) {
        SoundIo.End.NOTHING_HEARD -> if (test) "Self-test failed: this phone didn't hear its own tones. Turn the media volume up and try again."
            else "Didn't hear the code. Hold the phones closer, ask the owner to turn up the media volume, and try again."
        else -> why
    }

    /** The listener finished by itself: tidy up, and stop the self-test's tones. */
    private fun endListening() {
        soundListener = null
        testPlayer?.stop(); testPlayer = null
        listenButton?.text = "Listen"
        lastLevel = null
        showLevel(null)
    }

    /**
     * The countdown and the microphone meter while listening; hidden with null. The meter shows the loudest moment
     * since the last report, from SoundDsp.METER_FLOOR_DB (empty) to full scale (full).
     */
    private fun showLevel(v: SoundIo.Level?) {
        val visible = if (v != null) View.VISIBLE else View.GONE
        countdownText?.visibility = visible
        meter?.visibility = visible
        meterText?.visibility = visible
        if (v == null) return
        val seconds = (v.msLeft + 999) / 1000
        countdownText?.text = "Listening for ${if (seconds == 1L) "1 more second" else "$seconds more seconds"}"
        meter?.progress = SoundDsp.meterPercent(v.db)
        meterText?.text = "Microphone level: ${String.format(java.util.Locale.ROOT, "%.0f", v.db)} dB" +
            if (v.db <= SoundDsp.METER_FLOOR_DB) " (silent)" else ""
    }

    /** Stops listening, and the self-test's tones, at once; [message] replaces the line under Listen. */
    private fun stopListening(message: String) {
        listenRun++
        soundListener?.stop()
        endListening()
        setListenMessage(message)
    }

    private fun setListenMessage(t: String) {
        listenMessage = t
        listenStatus?.let { it.text = t; it.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE }
    }

    /** A code was heard: the self-test compares it; otherwise the import starts at once, as if Continue were tapped. */
    private fun heardCode(p: SoundPayload, how: String, test: SoundPayload?) {
        if (test != null) {
            val passed = p == test
            setListenMessage(if (passed) "Self-test passed: this phone heard its own tones ($how)."
                else "Self-test: heard a different code, so the tones came from another phone.")
            SoundLog.write(this, SoundLog.selfTest(passed, if (passed) how else "heard a different code, from another phone"))
            return
        }
        SoundLog.write(this, SoundLog.heard(p, how)) // never the password
        heard = true
        linkCode = p.code
        linkAddress = p.address?.let { InetSocketAddress(it, p.port) }
        heardNetwork = when {
            p.ssid == null -> null
            p.password != null -> "The owner's phone serves on its own hotspot, ${p.ssid}, password ${p.password}. Join it in Wi-Fi settings, then tap Continue."
            else -> "The owner's phone is on the Wi-Fi network ${p.ssid}. Join it, then tap Continue."
        }
        setListenMessage("Heard the code. Checking the network…")
        val name = nameField?.text?.toString()?.takeIf { it.isNotBlank() } ?: defaultPhoneName()
        val code = p.code
        val address = linkAddress
        val run = ++joinRun
        // First the phone's current Wi-Fi, for about 2 s (WifiJoin.REACH_MS), off the main thread.
        kotlin.concurrent.thread(name = "sound-reach") {
            val ok = WifiJoin.reachable(applicationContext, code, address)
            ui.post { if (run == joinRun && !isDestroyed) afterReach(p, name, ok) }
        }
    }

    /** Counts heard codes, so a late answer about an older one is ignored. */
    private var joinRun = 0

    /**
     * After the reachability check: start the import on the current Wi-Fi; or join the owner's own hotspot first
     * (Android 10+, the person taps Connect); or, on Android 9 or with no tap in 30 s, show its name and password
     * so the person joins by hand, then taps Continue. On other Wi-Fi the tones carry no password: the import
     * starts, and if it fails, the form says which network to join.
     */
    private fun afterReach(p: SoundPayload, name: String, reachable: Boolean) {
        val ssid = p.ssid
        val password = p.password
        if (reachable || ssid == null || password == null) {
            setListenMessage("Heard the code.")
            ImportJob.start(this, p.code, name, linkAddress)
            return
        }
        if (!WifiJoin.canJoin) {
            SoundLog.write(this, "the owner's hotspot $ssid must be joined by hand (Android 9)")
            setListenMessage(heardNetwork.orEmpty())
            render()
            return
        }
        SoundLog.write(this, "joining the owner's hotspot $ssid")
        setListenMessage("Joining the owner's hotspot, $ssid. Tap Connect when Android asks.")
        val run = joinRun
        // The join outlives a rotation: the import starts with the app's context, and the new screen shows it,
        // since ImportJob's listeners redraw it. Leaving the screen for good, or a newer heard code, releases the
        // join first (WifiJoin.release), so this answer never comes then.
        val app = applicationContext
        val address = linkAddress
        val byHand = heardNetwork.orEmpty()
        WifiJoin.join(this, ssid, password) { network ->
            val shown = run == joinRun && !isDestroyed
            if (network != null) {
                SoundLog.write(app, "joined the owner's hotspot $ssid")
                if (shown) setListenMessage("Joined the owner's hotspot.")
                ImportJob.start(app, p.code, name, address, network)
                WifiJoin.holdUntilImportEnds() // after start: the import is busy by now
            } else {
                SoundLog.write(app, "didn't join the owner's hotspot $ssid: no Connect tap within ${WifiJoin.CONNECT_TAP_MS / 1000} s, or refused")
                // After a rotation this screen is gone: the new one shows the join-by-hand text, with the code filled in.
                val b = ByHand(p.code, address, byHand)
                if (shown) applyByHand(b) else deliverByHand(b)
            }
        }
    }

    /** What the form needs to show the join-by-hand text for a heard code: the code, the owner's address, and the text. */
    class ByHand(val code: String, val address: InetSocketAddress?, val text: String)

    /** Shows the join-by-hand text on this screen, with the heard code filled in, so the person joins, then taps Continue. */
    private fun applyByHand(b: ByHand) {
        heard = true
        linkCode = b.code
        linkAddress = b.address
        heardNetwork = b.text
        setListenMessage(b.text)
        render()
    }

    /**
     * The screen that asked is gone (a rotation): the screen showing now gets the join-by-hand text, or, if none is
     * showing yet, the next one to resume does.
     */
    private fun deliverByHand(b: ByHand) {
        val now = shownScreen
        if (now != null && !now.isDestroyed) now.applyByHand(b) else pendingByHand = b
    }

    /** Triple tap: this phone plays a made-up own-hotspot payload and listens to itself, to test the sound path with one phone. */
    private fun selfTest() = withMicrophone {
        if (ImportJob.snapshot.busy || soundListener != null) return@withMicrophone
        val code = ShareCodes.normalize(ShareCodes.newCode()) ?: return@withMicrophone
        val address = java.net.InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 49, 1)) as java.net.Inet4Address
        val test = SoundPayload(code, address, 6175, true, "DIRECT-LS-TEST", "selftest-password")
        if (SoundIo.volumeLow(this)) android.widget.Toast.makeText(this, "The media volume is low: turn it up for the self-test.", android.widget.Toast.LENGTH_LONG).show()
        startListening(test)
        val run = listenRun
        // A moment for the microphone to start before the tones do.
        ui.postDelayed({
            if (run == listenRun && soundListener != null) testPlayer = SoundIo.Player(test.encode(), { error ->
                if (error != null && run == listenRun) stopListening(error)
            }, null).start()
        }, 300)
    }

    /** Nothing listens or plays once the screen is left: Android would record silence anyway. */
    override fun onStop() {
        ui.removeCallbacks(tapDone); taps = 0
        if (soundListener != null || testPlayer != null) {
            stopListening("Stopped: the screen was left.")
            SoundLog.write(this, SoundLog.stopped() + ": the screen was left")
        }
        super.onStop()
    }

    /**
     * Leaving the screen for good lets go of a joined hotspot, unless an import is still running on it (it carries on
     * in the background, and WifiJoin lets go when it ends). A failed transfer kept for Try again is given up then.
     */
    override fun onDestroy() {
        joinRun++ // a join still being asked for answers no one
        if (isFinishing && !isChangingConfigurations && !ImportJob.snapshot.busy) {
            WifiJoin.release()
            // A finished result (done, denied, cancelled, failed with no Try again) isn't shown on the next visit.
            ImportJob.clearFinished()
        }
        super.onDestroy()
    }

    // ---- screen 6: importing ----
    private fun showProgress(s: ImportJob.Snapshot) {
        val step = when (s.phase) { ImportJob.Phase.FINDING, ImportJob.Phase.CONNECTING -> 1; ImportJob.Phase.WAITING -> 2; else -> 3 }
        val views = mutableListOf<View>(heading(s.storeName ?: "Importing a store"))
        s.checkCode?.let { views += checkBox(it) }
        views += stepLine(1, if (step > 1) "Found the store" else s.message, step)
        views += stepLine(2, if (step > 2) "The owner allowed it" else if (step == 2) s.message else "The owner allows it", step)
        views += stepLine(3, "Receiving the store", step)
        if (step == 3) {
            views += ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }.also { progress = it }
            views += text("", 14f).also { progressText = it }
        }
        views += stepLine(4, "Ready to serve", step)
        body.addView(card(*views.toTypedArray()).help("import.check"))
        body.addView(text("Keep both phones close, with their screens on.", 13f))
        body.addView(button("Cancel") { ImportJob.cancel() }.help("import.cancel"))
        if (step == 3) updateProgress(s)
    }

    private fun updateProgress(s: ImportJob.Snapshot) {
        val fraction = if (s.total > 0) (s.received * 1000 / s.total).toInt() else 0
        progress?.progress = fraction
        progressText?.text = "${s.files} files · ${mb(s.received)} of ${mb(s.total)}"
    }

    private fun mb(bytes: Long) = if (bytes < 1_000_000) "${(bytes + 999) / 1000} KB" else String.format("%.1f MB", bytes / 1_000_000.0)

    private fun stepLine(n: Int, label: String, current: Int) = TextView(this).apply {
        text = (if (n < current) "✓  " else "$n  ") + label
        textSize = 16f; setTextColor(pal.ink)
        setTypeface(typeface, if (n == current) Typeface.BOLD else Typeface.NORMAL)
        alpha = if (n > current) 0.6f else 1f
    }

    // ---- done ----
    private fun showDone(s: ImportJob.Snapshot) {
        val expiry = s.expires?.let { "It expires on ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(it))}." } ?: "It never expires."
        body.addView(card(heading("Imported ${s.storeName}"),
            text("${s.files} files · ${mb(s.total)}. $expiry"),
            text("It's served as it was published, with your own PIN. It can't be changed or passed on.", 14f),
            // Serving it on the owner's network would depend on the owner's phone.
            // Checked now, not when the import ended: Wi-Fi may have been turned off or changed since.
            *(if (ImportJob.onOwnersNetworkNow(this)) arrayOf(text("This phone is on the owner's network. To serve the store yourself, leave it first: " +
                "join your own Wi-Fi, or turn Wi-Fi off, so this phone starts its own hotspot.", 14f).apply { setTextColor(pal.danger) }) else emptyArray())))
        body.addView(button("Serve it now") {
            startForegroundService(android.content.Intent(this, HostService::class.java).setAction(HostService.ACTION_SERVE_COPY))
            startActivity(android.content.Intent(this, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }.help("import.serve_now"))
        body.addView(button("Done") { finish() }.help("import.done"))
    }

    private fun ask(q: ImportJob.Question) {
        AlertDialog.Builder(this).setTitle(q.title).setMessage(q.message).setCancelable(false)
            .setPositiveButton("Replace") { _, _ -> q.answer(true) }
            .setNegativeButton("Keep mine") { _, _ -> q.answer(false) }
            .show()
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
    private fun label(t: String) = TextView(this).apply { text = t; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(pal.ink) }
    private fun text(t: String, size: Float = 15f) = TextView(this).apply { text = t; textSize = size; setTextColor(pal.ink) }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }

    private fun checkBox(check: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        val dp = resources.displayMetrics.density
        background = GradientDrawable().apply { setColor(pal.digits); cornerRadius = 10 * dp; setStroke((2 * dp).toInt(), pal.ink) }
        setPadding(pad, pad / 2, pad, pad / 2)
        addView(TextView(this@ImportActivity).apply { text = "Their screen should show"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(pal.ink) })
        addView(TextView(this@ImportActivity).apply {
            text = check.chunked(2).joinToString(" "); textSize = 34f; gravity = Gravity.CENTER; setTextColor(pal.ink)
            setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.15f
        })
    }

    private val pal by lazy { Palette.of(this) }

    companion object {
        /** Taps on Listen closer together than this count as one series: three run the self-test. */
        private const val TAP_MS = 400L
        private const val REQUEST_MIC = 41
        private const val LOG_TAG = "ShareYourAppsSound"
        /** The import screen in front, between onResume and onPause; null otherwise. Main thread only. */
        private var shownScreen: ImportActivity? = null
        /** Join-by-hand text for a screen that isn't in front yet (after a rotation); the next one to resume shows it. */
        private var pendingByHand: ByHand? = null

        /**
         * The name the owner sees when this phone asks for a copy: the phone's own name, or its model. At most 40
         * characters, as the import screen's field allows. Public so an MCP import (import_start) uses the same default.
         */
        fun defaultPhoneName(context: android.content.Context): String =
            ((if (Build.VERSION.SDK_INT >= 25) Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) else null)
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL).take(40)
    }
}
