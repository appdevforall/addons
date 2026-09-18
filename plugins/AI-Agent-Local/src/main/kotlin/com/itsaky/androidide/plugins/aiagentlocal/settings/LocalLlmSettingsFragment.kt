package com.itsaky.androidide.plugins.aiagentlocal.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentlocal.plugin.LocalLlmPlugin
import com.itsaky.androidide.plugins.aiagentlocal.R
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeTooltipService
import kotlinx.coroutines.launch

/**
 * This backend's settings pane, mounted by whichever screen offers a backend selector.
 *
 * Named to the host through `LocalLlmBackend.getSettingsFragmentClassName()`, loaded with this
 * plugin's own classloader and inflated against this plugin's own resources — so the consumer
 * needs to know nothing about `.gguf` files, engine state or memory headroom.
 */
class LocalLlmSettingsFragment : Fragment(), MemoryWarningDialogFragment.Host {

    private lateinit var viewModel: LocalLlmSettingsViewModel
    private var tooltipService: IdeTooltipService? = null

    private val filePickerLauncher =
        registerForActivityResult(PickLocalDocument) { uri: Uri? ->
            uri?.let {
                // The durable read grant is taken by the view model, with the rest of the
                // selection's bookkeeping — see LocalLlmSettingsViewModel.loadModelFromUri. It
                // runs in its own scope and puts every outcome on the status line, so nothing
                // here can throw and there is nothing to catch.
                viewModel.loadModelFromUri(it.toString())
                Toast.makeText(
                    requireContext(),
                    getString(R.string.model_loading_toast),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // This pane is replaced in and out whenever the backend selector changes, so a theme-default
        // Material transition would be resolved here. Plugin resources are compileOnly, so those
        // transition resources aren't bundled; nulling them keeps the swap from touching them.
        enterTransition = null
        exitTransition = null

        try {
            tooltipService = PluginFragmentHelper.getServiceRegistry(LocalLlmPlugin.PLUGIN_ID)
                ?.get(IdeTooltipService::class.java)
        } catch (e: Exception) {
            // Tooltip help is optional; long-press simply shows nothing when it's unavailable.
            LocalLlmPlugin.getContext()?.logger
                ?.warn("LocalLlmSettingsFragment: tooltip service unavailable", e)
        }
    }

    /**
     * Route inflation through the host so this pane resolves against *this* plugin's resources and
     * a Context whose Configuration tracks the IDE's day/night setting. The inflater inherited from
     * the hosting screen belongs to that plugin and cannot see this one's layouts.
     */
    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(LocalLlmPlugin.PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_local_llm_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(
            this,
            LocalLlmSettingsViewModelFactory { LocalLlmPlugin.getContext() }
        )[LocalLlmSettingsViewModel::class.java]

        setupModelControls(view)
        observeMemoryWarnings()
    }

    /** Shows this plugin's tooltip for [tag] when [view] is long-pressed (Tier 1/2 + guide button). */
    private fun wireTooltip(view: View, tag: String) {
        view.setOnLongClickListener { anchor ->
            val service = tooltipService ?: return@setOnLongClickListener false
            service.showTooltip(anchor, LocalLlmPlugin.TOOLTIP_CATEGORY, tag)
            true
        }
    }

    private fun setupModelControls(view: View) {
        val modelPathTextView = view.findViewById<TextView>(R.id.selected_model_path)
        val browseButton = view.findViewById<Button>(R.id.btn_browse_model)
        val loadSavedButton = view.findViewById<Button>(R.id.loadSavedButton)
        val modelStatusTextView = view.findViewById<TextView>(R.id.model_status_text_view)
        val engineStatusTextView = view.findViewById<TextView>(R.id.engine_status_text)
        val simplePromptCheckbox = view.findViewById<CheckBox>(R.id.switch_simple_local_prompt)
        val shaInput = view.findViewById<EditText>(R.id.local_model_sha_input)

        // This pane is the only UI this plugin draws, so the whole-plugin entry — and the guide
        // button that hangs off it — has to be reachable from somewhere on it.
        wireTooltip(engineStatusTextView, LocalLlmPlugin.TOOLTIP_TAG_PLUGIN)

        browseButton.setOnClickListener { filePickerLauncher.launch(arrayOf("*/*")) }
        wireTooltip(browseButton, LocalLlmPlugin.TOOLTIP_TAG_SETTINGS_LOCAL_MODEL)

        loadSavedButton.setOnClickListener {
            viewModel.state.value?.savedModelPath?.let(viewModel::loadModelFromUri)
        }
        // Same concept as Browse — choosing which local model to run.
        wireTooltip(loadSavedButton, LocalLlmPlugin.TOOLTIP_TAG_SETTINGS_LOCAL_MODEL)

        shaInput?.apply {
            setText(viewModel.getLocalModelSha256().orEmpty())
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    viewModel.saveLocalModelSha256(text?.toString())
                }
            }
        }
        // On the labelled wrapper, not the field: long-press there is the paste menu.
        view.findViewById<View>(R.id.local_model_sha_layout)
            ?.let { wireTooltip(it, LocalLlmPlugin.TOOLTIP_TAG_SETTINGS_LOCAL_SHA) }

        simplePromptCheckbox?.apply {
            isChecked = viewModel.isUseSimpleLocalPromptEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                viewModel.setUseSimpleLocalPrompt(isChecked)
            }
            wireTooltip(this, LocalLlmPlugin.TOOLTIP_TAG_SETTINGS_SIMPLE_PROMPT)
        }

