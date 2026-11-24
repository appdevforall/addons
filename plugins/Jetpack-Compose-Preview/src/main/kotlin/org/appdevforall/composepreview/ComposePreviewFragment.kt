package org.appdevforall.composepreview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.GradleTaskResult
import com.itsaky.androidide.plugins.services.IdeBuildService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.appdevforall.composepreview.databinding.FragmentComposePreviewBinding
import org.appdevforall.composepreview.runtime.ComposableRenderer
import org.appdevforall.composepreview.runtime.ComposeClassLoader
import org.appdevforall.composepreview.runtime.ProjectResourceContextFactory
import org.appdevforall.composepreview.ui.BoundedComposeView
import org.appdevforall.composepreview.R
import org.appdevforall.composepreview.R as ResourcesR
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Locale

/**
 * Full-screen Compose preview, hosted by the IDE's PluginScreenActivity. Ports the original
 * in-IDE ComposePreviewActivity: multi-preview (ALL mode) with labelled cards, a SINGLE-mode
 * selector, an ALL/SINGLE toggle, @PreviewParameter expansion, and per-@Preview background/size.
 *
 * Render views are created with the host Activity context so previewed app code that does
 * `(view.context as Activity).window` (the standard Compose theme template) works. The
 * composables' own resources come from the project via LocalContext (ProjectResourceContextFactory).
 */
class ComposePreviewFragment : Fragment() {

    private var _binding: FragmentComposePreviewBinding? = null
    private val binding get() = _binding ?: throw IllegalStateException("Binding accessed after view destroyed")

    private val viewModel: ComposePreviewViewModel by viewModels()

    private var classLoader: ComposeClassLoader? = null
    private var singlePreviewView: ComposeView? = null
    private var singleRenderer: ComposableRenderer? = null
    private val multiRenderers = mutableMapOf<String, ComposableRenderer>()

    private var resourceContextFactory: ProjectResourceContextFactory? = null
    private var loadedClass: Class<*>? = null
    private var loadJob: Job? = null
    private var previewInstances: List<PreviewInstance> = emptyList()
    private var renderedKeys: List<String> = emptyList()

    private var toggleMenuItem: android.view.MenuItem? = null
    private var selectorAdapter: ArrayAdapter<String>? = null
    private var selectedSingleKey: String? = null
    private var suppressSelectionCallback = false
    private var buildTriggered = false
    private var lastErrorText: String = ""

    private var sourceCode: String = DEFAULT_SOURCE

    private val pluginContext: Context
        get() = PluginFragmentHelper.getPluginContext(ComposePreviewPlugin.PLUGIN_ID) ?: requireContext()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val pluginInflater = PluginFragmentHelper.getPluginInflater(ComposePreviewPlugin.PLUGIN_ID, inflater)
        _binding = FragmentComposePreviewBinding.inflate(pluginInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        resourceContextFactory = ProjectResourceContextFactory(requireActivity())
        classLoader = ComposeClassLoader(pluginContext)

        setupToolbar()
        setupPreviewSelector()
        setupSinglePreview()
        setupBuildButtons()
        observeState()

        val filePath = ComposePreviewState.filePath ?: ""
        ComposePreviewState.sourceCode?.let { sourceCode = it }
        viewModel.initialize(pluginContext, filePath, sourceCode)
    }

    private fun setupToolbar() {
        binding.toolbar.title = (ComposePreviewState.filePath ?: "").substringAfterLast('/')
            .ifEmpty { getString(ResourcesR.string.title_compose_preview) }
        // Close (X): set programmatically — app:navigationIcon in the layout isn't applied when
        // the toolbar is inflated in the plugin context.
        binding.toolbar.setNavigationIcon(R.drawable.ic_close)
        binding.toolbar.navigationContentDescription = "Close preview"
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }

