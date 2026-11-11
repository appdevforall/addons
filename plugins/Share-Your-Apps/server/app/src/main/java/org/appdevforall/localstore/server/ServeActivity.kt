package org.appdevforall.localstore.server

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PersistableBundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.appdevforall.localstore.server.HelpUi.help

/**
 * The screen the owner holds up for buyers: step 1, a QR code to join the network; step 2, a QR
 * code to open the store. Always black on white, so cameras read it easily, and it keeps the
 * screen on while it's showing.
 *
 * On a hotspot, step 1 carries its name and password. On Wi-Fi, Android doesn't let apps read the
 * joined network's password, so the owner may type it. It's held only in memory — kept across
 * rotation, font-size and theme changes by the framework's retained instance, never written to
 * disk — and gone when the screen closes. With no password typed, only step 2 is shown.
 */
class ServeActivity : Activity() {

    /** Kept across configuration changes, in memory only. */
    private class Typed(var ssid: String, var password: String, var done: Boolean = false)
    private lateinit var typed: Typed
    /** What this screen was drawn for; any change redraws it, so buyers never see stale codes. */
    private var shownUrl: String? = null
    private var shownHotspot: String? = null
    private val statusListener: () -> Unit = {
        if (ServerStatus.url != shownUrl || ServerStatus.hotspot != shownHotspot) recreate()
    }

    private lateinit var step1: LinearLayout
    private var step2Label: TextView? = null
    private lateinit var scroll: ScrollView
    private var ownerBox: LinearLayout? = null
    private var pad = 0
    private var qrSize = 0
    private val ink = Color.parseColor("#20242b")
    private val muted = Color.parseColor("#6b7280")
    private val copiedGreen = Color.parseColor("#4CAF50")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        // The name Android can report is a joined network's, never the phone's own Mobile hotspot's.
        typed = (lastNonConfigurationInstance as? Typed) ?: Typed(if (ServerStatus.onPhoneHotspot) "" else joinedSsid(), "")

