package org.appdevforall.aichat

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat

/**
 * The chat tab: a WebView showing the bundled web app (web/, built into assets/web/), served over
 * a virtual https origin, with [ChatBridge] installed as `window.CoGoChat`.
 *
 * The bridge can start generation and write the chat history, so the WebView never leaves the
 * bundled app: any other navigation is refused, and links the model writes open in the browser
 * through the bridge instead.
 */
class ChatFragment : Fragment() {

    private var webView: WebView? = null
    private var bridge: ChatBridge? = null
    private var sheetListener: Pair<View, View.OnLayoutChangeListener>? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val wv = WebView(ctx)
        webView = wv
        return FrameLayout(ctx).apply {
            addView(wv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val wv = webView ?: return
        val pluginContext = AiChatPlugin.pluginContext
        if (pluginContext == null) {
            wv.loadDataWithBaseURL(null, "<p style='font-family:sans-serif;padding:16px'>AI Chat is not active.</p>", "text/html", "utf-8", null)
            return
        }

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            // Nothing in a chat may make a network request of its own: the bundle is local and
            // remote images are dropped by the renderer. Requests are blocked here as well.
            blockNetworkLoads = true
            setSupportZoom(false)
        }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/web/", BundlePathHandler())
            .build()

        wv.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Stay on the bundled app; refuse everything else.
                return !(request.url.host == ORIGIN_HOST && request.url.path.orEmpty().startsWith("/web/"))
            }
        }

        val b = ChatBridge(pluginContext, wv)
        bridge = b
        wv.addJavascriptInterface(b, "CoGoChat")

        if (savedInstanceState == null || wv.restoreState(savedInstanceState) == null) {
            wv.loadUrl(START_URL)
        }

        keepClearOfBottomSheet(view)
    }

    /**
     * The editor's tab area runs underneath the IDE's collapsed bottom sheet (build status, logs).
     * A code editor just scrolls, but the composer is pinned to the bottom of the page, so pad the
     * page by however much the collapsed sheet overlaps this tab.
     *
     * The sheet is found by its id name in the host's own resources; when the sheet is expanded it
     * covers the whole tab anyway, so only a partial overlap updates the padding.
     */
    private fun keepClearOfBottomSheet(root: View) {
        val activity = activity ?: return
        val density = resources.displayMetrics.density
        // The host's collapsed sheet is 100dp and its tab container has a 16dp bottom margin.
        val fallback = (FALLBACK_SHEET_OVERLAP_DP * density).toInt()
        @SuppressLint("DiscouragedApi")
        val sheetId = activity.resources.getIdentifier("bottom_sheet", "id", activity.packageName)
        val sheet: View? = if (sheetId != 0) activity.findViewById(sheetId) else null
        if (sheet == null) {
            root.setPadding(0, 0, 0, fallback)
            return
        }

        val mine = IntArray(2)
        val theirs = IntArray(2)
        val update = {
            if (root.isAttachedToWindow && root.height > 0) {
                root.getLocationOnScreen(mine)
                sheet.getLocationOnScreen(theirs)
                val contentBottom = mine[1] + root.height
                val overlap = contentBottom - theirs[1]
                // Expanded or half-expanded: the tab is hidden; keep the collapsed value.
                if (overlap in 0..(root.height / 2) && overlap != root.paddingBottom) {
                    root.setPadding(0, 0, 0, overlap)
                }
            }
        }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> root.post(update) }
        sheet.addOnLayoutChangeListener(listener)
        root.addOnLayoutChangeListener(listener)
        sheetListener = sheet to listener
        root.setPadding(0, 0, 0, fallback)
        root.post(update)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroyView() {
        sheetListener?.let { (sheet, l) -> sheet.removeOnLayoutChangeListener(l) }
        sheetListener = null
        bridge?.detach()
        bridge = null
        webView?.let { wv ->
            wv.removeJavascriptInterface("CoGoChat")
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        webView = null
        super.onDestroyView()
    }

    /**
     * Serves assets/web/ from this plugin's own APK. Read through the plugin's class loader, which
     * is the one place every CoGo version can find a plugin's bundled files.
     */
    private class BundlePathHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val clean = path.ifEmpty { "index.html" }
            if (clean.contains("..")) return null
            val stream = ChatFragment::class.java.classLoader?.getResourceAsStream("assets/web/$clean") ?: return null
            val mime = mimeOf(clean)
            val encoding = if (mime.startsWith("text/") || mime == "application/json" || mime == "image/svg+xml") "utf-8" else null
            return WebResourceResponse(mime, encoding, stream)
        }

        private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val ORIGIN_HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$ORIGIN_HOST/web/index.html"
        private const val FALLBACK_SHEET_OVERLAP_DP = 84
    }
}
