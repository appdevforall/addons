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
    }
}