        val dm = resources.displayMetrics
        val dp = dm.density
        qrSize = minOf((dm.widthPixels * 0.72).toInt(), (320 * dp).toInt())
        pad = (20 * dp).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(pad, pad, pad, pad)
        }
        scroll = ScrollView(this).apply { setBackgroundColor(Color.WHITE); addView(column) }
        setContentView(scroll)
        SystemBars.fit(scroll)

        val storeName = getSharedPreferences(HostService.PREFS, MODE_PRIVATE).getString(HostService.PREF_STORE_NAME, null) ?: "Local Store"
        column.addView(text(storeName, 26f, bold = true))
        val url = ServerStatus.url
        shownUrl = url
        shownHotspot = ServerStatus.hotspot
        if (url == null) {
            column.addView(text("The store isn't being served right now. Start the server, or publish from the Local Store plugin; the codes appear here as soon as it's serving.", 16f).apply { setPadding(0, pad, 0, pad) })
            column.addView(Button(this).help("serve.close").apply { text = "Close"; setOnClickListener { finish() } })
            return
        }

        step1 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val h = Hotspot.info
        if (h == null) {
            // Owner-facing, so it comes first: the joined network's name and, optionally, its password.
            ownerBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
                background = GradientDrawable().apply { setColor(Color.parseColor("#eef0f3")); cornerRadius = pad / 2f }
            }
            column.addView(ownerBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad / 2; bottomMargin = pad })
            renderOwnerBox()
        }
        column.addView(step1)
        if (h != null) showStep1(h.ssid, h.password, showPassword = true) else refreshStep1()

        step2Label = text("", 20f, bold = true).apply { setPadding(0, pad, 0, pad / 2) }
        column.addView(step2Label)
        numberSteps()
        column.addView(qr(url))
        column.addView(copyable(url, url).apply { setPadding(0, pad / 2, 0, 0) })
        // These networks have no internet, so a buyer's phone may send the browser over mobile data instead;
        // airplane mode, then Wi-Fi, leaves Wi-Fi the only way out. Airplane mode is also the easiest switch to find.
        if (h != null || ServerStatus.onPhoneHotspot) column.addView(text(
            "This network has no internet. If a buyer's phone can't open the store, have them turn on airplane mode, then turn Wi-Fi back on. " +
                "Remind them to turn airplane mode off afterwards.", 14f)
            .apply { setPadding(0, pad, 0, 0) })
        column.addView(Button(this).help("serve.done").apply { text = "Done"; setOnClickListener { finish() } },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = pad })
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any = typed

    override fun onResume() { super.onResume(); ServerStatus.observe(statusListener) }
    override fun onPause() { super.onPause(); ServerStatus.remove(statusListener) }

    private fun refreshStep1() {
        if (typed.ssid.isNotBlank() && typed.password.isNotEmpty()) showStep1(typed.ssid, typed.password, showPassword = false)
        else {
            step1.removeAllViews()
            if (typed.ssid.isNotBlank()) step1.addView(text("Buyers need to be on the Wi-Fi network “${typed.ssid}” first.", 15f).apply { setTextColor(muted) })
        }
        numberSteps()
    }

    /** "2. Open the store" when there's a step 1 code above it; just "Open the store" otherwise. */
    private fun numberSteps() {
        val hasJoinCode = step1.childCount > 1
        step2Label?.text = if (hasJoinCode) "2. Open the store" else "Open the store"
    }

    private fun showStep1(ssid: String, password: String, showPassword: Boolean) {
        step1.removeAllViews()
        step1.addView(text("1. Join this Wi-Fi network", 20f, bold = true).apply { setPadding(0, 0, 0, qrSize / 20) })
        step1.addView(qr(QrCodes.wifi(ssid, password)))
        // A hotspot's password is the app's own, and buyers may have to type it. A home
        // network's password stays off the screen, and off the clipboard: the code carries it.
        val gap = qrSize / 20
        val network = copyable("Network: $ssid", ssid)
        if (!showPassword) {
            step1.addView(network.apply { setPadding(0, gap, 0, 0) })
            return
        }
        val pass = copyable("Password: $password", password, sensitive = true)
        // The two lines, a brace as tall as both, and an icon that copies both.
        network.gravity = Gravity.START
        pass.gravity = Gravity.START
        val lines = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(network); addView(pass) }
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, gap, 0, 0)
            addView(lines)
            addView(BraceView(this@ServeActivity, ink), LinearLayout.LayoutParams((14 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                marginStart = (8 * dp).toInt(); marginEnd = (6 * dp).toInt()
            })
            addView(CopyIconView(this@ServeActivity, ink).apply {
                contentDescription = "Copy the network name and password"
                setOnClickListener { copy("$ssid\n$password", sensitive = true); flash(network); flash(pass) }
            }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
        }
        step1.addView(row)
    }

    /** A line that copies [value] when tapped, and flashes a green "Copied!" in its place. */
    private fun copyable(label: String, value: String, sensitive: Boolean = false) = text(label, 16f).apply {
        tag = label
        setOnClickListener { copy(value, sensitive); flash(this) }
    }

    private fun copy(value: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText("Local Store", value)
        // Hides a password from the clipboard preview on Android 13+; ignored before that.
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    private fun flash(v: TextView) {
        v.text = "Copied!"
        v.setTextColor(copiedGreen)
        v.animate().alpha(0.2f).setDuration(120).withEndAction { v.animate().alpha(1f).setDuration(120) }
        v.postDelayed({ v.text = v.tag as String; v.setTextColor(ink) }, 1200)
    }

    /** The Wi-Fi fields while they're being filled in; afterwards, one line with a way back. */
    private fun renderOwnerBox() {
        val box = ownerBox ?: return
        box.removeAllViews()
        if (typed.done) {
            box.addView(text("Joining “${typed.ssid}” · Change", 14f).apply {
                setTextColor(muted)
                setOnClickListener { typed.done = false; renderOwnerBox() }
            })
            return
        }
        val phone = ServerStatus.onPhoneHotspot
        box.addView(text(if (phone) "For you, not buyers: to show a code for joining your Mobile hotspot, enter its name and password, from Settings. They aren't saved."
            else "For you, not buyers: to show a code for joining this Wi-Fi network, enter its password. It isn't saved.", 13f).apply { setTextColor(muted) }.help("server.serve_wifi"))
        val problem = text("", 13f).apply { setTextColor(Color.parseColor("#f44336")); visibility = View.GONE }
        box.addView(field(if (phone) "Mobile hotspot name" else "Wi-Fi network name", typed.ssid, password = false, onDone = null) { typed.ssid = it; refreshStep1() })
        box.addView(field(if (phone) "Mobile hotspot password" else "Wi-Fi password", typed.password, password = true, onDone = { finishEntry(problem) }) { typed.password = it; refreshStep1() })
        box.addView(problem)
        box.addView(Button(this).help("serve.show_join").apply { text = "Show the join code"; isAllCaps = false; setOnClickListener { finishEntry(problem) } })
    }

    /** Closes the keyboard, collapses the fields, and brings the codes into view. */
    private fun finishEntry(problem: TextView) {
        if (typed.ssid.isBlank() || typed.password.isEmpty()) {
            problem.text = "Enter both the network name and its password."
            problem.visibility = View.VISIBLE
            return
        }
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(window.decorView.windowToken, 0)
        typed.done = true
        renderOwnerBox()
        refreshStep1()
        scroll.post { scroll.smoothScrollTo(0, 0) }
    }

    private fun field(hint: String, value: String, password: Boolean, onDone: (() -> Unit)?, onChange: (String) -> Unit) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(ink)
        setSingleLine()
        // The keyboard's action key: Next on the name, Done on the password, which shows the code.
        imeOptions = if (onDone != null) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
        if (password) {
            // Kept out of the keyboard's learning and suggestions; the password type also blocks copying.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        if (onDone != null) setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { onDone(); true } else false
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString().orEmpty()) }
        })
    }

    private fun qr(content: String) = ImageView(this).apply {
        setImageBitmap(Bitmap.createBitmap(QrCodes.pixels(content, qrSize), qrSize, qrSize, Bitmap.Config.ARGB_8888))
        layoutParams = LinearLayout.LayoutParams(qrSize, qrSize)
        contentDescription = "QR code"
    }

    private fun text(t: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(ink); gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    /**
     * The joined network's name, when Android will tell us: reading it needs location access,
     * which this app only holds on Android 12 and earlier. Otherwise the owner types it.
     */
    private fun joinedSsid(): String = try {
        @Suppress("DEPRECATION")
        val raw = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.ssid
        raw?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: ""
    } catch (e: Exception) { "" }
}

