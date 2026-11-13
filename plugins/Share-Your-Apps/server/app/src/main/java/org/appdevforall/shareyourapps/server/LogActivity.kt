package org.appdevforall.shareyourapps.server

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/**
 * The full access log, per the wireframe (FullScreenLogView): one chip per file (Current, then
 * older ones by age), search, All/Requests/Status filters, pinch to zoom, pan in both directions,
 * and Share to send the chosen file off the phone. Newest lines first. To stay smooth it shows at
 * most the newest [CAP] matching lines, and says so; search always covers the whole file.
 */
class LogActivity : Activity() {

    companion object { private const val CAP = 2000 }

    private val main = Handler(Looper.getMainLooper())
    private val pal by lazy { Palette.of(this) }
    private val accent by lazy { pal.link }
    private lateinit var files: List<File>
    private var selected = 0
    private var kind = LogFilter.Kind.ALL
    private var query = ""
    private var lines: List<String> = emptyList()

    private lateinit var summary: TextView
    private lateinit var note: TextView
    private lateinit var body: TextView
    private lateinit var fileChips: LinearLayout
    private lateinit var kindChips: LinearLayout
    private val applySoon = Runnable { applyFilter() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Access log"
        val dp = resources.displayMetrics.density
        val pad = (12 * dp).toInt()
        files = LogProvider.files(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        summary = TextView(this).apply { textSize = 13f; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(summary)
            addView(Button(this@LogActivity).apply {
                text = "Share"; isAllCaps = false
                setOnClickListener { files.getOrNull(selected)?.let { LogProvider.share(this@LogActivity, listOf(it.name)) } }
            })
        })

        fileChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(fileChips) })

        val search = EditText(this).apply {
            hint = "Search"; setSingleLine(); layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); main.removeCallbacks(applySoon); main.postDelayed(applySoon, 250) }
            })
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(search)
            addView(Button(this@LogActivity).apply { text = "✕"; setOnClickListener { search.setText("") } })
        })

        kindChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(kindChips)
        note = TextView(this).apply { textSize = 12f; alpha = 0.7f }
        root.addView(note)

        body = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true); setPadding(0, pad / 2, pad, pad)
        }
        // Pan in both directions; pinch to zoom the text.
        val panes = ScrollView(this).apply { addView(HorizontalScrollView(this@LogActivity).apply { addView(body) }) }
        root.addView(ZoomFrame(this, body).apply { addView(panes) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply { text = "Pinch to zoom · swipe to pan"; textSize = 11f; alpha = 0.6f; gravity = Gravity.CENTER; setPadding(0, 4, 0, pad / 2) })
        setContentView(root)
        SystemBars.fit(root)

        renderChips()
        load(0)
    }

    private fun renderChips() {
        fileChips.removeAllViews()
        if (files.isEmpty()) fileChips.addView(TextView(this).apply { text = "No log files yet." })
        val now = System.currentTimeMillis()
        files.forEachIndexed { i, f -> fileChips.addView(chip(LogFilter.ageLabel(i, f.lastModified(), now), i == selected) { load(i) }) }
        kindChips.removeAllViews()
        listOf(LogFilter.Kind.ALL to "All", LogFilter.Kind.REQUESTS to "Requests", LogFilter.Kind.STATUS to "Status").forEach { (k, label) ->
            kindChips.addView(chip(label, k == kind) { kind = k; renderChips(); applyFilter() })
        }
    }

    private fun chip(label: String, on: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; stateListAnimator = null
        val dp = resources.displayMetrics.density
        background = GradientDrawable().apply {
            cornerRadius = 16 * dp; setColor(if (on) accent else pal.panelTop); setStroke((1 * dp).toInt(), accent)
        }
        setTextColor(if (on) pal.onLink else accent)
        minHeight = 0; minimumHeight = 0; setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (8 * dp).toInt(); topMargin = (4 * dp).toInt(); bottomMargin = (4 * dp).toInt() }
        setOnClickListener { onClick() }
    }

    /** Reads a file in the background; tapping the current chip again reloads it. */
    private fun load(i: Int) {
        selected = i
        renderChips()
        val f = files.getOrNull(i) ?: run { summary.text = "Access log"; body.text = ""; return }
        summary.text = "Loading…"
        thread(name = "read-log") {
            val read = try { f.readLines() } catch (e: Exception) { listOf("[this file couldn't be read: ${e.message}]") }
            main.post {
                if (selected != i) return@post
                lines = read
                summary.text = "${read.count { it.isNotEmpty() }} lines · ${sizeLabel(f.length())}"
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val snapshot = lines; val k = kind; val q = query
        thread(name = "filter-log") {
            val r = LogFilter.apply(snapshot, k, q, CAP)
            main.post {
                if (snapshot !== lines || k != kind || q != query) return@post // superseded
                body.text = r.lines.joinToString("\n")
                note.text = when {
                    r.matches == 0 -> "No matching lines."
                    r.matches > r.lines.size -> "Showing the newest ${r.lines.size} of ${r.matches} matching lines. Search to narrow it down."
                    else -> "${r.matches} matching lines, newest first."
                }
            }
        }
    }

    private fun sizeLabel(b: Long) = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format("%.1f KB", b / 1024.0)
        else -> String.format("%.1f MB", b / (1024.0 * 1024))
    }
}

/** Passes pinches to a zoom on [text] while leaving scrolling to the views inside. */
private class ZoomFrame(ctx: Context, private val text: TextView) : FrameLayout(ctx) {
    // Zoom in pixels, within limits of 6 sp and 28 sp converted once: TypedValue respects the
    // phone's font scale, including Android 14's nonlinear scaling.
    private val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 6f, ctx.resources.displayMetrics)
    private val maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 28f, ctx.resources.displayMetrics)
    private val zoom = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, (text.textSize * d.scaleFactor).coerceIn(minPx, maxPx))
            return true
        }
    })

    private var pinching = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        zoom.onTouchEvent(ev)
        if (zoom.isInProgress) {
            // A pinch has started: tell the scroll views to let go once, then keep it to ourselves.
            if (!pinching) {
                pinching = true
                val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            val wasPinching = pinching
            pinching = false
            if (wasPinching) return true
        }
        return if (pinching) true else super.dispatchTouchEvent(ev)
    }
}