        if (binding.toolbar.menu.size() == 0) {
            binding.toolbar.inflateMenu(R.menu.menu_compose_preview)
        }
        toggleMenuItem = binding.toolbar.menu.findItem(R.id.action_toggle_mode)?.apply {
            // Force the toolbar icon: app:showAsAction in the menu XML is ignored when the
            // menu is inflated in the plugin context, so it would otherwise fall to overflow.
            // Always in-bar AND with a text label, so the mode switch is self-explanatory
            // instead of a cryptic icon. The title is updated per mode in updateDisplayMode.
            setShowAsAction(
                android.view.MenuItem.SHOW_AS_ACTION_ALWAYS or android.view.MenuItem.SHOW_AS_ACTION_WITH_TEXT
            )
            setIcon(R.drawable.ic_view_single)
            title = "Single"
            // Per-item listener: the toolbar-level OnMenuItemClickListener does not fire for
            // this in-bar action item in the plugin-hosted toolbar; a per-item listener does.
            setOnMenuItemClickListener {
                viewModel.toggleDisplayMode()
                true
            }
        }
    }

    private fun setupPreviewSelector() {
        selectorAdapter = ArrayAdapter(
            requireActivity(),
            android.R.layout.simple_spinner_item,
            mutableListOf()
        )
        selectorAdapter?.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.previewSelector.adapter = selectorAdapter

        binding.previewSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSelectionCallback) return
                val instance = previewInstances.getOrNull(position) ?: return
                selectedSingleKey = instance.cardKey
                if (viewModel.displayMode.value == DisplayMode.SINGLE) {
                    renderSinglePreview()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSinglePreview() {
        // Swap the layout-inflated ComposeView (plugin context) for one backed by the host
        // Activity, so the `view.context as Activity` window cast in the previewed theme works.
        val activityView = ComposeView(requireActivity())
        val container = binding.singlePreviewView.parent as ViewGroup
        val index = container.indexOfChild(binding.singlePreviewView)
        val params = binding.singlePreviewView.layoutParams
        container.removeView(binding.singlePreviewView)
        container.addView(activityView, index, params)
        activityView.isVisible = true
        activityView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
        )
        singlePreviewView = activityView
        singleRenderer = ComposableRenderer(activityView)
    }

    private fun setupBuildButtons() {
        binding.buildProjectButton.setOnClickListener { triggerBuild() }
        binding.errorBuildButton.setOnClickListener { triggerBuild() }
        binding.copyErrorButton.setOnClickListener { copyError() }
    }

    private fun copyError() {
        val text = lastErrorText.ifBlank { binding.errorMessage.text?.toString().orEmpty() }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Compose preview error", text))
        Toast.makeText(requireContext(), "Error copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun triggerBuild() {
        if (buildTriggered) return
        val modulePath = viewModel.getModulePath()
        val variantName = viewModel.getVariantName()
        val buildService = PluginFragmentHelper
            .getServiceRegistry(ComposePreviewPlugin.PLUGIN_ID)
            ?.get(IdeBuildService::class.java)
        if (buildService == null) {
            viewModel.setBuildFailed()
            return
        }
        if (buildService.isBuildInProgress()) return

        buildTriggered = true
        viewModel.setBuildingState()
        val variant = variantName.replaceFirstChar { it.uppercaseChar() }
        val task = if (modulePath.isNotEmpty()) "$modulePath:assemble$variant" else "assemble$variant"
        LOG.info("Compose preview triggering build: {}", task)

        buildService.executeTasks(listOf(task), emptyList()).whenComplete { result, error ->
            view?.post {
                buildTriggered = false
                when {
                    error == null && result is GradleTaskResult.Success -> viewModel.refreshAfterBuild(pluginContext)
                    result is GradleTaskResult.Refused -> {
                        LOG.error("Compose preview build refused: {}", result.reason)
                        viewModel.setBuildFailed(result.reason)
                    }
                    else -> {
                        LOG.error("Compose preview build failed: {}", result, error)
                        viewModel.setBuildFailed()
                    }
                }
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.previewState.collect { handlePreviewState(it) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.displayMode.collect { updateDisplayMode(it) }
            }
        }
    }

    private fun handlePreviewState(state: PreviewState) {
        binding.loadingOverlay.isVisible = state is PreviewState.Initializing ||
            state is PreviewState.Compiling ||
            state is PreviewState.Idle ||
            state is PreviewState.Building
        binding.errorContainer.isVisible = state is PreviewState.Error
        binding.emptyContainer.isVisible = state is PreviewState.Empty
        binding.needsBuildContainer.isVisible = state is PreviewState.NeedsBuild

        val isReady = state is PreviewState.Ready
        val isAllMode = viewModel.displayMode.value == DisplayMode.ALL
        binding.previewScrollView.isVisible = isReady && isAllMode
        binding.singlePreviewWrapper.isVisible = isReady && !isAllMode

        when (state) {
            is PreviewState.Idle -> setStatus("Rendering…")
            is PreviewState.Initializing -> setStatus(getString(ResourcesR.string.preview_initializing))
            is PreviewState.Compiling -> setStatus("Compiling…")
            is PreviewState.Building -> setStatus(
                getString(ResourcesR.string.preview_building_project),
                "First build may take a few minutes"
            )
            is PreviewState.NeedsBuild -> { /* needsBuildContainer + Build button handle this */ }
            is PreviewState.Empty -> { /* emptyContainer handles this */ }
            is PreviewState.Ready -> loadAndRender(state)
            is PreviewState.Error -> showError(state)
        }
    }

    private fun setStatus(text: String, subtext: String? = null) {
        binding.statusText.text = text
        binding.statusSubtext.text = subtext ?: ""
        binding.statusSubtext.isVisible = subtext != null
        binding.loadingIndicator.isVisible = true
    }

    private fun showError(state: PreviewState.Error) {
        // Short headline only — the full output goes in the scrollable details + Copy button,
        // so a long compiler error never pushes the action buttons off-screen.
        binding.errorMessage.text = if (state.diagnostics.isNotEmpty()) {
            "Compilation failed — ${state.diagnostics.size} issue(s)"
        } else {
            state.message.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(160)
                ?: "Preview error"
        }
        val details = if (state.diagnostics.isNotEmpty()) {
            state.diagnostics.joinToString("\n\n") { d ->
                buildString {
                    if (d.file != null || d.line != null) {
                        d.file?.let { append(it.substringAfterLast('/')) }
                        d.line?.let { append(":$it") }
                        d.column?.let { append(":$it") }
                        append("\n")
                    }
                    append("[${d.severity}] ${d.message}")
                }
            }
        } else {
            state.message
        }
        // Full, scrollable + selectable details, and the same text feeds the Copy button so a
        // long error that overflows the view is always recoverable.
        val full = if (details.isNotBlank() && details != state.message) {
            "${state.message}\n\n$details"
        } else {
            state.message
        }
        lastErrorText = full
        binding.errorDetails.text = full
        binding.errorDetails.isVisible = true
        binding.errorBuildButton.isVisible = viewModel.canTriggerBuild()
        LOG.error("Preview error: {}", state.message)
    }

    private fun updateDisplayMode(mode: DisplayMode) {
        val isAllMode = mode == DisplayMode.ALL
        toggleMenuItem?.apply {
            // Label/icon describe the mode you switch TO, so the action reads clearly.
            setIcon(if (isAllMode) R.drawable.ic_view_single else R.drawable.ic_view_grid)
            title = if (isAllMode) "Single" else "All"
        }
        refreshSelector()

        if (viewModel.previewState.value is PreviewState.Ready) {
            binding.previewScrollView.isVisible = isAllMode
            binding.singlePreviewWrapper.isVisible = !isAllMode
            if (isAllMode) renderAllPreviews() else renderSinglePreview()
        }
    }

    private fun refreshSelector() {
        val labels = previewInstances.map { it.label }
        suppressSelectionCallback = true
        selectorAdapter?.clear()
        selectorAdapter?.addAll(labels)
        selectorAdapter?.notifyDataSetChanged()
        val currentIndex = previewInstances.indexOfFirst { it.cardKey == selectedSingleKey }
        if (currentIndex >= 0) binding.previewSelector.setSelection(currentIndex)
        suppressSelectionCallback = false
        binding.previewSelector.isVisible =
            viewModel.displayMode.value == DisplayMode.SINGLE && labels.size > 1
    }

    private fun loadAndRender(state: PreviewState.Ready) {
        val loader = classLoader ?: return
        loadedClass = null
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                loader.setProjectDexFiles(state.projectDexFiles)
                loader.setRuntimeDex(state.runtimeDex)
                val clazz = loader.loadClass(state.dexFile, state.className)
                val instances = if (clazz == null) emptyList() else buildPreviewInstances(state)
                clazz to instances
            }
            val clazz = result.first ?: run {
                LOG.error("render: failed to load class {}", state.className)
                return@launch
            }
            loadedClass = clazz
            previewInstances = result.second
            if (selectedSingleKey == null || previewInstances.none { it.cardKey == selectedSingleKey }) {
                selectedSingleKey = previewInstances.firstOrNull()?.cardKey
            }
            refreshSelector()
            if (viewModel.displayMode.value == DisplayMode.ALL) renderAllPreviews() else renderSinglePreview()
        }
    }

    private fun buildPreviewInstances(state: PreviewState.Ready): List<PreviewInstance> =
        state.previewConfigs.flatMap { config -> instancesForConfig(config, state) }

    private fun instancesForConfig(config: PreviewConfig, state: PreviewState.Ready): List<PreviewInstance> {
        val factory = resourceContextFactory ?: return emptyList()
        val context = factory.contextFor(state.resourceApk, buildConfiguration(config))
        val single = listOf(PreviewInstance(config, context, null, 0, 1))

        val provider = config.parameterProvider ?: return single
        val values = resolveParameterValues(state.dexFile, provider, config.parameterLimit)
        if (values.isEmpty()) return single
        return values.mapIndexed { index, value -> PreviewInstance(config, context, value, index, values.size) }
    }

    private fun buildConfiguration(config: PreviewConfig): Configuration {
        val configuration = Configuration(requireActivity().resources.configuration)
        // Previews must be deterministic and independent of the IDE's day/night theme — the way
        // Studio renders them. Default night mode to NIGHT_NO (light) and only switch to dark when
        // the @Preview's uiMode explicitly asks for it; otherwise the preview inherits the IDE's
        // dark mode and every preview (including "light" ones) renders dark.
        val requestedType = config.uiMode?.and(Configuration.UI_MODE_TYPE_MASK) ?: 0
        val requestedNight = config.uiMode?.and(Configuration.UI_MODE_NIGHT_MASK) ?: 0
        var merged = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()
        merged = merged or (if (requestedNight != 0) requestedNight else Configuration.UI_MODE_NIGHT_NO)
        if (requestedType != 0) {
            merged = (merged and Configuration.UI_MODE_TYPE_MASK.inv()) or requestedType
        }
        configuration.uiMode = merged
        config.fontScale?.let { configuration.fontScale = it }
        config.locale?.let { configuration.setLocale(Locale.forLanguageTag(it.replace('_', '-'))) }
        return configuration
    }

    private fun resolveParameterValues(dexFile: File, providerFqn: String, limit: Int): List<Any?> {
        val loader = classLoader ?: return emptyList()
        return try {
            val providerClass = loader.loadClass(dexFile, providerFqn) ?: return emptyList()
            val instance = providerClass.getDeclaredConstructor().newInstance()
            val values = providerClass.getMethod("getValues").invoke(instance) as? Sequence<*> ?: return emptyList()
            values.take(minOf(limit, MAX_PARAMETER_VALUES)).toList()
        } catch (e: Throwable) {
            LOG.error("Failed to resolve @PreviewParameter values from {}", providerFqn, e)
            emptyList()
        }
    }

    private fun renderAllPreviews() {
        val container = binding.previewListContainer
        val clazz = loadedClass ?: return
        val instances = previewInstances
        val keys = instances.map { it.cardKey }

        if (keys == renderedKeys && multiRenderers.keys == keys.toSet()) {
            instances.forEach { instance ->
                multiRenderers[instance.cardKey]?.render(
                    clazz, instance.config.functionName, instance.context,
                    instance.parameterValue, instance.config.parameterIndex
                )
            }
            return
        }

        container.removeAllViews()
        multiRenderers.clear()
        renderedKeys = keys

        val inflater = PluginFragmentHelper.getPluginInflater(ComposePreviewPlugin.PLUGIN_ID, layoutInflater)
        instances.forEachIndexed { index, instance ->
            val item = createPreviewItem(inflater, container, instance, index == 0)
            container.addView(item)

            val bounded = item.findViewById<BoundedComposeView>(R.id.composePreview)
            val cv = swapToActivityComposeView(bounded)
            applyCardAttributes(bounded, cv, instance.config)
            cv.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )

            val renderer = ComposableRenderer(cv)
            multiRenderers[instance.cardKey] = renderer
            renderer.render(
                clazz, instance.config.functionName, instance.context,
                instance.parameterValue, instance.config.parameterIndex
            )
        }
    }

    private fun renderSinglePreview() {
        val clazz = loadedClass ?: return
        val instance = previewInstances.firstOrNull { it.cardKey == selectedSingleKey }
            ?: previewInstances.firstOrNull()
            ?: return
        selectedSingleKey = instance.cardKey
        binding.singlePreviewLabel.text = buildString {
            append(instance.label)
            instance.config.group?.let { append("  ·  ").append(it) }
        }
        singlePreviewView?.let { applyBackground(it, instance.config) }
        singleRenderer?.render(
            clazz, instance.config.functionName, instance.context,
            instance.parameterValue, instance.config.parameterIndex
        )
    }

    /** Exact replica of the module's createPreviewItem — inflates item_preview_card.xml. */
    private fun createPreviewItem(
        inflater: LayoutInflater,
        container: ViewGroup,
        instance: PreviewInstance,
        isFirst: Boolean
    ): View {
        val item = inflater.inflate(R.layout.item_preview_card, container, false)
        item.findViewById<TextView>(R.id.previewLabel)?.let { label ->
            label.text = buildString {
                append(instance.label)
                instance.config.group?.let { append("  ·  ").append(it) }
            }
        }
        item.findViewById<View>(R.id.divider)?.isVisible = !isFirst
        return item
    }

    /**
     * Replace the inflated BoundedComposeView's inner ComposeView with one backed by the host
     * Activity, so previewed theme code that does (view.context as Activity) works. The card's
     * item_preview_card.xml layout — label, divider, frame, BoundedComposeView sizing — is
     * otherwise used exactly as the module defines it.
     */
    private fun swapToActivityComposeView(bounded: BoundedComposeView): ComposeView {
        bounded.removeAllViews()
        val cv = ComposeView(requireActivity()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        bounded.addView(cv)
        return cv
    }

    private fun applyCardAttributes(bounded: BoundedComposeView, composeView: View, config: PreviewConfig) {
        val density = requireActivity().resources.displayMetrics.density
        bounded.explicitWidthPx = config.widthDp?.let { (it * density).toInt() }
        bounded.explicitHeightPx = config.heightDp?.let { (it * density).toInt() }
        applyBackground(composeView, config)
    }

    private fun applyBackground(view: View, config: PreviewConfig) {
        view.setBackgroundColor(
            if (config.showBackground) resolveBackgroundColor(config.backgroundColor) else Color.TRANSPARENT
        )
    }

    private fun resolveBackgroundColor(raw: Long?): Int {
        if (raw == null || raw == 0L) return Color.WHITE
        val argb = raw.toInt()
        return if ((argb ushr 24) == 0) argb or OPAQUE_ALPHA else argb
    }

    fun updateSource(source: String) {
        sourceCode = source
        viewModel.onSourceChanged(source)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loadJob?.cancel()
        loadJob = null
        loadedClass = null
        previewInstances = emptyList()
        renderedKeys = emptyList()
        resourceContextFactory?.release()
        resourceContextFactory = null
        multiRenderers.clear()
        singleRenderer = null
        singlePreviewView = null
        classLoader?.release()
        classLoader = null
        selectorAdapter = null
        toggleMenuItem = null
        _binding = null
    }

    private data class PreviewInstance(
        val config: PreviewConfig,
        val context: Context,
        val parameterValue: Any?,
        val valueIndex: Int,
        val valueCount: Int
    ) {
        val cardKey: String get() = if (valueCount > 1) "${config.key}[$valueIndex]" else config.key
        val label: String get() = if (valueCount > 1) "${config.displayName} [$valueIndex]" else config.displayName
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ComposePreviewFragment::class.java)
        private const val OPAQUE_ALPHA = 0xFF shl 24
        private const val MAX_PARAMETER_VALUES = 25

        private const val DEFAULT_SOURCE = """
package preview

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun Preview() {
    Text("Hello, Compose Preview!")
}
"""
    }
}