/** A "}" drawn to exactly the height it's given, so it spans the lines beside it. */
private class BraceView(ctx: Context, color: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = 2 * ctx.resources.displayMetrics.density
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val s = paint.strokeWidth
        val w = width.toFloat() - s
        val h = height.toFloat()
        val m = w / 2
        path.reset()
        path.moveTo(s, s)
        path.quadTo(m, s, m, h * 0.22f)
        path.lineTo(m, h * 0.40f)
        path.quadTo(m, h / 2, w, h / 2)          // the point
        path.quadTo(m, h / 2, m, h * 0.60f)
        path.lineTo(m, h * 0.78f)
        path.quadTo(m, h - s, s, h - s)
        canvas.drawPath(path, paint)
    }
}

/** The familiar copy symbol: two overlapping rounded squares. */
private class CopyIconView(ctx: Context, color: Int) : View(ctx) {
    private val dp = ctx.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 2 * dp }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; style = Paint.Style.FILL }

    init { isClickable = true; isFocusable = true }

    override fun onDraw(canvas: Canvas) {
        val side = 15 * dp
        val shift = 5 * dp
        val r = 3 * dp
        val x = (width - side - shift) / 2f
        val y = (height - side - shift) / 2f
        val back = RectF(x + shift, y, x + shift + side, y + side)
        val front = RectF(x, y + shift, x + side, y + shift + side)
        canvas.drawRoundRect(back, r, r, line)
        canvas.drawRoundRect(front, r, r, fill)   // covers the back square where they overlap
        canvas.drawRoundRect(front, r, r, line)
    }
}
