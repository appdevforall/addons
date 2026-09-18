package org.appdevforall.ailiteracycourse.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.appdevforall.ailiteracycourse.AiLiteracyCoursePlugin
import org.appdevforall.ailiteracycourse.CourseInstaller
import org.appdevforall.ailiteracycourse.R
import java.io.File
import java.io.FileInputStream

/**
 * Full-screen host for the course. Ensures the bundle is extracted (once),
 * serves the extracted tree over a virtual https origin via [WebViewAssetLoader],
 * and loads the generated shell.
 *
 * Three host requirements are handled explicitly:
 *   - BACK always works: exit fullscreen video → step back through course
 *     history → close the screen.
 *   - Rotation: page + scroll restored via [WebView.saveState]/[WebView.restoreState].
 *   - Fullscreen video: HTML5 fullscreen routed through [WebChromeClient].
 */
class CourseFragment : Fragment() {

    private var root: FrameLayout? = null
    private var webView: WebView? = null
    private var loadingView: View? = null
    private var progressBar: ProgressBar? = null
    private var statusTitle: TextView? = null
    private var statusDetail: TextView? = null

    // Read from the WebView's request thread, written on the main thread.
    @Volatile
    private var assetLoader: WebViewAssetLoader? = null

    // Fullscreen video state.
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private lateinit var backCallback: OnBackPressedCallback

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        // Resolve R.layout.* against the plugin's APK, not the host IDE's.
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(AiLiteracyCoursePlugin.PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_course, container, false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        root = view as? FrameLayout
        webView = view.findViewById(R.id.course_web_view)
        loadingView = view.findViewById(R.id.course_loading)
        progressBar = view.findViewById(R.id.course_progress)
        statusTitle = view.findViewById(R.id.course_status)
        statusDetail = view.findViewById(R.id.course_status_detail)

        val wv = webView ?: return
        configureWebView(wv)
        registerBackHandler()
        prepareAndLoad(savedInstanceState)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true          // activities + NeuroPocket are JS apps
            domStorageEnabled = true          // course uses device-local storage
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false           // everything is served via the https origin
            allowContentAccess = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }

        // Serve the extracted course at https://appassets.androidplatform.net/course/.
        wv.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader?.shouldInterceptRequest(request.url)
        }

        // Route HTML5 fullscreen video through the host container.
        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                root?.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                webView?.visibility = View.GONE
            }

            override fun onHideCustomView() = hideCustomView()
        }
    }

    private fun registerBackHandler() {
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                when {
                    customView != null -> hideCustomView()       // leave fullscreen video
                    wv != null && wv.canGoBack() -> wv.goBack()  // back through the course
                    else -> {                                    // close the plugin screen
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
    }

    private fun prepareAndLoad(savedInstanceState: Bundle?) {
        val ctx = AiLiteracyCoursePlugin.pluginContext
        if (ctx == null) {
            showError()
            return
        }
        showLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val courseRoot = try {
                withContext(Dispatchers.IO) {
                    CourseInstaller.ensureInstalled(ctx) { step ->
                        statusDetail?.post { statusDetail?.text = step }
                    }
                }
            } catch (t: Throwable) {
                ctx.logger.error("Course install failed", t)
                showError()
                return@launch
            }

            val wv = webView ?: return@launch
            try {
                assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/course/", CoursePathHandler(courseRoot))
                    .build()
            } catch (t: Throwable) {
                ctx.logger.error("Could not serve course from ${courseRoot.absolutePath}", t)
                showError()
                return@launch
            }

            showLoading(false)
            // Restore page + scroll on rotation; otherwise open the shell.
            if (savedInstanceState == null || wv.restoreState(savedInstanceState) == null) {
                wv.loadUrl(START_URL)
            }
        }
    }

    private fun hideCustomView() {
        val cv = customView ?: return
        root?.removeView(cv)
        customView = null
        webView?.visibility = View.VISIBLE
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    private fun showLoading(show: Boolean) {
        loadingView?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showError() {
        showLoading(true)
        progressBar?.visibility = View.GONE
        statusTitle?.setText(R.string.prepare_failed)
        statusDetail?.text = ""
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
        hideCustomView()
        webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        webView = null
        root = null
        loadingView = null
        progressBar = null
        statusTitle = null
        statusDetail = null
        assetLoader = null
        super.onDestroyView()
    }

    /**
     * Serves the extracted course straight from [rootDir] over the virtual
     * https origin. Replaces [WebViewAssetLoader.InternalStoragePathHandler],
     * whose internal-storage location check rejects the plugin data dir under
     * the host's wrapped resource context. Guards against path traversal and
     * tags each file with a correct MIME type (matters for the service worker,
     * JS modules, and video).
     */
    private class CoursePathHandler(rootDir: File) : WebViewAssetLoader.PathHandler {
        private val root = rootDir.canonicalFile

        override fun handle(path: String): WebResourceResponse? = try {
            val file = File(root, path).canonicalFile
            if (!file.path.startsWith(root.path) || !file.isFile) {
                null
            } else {
                val mime = mimeOf(file.name)
                val encoding = if (mime.startsWith("text/") ||
                    mime == "application/json" ||
                    mime == "image/svg+xml" ||
                    mime == "application/manifest+json"
                ) "utf-8" else null
                WebResourceResponse(mime, encoding, FileInputStream(file))
            }
        } catch (_: Exception) {
            null
        }

        private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "webmanifest" -> "application/manifest+json"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "pdf" -> "application/pdf"
            "wasm" -> "application/wasm"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "txt", "md" -> "text/plain"
            "ftl" -> "text/plain"          // PDF.js viewer locale (Fluent) files
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val START_URL =
            "https://appassets.androidplatform.net/course/index.html"
    }
}
