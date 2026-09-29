package com.itsaky.androidide.plugins.aicore.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
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
import com.itsaky.androidide.plugins.aicore.models.isRunning
import com.itsaky.androidide.plugins.aicore.models.traceLabel
import com.itsaky.androidide.plugins.aicore.plugin.AiCorePlugin
import com.itsaky.androidide.plugins.aicore.shortcuts.ChatShortcuts
import com.itsaky.androidide.plugins.aicore.shortcuts.bindShortcuts
import com.itsaky.androidide.plugins.aicore.shortcuts.runs
import com.itsaky.androidide.plugins.aicore.viewmodel.ChatViewModel
import com.itsaky.androidide.plugins.aicore.viewmodel.ChatViewModelStore
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeTooltipService
import com.itsaky.androidide.plugins.services.IdeUIService
import io.noties.markwon.Markwon
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val TAG = "$LOG_PREFIX.ChatFragment"

/** Runs of whitespace, including the line breaks a pasted prompt carries. */
private val WHITESPACE = Regex("\\s+")

/** One line, so a title taken from a multi-line prompt ellipsizes instead of stopping at a break. */
private fun String.oneLine(): String = trim().replace(WHITESPACE, " ")

/**
 * ChatFragment for Agent chat UI.
 * Provides a full chat interface with LLM integration.
 */
class ChatFragment : Fragment(), ApprovalDialogFragment.Host {

    private companion object {
        /** Tag the approval dialog is shown under, so it can be found again after recreation. */
        const val APPROVAL_DIALOG_TAG = "approval_dialog"
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
        view.setOnLongClickListener { anchor ->
            val service = tooltipService ?: return@setOnLongClickListener false
            service.showTooltip(anchor, AiCorePlugin.TOOLTIP_CATEGORY, tag)
            true
        }
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
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeMarkwon()
        initializeViewModel()
        syncStorageToCurrentProject()
        setupChatTitle()
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
        chatAdapter = ChatAdapter(
            markwon = markwon,
            wireTooltip = ::wireTooltip,
            isUserMessageExpanded = viewModel::isUserMessageExpanded,
            toggleUserMessageExpanded = viewModel::toggleUserMessageExpanded,
            onMessageAction = ::onMessageAction,
        )
        binding.chatRecyclerView.apply {
            adapter = chatAdapter
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
        }
    }

    /**
     * Keeps the toolbar showing the conversation that is on screen, retitling it as the user's
     * first message arrives or the chat is renamed. A chat that is neither named nor started reads
     * as the sidebar calls it, rather than leaving the header blank.
     */
    private fun setupChatTitle() {
        wireTooltip(binding.chatTitle, AiCorePlugin.TOOLTIP_TAG_CHAT_TITLE)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // The current session is republished on every streamed token, and its title is the
                // same string in nearly all of them. Mapped and de-duplicated first, so a run costs
                // one String comparison a token instead of a collapse and a toolbar layout pass.
                combine(
                    viewModel.currentSession.map { it?.id to it?.displayTitle }.distinctUntilChanged(),
                    viewModel.titlePending,
                ) { (id, title), pending -> (id != null && id in pending) to title }
                    .distinctUntilChanged()
                    .collect { (naming, title) -> showChatTitle(naming, title) }
            }
        }
    }

    /**
     * @param naming whether the backend is still writing this chat's title: a spinner and a muted
     *   placeholder stand in, so the raw prompt never flashes up before the real title.
     * @param title the chat's title, or null for one neither named nor started.
     */
    private fun showChatTitle(naming: Boolean, title: String?) {
        val binding = _binding ?: return
        binding.chatTitleProgress.isVisible = naming
        val shown = title?.oneLine()?.takeIf { it.isNotEmpty() }
        binding.chatTitle.text = when {
            naming -> getString(R.string.session_title_generating)
            else -> shown ?: getString(R.string.session_untitled)
        }
        // The view's own Context: it carries the plugin's resources and the IDE's day/night mode.
        val color = if (naming) R.color.plugin_on_surface_variant else R.color.plugin_on_surface
        binding.chatTitle.setTextColor(binding.chatTitle.context.getColor(color))
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
            dialogContext = { if (isAdded) themedDialogContext() else null },
            onOpenSettings = ::openSettingsFragment,
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

    private fun setupInputArea() {
        binding.sendButton.setOnClickListener {
            if (viewModel.agentState.value.isRunning) {
                viewModel.stopProcessing(reason = "stop button")
            } else {
                sendPrompt()
            }
        }

        // The field scrolls its own long prompts, and answers the chat's keyboard chords.
        binding.promptInputEdittext.keepVerticalDragsToItself()
        binding.promptInputEdittext.growUpTo(
            resources.getInteger(R.integer.chat_input_max_lines),
            binding.root,
        )
        binding.promptInputEdittext.bindShortcuts(ChatShortcuts.SEND_MESSAGE runs ::sendPrompt)

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
     * Sends what is in the composer, from the button or from [ChatShortcuts.SEND_MESSAGE].
     *
     * A refused send — no backend configured, no key saved — leaves the composer exactly as it
     * was, text and keyboard both: it used to wipe the prompt the user had just typed, and putting
     * the keyboard away as well would take the caret off a prompt they still have to re-send.
     */
    private fun sendPrompt() {
        val message = binding.promptInputEdittext.text?.toString() ?: return
        if (message.isBlank()) return
        if (!viewModel.sendMessage(message)) return
        composer?.hideKeyboard()
        binding.promptInputEdittext.text?.clear()
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
            ChatAdapter.ACTION_RETRY -> {
                // The prompt behind this row, not its text: the row may be a tool failure.
                viewModel.retryLastRun()
            }
            ChatAdapter.ACTION_OPEN_SETTINGS -> {
                // Open settings fragment
                openSettingsFragment()
            }
            ChatAdapter.ACTION_COPY -> copyToClipboard(message.text)
        }
    }

    private fun copyToClipboard(text: String) {
        val binding = _binding ?: return
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("chat_message", text))
        Snackbar.make(binding.root, getString(R.string.msg_copied), Snackbar.LENGTH_SHORT).show()
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