        // All three lines describe the same model, so they are drawn from one state in one pass:
        // an unreachable model must not read as ready on one line and missing on another.
        viewModel.state.observe(viewLifecycleOwner) { state ->
            // Every branch is a short phrase about the engine; the refusal's own sentence belongs
            // to the model line below, which would otherwise draw the same paragraph twice.
            engineStatusTextView.text = when (state.engine) {
                is EngineState.NoModel -> getString(R.string.engine_no_model)
                is EngineState.ModelUnavailable -> getString(R.string.engine_model_unavailable)
                is EngineState.Initializing -> getString(R.string.engine_initializing)
                is EngineState.Initialized -> getString(R.string.engine_ready)
                is EngineState.Error -> getString(R.string.engine_error)
            }

            // Enabled off the model status, not off engine readiness: picking a model is exactly
            // how the user recovers from an engine that isn't ready, so it must stay reachable.
            val busy = state.model is ModelLoadingState.Loading
            browseButton.isEnabled = !busy
            loadSavedButton.isEnabled = state.savedModelPath != null && !busy

            val savedName = state.savedModelName
            if (savedName != null) {
                modelPathTextView.visibility = View.VISIBLE
                // Off the engine status, which describes the configured model; the model status
                // also carries the outcome of a rejected pick, which says nothing about it.
                modelPathTextView.text = if (state.engine is EngineState.ModelUnavailable) {
                    getString(R.string.model_saved_path_unavailable, savedName)
                } else {
                    getString(R.string.model_saved_path, savedName)
                }
            } else {
                modelPathTextView.visibility = View.GONE
            }

            modelStatusTextView.visibility = View.VISIBLE
            modelStatusTextView.text = when (val model = state.model) {
                is ModelLoadingState.Idle -> getString(R.string.model_none_loaded)
                is ModelLoadingState.Loading -> getString(R.string.model_loading_wait)
                is ModelLoadingState.Loaded ->
                    if (model.accessPersisted) {
                        getString(R.string.model_loaded, model.modelName)
                    } else {
                        getString(R.string.model_loaded_access_not_persisted, model.modelName)
                    }
                is ModelLoadingState.Unavailable ->
                    getString(R.string.model_unavailable, model.modelName)
                is ModelLoadingState.Error -> getString(R.string.model_load_error, model.message)
            }
        }
    }

    /**
     * The model file lives outside the IDE and can be deleted or unmounted while this screen is
     * away, so its availability is re-checked on every return rather than only at first load.
     */
    override fun onResume() {
        super.onResume()
        viewModel.refreshSavedModelAvailability()
    }

    /**
     * Puts a "this model may not fit" question to the user. Collected under STARTED so the dialog is
     * never shown to a stopped fragment; the event waits in the ViewModel until then.
     */
    private fun observeMemoryWarnings() {
        dropStaleMemoryWarning()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.modelMemoryWarnings.collect(::showMemoryWarning)
            }
        }
    }

    /**
     * Dismiss a warning dialog the framework restored around a question that no longer exists.
     * After process death the load that raised it is gone, so every button on it would be a silent
     * no-op — better to take it away than to leave the user pressing a dialog that decides nothing.
     */
    private fun dropStaleMemoryWarning() {
        if (viewModel.hasPendingMemoryWarning) return
        val restored = childFragmentManager.findFragmentByTag(MemoryWarningDialogFragment.FRAGMENT_TAG)
        (restored as? MemoryWarningDialogFragment)?.dismissAllowingStateLoss()
    }

    /**
     * Shown as a child fragment, so it survives rotation and can still reach this host. Must stay
     * idempotent: an unanswered question is re-published to every new collector by
     * [UserConfirmation].
     *
     * @param warning the model and the figures to put to the user
     */
    private fun showMemoryWarning(warning: ModelMemoryWarning) {
        if (childFragmentManager.findFragmentByTag(MemoryWarningDialogFragment.FRAGMENT_TAG) != null) return
        MemoryWarningDialogFragment.newInstance(warning)
            .show(childFragmentManager, MemoryWarningDialogFragment.FRAGMENT_TAG)
    }

    override fun onModelMemoryDecision(proceed: Boolean) {
        viewModel.onMemoryWarningDecision(proceed)
        // Not requireContext(): onCancel can reach us as the fragment is going away.
        val ctx = context ?: return
        if (!proceed) {
            Toast.makeText(ctx, getString(R.string.llm_memory_warning_declined), Toast.LENGTH_LONG)
                .show()
        }
    }
}

/**
 * The document picker, asked for documents already on the device: a streaming provider hands back
 * a pipe the in-place loader cannot `mmap`. Advisory only, so the load path still refuses a
 * non-seekable descriptor as `Diagnosis.SourceNotSeekable` (ADFA-5253).
 */
private object PickLocalDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

/**
 * Factory for creating [LocalLlmSettingsViewModel] with its PluginContext dependency.
 */
class LocalLlmSettingsViewModelFactory(
    private val getContext: () -> PluginContext?
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LocalLlmSettingsViewModel::class.java)) {
            return LocalLlmSettingsViewModel(getContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
