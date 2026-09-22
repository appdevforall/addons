package org.appdevforall.markdownpreviewer.fragments

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import org.appdevforall.markdownpreviewer.MarkdownPreviewerPlugin
import org.appdevforall.markdownpreviewer.PreviewState
import org.appdevforall.markdownpreviewer.R
import org.appdevforall.markdownpreviewer.viewmodel.MarkdownPreviewViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeFileService
import com.itsaky.androidide.plugins.services.IdeProjectService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MarkdownPreviewFragment : Fragment() {

    companion object {
        private const val PLUGIN_ID = MarkdownPreviewerPlugin.PLUGIN_ID
    }

    private val viewModel get() = MarkdownPreviewViewModel

    private var projectService: IdeProjectService? = null
    private var fileService: IdeFileService? = null

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var btnSelectFile: ImageButton
    private lateinit var btnSelectFromStorage: ImageButton
    private lateinit var btnToggleView: ImageButton
    private lateinit var btnEmptySelectProject: Button
    private lateinit var btnEmptySelectStorage: Button
    private lateinit var currentFileText: TextView
    private lateinit var placeholderText: TextView
    private lateinit var emptyStateContainer: LinearLayout
    private lateinit var sourceScrollView: ScrollView
    private lateinit var sourceCodeText: TextView

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val ctx = context?.applicationContext
        if (ctx == null) {
            PreviewState.pendingUri = uri
            return@registerForActivityResult
        }
        viewModel.loadUri(ctx, uri)
    }

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(PLUGIN_ID, inflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            val serviceRegistry = PluginFragmentHelper.getServiceRegistry(PLUGIN_ID)
            projectService = serviceRegistry?.get(IdeProjectService::class.java)
            fileService = serviceRegistry?.get(IdeFileService::class.java)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_markdown_preview, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)
        setupWebView()
        setupClickListeners()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(it) }
            }
        }

        checkPendingFile()
        processPendingUri()
    }

    override fun onResume() {
        super.onResume()
        if (view != null) render(viewModel.uiState.value)
        processPendingUri()
    }

    fun checkPendingFile() {
        PreviewState.consumePendingFile()?.let { path ->
            viewModel.loadFile(File(path))
        }
    }

    private fun processPendingUri() {
        val uri = PreviewState.consumePendingUri() ?: return
        viewModel.loadUri(requireContext(), uri)
    }

    private fun initializeViews(view: View) {
        webView = view.findViewById(R.id.web_view)
        progressBar = view.findViewById(R.id.progress_bar)
        statusContainer = view.findViewById(R.id.status_container)
        statusText = view.findViewById(R.id.tv_status)
        btnSelectFile = view.findViewById(R.id.btn_select_file)
        btnSelectFromStorage = view.findViewById(R.id.btn_select_storage)
        btnToggleView = view.findViewById(R.id.btn_toggle_view)
        btnEmptySelectProject = view.findViewById(R.id.btn_empty_select_project)
        btnEmptySelectStorage = view.findViewById(R.id.btn_empty_select_storage)
        currentFileText = view.findViewById(R.id.tv_current_file)
        placeholderText = view.findViewById(R.id.tv_placeholder)
        emptyStateContainer = view.findViewById(R.id.empty_state_container)
        sourceScrollView = view.findViewById(R.id.source_scroll_view)
        sourceCodeText = view.findViewById(R.id.tv_source_code)
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webView.webViewClient = WebViewClient()
        webView.setBackgroundColor(
            if (isNightMode()) Color.parseColor("#000000") else Color.WHITE
        )
    }

    private fun setupClickListeners() {
        val projectClick = View.OnClickListener { showProjectFilePicker() }
        val storageClick = View.OnClickListener { openFilePicker() }

        btnSelectFile.setOnClickListener(projectClick)
        btnEmptySelectProject.setOnClickListener(projectClick)

        btnSelectFromStorage.setOnClickListener(storageClick)
        btnEmptySelectStorage.setOnClickListener(storageClick)

        btnToggleView.setOnClickListener { viewModel.toggleSource() }
    }

    private fun render(state: MarkdownPreviewViewModel.UiState) {
        when (state) {
            is MarkdownPreviewViewModel.UiState.Empty -> renderEmpty()
            is MarkdownPreviewViewModel.UiState.Loading -> renderLoading(state.message)
            is MarkdownPreviewViewModel.UiState.Loaded -> renderLoaded(state)
            is MarkdownPreviewViewModel.UiState.Error -> renderError(state.message)
        }
    }

    private fun renderEmpty() {
        statusContainer.visibility = View.GONE
        webView.visibility = View.GONE
        sourceScrollView.visibility = View.GONE
        emptyStateContainer.visibility = View.VISIBLE
        currentFileText.visibility = View.GONE
        placeholderText.visibility = View.VISIBLE
        btnToggleView.visibility = View.GONE
    }

    private fun renderLoading(message: String) {
        progressBar.visibility = View.VISIBLE
        statusContainer.visibility = View.VISIBLE
        statusContainer.setBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.status_background)
        )
        statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_text))
        statusText.text = message
    }

    private fun renderLoaded(state: MarkdownPreviewViewModel.UiState.Loaded) {
        progressBar.visibility = View.GONE
        statusContainer.visibility = View.GONE
        emptyStateContainer.visibility = View.GONE
        placeholderText.visibility = View.GONE
        currentFileText.visibility = View.VISIBLE
        currentFileText.text = state.fileName
        btnToggleView.visibility = View.VISIBLE

        if (state.showingSource) {
            webView.visibility = View.GONE
            sourceScrollView.visibility = View.VISIBLE
            sourceCodeText.text = state.content
            btnToggleView.setImageResource(R.drawable.ic_visibility)
            btnToggleView.contentDescription = getString(R.string.view_preview)
            btnToggleView.tooltipText = getString(R.string.view_preview)
        } else {
            sourceScrollView.visibility = View.GONE
            webView.visibility = View.VISIBLE
            btnToggleView.setImageResource(R.drawable.ic_source_code)
            btnToggleView.contentDescription = getString(R.string.view_source)
            btnToggleView.tooltipText = getString(R.string.view_source)
            renderHtml(state.content, state.isMarkdown)
        }
    }

    private fun renderError(message: String) {
        progressBar.visibility = View.GONE
        statusContainer.visibility = View.VISIBLE
        statusText.text = message
        statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_error_text))
        statusContainer.setBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.status_error_background)
        )
    }

    private fun showProjectFilePicker() {
        val project = projectService?.getCurrentProject()
        if (project == null) {
            showMessage(R.string.no_project)
            return
        }
        val rootDir = project.rootDir
        viewLifecycleOwner.lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { findSupportedFiles(rootDir) }
            if (files.isEmpty()) showMessage(R.string.no_files_found)
            else showFileSelectionDialog(files, rootDir)
        }
    }

    private fun findSupportedFiles(rootDir: File): List<File> {
        return rootDir.walkTopDown()
            .onEnter { it == rootDir || (it.name != "build" && !it.name.startsWith(".")) }
            .filter { it.isFile && MarkdownPreviewerPlugin.isSupportedFile(it) }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    private fun showMessage(messageRes: Int) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_from_project)
            .setMessage(messageRes)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showFileSelectionDialog(files: List<File>, rootDir: File) {
        val fileNames = files.map { it.absolutePath.removePrefix(rootDir.absolutePath + "/") }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select File to Preview")
            .setItems(fileNames) { _, which -> viewModel.loadFile(files[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "text/markdown",
                "text/x-markdown",
                "text/html",
                "application/xhtml+xml",
                "text/plain"
            ))
        }
        runCatching { pickFileLauncher.launch(intent) }
    }

    private fun renderHtml(content: String, isMarkdown: Boolean) {
        val html = if (isMarkdown) buildMarkdownHtml(content) else wrapHtmlContent(content)
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun buildMarkdownHtml(markdown: String): String {
        val isDarkMode = isNightMode()
        val textColor = if (isDarkMode) "#E0E0E0" else "#121212"
        val bgColor = if (isDarkMode) "#000000" else "#FFFFFF"
        val codeBlockBg = if (isDarkMode) "#0D0D0D" else "#F5F5F5"
        val codeBorder = if (isDarkMode) "#1A1A1A" else "#E0E0E0"
        val linkColor = if (isDarkMode) "#B1C5FF" else "#485D92"
        val blockquoteBorder = if (isDarkMode) "#616161" else "#BDBDBD"
        val blockquoteBg = if (isDarkMode) "#0A0A0A" else "#FAFAFA"
        val tableBorder = if (isDarkMode) "#1A1A1A" else "#DDDDDD"
        val tableHeaderBg = if (isDarkMode) "#121212" else "#F0F0F0"

        return """
            <!DOCTYPE html>
            <html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                * { box-sizing: border-box; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif; font-size: 16px; line-height: 1.6; color: $textColor; background-color: $bgColor; padding: 16px; margin: 0; word-wrap: break-word; }
                h1, h2, h3, h4, h5, h6 { margin-top: 24px; margin-bottom: 16px; font-weight: 600; line-height: 1.25; }
                h1 { font-size: 2em; border-bottom: 1px solid $codeBorder; padding-bottom: 0.3em; }
                h2 { font-size: 1.5em; border-bottom: 1px solid $codeBorder; padding-bottom: 0.3em; }
                h3 { font-size: 1.25em; }
                p { margin-top: 0; margin-bottom: 16px; }
                a { color: $linkColor; text-decoration: none; }
                a:hover { text-decoration: underline; }
                code { font-family: 'SF Mono', Monaco, 'Cascadia Code', Consolas, monospace; font-size: 0.875em; background-color: $codeBlockBg; padding: 0.2em 0.4em; border-radius: 4px; }
                pre { background-color: $codeBlockBg; border: 1px solid $codeBorder; border-radius: 6px; padding: 16px; overflow-x: auto; font-size: 0.875em; line-height: 1.45; }
                pre code { background-color: transparent; padding: 0; border-radius: 0; }
                blockquote { margin: 0 0 16px 0; padding: 0 16px; border-left: 4px solid $blockquoteBorder; background-color: $blockquoteBg; color: ${if (isDarkMode) "#AAAAAA" else "#666666"}; }
                ul, ol { margin-top: 0; margin-bottom: 16px; padding-left: 2em; }
                li { margin-bottom: 4px; }
                table { border-collapse: collapse; width: 100%; margin-bottom: 16px; }
                th, td { border: 1px solid $tableBorder; padding: 8px 12px; text-align: left; }
                th { background-color: $tableHeaderBg; font-weight: 600; }
                img { max-width: 100%; height: auto; }
                hr { border: none; border-top: 1px solid $codeBorder; margin: 24px 0; }
                .task-list-item { list-style-type: none; }
                .task-list-item input[type="checkbox"] { margin-right: 8px; }
            </style></head><body>${markdownToInnerHtml(markdown)}</body></html>
        """.trimIndent()
    }

    private fun markdownToInnerHtml(markdown: String): String {
        var html = markdown

        html = html.replace(Regex("```(\\w*)\\n([\\s\\S]*?)```")) { match ->
            val lang = match.groupValues[1]
            val code = match.groupValues[2].trim()
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            "\n<pre><code class=\"language-$lang\">$code</code></pre>\n"
        }
        html = html.replace(Regex("`([^`]+)`")) { match ->
            val code = match.groupValues[1]
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            "<code>$code</code>"
        }
        html = convertTables(html)
        html = html.replace(Regex("^######\\s+(.*)$", RegexOption.MULTILINE)) { "<h6>${it.groupValues[1]}</h6>" }
        html = html.replace(Regex("^#####\\s+(.*)$", RegexOption.MULTILINE)) { "<h5>${it.groupValues[1]}</h5>" }
        html = html.replace(Regex("^####\\s+(.*)$", RegexOption.MULTILINE)) { "<h4>${it.groupValues[1]}</h4>" }
        html = html.replace(Regex("^###\\s+(.*)$", RegexOption.MULTILINE)) { "<h3>${it.groupValues[1]}</h3>" }
        html = html.replace(Regex("^##\\s+(.*)$", RegexOption.MULTILINE)) { "<h2>${it.groupValues[1]}</h2>" }
        html = html.replace(Regex("^#\\s+(.*)$", RegexOption.MULTILINE)) { "<h1>${it.groupValues[1]}</h1>" }
        html = html.replace(Regex("\\*\\*\\*(.+?)\\*\\*\\*")) { "<strong><em>${it.groupValues[1]}</em></strong>" }
        html = html.replace(Regex("___(.+?)___")) { "<strong><em>${it.groupValues[1]}</em></strong>" }
        html = html.replace(Regex("\\*\\*(.+?)\\*\\*")) { "<strong>${it.groupValues[1]}</strong>" }
        html = html.replace(Regex("__(.+?)__")) { "<strong>${it.groupValues[1]}</strong>" }
        html = html.replace(Regex("\\*([^*]+)\\*")) { "<em>${it.groupValues[1]}</em>" }
        html = html.replace(Regex("_([^_]+)_")) { "<em>${it.groupValues[1]}</em>" }
        html = html.replace(Regex("~~(.+?)~~")) { "<del>${it.groupValues[1]}</del>" }
        html = html.replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")) {
            "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>"
        }
        html = html.replace(Regex("!\\[([^\\]]*)]\\(([^)]+)\\)")) {
            "<img src=\"${it.groupValues[2]}\" alt=\"${it.groupValues[1]}\">"
        }
        html = html.replace(Regex("^>\\s+(.*)$", RegexOption.MULTILINE)) {
            "<blockquote>${it.groupValues[1]}</blockquote>"
        }
        html = html.replace(Regex("^(-{3,}|\\*{3,}|_{3,})$", RegexOption.MULTILINE)) { "<hr>" }
        html = html.replace(Regex("^\\s*-\\s+\\[x\\]\\s+(.*)$", RegexOption.MULTILINE)) {
            "<li class=\"task-list-item\"><input type=\"checkbox\" checked disabled> ${it.groupValues[1]}</li>"
        }
        html = html.replace(Regex("^\\s*-\\s+\\[\\s\\]\\s+(.*)$", RegexOption.MULTILINE)) {
            "<li class=\"task-list-item\"><input type=\"checkbox\" disabled> ${it.groupValues[1]}</li>"
        }
        html = html.replace(Regex("^\\s*[-*+]\\s+(.*)$", RegexOption.MULTILINE)) { "<li>${it.groupValues[1]}</li>" }
        html = html.replace(Regex("^\\s*\\d+\\.\\s+(.*)$", RegexOption.MULTILINE)) { "<li>${it.groupValues[1]}</li>" }
        html = html.replace(Regex("((?:<li[^>]*>.*?</li>\\s*)+)")) { match ->
            val content = match.groupValues[1]
            if (content.contains("task-list-item")) "<ul style=\"list-style: none; padding-left: 0;\">$content</ul>"
            else "<ul>$content</ul>"
        }

        val lines = html.split("\n")
        val result = StringBuilder()
        var inParagraph = false
        for (line in lines) {
            val trimmed = line.trim()
            val isBlockElement = trimmed.startsWith("<h") || trimmed.startsWith("<ul") ||
                trimmed.startsWith("<ol") || trimmed.startsWith("<table") ||
                trimmed.startsWith("<blockquote") || trimmed.startsWith("<pre") ||
                trimmed.startsWith("<hr") || trimmed.startsWith("</")
            when {
                trimmed.isEmpty() -> {
                    if (inParagraph) { result.append("</p>\n"); inParagraph = false }
                    result.append("\n")
                }
                isBlockElement -> {
                    if (inParagraph) { result.append("</p>\n"); inParagraph = false }
                    result.append(trimmed).append("\n")
                }
                else -> {
                    if (!inParagraph) { result.append("<p>"); inParagraph = true }
                    else result.append("<br>")
                    result.append(trimmed)
                }
            }
        }
        if (inParagraph) result.append("</p>")
        return result.toString()
    }

    private fun convertTables(markdown: String): String {
        val lines = markdown.split("\n")
        val result = StringBuilder()
        var inTable = false
        var headerProcessed = false
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("|") && line.endsWith("|")) {
                val isHeader = i + 1 < lines.size &&
                    lines[i + 1].trim().matches(Regex("\\|[-:\\s|]+\\|"))
                val isSeparator = line.matches(Regex("\\|[-:\\s|]+\\|"))
                if (isSeparator) continue
                if (!inTable) {
                    result.append("<table>\n"); inTable = true; headerProcessed = false
                }
                val cells = line.split("|").filter { it.isNotBlank() }.map { it.trim() }
                if (isHeader && !headerProcessed) {
                    result.append("<thead><tr>")
                    cells.forEach { result.append("<th>$it</th>") }
                    result.append("</tr></thead>\n<tbody>\n")
                    headerProcessed = true
                } else {
                    result.append("<tr>")
                    cells.forEach { result.append("<td>$it</td>") }
                    result.append("</tr>\n")
                }
            } else {
                if (inTable) {
                    result.append("</tbody></table>\n"); inTable = false; headerProcessed = false
                }
                result.append(line).append("\n")
            }
        }
        if (inTable) result.append("</tbody></table>\n")
        return result.toString()
    }

    private fun wrapHtmlContent(html: String): String {
        if (html.lowercase().contains("<html")) return injectDarkModeStyles(html)
        val isDarkMode = isNightMode()
        val textColor = if (isDarkMode) "#E0E0E0" else "#121212"
        val bgColor = if (isDarkMode) "#000000" else "#FFFFFF"
        return """
            <!DOCTYPE html><html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; font-size: 16px; line-height: 1.6; color: $textColor; background-color: $bgColor; padding: 16px; margin: 0; } img { max-width: 100%; height: auto; }</style>
            </head><body>$html</body></html>
        """.trimIndent()
    }

    private fun injectDarkModeStyles(html: String): String {
        if (!isNightMode()) return html
        val darkModeStyle = "<style>body { background-color: #000000 !important; color: #E0E0E0 !important; }</style>"
        return when {
            html.lowercase().contains("<head>") ->
                html.replace(Regex("<head>", RegexOption.IGNORE_CASE)) { "<head>$darkModeStyle" }
            html.lowercase().contains("<html>") ->
                html.replace(Regex("<html>", RegexOption.IGNORE_CASE)) { "<html><head>$darkModeStyle</head>" }
            else -> "$darkModeStyle$html"
        }
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
}
