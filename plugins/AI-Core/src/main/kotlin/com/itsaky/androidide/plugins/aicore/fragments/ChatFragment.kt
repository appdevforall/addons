package com.itsaky.androidide.plugins.aicore.fragments

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.BuildConfig
import com.itsaky.androidide.plugins.aicore.R
import com.itsaky.androidide.plugins.aicore.adapters.ChatAdapter
import com.itsaky.androidide.plugins.aicore.databinding.FragmentChatBinding
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.managers.ProjectKey
import com.itsaky.androidide.plugins.aicore.models.AgentState
import com.itsaky.androidide.plugins.aicore.models.ChatTranscript
import com.itsaky.androidide.plugins.aicore.models.SessionRow
import com.itsaky.androidide.plugins.aicore.models.isRunning
import com.itsaky.androidide.plugins.aicore.models.traceLabel
import com.itsaky.androidide.plugins.aicore.plugin.AiCorePlugin
import com.itsaky.androidide.plugins.aicore.viewmodel.ChatViewModel
import com.itsaky.androidide.plugins.aicore.viewmodel.ChatViewModelStore
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeTooltipService
import com.itsaky.androidide.plugins.services.IdeUIService
import io.noties.markwon.Markwon
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

private const val TAG = "$LOG_PREFIX.ChatFragment"

/**
 * ChatFragment for Agent chat UI.
 * Provides a full chat interface with LLM integration.
 */
class ChatFragment : Fragment(), ApprovalDialogFragment.Host {

    private companion object {
        /** Tag the approval dialog is shown under, so it can be found again after recreation. */
        const val APPROVAL_DIALOG_TAG = "approval_dialog"

        /** Saved-state key for [pendingExportSessionId], which must outlive the picker's round trip. */
        const val STATE_PENDING_EXPORT = "pending_export_session_id"
    }

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: ChatViewModel
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var markwon: Markwon
    private val contextFiles = mutableListOf<File>()

    private var composer: ComposerAutoHideController? = null
    private var sidebar: ChatSidebarController? = null

