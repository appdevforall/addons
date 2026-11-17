package org.appdevforall.shareyourapps.server

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import java.io.ByteArrayInputStream

/** Long-press help: Tier 1 is the summary, "See more" adds Tier 2 and links to Tier-3 pages. */
object HelpUi {

    fun <V : View> V.help(tag: String): V = apply {
        setOnLongClickListener { v -> show(v, tag); true }
    }

    fun show(anchor: View, tag: String) {
        val ctx = anchor.context
        val pal = Palette.of(ctx)
        val tip = try { HelpDb.store(ctx).tooltip(tag) } catch (e: Exception) { null }
        val dp = ctx.resources.displayMetrics.density
        val pad = (14 * dp).toInt()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val scroll = ScrollView(ctx).apply {
            addView(box)
            background = GradientDrawable().apply { setColor(pal.help); cornerRadius = 10 * dp; setStroke((1 * dp).toInt(), pal.helpEdge) }
            elevation = 8 * dp
        }
        val screen = ctx.resources.displayMetrics
        val popup = PopupWindow(scroll, minOf((360 * dp).toInt(), (screen.widthPixels * 0.9).toInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = 8 * dp

        fun text(html: String, size: Float) = TextView(ctx).apply {
            text = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT); textSize = size; setTextColor(pal.helpInk); setLinkTextColor(pal.link)
        }
        if (tip == null) {
            box.addView(text("No help for this yet.", 15f))
        } else {
            // Tier 1, with a faint ⓘ in the corner: the documentation rows behind this help, as in CodeOnTheGo.
            val info = TextView(ctx).apply {
                text = "ⓘ"; textSize = 16f; setTextColor(pal.helpInk); alpha = 0.35f
                val touch = (48 * dp).toInt(); minWidth = touch; minHeight = touch; gravity = Gravity.TOP or Gravity.END
                contentDescription = "Help data, for developers"
                setOnClickListener { showRows(ctx, tag) }
            }
            box.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(text(tip.summary, 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(info)
            })
            if (tip.detail.isNotBlank() || tip.buttons.isNotEmpty()) {
                // "+ See more": a link, not a button, as CodeOnTheGo shows it — still a full-size touch target.
                val more = TextView(ctx).apply {
                    text = android.text.SpannableString("+ See more").apply { setSpan(android.text.style.UnderlineSpan(), 0, length, 0) }
                    textSize = 15f; setTextColor(pal.link); minHeight = (48 * dp).toInt(); gravity = Gravity.CENTER_VERTICAL
                    isClickable = true; isFocusable = true
                }
                more.setOnClickListener {
                    box.removeView(more)
                    if (tip.detail.isNotBlank()) box.addView(text(tip.detail, 14f).apply { setPadding(0, pad / 2, 0, 0) })
                    for ((label, uri) in tip.buttons) box.addView(Button(ctx).apply {
                        text = label; isAllCaps = false
                        setOnClickListener { popup.dismiss(); HelpActivity.open(ctx, uri) }
                    })
                    place(popup, anchor, box, showing = true)
                }
                box.addView(more)
            }
        }
        place(popup, anchor, box, showing = false)
    }

    // Help sits on beige, or in dark mode a warm dark brown (Palette.help), apart from the screens around it.

    /** The ⓘ panel: which documentation database rows supplied this help. Selectable, so it can be copied into a report. */
    private fun showRows(ctx: android.content.Context, tag: String) {
        val sections = try { HelpDb.store(ctx).debug(tag) } catch (e: Exception) { listOf("Error" to listOf(e.toString())) }
        val text = sections.joinToString("\n\n") { (title, lines) -> title + "\n" + lines.joinToString("\n") { "  $it" } }
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
        val view = TextView(ctx).apply {
            this.text = text; typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true)
            setPadding(pad, pad / 2, pad, 0)   // the dialog's own text colour, light or dark
        }
        android.app.AlertDialog.Builder(ctx).setTitle("Help data").setView(ScrollView(ctx).apply { addView(view) })
            .setPositiveButton("OK", null).show()
    }

