package com.itsaky.androidide.plugins.vectorsearch.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Filter
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeTooltipService
import com.itsaky.androidide.plugins.vectorsearch.R
import com.itsaky.androidide.plugins.vectorsearch.VectorSearchHelp
import com.itsaky.androidide.plugins.vectorsearch.VectorSearchPlugin
import java.text.NumberFormat
import kotlinx.coroutines.launch

/** Material's opacity for a disabled control, applied by hand where `isEnabled` cannot be used. */
private const val DISABLED_ALPHA = 0.38f

/** Date and time, month abbreviated, in the user's locale: "Oct 5, 2:05 PM". */
private const val LAST_BUILT_FORMAT =
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH

/** Material's pressed-state overlay opacity, out of 255, for the destructive button's ripple. */
private const val RIPPLE_ALPHA = 0x1F

/**
 * The Semantic Search screen, opened from Preferences → Configuration → Semantic Search: the
 * backend Vector Search embeds with and whether it can, its embedding model, where the code goes,
 * and the index. Loaded by name with this plugin's classloader, inflated against its resources.
 */
class SemanticSearchSettingsFragment : Fragment() {

    private lateinit var viewModel: SemanticSearchSettingsViewModel
    private var tooltipService: IdeTooltipService? = null

    /** The screen's views, bound once per view; null between onDestroyView and the next view. */
    private var views: Views? = null

    /** The models the dropdown's adapter holds, so a render changing nothing keeps the popup. */
    private var adapterModels: List<String>? = null

    /** Tracked so a rotation takes it down with the view it was anchored to, not as a leak. */
    private var clearDialog: AlertDialog? = null