    /**
     * Closes the sidebar on Back rather than letting the press reach the host, which would shut the
     * whole bottom sheet with the panel still open over it. Enabled only while the panel is open —
     * the controller toggles it through onOpenChanged — so Back otherwise means what it always did.
     */
    private val sidebarBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            sidebar?.close()
        }
    }

    /**
     * The chat Export was picked for, held while the system file picker is up: the picker answers
     * with a destination only, and the chat may have grown by the time it does.
     */
    private var pendingExportSessionId: String? = null

    /**
     * The system picker for where an exported chat goes. Registered as a field, since a launcher
     * must exist before the fragment is started; a null Uri is the user cancelling.
     */
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(ChatTranscript.MIME_TYPE)
    ) { uri ->
        val sessionId = pendingExportSessionId
        pendingExportSessionId = null
        if (uri != null && sessionId != null) writeExport(sessionId, uri)
    }

    /** The system picker for a transcript to import; a null Uri is the user cancelling. */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) readImport(uri)
    }

    /** The message list's layout-declared padding, before any cutout inset is added. */
    private val basePadding = Rect()

    /** Message count last logged, so streaming re-emissions do not each get a line. */
    private var renderedMessageCount = -1

    private val tooltipService: IdeTooltipService? by lazy {
        try {
            PluginFragmentHelper.getServiceRegistry(AiCorePlugin.PLUGIN_ID)
                ?.get(IdeTooltipService::class.java)
        } catch (e: Exception) {
            AiCorePlugin.getContext()?.logger
                ?.warn("ChatFragment: tooltip service unavailable; long-press help disabled", e)
            null
        }
    }

    /** Shows this plugin's tooltip for [tag] when [view] is long-pressed (Tier 1/2 + guide). */
    private fun wireTooltip(view: View, tag: String) {
        view.setOnLongClickListener { anchor -> showTooltip(anchor, tag) }
    }

    /**
     * Shows this plugin's tooltip for [tag] on [anchor] now.
     *
     * [anchor] must sit in an Activity or Dialog window: the host's tooltip is a PopupWindow, and
     * one anchored inside another PopupWindow throws BadTokenException and takes the IDE down.
     *
     * @return false when the tooltip service is unavailable, so the long press is not consumed.
     */
    private fun showTooltip(anchor: View, tag: String): Boolean {
        val service = tooltipService ?: return false
        service.showTooltip(anchor, AiCorePlugin.TOOLTIP_CATEGORY, tag)
        return true
    }

    /**
     * Routes inflation through the host so views resolve against a Context whose Configuration
     * tracks the IDE's day/night setting, which is what lets values-night/ colors and the DayNight
     * PluginTheme take effect. The old cloneInContext() stayed pinned to light mode.
     * @param savedInstanceState forwarded to the superclass inflater.
     * @return the theme-aware inflater the plugin's layouts must be inflated with.
     */
    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return com.itsaky.androidide.plugins.base.PluginFragmentHelper.getPluginInflater(
            com.itsaky.androidide.plugins.aicore.plugin.AiCorePlugin.PLUGIN_ID, inflater
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    /**
     * Tears down the view only. It deliberately does **not** stop the agent: the host removes this
     * fragment whenever the user switches bottom-sheet tabs, and cancelling here killed the run in
     * flight — a `run_app` wait can be ten minutes long — so the build finished with nobody left to
     * report it. Only Stop, Clear Chat and plugin dispose cancel a run.
     */
    override fun onDestroyView() {
        if (::chatAdapter.isInitialized) {
            chatAdapter.stopAllAnimations()
        }
        super.onDestroyView()
        // runInFlight=true here, followed by that run still reporting, is the tab-switch fix
        // working; the run being gone from the trace after it is the bug coming back. Guarded
        // because a log line must never be the thing that takes the IDE down.
        if (::viewModel.isInitialized) {
            val state = viewModel.agentState.value
            AgentTrace.stage(
                "UI",
                "chat view destroyed runInFlight=${state.isRunning} state=${state.traceLabel}"
            )
        }
        composer?.detach()
        composer = null
        sidebar?.detach()
        sidebar = null
        _binding = null
    }

    /**
     * Writes the transcript out on the way off screen. The ViewModel now outlives this fragment, so
     * its onCleared() no longer fires on a tab switch and is no longer the only writer.
     */
    override fun onStop() {
        super.onStop()
        if (::viewModel.isInitialized) {
            viewModel.persistState()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The process can be killed while backgrounded even though rotation never recreates us.
        composer?.saveState(outState)
        // The same holds while the export picker is up, which is exactly when the IDE is backgrounded.
        outState.putString(STATE_PENDING_EXPORT, pendingExportSessionId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExportSessionId = savedInstanceState?.getString(STATE_PENDING_EXPORT)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeMarkwon()
        initializeViewModel()
        syncStorageToCurrentProject()
        setupSidebar()
        setupRecyclerView()
        setupInputArea()
        restoreContextChips()
        setupCutoutPadding()
        setupComposer(savedInstanceState)
        setupStatusBar()
        setupBackendIndicator()
        observeViewModel()

        AgentTrace.stage(
            "UI",
            "chat view created runInFlight=${viewModel.agentState.value.isRunning} " +
                "state=${viewModel.agentState.value.traceLabel} " +
                "messages=${viewModel.messages.value.size} " +
                "contextFiles=${contextFiles.size} " +
                "pendingApproval=${viewModel.pendingApprovalRequest.value?.toolName}"
        )

        // Check for test prompt from broadcast receiver (E2E testing)
        injectPendingTestPrompt()
    }

    /**
     * Auto-sends a test prompt left in SharedPreferences by TestBroadcastReceiver. Debug builds
     * only: this drives the agent, and its file-mutating tools, with no user gesture, so it must
     * not exist in a released plugin.
     */
    private fun injectPendingTestPrompt() {
        if (!BuildConfig.DEBUG) return
        try {
            // Check SharedPreferences for pending test prompt (set by TestBroadcastReceiver)
            val context = requireContext()
            val prefs = context.getSharedPreferences("test_ai_prefs", android.content.Context.MODE_PRIVATE)
            val pendingPrompt = prefs.getString("pending_prompt", null)
            val shouldAutoSend = prefs.getBoolean("auto_send", false)

            if (!pendingPrompt.isNullOrBlank() && shouldAutoSend) {
                android.util.Log.d(TAG, "📝 Found pending test prompt: '$pendingPrompt'")

                // Inject into input field
                binding.promptInputEdittext.setText(pendingPrompt)
                android.util.Log.d(TAG, "✅ Prompt injected into input field")

                // Auto-send after a short delay to ensure UI is ready
                binding.promptInputEdittext.post {
                    android.util.Log.d(TAG, "🚀 Sending prompt automatically...")
                    binding.sendButton.performClick()

                    // Clear the SharedPreferences after sending
                    prefs.edit().apply {
                        remove("pending_prompt")
                        remove("auto_send")
                        remove("auto_approve")
                        remove("timestamp")
                        apply()
                    }
                    android.util.Log.d(TAG, "🧹 Cleared pending prompt from preferences")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error checking for pending test prompt: ${e.message}")
        }
    }

    private fun initializeMarkwon() {
        markwon = Markwon.create(requireContext())
    }

    /**
     * Binds storage to the project open right now, re-binding it when that project has changed.
     *
     * The ViewModel is plugin-scoped, so it survives a project switch holding the previous
     * project's sessions. Those are written out under the outgoing key *before* the swap, or the
     * switch silently drops that project's last turn.
     */
    private fun syncStorageToCurrentProject() {
        val bound = viewModel.isStorageInitialized()
        // A null key means nothing answered — the host could not be asked, or has not published a
        // root yet — which is not the same as the project having changed; rebinding on it would
        // show an empty history for a transient failure.
        // With nothing bound yet there is no binding to keep, so the no-project namespace it is.
        val projectKey = ProjectKey.current() ?: if (bound) return else ProjectKey.NO_PROJECT
        if (bound && viewModel.activeProjectKey == projectKey) return
        if (bound) {
            AgentTrace.stage("UI", "project changed; rebinding chat history to $projectKey")
            // A run still in flight would append its remaining turns to whichever session the new
            // project restores, which is the cross-project bleed this ticket exists to stop. Its
            // file tools are also rooted at the project that just closed.
            viewModel.stopProcessing(reason = "project changed")
            viewModel.persistState()
        }
        // Application context: the ViewModel outlives this fragment, and the activity.
        viewModel.initializeStorage(requireContext().applicationContext, projectKey)
    }

    override fun onResume() {
        super.onResume()
        // The plugin API has no project-change callback, so the open project is re-read at every
        // lifecycle edge instead. The host tears this fragment down on each tab switch, so this
        // fires well before the user can type into the wrong project's history.
        syncStorageToCurrentProject()
        // On becoming visible, so the check runs after every plugin has loaded.
        viewModel.checkBackendAvailability()
        // Re-resolve the selected backend here: the settings screen is a separate activity that
        // fully covers chat, so returning from it always delivers onResume.
        viewModel.refreshBackendLabel()
    }

    /**
     * Resolves the plugin-scoped ViewModel rather than a fragment-scoped one, so an agent run
     * survives this fragment being removed and re-attaches to the tab that comes back. See
     * [ChatViewModelStore].
     */
    private fun initializeViewModel() {
        viewModel = ChatViewModelStore.get()
    }

    private fun getPluginContext(): PluginContext? {
        // Access the plugin context via the companion object
        return com.itsaky.androidide.plugins.aicore.plugin.AiCorePlugin.getContext()
    }

    private fun setupRecyclerView() {
        // Item views inflate from parent.context, so no Context needs passing in.
        chatAdapter = ChatAdapter(markwon, ::wireTooltip) { action, message ->
            onMessageAction(action, message)
        }
        binding.chatRecyclerView.apply {
            adapter = chatAdapter
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
        }
    }

    /**
     * Stands the sidebar up: the panel that replaced the toolbar overflow menu and the chat-history
     * dialog behind it. Everything it does lives in [ChatSidebarController]; this only hands it
     * what the fragment owns.
     */
    private fun setupSidebar() {
        val controller = ChatSidebarController(
            binding = binding,
            viewModel = viewModel,
            scope = viewLifecycleOwner.lifecycleScope,
            wireTooltip = ::wireTooltip,
            showTooltip = ::showTooltip,
            showMessage = ::showInfoSnackbar,
            dialogContext = { if (isAdded) themedDialogContext() else null },
            onOpenSettings = ::openSettingsFragment,
            onExportChat = ::launchExport,
            onImportChat = ::launchImport,
            onOpenChanged = { open ->
                sidebarBackCallback.isEnabled = open
                // A panel over the chat with the keyboard still up leaves the list two rows tall.
                if (open) composer?.hideKeyboard()
            },
        )
        controller.attach()
        sidebar = controller

        requireActivity().onBackPressedDispatcher
            .addCallback(viewLifecycleOwner, sidebarBackCallback)
    }

    /**
     * The Context the sidebar's rename and delete prompts are built against.
     *
     * Activity-backed, through [requireContext]: a Dialog takes its window token from its Context,
     * and the plugin Context behind `PluginFragmentHelper.getPluginInflater` is application-scoped
     * with a package name no PackageManager knows — showing a Dialog on it throws BadTokenException
     * and takes the IDE down. Plugin layouts, strings and colors resolve through this one anyway,
     * which is why the plugin's other dialogs are built exactly this way.
     */
    private fun themedDialogContext(): Context =
        ContextThemeWrapper(requireContext(), R.style.PluginTheme)

    /**
     * Asks the system file picker where to save [row]'s chat, suggesting a name from its title.
     *
     * @param row the conversation to export.
     */
    private fun launchExport(row: SessionRow) {
        pendingExportSessionId = row.id
        try {
            exportLauncher.launch(ChatTranscript.fileName(row.title))
        } catch (e: ActivityNotFoundException) {
            pendingExportSessionId = null
            AiCorePlugin.getContext()?.logger?.warn("ChatFragment: no picker to export with", e)
            showInfoSnackbar(getString(R.string.session_picker_unavailable))
        }
    }

    /** Asks the system file picker for a transcript to read back as a new chat. */
    private fun launchImport() {
        try {
            // text/* rather than text/plain alone: some providers label a .txt file text/x-log.
            importLauncher.launch(arrayOf("text/*"))
        } catch (e: ActivityNotFoundException) {
            AiCorePlugin.getContext()?.logger?.warn("ChatFragment: no picker to import with", e)
            showInfoSnackbar(getString(R.string.session_picker_unavailable))
        }
    }

    /**
     * Writes a chat to the file the picker returned and says how that went.
     *
     * On the fragment's own scope, not the view's: a tab switch mid-write must not abandon it.
     *
     * @param sessionId the chat to export.
     * @param uri where the picker said to write it.
     */
    private fun writeExport(sessionId: String, uri: Uri) {
        val resolver = requireContext().contentResolver
        lifecycleScope.launch {
            val message = when (viewModel.exportSession(sessionId, resolver, uri)) {
                ChatViewModel.ExportResult.EXPORTED -> R.string.session_exported
                ChatViewModel.ExportResult.MISSING -> R.string.session_export_missing
                ChatViewModel.ExportResult.FAILED -> R.string.session_export_failed
            }
            showInfoSnackbar(getString(message))
        }
    }

    /**
     * Imports the file the picker returned as a new chat, opening it, or reports that it could not.
     *
     * @param uri the file the user picked.
     */
    private fun readImport(uri: Uri) {
        val resolver = requireContext().contentResolver
        lifecycleScope.launch {
            if (!viewModel.importTranscript(resolver, uri)) {
                showInfoSnackbar(getString(R.string.session_import_failed))
                return@launch
            }
            sidebar?.close()
            showInfoSnackbar(getString(R.string.session_imported))
        }
    }

    private fun setupInputArea() {
        binding.sendButton.setOnClickListener {
            if (viewModel.agentState.value.isRunning) {
                viewModel.stopProcessing(reason = "stop button")
            } else {
                val message = binding.promptInputEdittext.text?.toString() ?: return@setOnClickListener
                if (message.isNotBlank()) {
                    composer?.hideKeyboard()
                    viewModel.sendMessage(message)
                    binding.promptInputEdittext.text?.clear()
                }
            }
        }

        binding.btnAddContext.setOnClickListener {
            showFilePicker()
        }

        // Anchored on the bar, not promptInputEdittext: long-press there is the paste menu.
        wireTooltip(binding.btnAddContext, AiCorePlugin.TOOLTIP_TAG_CONTEXT_FILES)
        wireTooltip(binding.inputBarCard, AiCorePlugin.TOOLTIP_TAG_CHAT_INPUT)
        wireTooltip(binding.sendButton, AiCorePlugin.TOOLTIP_TAG_CHAT_SEND)
        wireTooltip(binding.backendStatusText, AiCorePlugin.TOOLTIP_TAG_SETTINGS_BACKEND)
    }

    /**
     * Records the message list's own padding so the cutout inset is added to it rather than
     * replacing it, and stays correct however many inset passes the window makes.
     */
    private fun setupCutoutPadding() {
        val list = binding.chatRecyclerView
        basePadding.set(list.paddingLeft, list.paddingTop, list.paddingRight, list.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            applyCutoutPadding(insets)
            insets
        }
        // No dispatched insets to read on the first attach, so take them from the window.
        binding.root.doOnAttach { view ->
            ViewCompat.getRootWindowInsets(view)?.let(::applyCutoutPadding)
        }
    }

    /**
     * Holds the message list clear of the camera cutout. Portrait puts it in the status bar and
     * these insets come back zero; landscape moves it onto a side edge, right where message text
     * would otherwise start. The toolbar and composer keep their own edge-to-edge alignment.
     */
    private fun applyCutoutPadding(insets: WindowInsetsCompat) {
        val binding = _binding ?: return
        val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
        binding.chatRecyclerView.setPadding(
            basePadding.left + cutout.left,
            basePadding.top,
            basePadding.right + cutout.right,
            basePadding.bottom,
        )
    }

    /**
     * On a short screen the composer folds away so the history gets its height, and a floating
     * chevron brings it back. Everywhere else the composer stays pinned and the chevron is absent.
     */
    private fun setupComposer(savedInstanceState: Bundle?) {
        val controller = ComposerAutoHideController(
            binding,
            viewLifecycleOwner.lifecycleScope,
            ::wireTooltip,
        )
        // The fragment's own Resources, which is the host activity's and tracks rotation.
        controller.attach(savedInstanceState, resources.configuration)
        composer = controller
    }

    /**
     * EditorActivity handles orientation itself, so rotating never recreates this fragment and an
     * alternative-resource bucket would stay frozen at the orientation it was inflated in. The one
     * thing that must track rotation is re-applied here instead.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val binding = _binding ?: return
        composer?.onConfigurationChanged(newConfig)
        // Posted so the window has published the rotated cutout and width before they are read back.
        binding.root.post {
            val root = _binding?.root ?: return@post
            sidebar?.onConfigurationChanged()
            ViewCompat.getRootWindowInsets(root)?.let(::applyCutoutPadding)
        }
    }

    private fun setupStatusBar() {
        binding.agentStatusContainer.isVisible = false
    }

    private fun setupBackendIndicator() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.activeBackendLabel.collect { label ->
                    _binding?.backendStatusText?.text = label
                }
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { observeMessages() }
                launch { observeAgentState() }
                launch { observePendingApprovalRequest() }
            }
        }
    }

    private suspend fun observeMessages() {
        viewModel.messages.collect { messages ->
            val binding = _binding ?: return@collect
            // One line, and only when the count moves: this collector re-emits on every streamed
            // token, and a per-message dump here made the log unreadable during a run.
            if (messages.size != renderedMessageCount) {
                renderedMessageCount = messages.size
                AgentTrace.detail(
                    "UI",
                    "rendering messages=$renderedMessageCount " +
                        "last=${messages.lastOrNull()?.sender}"
                )
            }
            binding.emptyChatView.isVisible = messages.isEmpty()
            // Sampled before the list changes: streaming re-emits on every token, so scrolling
            // unconditionally would drag the user back down whenever they scrolled up to read.
            val stickToBottom = binding.chatRecyclerView.isAtBottom()
            chatAdapter.submitList(messages) {
                if (stickToBottom && messages.isNotEmpty()) {
                    // Null after onDestroyView: submitList posts this callback.
                    _binding?.chatRecyclerView?.scrollToPosition(messages.lastIndex)
                }
            }
        }
    }

    /** True while the newest message is fully visible, i.e. the user is not reading back. */
    private fun RecyclerView.isAtBottom(): Boolean = !canScrollVertically(1)

    private suspend fun observeAgentState() {
        viewModel.agentState.collect { state ->
            val binding = _binding ?: return@collect
            when (state) {
                is AgentState.Idle -> binding.agentStatusContainer.isVisible = false
                is AgentState.Executing -> {
                    binding.agentStatusContainer.isVisible = true
                    binding.agentStatusMessage.text = getString(
                        R.string.state_executing,
                        state.stepNumber,
                        state.totalSteps,
                        state.description,
                    )
                    binding.agentStatusTimer.text = getString(
                        R.string.state_executing_timing,
                        formatDuration(state.elapsedMillis),
                        formatDuration(state.estimatedTotalMillis),
                    )
                    viewModel.startStateTimer(state)
                }
                is AgentState.Processing -> {
                    binding.agentStatusContainer.isVisible = true
                    binding.agentStatusMessage.text = getString(R.string.generating_response)
                    binding.agentStatusTimer.text = ""
                }
                is AgentState.Error -> {
                    binding.agentStatusContainer.isVisible = false
                    viewModel.stopStateTimer()
                    showErrorSnackbar(state.message)
                    // One-shot: an error raised while this tab was gone must not re-raise on every
                    // later re-attach, now that the state outlives the fragment.
                    viewModel.clearErrorState()
                }
                else -> Unit
            }
            // The composer owns the send/stop button, since Stop is what pins it open.
            composer?.onAgentRunningChanged(state.isRunning)
        }
    }

    /**
     * Renders a duration the way the status timer reads it: `4.5s`, or `1m 4.5s` past the minute.
     *
     * @param millis the duration; a clock that ran backwards reads as zero.
     * @return the localised figure, with no surrounding words.
     */
    private fun formatDuration(millis: Long): String {
        val total = millis.coerceAtLeast(0)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(total)
        val seconds = (total - TimeUnit.MINUTES.toMillis(minutes)) / 1000.0
        return if (minutes > 0) {
            getString(R.string.time_format_minutes, minutes, seconds)
        } else {
            getString(R.string.time_format_seconds, seconds)
        }
    }

    /**
     * The approval dialog currently on screen, looked up by tag rather than held in a field:
     * after a configuration change this fragment is a new instance, and a field would be null
     * while the framework-recreated dialog is still up — leaving it un-dismissable.
     */
    private fun currentApprovalDialog(): ApprovalDialogFragment? =
        childFragmentManager.findFragmentByTag(APPROVAL_DIALOG_TAG) as? ApprovalDialogFragment

    private suspend fun observePendingApprovalRequest() {
        viewModel.pendingApprovalRequest.collect { request ->
            if (request != null) {
                showApprovalDialog(request)
            } else {
                // Withdrawn by Stop or Clear Chat: nothing awaits it, so don't strand the dialog.
                currentApprovalDialog()?.dismissAllowingStateLoss()
            }
        }
    }

    private fun showApprovalDialog(request: com.itsaky.androidide.plugins.aicore.tool.ApprovalRequest) {
        // childFragmentManager makes this fragment the parent, which is how Host is resolved.
        if (currentApprovalDialog() != null) return
        AgentTrace.detail("UI", "approval dialog shown tool=${request.toolName}")
        ApprovalDialogFragment.newInstance(request).show(childFragmentManager, APPROVAL_DIALOG_TAG)
    }

    override fun onApprovalDecision(
        result: com.itsaky.androidide.plugins.aicore.tool.ApprovalResult,
        correction: String?,
    ) {
        AgentTrace.detail("UI", "approval decided choice=$result corrected=${correction != null}")
        viewModel.submitApproval(result, correction)
    }

    /**
     * Opens the file picker rooted at the open project. [IdeProjectService] is the only source of
     * truth for that root; PathGuard's property fallback resolves to "/" here, which would root the
     * picker at the whole device. With no project open this fails closed and says so.
     */
    private fun showFilePicker() {
        val projectService = getPluginContext()?.services?.get(IdeProjectService::class.java)
        val startPath = projectService?.getCurrentProject()?.rootDir?.absolutePath
        if (startPath.isNullOrBlank()) {
            showInfoSnackbar(getString(R.string.file_picker_error_no_project))
            return
        }

        val dialog = FilePickerDialogFragment.newInstance(startPath) { files ->
            addContextFiles(files)
        }
        composer?.pauseUntilClosed(dialog.lifecycle)
        dialog.show(parentFragmentManager, "file_picker")
    }

    /**
     * Rebuilds the attached-file chips from the ViewModel. It keeps the attachments across a tab
     * switch, so without this they would still go into the next prompt with nothing on screen
     * naming them.
     */
    private fun restoreContextChips() {
        contextFiles.clear()
        contextFiles.addAll(viewModel.contextFiles)
        contextFiles.forEach(::addChipForFile)
    }

    private fun addContextFiles(files: List<File>) {
        files.forEach { file ->
            if (!contextFiles.contains(file)) {
                contextFiles.add(file)
                addChipForFile(file)
            }
        }
        viewModel.setContextFiles(contextFiles)
    }

    private fun addChipForFile(file: File) {
        val chip = Chip(requireContext()).apply {
            text = file.name
            isCloseIconVisible = true
            setOnCloseIconClickListener {
                contextFiles.remove(file)
                binding.contextChipGroup.removeView(this)
                viewModel.setContextFiles(contextFiles)
                updateContextChipVisibility()
            }
        }
        binding.contextChipGroup.addView(chip)
        updateContextChipVisibility()
    }

    /**
     * Keeps the chip row out of the layout while empty; in a short bottom sheet the row it would
     * otherwise occupy comes straight out of the chat history's height.
     */
    private fun updateContextChipVisibility() {
        binding.contextChipScroll.isVisible = binding.contextChipGroup.childCount > 0
    }

    private fun onMessageAction(action: String, message: com.itsaky.androidide.plugins.aicore.models.ChatMessage) {
        when (action) {
            ChatAdapter.ACTION_EDIT -> {
                // Show dialog to edit message
                binding.promptInputEdittext.setText(message.text)
                binding.promptInputEdittext.requestFocus()
            }
            ChatAdapter.ACTION_RETRY -> {
                // The prompt behind this row, not its text: the row may be a tool failure.
                viewModel.retryLastRun()
            }
            ChatAdapter.ACTION_OPEN_SETTINGS -> {
                // Open settings fragment
                openSettingsFragment()
            }
        }
    }

    /**
     * Open the Agent settings screen — the same one Preferences → Configuration → Agent opens, so
     * there is one implementation of it. The host mounts it full-screen in PluginScreenActivity;
     * returning from it gives this fragment a real onResume.
     */
    private fun openSettingsFragment() {
        val opened = PluginFragmentHelper.getServiceRegistry(AiCorePlugin.PLUGIN_ID)
            ?.get(IdeUIService::class.java)
            ?.openPluginScreen(
                AiCorePlugin.PLUGIN_ID,
                AiSettingsFragment::class.java.name,
                getString(R.string.pref_agent_title)
            ) ?: false
        if (!opened) {
            AiCorePlugin.getContext()?.logger
                ?.warn("ChatFragment: could not open the Agent settings screen")
            showInfoSnackbar(getString(R.string.msg_settings_unavailable))
        }
    }

    /**
     * Surfaces an [AgentState.Error] as a Snackbar with a shortcut into settings. Snackbar, never
     * Toast: a Toast built from the plugin's Context crashes the IDE with a SecurityException,
     * since the plugin package is not a real installed UID.
     * @param message the error text to show.
     */
    private fun showErrorSnackbar(message: String) {
        val binding = _binding ?: return
        Snackbar
            .make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction(getString(R.string.menu_settings)) { openSettingsFragment() }
            .show()
    }

    private fun showInfoSnackbar(message: String) {
        val binding = _binding ?: return
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

}