    /**
     * Puts the popup wholly on the screen, beside [anchor]: below it if it fits there, else above it, else on whichever
     * side has more room, scrolling inside. showAsDropDown only ever went below, so help for a button at the bottom
     * of a screen (Done, Cancel) was cut off. Placed again after "See more", since the popup grows.
     */
    private fun place(popup: PopupWindow, anchor: View, box: View, showing: Boolean) {
        val dp = anchor.resources.displayMetrics.density
        val margin = (8 * dp).toInt()
        // The part of the screen the app can use, without the status and navigation bars.
        val frame = android.graphics.Rect().also { anchor.getWindowVisibleDisplayFrame(it) }
        val at = IntArray(2).also { anchor.getLocationOnScreen(it) }
        box.measure(
            View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        // A long detail scrolls inside the popup instead of filling the screen.
        val wanted = minOf(box.measuredHeight, (frame.height() * 0.7).toInt())
        val below = frame.bottom - margin - (at[1] + anchor.height)
        val above = at[1] - margin - (frame.top + margin)
        val (y, height) = when {
            wanted <= below -> at[1] + anchor.height to wanted
            wanted <= above -> at[1] - wanted to wanted
            below >= above -> at[1] + anchor.height to below
            else -> at[1] - above to above
        }
        val x = at[0].coerceIn(frame.left + margin, maxOf(frame.left + margin, frame.right - margin - popup.width))
        if (showing) popup.update(x, y, popup.width, height)
        else { popup.height = height; popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, y) }
    }
}

/**
 * Tier-3 help pages, read from the help database. The WebView answers every request itself,
 * from the database, so it can't reach the network; links between help pages work normally.
 */
class HelpActivity : Activity() {

    companion object {
        private const val EXTRA_PATH = "path"
        private const val HOST = "help.shareyourapps.invalid"

        fun open(ctx: Context, path: String) {
            ctx.startActivity(Intent(ctx, HelpActivity::class.java).putExtra(EXTRA_PATH, path))
        }
    }

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Help"
        // Beige behind the page too, like the pages themselves, so nothing flashes white while one loads.
        web = WebView(this).apply { setBackgroundColor(Palette.of(this@HelpActivity).help) }
        web.settings.javaScriptEnabled = false
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, t: String?) { if (!t.isNullOrBlank()) title = t }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != HOST // help pages only; anything else goes nowhere

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url
                if (url.host != HOST) return reply(403, "text/plain", "Help pages can't reach the network.".toByteArray())
                val path = url.path.orEmpty().removePrefix("/").ifEmpty { "index.html" }
                return try {
                    val page = HelpDb.store(this@HelpActivity).page(path)
                    if (page == null) reply(404, "text/html", "<p>There's no help page called $path.".toByteArray())
                    else reply(200, page.mimeType, page.bytes)
                } catch (e: Exception) {
                    reply(500, "text/html", "<p>This help page couldn't be read: ${e.message}".toByteArray())
                }
            }
        }
        setContentView(web)
        SystemBars.fit(web)
        web.loadUrl("https://$HOST/" + (intent.getStringExtra(EXTRA_PATH) ?: "index.html"))
        // Android 13+ delivers Back through this callback; apps targeting Android 16 get predictive
        // back by default, and then onBackPressed() is never called at all.
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
    }

    private fun reply(code: Int, mime: String, body: ByteArray) =
        WebResourceResponse(mime, "utf-8", code, if (code == 200) "OK" else "Error", emptyMap(), ByteArrayInputStream(body))

    /** Back goes back a page within help, and leaves help from the first page. */
    private fun back() {
        if (web.canGoBack()) web.goBack() else finish()
    }

    /** Before Android 13, Back arrives as a key event. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT < 33 && keyCode == KeyEvent.KEYCODE_BACK) { back(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