    /** Groups thousands in the user's locale, so 3104 reads as 3,104 or 3.104. */
    private val numbers: NumberFormat = NumberFormat.getIntegerInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            tooltipService = PluginFragmentHelper.getServiceRegistry(VectorSearchPlugin.PLUGIN_ID)
                ?.get(IdeTooltipService::class.java)
        } catch (e: Exception) {
            // Tooltip help is optional; long-press simply shows nothing when it's unavailable.
            VectorSearchPlugin.getInstance()?.logger()
                ?.warn("SemanticSearch: no tooltip service", e)
        }
    }

    /**
     * Routes inflation through the host so this screen resolves against *this* plugin's resources
     * and a Context whose Configuration tracks the IDE's day/night setting.
     */
    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(VectorSearchPlugin.PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_semantic_search_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(
            this,
            SemanticSearchSettingsViewModelFactory {
                VectorSearchPlugin.getInstance()?.semanticSearchSource()
            },
        )[SemanticSearchSettingsViewModel::class.java]

        val bound = Views(view)
        views = bound
        adapterModels = null

        wireTooltip(bound.back, VectorSearchHelp.TAG_BACK)
        bound.back.setOnClickListener { leaveScreen() }
        // The whole-plugin entry, whose guide button opens the Tier 3 page.
        listOf(bound.title, bound.description)
            .forEach { wireTooltip(it, VectorSearchHelp.TAG_PLUGIN) }

        listOf(
            bound.backendLabel, bound.backendMessage, bound.backendName,
            bound.backendSupport, bound.chatModel, bound.backendHint,
        ).forEach { wireTooltip(it, VectorSearchHelp.TAG_BACKEND) }
        wireTooltip(bound.privacy, VectorSearchHelp.TAG_PRIVACY)
        listOf(
            bound.indexLabel, bound.indexState, bound.indexMessage,
            bound.indexProgressGroup, bound.indexDetails,
        ).forEach { wireTooltip(it, VectorSearchHelp.TAG_INDEX_STATUS) }
        setupProgress(bound)

        setupModelPicker(bound)

        wireTooltip(bound.clearIndex, VectorSearchHelp.TAG_CLEAR_INDEX)
        styleAsDestructive(bound.clearIndex)
        bound.clearIndex.setOnClickListener { confirmClear() }

        // viewLifecycleOwner, not the fragment: the collector holds views and must stop with them.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> views?.let { render(it, state) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A search run since the screen was last shown changes the index line.
        viewModel.refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Anchored to the view being destroyed; see the field's comment.
        clearDialog?.dismiss()
        clearDialog = null
        views = null
    }

    /**
     * Leaves this screen. The host mounts a settings pane on its own back stack or as the whole
     * activity, so both are handled, as on the MCP servers screen.
     */
    private fun leaveScreen() {
        if (parentFragmentManager.backStackEntryCount > 0) {
            parentFragmentManager.popBackStack()
        } else {
            requireActivity().finish()
        }
    }

    // --- Embedding model picker ----------------------------------------------------------------

    /**
     * Picked or typed: the list is filtered by name on OpenAI-compatible servers, so it can miss a
     * model the backend accepts.
     */
    private fun setupModelPicker(bound: Views) {
        val box = bound.modelBox
        val input = bound.modelInput

        // In code, not as app:endIconDrawable, which draws blank inside the host.
        box.endIconMode = TextInputLayout.END_ICON_CUSTOM
        box.setEndIconDrawable(R.drawable.ic_dropdown)
        box.endIconContentDescription = getString(R.string.cd_vs_show_embedding_models)
        box.isEndIconCheckable = false

        listOf(bound.modelLabel, input, bound.modelHint)
            .forEach { wireTooltip(it, VectorSearchHelp.TAG_EMBEDDING_MODEL) }
        box.setEndIconOnLongClickListener { icon ->
            showTooltip(icon, VectorSearchHelp.TAG_EMBEDDING_MODEL)
        }

        // Rendered from the state on every view creation; a restored setText() would filter.
        input.isSaveEnabled = false
        // No adapter means nothing to list, so a tap opens nothing then.
        input.setOnClickListener { if (input.adapter != null) input.showDropDown() }
        box.setEndIconOnClickListener { if (input.adapter != null) input.showDropDown() }
        input.setOnItemClickListener { parent, _, position, _ ->
            (parent.getItemAtPosition(position) as? String)?.let(viewModel::selectModel)
        }
        val commitTyped = {
            input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(viewModel::selectModel)
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) commitTyped()
            false
        }
        input.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitTyped() }
    }

    /** In code: the bar's app: colour attributes are dropped inside the host. */
    private fun setupProgress(bound: Views) {
        val context = bound.indexProgress.context
        bound.indexProgress.setIndicatorColor(context.getColor(R.color.plugin_primary))
        bound.indexProgress.trackColor = context.getColor(R.color.plugin_outline_variant)
    }

    /**
     * Outlined in the error colour, since the button deletes every project's index. In code: the
     * style's app: items (stroke, ripple) are dropped inside the host, and its roles resolve there.
     */
    private fun styleAsDestructive(button: MaterialButton) {
        val error = ColorStateList.valueOf(button.context.getColor(R.color.plugin_error))
        button.setTextColor(error)
        button.strokeColor = error
        button.strokeWidth = button.resources.getDimensionPixelSize(R.dimen.vs_outline_stroke)
        button.rippleColor = error.withAlpha(RIPPLE_ALPHA)
        button.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
    }

    // --- Rendering -----------------------------------------------------------------------------

    private fun render(bound: Views, state: SemanticSearchState) {
        renderBackend(bound, state)
        renderIndex(bound, state)
    }

    private fun renderBackend(bound: Views, state: SemanticSearchState) {
        val selected = state.compatibility as? BackendCompatibility.Selected
        if (selected == null) {
            // Null only while the first read runs, when nothing true can be said yet.
            val text = state.compatibility?.let(::unavailableMessage)
            bound.backendMessage.text = text
            bound.backendMessage.visibility = if (text == null) View.GONE else View.VISIBLE
            bound.backendDetails.visibility = View.GONE
            return
        }
        bound.backendMessage.visibility = View.GONE
        bound.backendDetails.visibility = View.VISIBLE

        bound.backendName.text = selected.backendName
        bound.chatModel.text = selected.chatModel?.let { getString(R.string.vs_chat_model, it) }
        bound.chatModel.visibility = if (selected.chatModel == null) View.GONE else View.VISIBLE

        val supported = selected.support is EmbeddingSupport.Selectable
        bound.backendSupport.setText(
            if (supported) R.string.vs_supported else R.string.vs_not_supported
        )
        bound.backendSupport.setTextColor(
            bound.backendSupport.context.getColor(
                if (supported) R.color.plugin_success else R.color.plugin_error
            )
        )
        bound.backendHint.text =
            if (supported) null else getString(R.string.vs_hint_not_supported, selected.backendName)
        bound.backendHint.visibility = if (supported) View.GONE else View.VISIBLE

        bound.privacy.text = getString(R.string.vs_privacy, selected.backendName)

        renderModelPicker(bound, selected.support, state)
    }

    /** What to say instead of the backend's details when there is no backend to describe. */
    private fun unavailableMessage(compatibility: BackendCompatibility): String? =
        when (compatibility) {
            BackendCompatibility.NoService -> getString(R.string.vs_no_service)
            BackendCompatibility.NoSelection -> getString(R.string.vs_no_selection)
            is BackendCompatibility.NotInstalled ->
                getString(R.string.vs_not_installed, compatibility.backendId)
            is BackendCompatibility.Selected -> null
        }

    private fun renderModelPicker(
        bound: Views,
        support: EmbeddingSupport,
        state: SemanticSearchState,
    ) {
        val input = bound.modelInput
        val current = (support as? EmbeddingSupport.Selectable)?.modelId.orEmpty()
        // The suppressing overload: a filtering write would narrow the list to this one entry.
        if (!input.hasFocus() && input.text.toString() != current) input.setText(current, false)

        val options = state.selectedModels
        val models = (options as? ModelOptions.Loaded)?.models.orEmpty()
        setPickerEnabled(bound, support is EmbeddingSupport.Selectable)
        if (models != adapterModels) {
            adapterModels = models
            input.setAdapter(if (models.isEmpty()) null else DropdownAdapter(input.context, models))
        }

        var isError = false
        val hintText: String? = when {
            support !is EmbeddingSupport.Selectable -> null
            options is ModelOptions.Failed -> {
                isError = true
                options.reason?.let { getString(R.string.vs_models_failed, it) }
                    ?: getString(R.string.vs_models_failed_unknown)
            }
            options !is ModelOptions.Loaded -> getString(R.string.vs_models_loading)
            options.models.isEmpty() -> getString(R.string.vs_models_empty)
            state.pickedModel != null -> getString(R.string.vs_model_changed, state.pickedModel)
            else -> getString(R.string.vs_models_help)
        }
        bound.modelHint.text = hintText
        bound.modelHint.visibility = if (hintText == null) View.GONE else View.VISIBLE
        bound.modelHint.setTextColor(
            bound.modelHint.context.getColor(
                if (isError) R.color.plugin_error else R.color.plugin_text_muted
            )
        )
    }

    /**
     * Draws the picker as enabled or not without `isEnabled`: a disabled view drops long-presses,
     * and the field's tooltip has to open in exactly the states that disable it.
     */
    private fun setPickerEnabled(bound: Views, enabled: Boolean) {
        bound.modelBox.alpha = if (enabled) 1f else DISABLED_ALPHA
        bound.modelInput.isFocusableInTouchMode = enabled
        ViewCompat.setStateDescription(
            bound.modelInput,
            if (enabled) null else getString(R.string.vs_state_unavailable),
        )
    }

    private fun renderIndex(bound: Views, state: SemanticSearchState) {
        val status = state.indexStatus
        val health = state.indexHealth

        bound.indexState.text = health?.let { getString(stateLabel(it)) }.orEmpty()
        bound.indexState.setTextColor(bound.indexState.context.getColor(stateColor(health)))

        val message = status?.let { indexMessage(it, health, state.compatibility) }
        bound.indexMessage.text = message
        bound.indexMessage.visibility = if (message == null) View.GONE else View.VISIBLE

        renderProgress(bound, status as? IndexStatus.Building)
        renderDetails(bound, status as? IndexStatus.Indexed, state.compatibility)

        // Not `isEnabled`: a disabled view drops the long-press its tooltip opens on.
        val canClear = state.canClear && !state.clearing
        bound.clearIndex.isClickable = canClear
        bound.clearIndex.alpha = if (canClear) 1f else DISABLED_ALPHA
        ViewCompat.setStateDescription(
            bound.clearIndex,
            if (canClear) null else getString(R.string.vs_state_unavailable),
        )
        bound.clearIndex.setText(
            if (state.clearing) R.string.vs_clearing_index else R.string.vs_clear_index
        )
    }

    private fun stateLabel(health: IndexHealth): Int = when (health) {
        IndexHealth.NO_PROJECT -> R.string.vs_index_no_project
        IndexHealth.NOT_INDEXED -> R.string.vs_index_not_indexed
        IndexHealth.INDEXING -> R.string.vs_index_state_indexing
        IndexHealth.BUILD_FAILED -> R.string.vs_index_state_failed
        IndexHealth.UP_TO_DATE -> R.string.vs_index_state_up_to_date
        IndexHealth.OUT_OF_DATE -> R.string.vs_index_state_out_of_date
        IndexHealth.INDEXED -> R.string.vs_index_state_indexed
    }

    private fun stateColor(health: IndexHealth?): Int = when (health) {
        IndexHealth.UP_TO_DATE -> R.color.plugin_success
        IndexHealth.OUT_OF_DATE -> R.color.plugin_warning
        IndexHealth.BUILD_FAILED -> R.color.plugin_error
        IndexHealth.INDEXING -> R.color.plugin_primary
        else -> R.color.plugin_text_muted
    }

    /** The sentence under the state that says what follows from it, or null when nothing does. */
    private fun indexMessage(
        status: IndexStatus,
        health: IndexHealth?,
        compatibility: BackendCompatibility?,
    ): String? = when (health) {
        IndexHealth.NOT_INDEXED -> getString(R.string.vs_index_msg_not_indexed)
        IndexHealth.BUILD_FAILED -> getString(R.string.vs_index_msg_failed)
        IndexHealth.INDEXED -> getString(R.string.vs_index_msg_indexed)
        IndexHealth.OUT_OF_DATE -> {
            val built = (status as IndexStatus.Indexed).embedders.map { it.modelId }.distinct()
            val selected = (compatibility as BackendCompatibility.Selected).support
            getString(
                R.string.vs_index_msg_out_of_date,
                built.joinToString(", "),
                (selected as EmbeddingSupport.Selectable).modelId,
            )
        }
        else -> null
    }

    /** A bar and a count while a build runs; indeterminate while the files are being collected. */
    private fun renderProgress(bound: Views, building: IndexStatus.Building?) {
        bound.indexProgressGroup.visibility = if (building == null) View.GONE else View.VISIBLE
        building ?: return

        val bar = bound.indexProgress
        val collecting = building.total == 0
        if (bar.isIndeterminate != collecting) {
            // Switched while hidden: some Material versions refuse to switch a visible bar's mode.
            bar.visibility = View.INVISIBLE
            bar.isIndeterminate = collecting
            bar.visibility = View.VISIBLE
        }
        if (collecting) {
            bound.indexProgressText.setText(R.string.vs_index_preparing)
            return
        }
        bar.max = building.total
        bar.setProgressCompat(building.stored, true)
        bound.indexProgressText.text = resources.getQuantityString(
            R.plurals.vs_index_progress,
            building.total,
            numbers.format(building.stored),
            numbers.format(building.total),
        )
    }

    /** One label and value per fact about the open project's index. */
    private fun renderDetails(
        bound: Views,
        indexed: IndexStatus.Indexed?,
        compatibility: BackendCompatibility?,
    ) {
        bound.indexDetails.visibility = if (indexed == null) View.GONE else View.VISIBLE
        indexed ?: return

        bound.indexChunks.text = numbers.format(indexed.chunkCount)
        bound.indexFiles.text = numbers.format(indexed.fileCount)
        val selected = compatibility as? BackendCompatibility.Selected
        bound.indexModel.text = indexed.embedders
            .map { embedder ->
                val backend = if (embedder.backendId == selected?.backendId) {
                    selected.backendName
                } else {
                    embedder.backendId
                }
                getString(
                    R.string.vs_index_model_value, backend, embedder.modelId, embedder.dimensions,
                )
            }
            .distinct()
            .joinToString("\n")
        bound.indexLastBuilt.text = indexed.lastBuiltAt?.let {
            DateUtils.formatDateTime(bound.indexLastBuilt.context, it, LAST_BUILT_FORMAT)
        } ?: getString(R.string.vs_index_last_built_unknown)
    }

    // --- Clear index ---------------------------------------------------------------------------

    /**
     * Asks before deleting anything. Cancel, Back and a tap outside all dismiss without a callback,
     * so only the positive button reaches [SemanticSearchSettingsViewModel.clearIndex].
     */
    private fun confirmClear() {
        if (clearDialog != null) return
        clearDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.vs_clear_title)
            .setMessage(R.string.vs_clear_message)
            .setPositiveButton(R.string.vs_clear_confirm) { _, _ -> viewModel.clearIndex() }
            .setNegativeButton(R.string.vs_clear_cancel, null)
            .setOnDismissListener { clearDialog = null }
            .create()
            .apply { show() }
    }

    // --- Tooltips ------------------------------------------------------------------------------

    /** Long-press on [view] shows [tag]'s entry, under this plugin's category (3-arg overload). */
    private fun wireTooltip(view: View, tag: String) {
        view.setOnLongClickListener { anchor -> showTooltip(anchor, tag) }
    }

    private fun showTooltip(anchor: View, tag: String): Boolean {
        val service = tooltipService ?: return false
        service.showTooltip(anchor, VectorSearchPlugin.TOOLTIP_CATEGORY, tag)
        return true
    }

    /** The screen's views, looked up once per view rather than on every render. */
    private class Views(root: View) {
        val back: ImageButton = root.findViewById(R.id.vsBack)
        val title: TextView = root.findViewById(R.id.vsTitle)
        val description: TextView = root.findViewById(R.id.vsDescription)
        val backendLabel: TextView = root.findViewById(R.id.vsBackendLabel)
        val backendMessage: TextView = root.findViewById(R.id.vsBackendMessage)
        val backendDetails: View = root.findViewById(R.id.vsBackendDetails)
        val backendName: TextView = root.findViewById(R.id.vsBackendName)
        val backendSupport: TextView = root.findViewById(R.id.vsBackendSupport)
        val chatModel: TextView = root.findViewById(R.id.vsChatModel)
        val backendHint: TextView = root.findViewById(R.id.vsBackendHint)
        val modelLabel: TextView = root.findViewById(R.id.vsEmbeddingModelLabel)
        val modelBox: TextInputLayout = root.findViewById(R.id.vsEmbeddingModelBox)
        val modelInput: AutoCompleteTextView = root.findViewById(R.id.vsEmbeddingModelInput)
        val modelHint: TextView = root.findViewById(R.id.vsEmbeddingModelHint)
        val privacy: TextView = root.findViewById(R.id.vsPrivacy)
        val indexLabel: TextView = root.findViewById(R.id.vsIndexLabel)
        val indexState: TextView = root.findViewById(R.id.vsIndexState)
        val indexMessage: TextView = root.findViewById(R.id.vsIndexMessage)
        val indexProgressGroup: View = root.findViewById(R.id.vsIndexProgressGroup)
        val indexProgress: LinearProgressIndicator = root.findViewById(R.id.vsIndexProgress)
        val indexProgressText: TextView = root.findViewById(R.id.vsIndexProgressText)
        val indexDetails: View = root.findViewById(R.id.vsIndexDetails)
        val indexChunks: TextView = root.findViewById(R.id.vsIndexChunks)
        val indexFiles: TextView = root.findViewById(R.id.vsIndexFiles)
        val indexModel: TextView = root.findViewById(R.id.vsIndexModel)
        val indexLastBuilt: TextView = root.findViewById(R.id.vsIndexLastBuilt)
        val clearIndex: MaterialButton = root.findViewById(R.id.vsClearIndex)
    }
}

/**
 * The dropdown's rows and, more importantly, its filter.
 *
 * [ArrayAdapter]'s own filter narrows the list to what is in the field, so after a day/night switch
 * replays the field's text only the chosen row is left. This one always offers every model.
 */
private class DropdownAdapter(
    context: Context,
    private val items: List<String>,
) : ArrayAdapter<String>(context, R.layout.item_dropdown, items.toMutableList()) {

    private val passThrough = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults =
            FilterResults().apply {
                values = items
                count = items.size
            }

        override fun publishResults(constraint: CharSequence?, results: FilterResults?) =
            notifyDataSetChanged()
    }

    override fun getFilter(): Filter = passThrough

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        // Set on every bind rather than once: these rows are recycled.
        (view as? TextView)?.setTextColor(context.getColor(R.color.plugin_on_surface))
        return view
    }
}
