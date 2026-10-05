package com.itsaky.androidide.plugins.aicore.viewmodel

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.R
import com.itsaky.androidide.plugins.aicore.backends.AiBackend
import com.itsaky.androidide.plugins.aicore.backends.BackendRegistry
import com.itsaky.androidide.plugins.aicore.backends.SelectedBackend
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.managers.ChatStorageManager
import com.itsaky.androidide.plugins.aicore.managers.ProjectKey
import com.itsaky.androidide.plugins.aicore.models.AgentState
import com.itsaky.androidide.plugins.aicore.models.ChatMessage
import com.itsaky.androidide.plugins.aicore.models.ChatSession
import com.itsaky.androidide.plugins.aicore.models.ChatTranscript
import com.itsaky.androidide.plugins.aicore.models.MessageStatus
import com.itsaky.androidide.plugins.aicore.models.newestFirst
import com.itsaky.androidide.plugins.aicore.models.Sender
import com.itsaky.androidide.plugins.aicore.models.isRunning
import com.itsaky.androidide.plugins.aicore.models.traceLabel
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.prompt.BackendPrompts
import com.itsaky.androidide.plugins.aicore.prompt.ContextFilesPrompt
import com.itsaky.androidide.plugins.aicore.prompt.IdeContextReader
import com.itsaky.androidide.plugins.aicore.prompt.PromptToolCatalog
import com.itsaky.androidide.plugins.aicore.prompt.ServiceBackendPrompts
import com.itsaky.androidide.plugins.aicore.prompt.SessionContext
import com.itsaky.androidide.plugins.aicore.prompt.SystemPromptFactory
import com.itsaky.androidide.plugins.aicore.prompt.ToolDescriptions
import com.itsaky.androidide.plugins.aicore.prompt.ToolResultsPrompt
import com.itsaky.androidide.plugins.aicore.prompt.config.sharedPromptConfig
import com.itsaky.androidide.plugins.aicore.tool.AgentLoop
import com.itsaky.androidide.plugins.aicore.tool.AgentTools
import com.itsaky.androidide.plugins.aicore.tool.ApprovalRequest
import com.itsaky.androidide.plugins.aicore.tool.ApprovalResult
import com.itsaky.androidide.plugins.aicore.tool.ToolApprovalManager
import com.itsaky.androidide.plugins.aicore.tool.ToolCall
import com.itsaky.androidide.plugins.aicore.tool.ToolCallExtractor
import com.itsaky.androidide.plugins.aicore.tool.ToolExecutionTracker
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.pathsIn
import com.itsaky.androidide.plugins.aicore.tool.sources.ToolSourceStore
import com.itsaky.androidide.plugins.aicore.tool.handlers.BuiltInToolHandlers
import com.itsaky.androidide.plugins.aicore.tool.web.BackendWebSearch
import com.itsaky.androidide.plugins.aicore.tool.web.VerificationPolicy
import com.itsaky.androidide.plugins.aicore.tool.web.WebAccess
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

private const val TAG = "$LOG_PREFIX.ChatViewModel"

/**
 * ViewModel for managing chat state and LLM interactions.
 */
class ChatViewModel(
    private val getContext: () -> PluginContext?
) : ViewModel() {

    companion object {
        /** Terminal tool: shared by [agentLoop] (stops on it) and [runModelTurn] (renders its message). */
        const val RESPOND_TOOL = "respond"

        /**
         * [LlmConfig.extraParams] key for the local-backend GBNF; must match ai-agent-local's
         * `LocalLlmBackend.EXTRA_PARAM_GRAMMAR`.
         */
        private const val EXTRA_PARAM_GRAMMAR = "grammar"

        /**
         * The call envelope this side parses back (see [ToolCallExtractor]) and constrains local
         * sampling to (see [com.itsaky.androidide.plugins.aicore.tool.ToolCallGrammar]). Handed to
         * every backend composing a system prompt, so all three can never drift apart.
         */
        const val TOOL_CALL_SYNTAX =
            """<tool_call>{"tool":"TOOL_NAME","args":{"arg":"value"}}</tool_call>"""

        /** Sampling temperature for a backend that declares no preference of its own. */
        private const val DEFAULT_TEMPERATURE = 0.2f

        /** Output cap elsewhere: older OpenAI models and small servers reject a larger one. */
        private const val DEFAULT_MAX_TOKENS = 4096

        /**
         * Output cap for Gemini, whose 2.5 models spend thinking tokens from it: 4096 cut
         * multi-part answers and whole-file tool calls short (ADFA-6223).
         */
        private const val GEMINI_MAX_TOKENS = 16384

        /** Quiet period a debounced persist waits out; see [schedulePersist]. */
        private const val PERSIST_DEBOUNCE_MS = 1_000L

        /**
         * How many of a restored transcript's messages the model is given back; one exchange
         * spends two. See [rebuildHistoryFrom].
         */
        private const val MAX_RESTORED_HISTORY = 40

        /**
         * Character budget for the same restore, since 40 messages carrying code blocks would
         * otherwise push the next send past a small local model's created context.
         */
        private const val MAX_RESTORED_HISTORY_CHARS = 8_000
    }

    private fun getLlmService(): LlmInferenceService? {
        return try {
            SharedServices.get(LlmInferenceService::class.java)
        } catch (e: Exception) {
            logError("could not obtain the LLM service", e)
            null
        }
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _agentState = MutableStateFlow<AgentState>(AgentState.Idle)
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    /**
     * Publishes an agent state and traces the transition, so the shape of a run — generating,
     * executing which tool, idle, cancelled — reads as one `STATE` line per change in the trace.
     *
     * Every transition goes through here except the timer's own elapsed-time updates in
     * [startStateTimer], which fire ten times a second and would bury everything else.
     *
     * @param state the state to publish; an unchanged state is neither published nor logged.
     */
    private fun setState(state: AgentState) {
        val previous = _agentState.value
        if (previous == state) return
        _agentState.value = state
        AgentTrace.stage("STATE", "${previous.traceLabel} -> ${state.traceLabel}")
    }

    private val _backendStatus = MutableStateFlow(BackendStatus(AiBackend.DEFAULT_ID, false))
    val isBackendAvailable: StateFlow<Boolean> = _backendStatus
        .map { it.isAvailable }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    // Conversation history for LLM context (separate from UI messages)
    private val _history = MutableStateFlow<List<LlmInferenceService.ChatMessage>>(emptyList())
    val history: StateFlow<List<LlmInferenceService.ChatMessage>> = _history.asStateFlow()

    private val _titlePending = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Sessions whose title is being written: from their first message until the backend's title
     * lands or the attempt ends. The UI shows a loading title for these instead of the raw prompt.
     */
    val titlePending: StateFlow<Set<String>> = _titlePending.asStateFlow()

    val currentSession: StateFlow<ChatSession?> = combine(_sessions, _currentSessionId) { sessions, id ->
        sessions.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    /**
     * What the last availability check resolved: which backend to send to, and whether it is ready.
     *
     * One value, not two fields. The check runs on IO and [sendMessage] reads it on Main, and a
     * superseded run writing one field after a newer run wrote both would leave the id naming one
     * backend while the verdict describes another.
     *
     * @param id the backend the request must be built for
     * @param isAvailable whether that backend is configured and ready
     */
    private data class BackendStatus(val id: String, val isAvailable: Boolean)

    /** Backend the last availability check resolved; see [BackendStatus]. */
    private val currentBackendId: String
        get() = _backendStatus.value.id

    /**
     * Label for the backend the user *selected* in settings, shown under the chat input. Tracks the
     * selection, not the availability-resolved backend: picking Gemini must read "Gemini API" before
     * its key check runs, or it would always show "Local LLM".
     */
    private val _activeBackendLabel = MutableStateFlow(selectedBackendLabel())
    val activeBackendLabel: StateFlow<String> = _activeBackendLabel.asStateFlow()

    private fun selectedBackendLabel(): String =
        // Resolved exactly as the settings screen and the availability check resolve it, so the
        // three cannot name different backends on the same launch.
        when (val selected = BackendRegistry.selected()) {
            is SelectedBackend.Installed -> selected.option.displayName
            // A stored selection resolving to nothing means its plugin is gone. Saying "no backend"
            // there would read as "install one" when one is installed — just not the chosen one.
            SelectedBackend.Missing -> str(R.string.backend_selected_missing_short)
            SelectedBackend.None -> str(R.string.backend_none_installed_short)
        }

    /** Re-read the selected backend and update [activeBackendLabel]; call when returning to chat. */
    fun refreshBackendLabel() {
        _activeBackendLabel.value = selectedBackendLabel()
    }

    // Tool execution infrastructure
    private val approvalManager = ToolApprovalManager(sharedPromptConfig) { handler ->
        ToolDescriptions.describe(sharedPromptConfig.config(), RESPOND_TOOL, handler)
    }
    private val toolResultsPrompt = ToolResultsPrompt(sharedPromptConfig, RESPOND_TOOL)
    private val agentLoop = AgentLoop(
        formatToolResults = toolResultsPrompt,
        terminalTool = RESPOND_TOOL,
        unfinishedTurn = toolResultsPrompt::unfinished,
        requiredToolTurn = toolResultsPrompt::requiredTool,
    )
    val toolExecutionTracker = ToolExecutionTracker()

    /** What the active backend answers about prompts and the tool-calling protocol. */
    private val backendPrompts: BackendPrompts = ServiceBackendPrompts(
        backendId = { currentBackendId },
        getService = ::getLlmService,
        logWarn = { message, error -> logWarn(message, error) },
    )

    /** Searches the web through whichever backend the run is against; see [BackendWebSearch]. */
    private val webSearch = BackendWebSearch(
        config = sharedPromptConfig,
        backendId = { currentBackendId },
        backend = { getLlmService()?.getBackend(currentBackendId) },
    )

    /** Builds the system prompt for a run; see [SystemPromptFactory]. */
    private val systemPromptFactory = SystemPromptFactory(
        config = sharedPromptConfig,
        ideContext = IdeContextReader(getContext),
        backend = backendPrompts,
        session = { SessionContext.current() },
        terminalTool = RESPOND_TOOL,
        toolCallSyntax = TOOL_CALL_SYNTAX,
    )

    /** Renders the user's attached files into the turn they were attached to. */
    private val contextFilesPrompt = ContextFilesPrompt(sharedPromptConfig) { message, error ->
        logWarn(message, error)
    }

    /** This plugin's own handlers, fixed for the ViewModel's life; the contributed ones are not. */
    private val builtInHandlers: List<ToolHandler>

    /**
     * Router, executor and grammar as one snapshot, replaced wholesale when a source registers.
     * Volatile because the rebuild arrives on whichever thread activated the contributing plugin,
     * while the agent loop reads it on its own.
     */
    @Volatile
    private var agentTools: AgentTools

    /** Rebuilds the tool set whenever the registered sources change. */
    private val toolSourcesChanged: () -> Unit = { rebuildAgentTools() }

    /** The in-flight agent run (streaming + tool loop), so it can be cancelled. */
    private var generationJob: Job? = null

    /**
     * The in-flight chat title request. The next run waits for it before generating: backends keep
     * per-request state process-wide (Gemini's current job, the local model's token cap).
     */
    @Volatile
    private var titleRequest: TitleRequest? = null

    /** A title being written, and the session it is for. */
    private class TitleRequest(val sessionId: String, val job: Job)

    /**
     * Whether a chat model turn is generating. The service's cancel is global, so Stop and Clear
     * Chat call it only then; otherwise it would cancel a title request, or another plugin's call.
     */
    @Volatile
    private var modelTurnInFlight = false

    /** Sessions asked for a title this process; a failing backend is not re-asked every run. */
    private val titleRequested = mutableSetOf<String>()

    /**
     * User messages the reader unfolded, kept here so a fold survives the chat view being rebuilt.
     * Main only. Never pruned: it grows by one id per tap, and message ids are never reused.
     */
    private val expandedUserMessageIds = mutableSetOf<String>()

    /** The in-flight backend availability check, so a resume can supersede the previous one. */
    private var backendCheckJob: Job? = null

    /**
     * Sequence number of the newest availability check, identifying which run may publish.
     *
     * Main-thread only: handed out in [checkBackendAvailability] and tested in
     * [publishBackendStatus], both on Main, so a superseded run cannot slip a write past the test.
     */
    private var backendCheckSequence = 0

    private val generationEpoch = AtomicInteger(0)

    /** True while a generation is admitted and its coroutine has not yet unwound; gates re-entry. */
    private val isGenerating = MutableStateFlow(false)

    /** Whether the current run's most recent tool batch failed; reset per run. */
    @Volatile
    private var lastToolFailedThisRun = false

    /**
     * The transcript row this run rewrites in place as each tool starts, closed as a one-line
     * summary when the run ends; null before the run's first tool. Main thread only.
     */
    private var activityMessageId: String? = null

    /** Every tool name this run executed, in order, for the activity line's closing summary. */
    private val runToolNames = mutableListOf<String>()

    /** Each executed call with its result, for the activity row's [ChatMessage.toolLog]. Main thread only. */
    private val runToolLog = mutableListOf<String>()

    /**
     * A reply of this run that holds code, for [reviewAnswer] to check once the run is done.
     *
     * @property messageId the bubble it was shown in.
     * @property displayText the bubble's text.
     * @property historyText what the model wrote, as the transcript keeps it.
     */
    private data class CodeReply(val messageId: String, val displayText: String, val historyText: String)

    /** This run's last reply holding code; written on the stream's thread, read after the loop. */
    @Volatile
    private var runCodeReply: CodeReply? = null

    /** Whether this run changed the project; its answer then reports that, and is not reviewed. */
    @Volatile
    private var runChangedProject = false

    /** The prompt the last run was started with, for [retryLastRun]; null before the first send. */
    @Volatile
    private var lastRunPrompt: String? = null

    /**
     * How long [history] was before the last run began appending to it, so [retryLastRun] can drop
     * that run's turns rather than retrying behind them.
     */
    @Volatile
    private var historySizeBeforeLastRun = 0

    /**
     * The user message the last run posted, so [editPrompt] knows [historySizeBeforeLastRun]
     * describes that prompt and not an earlier one; null when the retry point is forgotten.
     */
    @Volatile
    private var lastRunUserMessageId: String? = null

    /** The tool awaiting approval, straight from [approvalManager] — no polling in between. */
    val pendingApprovalRequest: StateFlow<ApprovalRequest?> = approvalManager.currentApprovalRequest

    /**
     * Whether the user's prompts offer Edit and version switching: false while a run is generating,
     * executing a tool, or waiting on an approval, which counts as busy on its own. A stopped run
     * stays busy until its coroutine unwinds, which a blocking tool can hold up for minutes.
     */
    val canChangePrompts: StateFlow<Boolean> =
        combine(isGenerating, _agentState, pendingApprovalRequest, ::isIdle)
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Every prompt on screen with other versions, and which of them it is; see [ChatBranches]. */
    internal val promptVersions: StateFlow<Map<String, ChatBranches.Position>> =
        combine(_sessions, _currentSessionId) { sessions, sessionId ->
            sessions.firstOrNull { it.id == sessionId }
        }
            // A streamed token replaces the session but moves no message, so it needs no recount.
            .distinctUntilChangedBy { session ->
                session?.let { Triple(it.id, it.messages.map(ChatMessage::id), it.otherBranches) }
            }
            .map { session -> session?.let(ChatBranches::positions).orEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** The one rule [canChangePrompts] and [isBusy] share: no run in flight, no approval open. */
    private fun isIdle(generating: Boolean, state: AgentState, approval: ApprovalRequest?): Boolean =
        !generating && !state.isRunning && approval == null

    /**
     * Whether a prompt may not be edited or switched right now. Read from the sources rather than
     * [canChangePrompts], which a collector can see a beat late.
     */
    private fun isBusy(): Boolean =
        !isIdle(isGenerating.value, _agentState.value, pendingApprovalRequest.value)

    private var _contextFiles = listOf<File>()

    /** Files the user attached; read back by the fragment to rebuild its chips on re-attach. */
    val contextFiles: List<File> get() = _contextFiles

    private var stateUpdateJob: Job? = null

    private lateinit var storageManager: ChatStorageManager

    /**
     * Namespace of the project the live sessions belong to, so the fragment can notice the open
     * project changing under a ViewModel that outlives it. Null until storage is initialized.
     */
    var activeProjectKey: String? = null
        private set

    /**
     * Coalesces the burst of [syncMessageToSession] calls a streamed reply makes into one write.
     * Main-thread only, like every call that schedules or cancels it.
     */
    private var persistJob: Job? = null

    /**
     * Where writes actually run. Deliberately not [viewModelScope]: that is already cancelled by
     * the time [onCleared] asks for the last write, and this scope must outlive it. A failure
     * reaching here is logged rather than thrown, so a full disk cannot take the IDE down.
     */
    private val persistScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            logError("Chat history write failed", e)
        }
    )

    /** Held for the whole of a write, so two writes can never interleave in the same prefs file. */
    private val persistMutex = Mutex()

    /** Numbers each snapshot handed to [persistScope]; see the staleness check in [persistState]. */
    private val persistTicket = AtomicLong(0)

    /**
     * Ticket of the newest snapshot written, per project namespace; read and written under
     * [persistMutex]. Per namespace because the flush of the outgoing project on a switch is older
     * than the incoming project's first write, and one counter discarded it as stale.
     */
    private val lastWrittenTicket = mutableMapOf<String, Long>()

    fun isStorageInitialized(): Boolean = ::storageManager.isInitialized

    init {
        builtInHandlers = getContext()?.let { BuiltInToolHandlers.create(it, webSearch::search) }.orEmpty()
        agentTools = buildAgentTools()
        ToolSourceStore.shared.addChangeListener(toolSourcesChanged)
    }

    /**
     * Builds a fresh snapshot from the built-ins plus whatever is contributed right now.
     * @return the new tool set.
     */
    private fun buildAgentTools(): AgentTools = AgentTools.build(
        builtInHandlers = builtInHandlers,
        store = ToolSourceStore.shared,
        approvalManager = approvalManager,
        toolExecutionTracker = toolExecutionTracker,
        terminalTool = RESPOND_TOOL,
    ).also(::logPromptBudget)

    /**
     * Logs what the prompt budget cost this snapshot.
     *
     * Once per rebuild rather than per message: silent truncation reads as "everything is exposed"
     * when it is not, and the same line on every turn is how a log stops being read.
     *
     * @param tools the snapshot just built.
     */
    private fun logPromptBudget(tools: AgentTools) {
        val budgeted = tools.promptTools
        if (budgeted.droppedTools.isNotEmpty()) {
            android.util.Log.w(
                TAG,
                "Prompt budget dropped ${budgeted.droppedTools.size} contributed tool(s): " +
                    budgeted.droppedTools.joinToString(", ")
            )
        }
        if (budgeted.truncatedDescriptions > 0) {
            android.util.Log.i(
                TAG,
                "Prompt budget shortened ${budgeted.truncatedDescriptions} tool description(s)"
            )
        }
    }

    /**
     * Swaps in a tool set that includes the current sources. One assignment, so a run can never see
     * a router and a grammar that disagree; a run already in flight keeps the snapshot it started
     * with and finishes against it.
     */
    private fun rebuildAgentTools() {
        agentTools = buildAgentTools()
        android.util.Log.i(
            TAG,
            "Tool set rebuilt: ${agentTools.router.getAllHandlers().size} tools, " +
                "${agentTools.contributedHandlers.size} contributed"
        )
    }

    /**
     * Points storage at [projectKey]'s history and loads it, replacing whatever was loaded before.
     *
     * Callers switching projects must [persistState] first: the live sessions still belong to the
     * outgoing project, and this call is the moment they stop being reachable.
     *
     * @param context any Android context; the application one, since this outlives the fragment.
     * @param projectKey the open project's namespace, from [ProjectKey].
     */
    fun initializeStorage(context: android.content.Context, projectKey: String) {
        activeProjectKey = projectKey
        storageManager = ChatStorageManager(context, projectKey)
        loadSessions()
    }

    fun loadSessions() {
        val loaded = storageManager.loadSessions()
        logDebug("loadSessions: loaded ${loaded.size} sessions")
        if (loaded.isEmpty()) {
            createNewSession()
        } else {
            _sessions.value = loaded
            val currentId = storageManager.loadCurrentSessionId()
            // Newest when nothing points anywhere — a stored id naming a session since deleted, or
            // a first read. The stored list is in append order, so `first()` is the oldest chat.
            val session = loaded.firstOrNull { it.id == currentId } ?: loaded.newestFirst().first()
            switchToSession(session.id)
        }
    }

    /**
     * Writes the transcript to disk if storage is up. Public because this ViewModel now outlives
     * the fragment, so [onCleared] fires only on plugin dispose and can no longer be the only
     * writer.
     *
     * Call it from the main thread. It returns as soon as it has taken its snapshot; serializing
     * and writing happen on [persistScope], in the order the snapshots were taken.
     */
    fun persistState() {
        persistJob?.cancel()
        persistJob = null
        if (!isStorageInitialized()) {
            AgentTrace.detail("PERSIST", "skipped=storage not initialized")
            return
        }
        // Read on the main thread, which owns all three: the manager is swapped on a project
        // change, and writing the outgoing project's sessions through the incoming project's
        // manager is exactly the bleed this ticket removes.
        val manager = storageManager
        val projectKey = activeProjectKey ?: ProjectKey.NO_PROJECT
        val currentSessionId = _currentSessionId.value
        // Safe to hand straight to the serializer, which runs on another thread: a session's
        // messages are immutable, so a running turn replaces the session rather than growing the
        // list under Gson's feet.
        val sessions = _sessions.value
        val ticket = persistTicket.incrementAndGet()

        AgentTrace.detail(
            "PERSIST",
            "sessions=${sessions.size} messages=${_messages.value.size} " +
                "session=$currentSessionId"
        )
        persistScope.launch {
            persistMutex.withLock {
                // Coroutines dispatched to the IO pool do not start in the order they were
                // launched, so an older snapshot can arrive after a newer one has landed. Only a
                // snapshot of the same project can supersede this one; another project's writes go
                // to another namespace and say nothing about how current this one is.
                if (ticket < (lastWrittenTicket[projectKey] ?: 0L)) return@withLock
                lastWrittenTicket[projectKey] = ticket
                manager.persist(sessions, currentSessionId)
            }
        }
    }

    /**
     * Persists shortly after the last change, rather than on the change itself.
     *
     * [syncMessageToSession] runs once per streamed token, and every write serializes every session
     * in the namespace, so writing on each one would snapshot and serialize the whole history per
     * token. The delay bounds what a process kill can cost to the tokens of the last second; a run
     * that ends normally writes again as it finishes.
     *
     * Main-dispatched because [persistState] reads main-confined state before handing it off, and
     * because [persistJob] is only ever touched from that thread.
     */
    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch(Dispatchers.Main) {
            delay(PERSIST_DEBOUNCE_MS)
            // Cleared first so the write below does not cancel the coroutine running it.
            persistJob = null
            persistState()
        }
    }

    /**
     * Submit user's approval decision.
     */
    fun submitApproval(result: ApprovalResult, correction: String? = null) {
        approvalManager.submitApproval(result, correction)
    }

    /**
     * Set context files to include in prompts.
     */
    fun setContextFiles(files: List<File>) {
        // Copied: the caller passes its own mutable list, which it keeps editing.
        _contextFiles = files.toList()
    }

    /**
     * Drops a message from the transcript and from the session list behind it.
     *
     * Both, always: [syncMessageToSession] republishes the transcript from the session's own list,
     * so a message removed from one and not the other comes back on the next sync. That is what
     * left a silenced turn's empty agent bubble on screen, animating its dots for the rest of the
     * conversation.
     *
     * @param messageId the message to remove; an unknown id is a no-op.
     */
    private fun removeMessageFromSession(messageId: String) {
        removeMessages { it.id == messageId }
    }

    /**
     * Drops every message [doomed] accepts, from the transcript and from the session behind it.
     *
     * @param doomed picks the messages to remove; the two lists mirror each other, so the
     *   transcript is what decides whether anything matched.
     * @return true when at least one message was removed.
     */
    private fun removeMessages(doomed: (ChatMessage) -> Boolean): Boolean {
        val remaining = _messages.value.filterNot(doomed)
        if (remaining.size == _messages.value.size) return false
        _messages.value = remaining
        val session = currentSessionOrNull() ?: return true
        // Through ChatBranches: a stored version may follow a removed message, and must not dangle.
        val trimmed = ChatBranches.removeFromScreen(session, doomed)
        _sessions.value = _sessions.value.map { if (it.id == session.id) trimmed else it }
        return true
    }

    /**
     * Removes the "backend is not ready" notices, which a ready backend has made wrong.
     *
     * Called from [publishBackendStatus] on the edge into readiness, and from [adoptSession] for a
     * transcript that was not on screen when that edge passed — between them, no conversation in
     * the project keeps a warning about a backend that now works. Main thread only.
     */
    internal fun clearBackendSetupNotices() {
        if (!removeMessages { it.isSetupError }) return
        AgentTrace.detail("UI", "backend configured; dropped the setup notices")
        schedulePersist()
    }

    /**
     * @return the session [_currentSessionId] names, or null when none is selected or it names a
     *   session the list no longer holds.
     */
    private fun currentSessionOrNull(): ChatSession? {
        val sessionId = _currentSessionId.value ?: return null
        return _sessions.value.firstOrNull { it.id == sessionId }
    }

    /**
     * Publishes the current session with [messages] in place of its transcript.
     *
     * Rebuilt around a copied session rather than edited in place: [_sessions] holds one immutable
     * value, and an in-place edit reaches collectors equal to the value they already hold.
     *
     * @param messages the current session's new transcript.
     */
    private fun replaceCurrentSessionMessages(messages: List<ChatMessage>) {
        val sessionId = _currentSessionId.value ?: return
        _sessions.value = _sessions.value.map {
            if (it.id == sessionId) it.copy(messages = messages) else it
        }
    }

    /**
     * Helper method to synchronize a message to the current session.
     * Updates or adds the message to the session's message list.
     */
    private fun syncMessageToSession(message: ChatMessage) {
        val session = currentSessionOrNull() ?: return
        val existingIndex = session.messages.indexOfFirst { it.id == message.id }
        val updated = if (existingIndex >= 0) {
            session.messages.mapIndexed { index, existing ->
                if (index == existingIndex) message else existing
            }
        } else {
            session.messages + message
        }
        replaceCurrentSessionMessages(updated)
        _messages.value = updated
        schedulePersist()
    }

    /**
     * Surfaces a setup problem both ways: a persistent SYSTEM bubble and [AgentState.Error] for the
     * fragment's Snackbar. Used by [sendMessage]'s pre-flight guards, which reject before any backend
     * runs, so the downstream `onError`/UserFeedback path never fires.
     *
     * Flagged as a setup error, which is what [clearBackendSetupNotices] removes it by once the
     * backend is configured.
     * @param text the error text to show.
     */
    private fun emitSystemError(text: String) {
        val errorMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = text,
            sender = Sender.SYSTEM,
            status = MessageStatus.ERROR,
            isSetupError = true
        )
        _messages.value = _messages.value + errorMessage
        syncMessageToSession(errorMessage)
        setState(AgentState.Error(text))
    }

    /**
     * The sampling temperature the active backend asks for.
     *
     * @return the backend's preference, or null when it declares none or cannot be reached
     */
    private fun backendTemperature(): Float? = try {
        getLlmService()?.getBackend(currentBackendId)?.defaultTemperature
    } catch (e: Throwable) {
        logWarn("backend '$currentBackendId' supplied no temperature", e)
        null
    }

    /** How many tokens a reply may use on the current backend. */
    private fun replyTokenCap(): Int =
        if (currentBackendId == AiBackend.GEMINI_ID) GEMINI_MAX_TOKENS else DEFAULT_MAX_TOKENS

    /**
     * The advice for a reply that meant to call a tool and produced nothing runnable.
     *
     * @param reason how the call failed to parse.
     * @return the string resource to show the user.
     */
    private fun unparsedReplyMessage(reason: ToolCallExtractor.UnparsedReply): Int = when (reason) {
        ToolCallExtractor.UnparsedReply.TRUNCATED -> R.string.agent_reply_truncated
        ToolCallExtractor.UnparsedReply.MALFORMED -> R.string.agent_reply_malformed
    }

    /**
     * Executes a batch of tool calls and returns the results for the [agentLoop] to feed back;
     * leaves [AgentState.Idle] to the loop.
     *
     * A successful call is reported only on the run's single activity line, which this rewrites as
     * each call starts. A failure keeps its own message: it is the one thing here a user has to act
     * on, and it carries the Retry button.
     *
     * @param tools the snapshot this run started with; a source registered mid-run does not join it.
     * @param toolCalls the calls to execute.
     * @return the results, positionally aligned with [toolCalls].
     */
    private suspend fun executeToolCalls(
        tools: AgentTools,
        toolCalls: List<ToolCall>,
    ): List<ToolResult> {
        if (toolCalls.isEmpty()) return emptyList()

        val executingState = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = toolCalls.size,
            description = toolCalls.first().name
        )
        withContext(Dispatchers.Main) { setState(executingState) }
        startStateTimer(executingState)

        val results = tools.executor.execute(toolCalls) { call ->
            withContext(Dispatchers.Main) { showActivity(call) }
        }
        if (toolCalls.any { tools.router.getHandler(it.name)?.mutatesProject == true }) {
            runChangedProject = true
        }

        // Record whether this batch's last tool failed (read by runModelTurn).
        lastToolFailedThisRun = results.lastOrNull()?.success == false

        withContext(Dispatchers.Main) {
            results.forEachIndexed { index, result ->
                toolCalls.getOrNull(index)?.let { runToolLog += AgentActivity.logEntry(it, result) }
            }
            activityMessageId?.let { id ->
                _messages.value.firstOrNull { it.id == id }?.let { putActivity(it.text, it.status) }
            }
            results.forEachIndexed { index, result ->
                if (result.success) return@forEachIndexed
                val toolCall = toolCalls[index]
                val resultMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    text = "${toolCall.name} failed: ${result.message}\n${result.error_details ?: ""}",
                    sender = Sender.TOOL,
                    status = MessageStatus.ERROR
                )
                _messages.value = _messages.value + resultMessage
                syncMessageToSession(resultMessage)
            }
        }

        stopStateTimer()
        return results
    }

    /**
     * Check whether the backend the user *selected* is available. The chat sends to that backend or
     * to none: substituting whichever other backend happened to be configured would hand the
     * prompt, and the source files with it, to a provider the user did not choose.
     *
     * Should be called when the fragment becomes visible. Retries with delays while the selection
     * is not registered yet, to absorb plugin loading order, but settles at once once it is
     * registered — "registered but unconfigured" is an answer, not a race.
     *
     * The previous run is cancelled first: every resume starts one, and a run still inside its
     * retry loop would otherwise write its stale verdict over a newer one — leaving the chat
     * refusing to send with a backend that was configured in between.
     */
    fun checkBackendAvailability() {
        backendCheckJob?.cancel()
        val sequence = ++backendCheckSequence
        backendCheckJob = viewModelScope.launch(Dispatchers.IO) {
            // Retry up to 5 times with 500ms delays to handle plugin loading order
            repeat(5) { attempt ->
                val llmService = getLlmService()
                if (llmService != null) {
                    try {
                        // Resolved through the registry, exactly as the settings screen and the
                        // status line resolve it. Going to the service's own list instead would
                        // hand AiBackend.preferredId a hash-ordered collection, and with nothing
                        // stored the two would answer differently on the same launch.
                        val selectedId =
                            (BackendRegistry.selected() as? SelectedBackend.Installed)?.option?.id
                        val backend = selectedId?.let { llmService.getBackend(it) }
                        if (backend != null) {
                            // Id set even when unavailable: a stale id from an earlier check would
                            // otherwise build the next request for a backend since moved off.
                            val published = publishBackendStatus(sequence) {
                                BackendStatus(backend.id, backend.isAvailable)
                            }
                            logDebug(
                                "backend check: selected=${backend.id} " +
                                    "available=${backend.isAvailable} published=$published"
                            )
                            return@launch // Answered — available or not, there is no substitute
                        }
                    } catch (e: Exception) {
                        logWarn("backend check failed on attempt ${attempt + 1}", e)
                    }
                }

                // Wait before next retry (except on last attempt)
                if (attempt < 4) {
                    delay(500)
                }
            }

            // All retries failed
            logWarn("backend check: nothing registered for the selected backend")
            // Keeps whichever id is on record: nothing was resolved to replace it with.
            publishBackendStatus(sequence) { it.copy(isAvailable = false) }
        }
    }

    /**
     * Write this check's verdict, unless a newer check has started.
     *
     * Confined to the main thread, where [checkBackendAvailability] hands out sequence numbers: the
     * test and the write then sit in one non-suspending block, so nothing can land between them.
     * Cancelling the previous job is not enough on its own — cancellation is cooperative, so a run
     * already past an `isActive` test still runs to its next suspension point and would write its
     * stale verdict over the newer one, leaving the chat refusing to send with a backend that was
     * configured in between.
     *
     * @param sequence the sequence number this run was started with
     * @param transform builds the new status from the current one
     * @return true if the verdict was written, false if a newer check had superseded this one
     */
    private suspend fun publishBackendStatus(
        sequence: Int,
        transform: (BackendStatus) -> BackendStatus,
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        if (sequence != backendCheckSequence) return@withContext false
        val previous = _backendStatus.value
        _backendStatus.value = transform(previous)
        // Only on the edge into readiness: that is the moment a stored notice became wrong, and
        // the guard keeps every later check off the transcript.
        if (_backendStatus.value.isAvailable && !previous.isAvailable) clearBackendSetupNotices()
        true
    }

    /**
     * Send a user message and get agent response.
     *
     * @param userMessage the prompt to send.
     * @return true once a run has been started; false when a pre-flight guard rejected the prompt,
     *   which is what keeps the composer's text in place for an unconfigured backend.
     */
    fun sendMessage(userMessage: String): Boolean {
        val llmService = passPreflight(userMessage) ?: return false
        // Reject re-entry while a generation is still in flight.
        if (!isGenerating.compareAndSet(false, true)) return false
        launchRun(llmService, userMessage, contextFiles)
        return true
    }

    /**
     * The checks a prompt must pass before any run starts; a setup failure leaves a notice.
     *
     * @param userMessage the prompt about to be sent.
     * @return the service to run against, or null when the prompt must not be sent.
     */
    private fun passPreflight(userMessage: String): LlmInferenceService? {
        val llmService = getLlmService()
        if (llmService == null) {
            emitSystemError(str(R.string.error_llm_service_not_available))
            return null
        }

        if (!_backendStatus.value.isAvailable) {
            // Names the selected backend: the point of stopping here is that the user learns which
            // backend is not ready, instead of the request quietly going somewhere else.
            emitSystemError(
                when (val selected = BackendRegistry.selected()) {
                    is SelectedBackend.Installed ->
                        str(R.string.error_backend_not_ready, selected.option.displayName)
                    // Resolved to nothing with a selection stored: the chosen backend's plugin is
                    // gone, which is a different fix from having installed no backend at all.
                    SelectedBackend.Missing -> str(R.string.backend_selected_not_installed)
                    SelectedBackend.None -> str(R.string.backend_none_installed)
                }
            )
            return null
        }

        return llmService.takeUnless { userMessage.isBlank() }
    }

    /**
     * Starts the agent run for a prompt that passed [passPreflight], once the caller has claimed
     * [isGenerating]. Main only: the prompt is on screen by the time this returns.
     *
     * @param llmService the service [passPreflight] returned.
     * @param userMessage the prompt to send.
     * @param runFiles the attachments, read by the caller before anything it changed could move them.
     */
    private fun launchRun(llmService: LlmInferenceService, userMessage: String, runFiles: List<File>) {
        AgentTrace.beginRun(currentBackendId, userMessage, runFiles.size)
        // Reset per-run tool tracking.
        lastToolFailedThisRun = false
        activityMessageId = null
        runToolNames.clear()
        runToolLog.clear()
        runCodeReply = null
        runChangedProject = false
        // Created here, on Main, so the retry point below names the prompt it belongs to.
        val userChatMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = userMessage,
            sender = Sender.USER,
            status = MessageStatus.SENT,
            contextFiles = runFiles.map { it.absolutePath }.takeIf { it.isNotEmpty() },
        )
        // Where a Retry has to rewind to; read here, on Main, while no run can be appending.
        lastRunPrompt = userMessage
        lastRunUserMessageId = userChatMessage.id
        historySizeBeforeLastRun = _history.value.size
        // Read once: prompt, grammar and executor must all describe the same tool set.
        val tools = agentTools
        val epoch = generationEpoch.incrementAndGet()
        // The session this run may title; read before the message lands, so the header never
        // shows it as the title first.
        val titleSessionId = currentSessionOrNull()?.takeIf { needsTitle(it) }?.id
        titleSessionId?.let { id -> _titlePending.value = _titlePending.value + id }
        // Posted now, not from the run: a Stop before the run's first dispatch left a fork with no
        // prompt at its fork point, and so no arrows to reach the version it had put aside.
        _messages.value = _messages.value + userChatMessage
        syncMessageToSession(userChatMessage)
        setState(AgentState.Processing(str(R.string.msg_generating)))
        // ATOMIC: a Stop before the first dispatch skipped the block, finally too, so isGenerating
        // stayed set and every later send was refused; now the cancel lands at the first suspension.
        generationJob = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            // Whether a title request took over settling this run's title placeholder.
            var titleRequestStarted = false
            try {
                // Queued behind a title still being written; the prompt shows as generating meanwhile.
                awaitTitleRequest()

                // One list for both halves of the protocol: the prompt describes it and a
                // natively calling backend is sent it, so the two can never name different tools.
                val canSearch = runCatching {
                    (getLlmService()?.getBackend(currentBackendId) as? LlmInferenceService.WebSearchBackend)
                        ?.canSearchWeb()
                }.getOrNull() == true
                // Offered only where it can succeed; elsewhere every search is a wasted round trip.
                val toolDefinitions =
                    PromptToolCatalog.definitions(tools, RESPOND_TOOL, sharedPromptConfig.config())
                        .filter { canSearch || it.name != WebAccess.WEB_SEARCH_TOOL }

                val config = LlmInferenceService.LlmConfig(currentBackendId).apply {
                    // The grammar shapes a local tool call but not its values, so paths get sampled.
                    temperature = backendTemperature() ?: DEFAULT_TEMPERATURE
                    maxTokens = replyTokenCap()
                    systemPrompt = systemPromptFactory.create(toolDefinitions)
                    // Local backend constrains generation to this grammar; cloud ignores it.
                    extraParams = mapOf(EXTRA_PARAM_GRAMMAR to tools.grammar)
                }

                val messageWithContext = buildString {
                    append(userMessage)
                    append(contextFilesPrompt.render(runFiles))
                }
                val history = _history.value.toMutableList()
                history.add(
                    LlmInferenceService.ChatMessage(
                        LlmInferenceService.ChatMessage.Role.USER,
                        messageWithContext
                    )
                )
                // Code to judge, or a question about what is current: search before answering.
                val requiredTool = WebAccess.WEB_SEARCH_TOOL.takeIf { search ->
                    toolDefinitions.any { it.name == search } &&
                        VerificationPolicy.requiresWebCheck(userMessage, runFiles.isNotEmpty())
                }
                requiredTool?.let { AgentTrace.stage("VERIFY", "required=$it on the first turn") }
                var firstTurn = true

                try {
                    // Which protocol is live for this run. `native=false` against a backend that
                    // should call natively is the first thing to check when a call reaches the chat
                    // as text instead of running.
                    AgentTrace.stage(
                        "PROTOCOL",
                        "native=${backendPrompts.callsToolsNatively()} tools=${toolDefinitions.size} " +
                            toolDefinitions.joinToString(",") { it.name },
                    )
                    val loopResult = agentLoop.run(
                        history = history,
                        generate = { turns ->
                            withContext(Dispatchers.Main) {
                                setState(AgentState.Processing(str(R.string.msg_generating)))
                            }
                            val turnConfig = requiredTool?.takeIf { firstTurn }
                                ?.let { VerificationPolicy.requiring(config, it) } ?: config
                            firstTurn = false
                            runModelTurn(llmService, turns, turnConfig, toolDefinitions, epoch)
                        },
                        executeTools = { calls -> executeToolCalls(tools, calls) },
                        // Read through the handler, so a path spelled `path` or left to a default
                        // still names the file a re-read of it should count as new again.
                        pathsOf = { call ->
                            tools.router.getHandler(call.name)?.pathsIn(call.args).orEmpty()
                        },
                        changesPaths = { call ->
                            tools.router.getHandler(call.name)?.mutatesProject == true
                        },
                        requiredTool = requiredTool,
                        events = AgentRunReporter(runNotices),
                    )
                    if (loopResult.completed && generationEpoch.get() == epoch) {
                        runCodeReply?.let { draft -> reviewAnswer(llmService, userMessage, draft, history, epoch) }
                    }
                    AgentTrace.endRun(loopResult.reason.name, loopResult.turns)
                    if (loopResult.completed && generationEpoch.get() == epoch) {
                        titleRequestStarted = withContext(Dispatchers.Main) {
                            requestTitleIfUntitled(llmService)
                        }
                    }
                } finally {
                    // On Main, so a Stop, clear or chat switch can't bump the epoch between check and write.
                    withContext(NonCancellable + Dispatchers.Main) {
                        // Persist history only if this run wasn't superseded (epoch bumped).
                        if (generationEpoch.get() == epoch) _history.value = history.toList()
                    }
                    stopStateTimer()
                }

                withContext(Dispatchers.Main) { setState(AgentState.Idle) }
            } catch (ce: CancellationException) {
                AgentTrace.endRun("cancelled")
                stopStateTimer()
                throw ce
            } catch (e: Exception) {
                logError("sendMessage failed", e)
                AgentTrace.endRun("error: ${e.message}")
                stopStateTimer()
                setState(AgentState.Error(str(R.string.state_error, e.message)))
                addSystemMessage(str(R.string.state_error, e.message), MessageStatus.ERROR)
            } finally {
                // Allow re-entry once the coroutine unwinds.
                isGenerating.value = false
                // The run can finish with the chat off screen, where nothing else writes.
                // NonCancellable so a Stop still saves what the run produced before it, and so the
                // activity line is closed rather than left reading as a tool still running.
                withContext(NonCancellable + Dispatchers.Main) {
                    finishActivity()
                    persistState()
                    // No title is coming for a run that failed or stopped; the header shows the prompt.
                    if (!titleRequestStarted) titleSessionId?.let(::settleTitle)
                }
            }
        }
    }

    /**
     * Checks [draft] in a second request and, when that returns a corrected answer, shows it in
     * the draft's bubble and keeps it in [history] in the draft's place. Every other outcome — a
     * failure, a timeout, a reply cut off before its end marker — leaves the draft as it was.
     *
     * Skipped when the run changed the project, since that answer reports work already done, and
     * on the on-device backend, where writing the answer a second time takes minutes.
     *
     * @param llmService the inference service the run used.
     * @param request what the user asked.
     * @param draft the run's last reply holding code.
     * @param history the run's transcript, updated in place.
     * @param epoch the run's epoch; a Stop or a newer message makes the result stale.
     */
    private suspend fun reviewAnswer(
        llmService: LlmInferenceService,
        request: String,
        draft: CodeReply,
        history: MutableList<LlmInferenceService.ChatMessage>,
        epoch: Int,
    ) {
        if (runChangedProject || currentBackendId == AiBackend.LOCAL_ID) return
        withContext(Dispatchers.Main) { setState(AgentState.Processing(str(R.string.msg_reviewing))) }
        val evidence = withContext(Dispatchers.Main) { runToolLog.joinToString("\n\n") }
        val started = System.currentTimeMillis()
        val response = try {
            val prompts = sharedPromptConfig.config()
            val config = LlmInferenceService.LlmConfig(currentBackendId).apply {
                temperature = AnswerReview.TEMPERATURE
                maxTokens = replyTokenCap()
                systemPrompt = AnswerReview.systemPrompt(prompts, SessionContext.current().currentTime)
            }
            withTimeoutOrNull(AnswerReview.TIMEOUT_MS) {
                llmService.generateCompletion(
                    AnswerReview.prompt(prompts, request, evidence, draft.displayText),
                    config,
                ).await()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            logWarn("answer review failed", e)
            return
        }
        val ms = System.currentTimeMillis() - started
        if (response == null || !response.success) {
            AgentTrace.stage("REVIEW", "kept draft ms=$ms (${response?.error ?: "timed out"})")
            return
        }
        val corrected = AnswerReview.corrected(response.text.orEmpty(), draft.displayText)
        AgentTrace.stage("REVIEW", "changed=${corrected != null} ms=$ms chars=${corrected?.length ?: 0}")
        if (corrected == null || generationEpoch.get() != epoch) return
        // The draft's turn, so a follow-up question builds on the answer the user was shown.
        val turn = history.indexOfLast {
            it.role == LlmInferenceService.ChatMessage.Role.ASSISTANT && it.content == draft.historyText
        }
        if (turn >= 0) {
            history[turn] = LlmInferenceService.ChatMessage(LlmInferenceService.ChatMessage.Role.ASSISTANT, corrected)
        }
        withContext(Dispatchers.Main) {
            val shown = _messages.value.firstOrNull { it.id == draft.messageId } ?: return@withContext
            val updated = shown.copy(text = corrected, historyText = null)
            _messages.value = _messages.value.map { if (it.id == draft.messageId) updated else it }
            syncMessageToSession(updated)
        }
    }

    /**
     * Asks the selected backend to name the current chat, once, after its first completed reply.
     * Leaves chats the user named, or that already have a title, alone. Main-thread only; the
     * request itself runs on IO as [titleRequest], which the next run waits out before generating.
     *
     * @param llmService the inference service the run just used.
     * @return whether a request was started; it then settles [titlePending] itself when it ends.
     */
    internal fun requestTitleIfUntitled(llmService: LlmInferenceService): Boolean {
        val session = currentSessionOrNull() ?: return false
        if (!needsTitle(session)) return false
        val userText = session.messages.firstOrNull { it.sender == Sender.USER }?.text ?: return false
        val replyText = session.messages.lastOrNull { it.sender == Sender.AGENT && it.text.isNotBlank() }
            ?.text ?: return false
        titleRequested.add(session.id)
        val sessionId = session.id
        // Lazy, so titleRequest is set before the finally below can compare against it.
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            try {
                generateTitle(llmService, sessionId, userText, replyText)
            } finally {
                // A request clearMessages() dropped must not end the placeholder of the run after it.
                withContext(NonCancellable + Dispatchers.Main) {
                    if (titleRequest?.job === self) settleTitle(sessionId)
                }
            }
        }
        titleRequest = TitleRequest(sessionId, job)
        job.start()
        return true
    }

    /**
     * Holds a new prompt until a title still being written has finished, so the two never generate
     * at once. Bounded by the title's own [ChatTitle.TIMEOUT_MS]; the title job settles itself.
     */
    internal suspend fun awaitTitleRequest() {
        val request = titleRequest?.takeIf { it.job.isActive } ?: return
        AgentTrace.stage("TITLE", "session=${request.sessionId} holding a queued prompt")
        request.job.join()
    }

    /** Whether [session] is still to be titled: not named by the user, untitled, and not yet asked. */
    private fun needsTitle(session: ChatSession): Boolean =
        session.name == null && session.generatedTitle == null && session.id !in titleRequested

    /** Ends [sessionId]'s loading title; it now shows its generated title or its clamped prompt. */
    private fun settleTitle(sessionId: String) {
        _titlePending.value = _titlePending.value - sessionId
    }

    /**
     * Asks for a title and stores it. Every way out short of a title leaves the chat on its prompt,
     * so failures are logged, not surfaced: nothing about the conversation itself went wrong.
     */
    private suspend fun generateTitle(
        llmService: LlmInferenceService,
        sessionId: String,
        userText: String,
        replyText: String,
    ) {
        val response = try {
            // Inside the try: a config that failed to load costs the title, not the chat.
            val prompts = sharedPromptConfig.config()
            val config = LlmInferenceService.LlmConfig(currentBackendId).apply {
                temperature = ChatTitle.TEMPERATURE
                maxTokens = ChatTitle.MAX_TOKENS
                systemPrompt = ChatTitle.systemPrompt(prompts)
            }
            withTimeoutOrNull(ChatTitle.TIMEOUT_MS) {
                llmService.generateCompletion(ChatTitle.prompt(prompts, userText, replyText), config).await()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            logWarn("title request failed for session $sessionId", e)
            return
        }
        if (response == null) {
            // The timeout already cancelled this future; cancelGeneration() would hit other plugins'.
            logWarn("title request timed out for session $sessionId")
            return
        }
        if (!response.success) {
            logWarn("title request refused for session $sessionId: ${response.error}")
            return
        }
        val title = ChatTitle.sanitize(response.text.orEmpty()) ?: return
        withContext(Dispatchers.Main) { applyGeneratedTitle(sessionId, title) }
    }

    /**
     * Stores [title] on the session, unless the user renamed it or it got a title meanwhile.
     *
     * @param sessionId the session the title was written for; it may have been deleted since.
     * @param title the cleaned title.
     */
    private fun applyGeneratedTitle(sessionId: String, title: String) {
        val session = _sessions.value.firstOrNull { it.id == sessionId } ?: return
        if (session.name != null || session.generatedTitle != null) return
        _sessions.value = _sessions.value.map {
            if (it.id == sessionId) it.copy(generatedTitle = title) else it
        }
        AgentTrace.stage("TITLE", "session=$sessionId chars=${title.length}")
        persistState()
    }

    /**
     * Re-runs the last prompt, dropping the failed run's turns from [history] first: a denied tool
     * leaves "FAILED: User denied permission" there, and retrying behind it had the model answer
     * from that line rather than ask for the tool again, so the dialog never reappeared.
     */
    fun retryLastRun() {
        val prompt = lastRunPrompt
        if (prompt == null) {
            logWarn("retryLastRun: nothing has been sent in this conversation yet")
            return
        }
        // Checked before the rewind, which would otherwise pull the transcript out of a live run.
        if (isGenerating.value) {
            logDebug("retryLastRun: a run is already in flight")
            return
        }
        _history.value = _history.value.take(historySizeBeforeLastRun)
        sendMessage(prompt)
    }

    /** How [editPrompt] ended, which decides whether the composer leaves edit mode. */
    enum class EditResult {
        /** The chat was rewound or forked and the edited prompt is running. */
        STARTED,
        /** A run is in flight or an approval is open; the user has to stop it first. */
        BUSY,
        /** The message is no longer a prompt on screen. */
        NOT_EDITABLE,
        /** The edited text was blank or the backend is not ready; nothing was changed. */
        REFUSED,
    }

    /**
     * Runs the agent on [newText] in place of the prompt [messageId]. The newest prompt is replaced
     * outright, as if it had never been sent; an older one forks, keeping the original as a version
     * (see [ChatBranches]). Either way the model sees only the messages above it. File edits a
     * run already made are not undone.
     *
     * @param messageId a user prompt on screen.
     * @param newText the edited prompt, sent with the context files attached right now.
     * @return what happened; only [EditResult.STARTED] changed anything.
     */
    fun editPrompt(messageId: String, newText: String): EditResult {
        // Before the pre-flight, whose setup notice would otherwise land inside a live run.
        if (isBusy()) return EditResult.BUSY
        if (_messages.value.none { it.id == messageId && it.sender == Sender.USER }) {
            return EditResult.NOT_EDITABLE
        }
        // Read before the cut: the composer leaves edit mode on it, handing back the draft's files.
        val runFiles = contextFiles
        // Checked before the rewind, so a refused send changes nothing.
        val llmService = passPreflight(newText) ?: return EditResult.REFUSED
        // Claimed before the cut too, so a chat is never cut for a run that then cannot start.
        if (!isGenerating.compareAndSet(false, true)) return EditResult.BUSY
        val cut = if (editForks(messageId)) forkAt(messageId) else rewind(messageId)
        if (!cut) {
            isGenerating.value = false
            return EditResult.NOT_EDITABLE
        }
        launchRun(llmService, newText, runFiles)
        return EditResult.STARTED
    }

    /**
     * Whether editing the prompt [messageId] forks the chat, keeping the original as a version,
     * rather than replacing it: true for every prompt but the newest.
     */
    fun editForks(messageId: String): Boolean = !PromptEdit.isLatestPrompt(_messages.value, messageId)

    /**
     * Shows the version of prompt [messageId] [step] places away, and gives the model that branch
     * alone: nothing said in another version reaches it. Main only.
     *
     * @param step -1 for the older version, 1 for the newer one.
     * @return false, changing nothing, while busy or past either end of the versions.
     */
    fun switchPromptVersion(messageId: String, step: Int): Boolean {
        if (isBusy()) return false
        val session = currentSessionOrNull() ?: return false
        val targetId = ChatBranches.versionId(session, messageId, step) ?: return false
        val switched = ChatBranches.switchTo(session, messageId, targetId) ?: return false
        adoptBranch(switched, "VERSION", "step=$step")
        return true
    }

    /**
     * Forks the chat at the older prompt [messageId]: it and everything after it become a stored
     * version, and the messages above it are all the next run sees. Main only; the caller checks
     * that no run is in flight.
     *
     * @return false, changing nothing, when [messageId] is not a prompt on screen.
     */
    private fun forkAt(messageId: String): Boolean {
        val session = currentSessionOrNull() ?: return false
        val forked = ChatBranches.fork(session, messageId) ?: return false
        adoptBranch(forked, "FORK", "keptMessages=${forked.messages.size}")
        return true
    }

    /**
     * Puts [session], a branch change of the current chat, on screen and rebuilds the model's
     * history from its messages alone, then writes it at once.
     */
    private fun adoptBranch(session: ChatSession, stage: String, detail: String) {
        _sessions.value = _sessions.value.map { if (it.id == session.id) session else it }
        showTranscript(session.messages)
        traceHistoryCut(stage, detail)
        persistState()
    }

    /** Traces a change to the model's history, with how much of it was kept and its last turn. */
    private fun traceHistoryCut(stage: String, detail: String) {
        AgentTrace.stage(
            stage,
            "$detail historyKept=${_history.value.size}",
            _history.value.lastOrNull()?.let { "${it.role}: ${AgentTrace.preview(it.content)}" },
        )
    }

    /**
     * Cuts the conversation back to just before [messageId]: transcript, session and history all
     * lose that prompt and every turn after it, and the cut session is written at once. Main only.
     *
     * @param messageId the newest user prompt.
     * @return false, changing nothing, while busy or when [messageId] is not the newest prompt.
     */
    internal fun rewindTo(messageId: String): Boolean = !isBusy() && rewind(messageId)

    /** [rewindTo] for a caller that has already checked, and claimed, that no run is in flight. */
    private fun rewind(messageId: String): Boolean {
        val kept = PromptEdit.messagesBefore(_messages.value, messageId) ?: return false
        val dropped = _messages.value.size - kept.size
        _messages.value = kept
        replaceCurrentSessionMessages(kept)
        // The live history carries the tool turns a rebuild cannot; use it when it is this prompt's.
        _history.value = if (messageId == lastRunUserMessageId) {
            _history.value.take(historySizeBeforeLastRun)
        } else {
            rebuildHistoryFrom(kept)
        }
        forgetRetryPoint()
        // A title written from the discarded first exchange would name a conversation that is gone.
        if (kept.none { it.sender == Sender.USER }) resetGeneratedTitle()
        traceHistoryCut("EDIT", "droppedMessages=$dropped")
        // Written now rather than debounced, like a clear: the discarded branch must not come back.
        persistState()
        return true
    }

    /**
     * Runs one streaming model turn: creates an agent bubble, streams tokens into it and suspends
     * until completion, throwing on backend error. Sends [turns] structurally to the local backend;
     * Gemini's transport carries one string, so it keeps the flattened transcript.
     * @param llmService the inference service.
     * @param turns the conversation so far; the last entry is the current user turn.
     * @param config the generation config.
     * @param toolDefinitions the tools to offer a natively-calling backend; see [BackendPrompts.callsToolsNatively].
     * @param epoch this run's epoch, for staleness checks against Stop/newer sends.
     * @return the turn: the reply with any native calls rendered into it for extraction, beside the
     *   text the model itself wrote, which is what the transcript keeps.
     */
    private suspend fun runModelTurn(
        llmService: LlmInferenceService,
        turns: List<LlmInferenceService.ChatMessage>,
        config: LlmInferenceService.LlmConfig,
        toolDefinitions: List<LlmInferenceService.ToolDefinition>,
        epoch: Int
    ): AgentLoop.ModelReply {
        val deferred = CompletableDeferred<AgentLoop.ModelReply>()
        val agentMessageId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()
        val responseBuilder = StringBuilder()

        // True once Stop (or a newer message) has superseded this generation.
        fun isStale() = generationEpoch.get() != epoch

        withContext(Dispatchers.Main) {
            val agentMessage = ChatMessage(
                id = agentMessageId,
                text = "",
                sender = Sender.AGENT,
                status = MessageStatus.SENT
            )
            _messages.value = _messages.value + agentMessage
            syncMessageToSession(agentMessage)
        }

        // Rendered into the reply on completion, so a natively-called tool reaches extraction,
        // the transcript badge and the loop's repeat guard by the one path text calls use.
        val nativeCalls = mutableListOf<LlmInferenceService.ToolCallRequest>()

        val streamCallback = object : LlmInferenceService.StreamCallback {
                override fun onToken(token: String) {
                    if (isStale()) return  // Stop pressed — ignore late tokens.
                    responseBuilder.append(token)
                    // Snapshot on the producer thread; only the immutable String crosses to Main.
                    val snapshot = responseBuilder.toString()
                    viewModelScope.launch(Dispatchers.Main) {
                        if (isStale()) return@launch
                        val updated = ChatMessage(
                            id = agentMessageId,
                            text = snapshot,
                            sender = Sender.AGENT,
                            status = MessageStatus.SENT
                        )
                        _messages.value = _messages.value.map { if (it.id == agentMessageId) updated else it }
                        syncMessageToSession(updated)
                    }
                }

                override fun onComplete(response: LlmInferenceService.LlmResponse) {
                    // Null text means a failed response, which arrives through onError instead.
                    val written = response.text.orEmpty()
                    val calls = synchronized(nativeCalls) { nativeCalls.toList() }
                    val text = withNativeCalls(written, calls)
                    // The transcript keeps what the model wrote, never the envelopes below.
                    val reply = AgentLoop.ModelReply(text = text, historyText = written)
                    if (isStale()) {
                        // Already cancelled; the awaiting loop was unblocked by job cancel.
                        deferred.complete(reply)
                        return
                    }
                    val durationMs = System.currentTimeMillis() - startTime
                    // Not from onModelTurn, which fires later and would order the trace wrongly.
                    AgentTrace.stage(
                        "LLM",
                        "chars=${text.length} generateMs=$durationMs",
                        AgentTrace.preview(text),
                    )
                    val toolCalls = ToolCallExtractor.extractToolCalls(text)
                    // Where a mis-escaped generation quietly becomes "the model said nothing".
                    if (toolCalls.isEmpty()) {
                        AgentTrace.detail("PARSE", "calls=0 generateMs=$durationMs (plain reply or unparsable)")
                    } else {
                        AgentTrace.stage(
                            "PARSE",
                            "calls=${toolCalls.size} generateMs=$durationMs",
                            toolCalls.joinToString("; ") { "${it.name}(${AgentTrace.previewArgs(it.args)})" },
                        )
                    }
                    // Per-run flag (set by executeToolCalls), not a session-wide scan.
                    val lastToolFailed = lastToolFailedThisRun

                    if (AgentReplyRenderer.isSilentTurn(toolCalls, RESPOND_TOOL)) {
                        viewModelScope.launch(Dispatchers.Main) {
                            removeMessageFromSession(agentMessageId)
                        }
                        deferred.complete(reply)
                        return
                    }

                    val displayText = AgentReplyRenderer.render(
                        rawText = text,
                        toolCalls = toolCalls,
                        terminalTool = RESPOND_TOOL,
                        lastToolFailed = lastToolFailed,
                        actionFailedText = str(R.string.agent_action_failed),
                        noResponseText = str(R.string.agent_no_response),
                        unparsedReplyText = { str(unparsedReplyMessage(it)) },
                    )
                    if (AnswerReview.holdsCode(displayText)) {
                        runCodeReply = CodeReply(agentMessageId, displayText, reply.historyText)
                    }
                    viewModelScope.launch(Dispatchers.Main) {
                        if (isStale()) return@launch
                        val finalMsg = ChatMessage(
                            id = agentMessageId,
                            text = displayText,
                            sender = Sender.AGENT,
                            status = MessageStatus.COMPLETED,
                            durationMs = durationMs,
                            // Only when it differs, so a turn is not stored twice over.
                            historyText = reply.historyText.takeIf { it != displayText }
                        )
                        _messages.value = _messages.value.map { if (it.id == agentMessageId) finalMsg else it }
                        syncMessageToSession(finalMsg)
                    }
                    // Hand the loop the reply with the envelopes, so extraction/stop logic sees
                    // a native call, and the model's own text for the transcript.
                    deferred.complete(reply)
                }

                override fun onError(error: String) {
                    if (isStale()) {
                        deferred.completeExceptionally(CancellationException("stopped"))
                        return
                    }
                    viewModelScope.launch(Dispatchers.Main) {
                        // Drop the empty/partial bubble; the error surfaces as a SYSTEM message.
                        removeMessageFromSession(agentMessageId)
                    }
                    deferred.completeExceptionally(RuntimeException(error))
                }
            }

        modelTurnInFlight = true
        // After the flag: Stop bumps the epoch before reading it, so one of the two sees the other.
        if (isStale()) {
            modelTurnInFlight = false
            throw CancellationException("stopped")
        }
        try {
            // Every backend takes the structured form: the last turn as the prompt, the rest as
            // history. A backend that reports no native calls simply never calls onToolCall, and
            // its calls arrive in the reply text as TOOL_CALL_SYNTAX instead.
            llmService.generateStreamingWithTools(
                turns.lastOrNull()?.content.orEmpty(),
                turns.dropLast(1),
                config,
                toolDefinitions,
                object : LlmInferenceService.ToolStreamCallback {
                    override fun onToken(token: String) = streamCallback.onToken(token)

                    override fun onToolCall(request: LlmInferenceService.ToolCallRequest) {
                        if (isStale()) return
                        // The proof a call came through the provider's API rather than the reply
                        // text: the PARSE line that follows reports the envelope this one becomes.
                        AgentTrace.stage(
                            "NATIVE",
                            "tool=${request.name} args=${request.args.orEmpty().keys.joinToString(",")}",
                            AgentTrace.previewArgs(request.args.orEmpty()),
                        )
                        synchronized(nativeCalls) { nativeCalls.add(request) }
                    }

                    // The reported calls are read back in streamCallback.onComplete, which
                    // renders them into the reply it hands the loop.
                    override fun onComplete(response: LlmInferenceService.LlmResponse) =
                        streamCallback.onComplete(response)

                    override fun onError(error: String) = streamCallback.onError(error)
                }
            )
        } catch (e: Exception) {
            // A synchronous throw fires no callback; complete deferred so await() doesn't hang.
            logError("generateStreaming threw synchronously", e)
            viewModelScope.launch(Dispatchers.Main) {
                removeMessageFromSession(agentMessageId)
            }
            if (!deferred.isCompleted) deferred.completeExceptionally(e)
        }

        return try {
            deferred.await()
        } finally {
            modelTurnInFlight = false
        }
    }

    /**
     * [written] with [calls] appended as canonical `<tool_call>` envelopes.
     *
     * The model never writes these: [ToolCallExtractor.renderEnvelope] encodes them from arguments
     * the provider already parsed, so the mis-escaping that loses a text-mode call cannot lose one.
     * For this turn only — see [AgentLoop.ModelReply] for why the transcript keeps [written].
     *
     * @param written the reply text as the model produced it.
     * @param calls the native calls reported during this turn; none leaves [written] unchanged.
     * @return the text carrying the calls in the form extraction reads back.
     */
    private fun withNativeCalls(
        written: String,
        calls: List<LlmInferenceService.ToolCallRequest>,
    ): String {
        if (calls.isEmpty()) return written
        val envelopes = calls.joinToString("\n") {
            ToolCallExtractor.renderEnvelope(it.name, it.args.orEmpty())
        }
        val prose = written.trim()
        return if (prose.isEmpty()) envelopes else prose + "\n" + envelopes
    }

    /**
     * Puts [call] on the run's activity line, creating that row on the run's first tool and
     * rewriting it in place from then on. One row per run is the whole point: the badge-per-call
     * and result-per-call rows it replaces buried the answer under twenty messages.
     *
     * @param call the call that is starting.
     */
    private fun showActivity(call: ToolCall) {
        runToolNames += call.name
        val subject = AgentActivity.subjectOf(call)
        putActivity(
            text = if (subject == null) {
                str(R.string.agent_activity_running_plain, call.name)
            } else {
                str(R.string.agent_activity_running, call.name, subject)
            },
            status = MessageStatus.SENT,
        )
    }

    /**
     * Closes the run's activity line: a run that used tools leaves a one-line summary of which
     * ones, a run that used none leaves nothing at all. Idempotent, since every exit from a run —
     * completion, Stop, error — passes through here.
     */
    private fun finishActivity() {
        val id = activityMessageId ?: return
        val names = AgentActivity.distinctNames(runToolNames)
        // Gone already means the chat was cleared or the session switched under the run.
        val stillShown = _messages.value.any { it.id == id }
        if (stillShown && names.isNotEmpty()) {
            putActivity(
                text = plural(
                    R.plurals.agent_activity_done,
                    runToolNames.size,
                    runToolNames.size,
                    names.joinToString(str(R.string.agent_activity_separator)),
                ),
                status = MessageStatus.COMPLETED,
            )
        } else {
            removeMessageFromSession(id)
        }
        activityMessageId = null
        runToolNames.clear()
        runToolLog.clear()
    }

    /**
     * Writes [text] to the activity row, appending it if this run has not shown one yet. Also
     * appends when the row it held has gone — clearing the chat mid-run drops it, and a silently
     * discarded update would leave the rest of the run with no progress at all.
     *
     * @param text the line to show.
     * @param status the row's status; [MessageStatus.COMPLETED] closes it.
     */
    private fun putActivity(text: String, status: MessageStatus) {
        val id = activityMessageId
        val existing = id != null && _messages.value.any { it.id == id }
        val message = ChatMessage(
            id = if (existing) id!! else UUID.randomUUID().toString(),
            text = text,
            sender = Sender.TOOL,
            status = status,
            // Any non-null value: a null one is what the adapter animates generating-dots on.
            durationMs = 0L,
            toolLog = runToolLog.takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
        )
        activityMessageId = message.id
        _messages.value = if (existing) {
            _messages.value.map { if (it.id == message.id) message else it }
        } else {
            _messages.value + message
        }
        // Rewrites the row in place by id, as it does for every other message.
        syncMessageToSession(message)
    }

    /**
     * Resolves a quantity string, empty when the plugin context has gone; see [str].
     * @param resId the plurals resource.
     * @param quantity the count the wording is chosen by.
     * @param args the format arguments.
     * @return the formatted line.
     */
    private fun plural(resId: Int, quantity: Int, vararg args: Any?): String =
        getContext()?.androidContext?.resources?.getQuantityString(resId, quantity, *args).orEmpty()

    /**
     * Logs to the IDE's plugin log, which is where a plugin's output is expected to land.
     * @param message the line to log.
     */
    private fun logDebug(message: String) {
        getContext()?.logger?.debug("$TAG: $message")
    }

    /**
     * Logs a condition the chat recovered from; see [logDebug].
     * @param message the line to log.
     * @param error the cause, when there was one.
     */
    private fun logWarn(message: String, error: Throwable? = null) {
        val logger = getContext()?.logger ?: return
        if (error == null) logger.warn("$TAG: $message") else logger.warn("$TAG: $message", error)
    }

    /**
     * Logs a failure; see [logDebug].
     * @param message the line to log.
     * @param error the cause.
     */
    private fun logError(message: String, error: Throwable) {
        getContext()?.logger?.error("$TAG: $message", error)
    }

    /**
     * Resolves a UI string resource via the plugin's Android context.
     * @param resId the string resource id.
     * @param args format arguments.
     * @return the resolved string, or empty if the context is gone.
     */
    private fun str(resId: Int, vararg args: Any?): String =
        getContext()?.androidContext?.getString(resId, *args).orEmpty()

    /**
     * Appends a SYSTEM message to the chat (on the main thread).
     * @param text the message text.
     * @param status the message status.
     */
    private suspend fun addSystemMessage(text: String, status: MessageStatus) {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = text,
            sender = Sender.SYSTEM,
            status = status
        )
        withContext(Dispatchers.Main) {
            _messages.value = _messages.value + message
            syncMessageToSession(message)
        }
    }

    /** Wording for the stops [AgentRunReporter] reports; the reporter holds no resource ids. */
    private val runNotices = object : AgentRunReporter.Notices {

        override suspend fun stepBudgetExhausted(turns: Int) =
            addSystemMessage(str(R.string.agent_max_steps_reached, turns), MessageStatus.SENT)

        override suspend fun repeatedCalls() =
            addSystemMessage(str(R.string.agent_repeated_calls), MessageStatus.SENT)

        override suspend fun noProgress() =
            addSystemMessage(str(R.string.agent_no_progress), MessageStatus.SENT)
    }

    /** Whether the user message [messageId] is shown unfolded. Main only. */
    fun isUserMessageExpanded(messageId: String): Boolean = messageId in expandedUserMessageIds

    /**
     * Unfolds the user message [messageId], or folds it back. Main only.
     *
     * @return whether it is unfolded now.
     */
    fun toggleUserMessageExpanded(messageId: String): Boolean {
        if (expandedUserMessageIds.add(messageId)) return true
        expandedUserMessageIds.remove(messageId)
        return false
    }

    /**
     * Clear all messages from the conversation.
     */
    fun clearMessages() {
        // Clear Chat must also stop any in-flight run, not just wipe the list.
        cancelActiveRun("clear chat")
        _messages.value = emptyList()
        _history.value = emptyList()
        // Without this the session keeps its messages and the cleared chat returns on the next sync.
        replaceCurrentSessionMessages(emptyList())
        // Its other versions go too: a cleared chat has no prompt left for them to belong to.
        _currentSessionId.value?.let { sessionId ->
            _sessions.value = _sessions.value.map {
                if (it.id == sessionId) it.copy(otherBranches = null, selectedBranches = null) else it
            }
        }
        // The title described the conversation just cleared; the next first reply writes a new one.
        resetGeneratedTitle()
        forgetRetryPoint()
        setState(AgentState.Idle)
        // Written now rather than debounced: a clear is deliberate and must survive a force-stop.
        persistState()
    }

    /**
     * Drops the current chat's generated title, and any request still writing one, so the next
     * first reply names it afresh. A title the user typed is kept. Main only.
     */
    private fun resetGeneratedTitle() {
        val sessionId = _currentSessionId.value ?: return
        // Its reply would name the discarded chat, and its settle would end the next run's placeholder.
        // Another chat's request is left to finish and settle its own placeholder.
        titleRequest?.takeIf { it.sessionId == sessionId }?.let {
            it.job.cancel()
            titleRequest = null
        }
        titleRequested.remove(sessionId)
        settleTitle(sessionId)
        _sessions.value = _sessions.value.map {
            if (it.id == sessionId) it.copy(generatedTitle = null) else it
        }
    }

    /**
     * Stops any in-flight run, so the transcript it is streaming into cannot be swapped out from
     * under it: without the epoch bump the stale run's callbacks keep writing, and its reply lands
     * in the conversation that replaced the one it was asked for.
     */
    private fun cancelActiveRun(reason: String) {
        AgentTrace.stage("CANCEL", "reason=$reason wasRunning=${_agentState.value.isRunning}")
        generationEpoch.incrementAndGet()
        approvalManager.cancelPendingApproval()
        cancelGenerationJob()
        stopStateTimer()
    }

    /**
     * Cancels the run's job and, only while one of its model turns is generating, the backend
     * stream too. A prompt still queued behind a title leaves that title running.
     */
    private fun cancelGenerationJob() {
        // Read before the cancel: the turn's finally may clear it as soon as the job is cancelled.
        val turnInFlight = modelTurnInFlight
        generationJob?.cancel()
        generationJob = null
        if (turnInFlight) getLlmService()?.cancelGeneration()
    }

    /**
     * Create a new chat session.
     */
    fun createNewSession() {
        endRunBeforeSessionChange("new chat")
        // Unconditional: isRunning above skips Thinking and Error, which a fresh chat must not open in.
        setState(AgentState.Idle)
        val newSession = ChatSession(projectKey = activeProjectKey)
        _sessions.value = _sessions.value + newSession
        adoptSession(newSession)
    }

    /**
     * Switch to an existing chat session.
     */
    fun switchToSession(sessionId: String) {
        val session = _sessions.value.firstOrNull { it.id == sessionId }
        if (session == null) {
            logWarn("switchToSession: no session $sessionId")
            return
        }
        if (sessionId == _currentSessionId.value) return
        endRunBeforeSessionChange("session switched")
        adoptSession(session)
    }

    /**
     * Makes [session] the live conversation: what the screen shows, what the model is given and
     * what the next message is appended to.
     *
     * @param session the conversation to make current; it must already be in [_sessions].
     */
    private fun adoptSession(session: ChatSession) {
        _currentSessionId.value = session.id
        showTranscript(session.messages)
        // Also dropped here: the readiness edge may have fired while another chat was current.
        if (_backendStatus.value.isAvailable) clearBackendSetupNotices()
        persistState()
    }

    /**
     * Puts [messages] on screen and rebuilds the model's history from them. The one place those
     * move together: set side by side at each call site, ADFA-5584 (screen restored, model handed
     * an empty history) was fixed in one of them and not the others.
     */
    private fun showTranscript(messages: List<ChatMessage>) {
        // Immutable snapshot, so a later mutation cannot reach collectors behind the StateFlow.
        _messages.value = messages.toList()
        _history.value = rebuildHistoryFrom(messages)
        // The rewind point names a run this transcript does not have, and would truncate it.
        forgetRetryPoint()
    }

    /**
     * Ends a run in flight before the conversation under it is replaced.
     *
     * Every write a run makes — its streamed bubble, its tool notices, the finalization Stop
     * performs — lands on whichever session is current *at the time of the write*, so a run left
     * alive across a switch finishes by appending its remaining turns to the conversation the user
     * moved to. Called before [_currentSessionId] moves, so the cancellation itself still settles
     * into the transcript the run belongs to.
     *
     * A no-op when nothing is running, so restoring stored sessions at startup stays silent.
     *
     * @param reason what caused the change, for the CANCEL trace line.
     */
    private fun endRunBeforeSessionChange(reason: String) {
        if (!_agentState.value.isRunning) return
        stopProcessing(reason = reason)
    }

    /**
     * Gives a session the name the user typed for it, or takes that name away again.
     *
     * @param sessionId the session to rename; an unknown id is a no-op.
     * @param name the new name. Blank clears it, so the session falls back to its generated title,
     *   or its first user turn — that is the only way back from a rename the user regrets.
     */
    fun renameSession(sessionId: String, name: String?) {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        val session = _sessions.value.firstOrNull { it.id == sessionId }
        if (session == null) {
            logWarn("renameSession: no session $sessionId")
            return
        }
        if (session.name == trimmed) return
        _sessions.value = _sessions.value.map {
            if (it.id == sessionId) it.copy(name = trimmed) else it
        }
        // A user name outranks the title being written, so its placeholder must not outlast it.
        if (trimmed != null) settleTitle(sessionId)
        // Written now rather than debounced: a rename is deliberate and may be the last thing the
        // user does before leaving the tab, where no streamed token follows to flush it.
        persistState()
    }

    /**
     * How [exportSession] ended, which decides what the user is told. [TOO_LARGE_TO_IMPORT] is
     * still written: the file keeps the chat, it just cannot come back through [importTranscript].
     */
    enum class ExportResult { EXPORTED, TOO_LARGE_TO_IMPORT, MISSING, FAILED }

    /** How [importTranscript] ended, which decides what the user is told. */
    enum class ImportResult { IMPORTED, TOO_LARGE, FAILED }

    /**
     * Writes a chat to the file the picker returned. Rendered from the chat as it is now, not as it
     * was when Export was tapped, so a reply that finished meanwhile is in it.
     *
     * @param sessionId the chat to export.
     * @param resolver opens [uri]; held only for this call.
     * @param uri where the picker said to write it.
     */
    suspend fun exportSession(sessionId: String, resolver: ContentResolver, uri: Uri): ExportResult {
        // Snapshotted here, on the caller's thread; rendering it waits for the IO dispatcher.
        val session = _sessions.value.firstOrNull { it.id == sessionId } ?: return ExportResult.MISSING
        val result = withContext(Dispatchers.IO) {
            try {
                // "wt" truncates, so overwriting a longer file leaves none of its tail behind.
                val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("no stream")
                val written = stream.use { ChatTranscript.write(session, it) }
                if (written > ChatTranscript.MAX_IMPORT_BYTES) {
                    ExportResult.TOO_LARGE_TO_IMPORT
                } else {
                    ExportResult.EXPORTED
                }
            } catch (e: Exception) {
                // Any provider can fail in its own way; none of them are suspension points.
                logWarn("chat export failed", e)
                ExportResult.FAILED
            }
        }
        AgentTrace.stage("UI", "chat exported id=$sessionId result=$result")
        return result
    }

    /**
     * Reads the file the picker returned and, only if it is a transcript this plugin wrote, adds it
     * as a new chat and opens it. Anything else adds nothing.
     *
     * @param resolver opens [uri]; held only for this call.
     * @param uri the file the user picked.
     * @return [ImportResult.IMPORTED] only when the chat was added.
     */
    suspend fun importTranscript(resolver: ContentResolver, uri: Uri): ImportResult {
        val projectKey = activeProjectKey
        val session = try {
            withContext(Dispatchers.IO) {
                val stream = resolver.openInputStream(uri) ?: throw IOException("no stream")
                ChatTranscript.parse(stream.use { ChatTranscript.read(it) }, projectKey)
            }
        } catch (e: ChatTranscript.TranscriptTooLargeException) {
            logWarn("chat import refused", e)
            return ImportResult.TOO_LARGE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Any provider can fail in its own way.
            logWarn("chat import failed", e)
            return ImportResult.FAILED
        }
        importSession(session)
        return ImportResult.IMPORTED
    }

    /**
     * Adds a chat read from an exported transcript and opens it, the way [createNewSession] opens
     * an empty one. Appended, never merged: no existing chat is touched.
     *
     * @param imported the chat [ChatTranscript.parse] built; its ids are already fresh.
     */
    fun importSession(imported: ChatSession) {
        endRunBeforeSessionChange("chat imported")
        setState(AgentState.Idle)
        // Bound to the open project, or the next restore drops it as another project's.
        val session = imported.copy(projectKey = activeProjectKey)
        AgentTrace.stage("UI", "chat imported id=${session.id} messages=${session.messages.size}")
        _sessions.value = _sessions.value + session
        adoptSession(session)
    }

    /**
     * Drops the rewind point [retryLastRun] uses, for a conversation that was cleared or swapped:
     * its prompt belongs to a transcript this one no longer has.
     */
    private fun forgetRetryPoint() {
        lastRunPrompt = null
        lastRunUserMessageId = null
        historySizeBeforeLastRun = 0
    }

    /**
     * Rebuilds the LLM context from a restored transcript, so the model remembers what the user is
     * looking at. Lossy on purpose: attached file bodies and tool scaffolding never reached the
     * saved messages, so they are not reconstructed here.
     *
     * @param messages the session's transcript, oldest first.
     * @return the eligible messages that fit both budgets, oldest first; the newest one is
     *   always kept, truncated to the character budget when it alone exceeds it.
     */
    private fun rebuildHistoryFrom(
        messages: List<ChatMessage>
    ): List<LlmInferenceService.ChatMessage> {
        // A new chat restores nothing; without this every one of them traces an empty restore.
        if (messages.isEmpty()) return emptyList()
        val eligible = messages.filter {
            // SYSTEM notices and TOOL output are the scaffolding this rebuild exists to leave out.
            (it.sender == Sender.USER || it.sender == Sender.AGENT) &&
                // Only a finished agent turn carries a duration: null is a bubble process death
                // cut mid sentence, zero the marker Stop leaves; neither was finished saying.
                (it.sender == Sender.USER || (it.durationMs ?: 0L) > 0L) &&
                // Blank means the model wrote nothing, which AgentLoop skips live too, so the
                // bubble must not stand in for it: that bubble is "the action failed".
                (it.historyText ?: it.text).isNotBlank()
        }
        // Newest-first, so both budgets are spent on the turns nearest the next message.
        val kept = ArrayDeque<LlmInferenceService.ChatMessage>()
        var chars = 0
        for (message in eligible.asReversed()) {
            // What the model wrote, not the bubble: a turn whose tool call failed renders as
            // "the action failed" in the IDE's language, a sentence the model never produced.
            var text = message.historyText ?: message.text
            // The newest eligible turn is never dropped — breaking on it would restore nothing at
            // all — but it is truncated, since nothing upstream bounds what a user can paste.
            if (kept.isNotEmpty() && (kept.size >= MAX_RESTORED_HISTORY ||
                    chars + text.length > MAX_RESTORED_HISTORY_CHARS)
            ) break
            if (text.length > MAX_RESTORED_HISTORY_CHARS) {
                text = text.takeLast(MAX_RESTORED_HISTORY_CHARS)
            }
            chars += text.length
            val role = if (message.sender == Sender.USER) {
                LlmInferenceService.ChatMessage.Role.USER
            } else {
                LlmInferenceService.ChatMessage.Role.ASSISTANT
            }
            // Starting on an ASSISTANT turn is left alone: every backend here flattens the array,
            // and trimming back to a USER turn would drop one the user can still see.
            kept.addFirst(LlmInferenceService.ChatMessage(role, text))
        }
        AgentTrace.detail(
            "RESTORE",
            "retained=${kept.size} discarded=${messages.size - eligible.size} " +
                "capped=${eligible.size - kept.size}"
        )
        return kept.toList()
    }

    /**
     * Deletes a chat session, leaving the project with a conversation to carry on in either way.
     *
     * Deleting the last one does not leave the project with none: an empty session takes its
     * place, since the transcript on screen, every message the user sends next and the whole
     * persistence path all address the *current* session, and there being none would quietly
     * discard all of it.
     *
     * @param sessionId the session to delete; an unknown id still leaves the invariant above true.
     */
    fun deleteSession(sessionId: String) = deleteSessions(setOf(sessionId))

    /**
     * Deletes several chat sessions at once, under the same guarantee as [deleteSession]: the
     * project is never left without a conversation to carry on in.
     *
     * One pass, not a [deleteSession] per id: that would write the history once per session, and
     * would pick a successor from a list still holding the rest of the doomed ones — landing the
     * user in a conversation that is about to go.
     *
     * @param sessionIds the sessions to delete. An empty set is a no-op, and an id naming nothing
     *   is ignored rather than treated as a deletion.
     */
    fun deleteSessions(sessionIds: Set<String>) {
        if (sessionIds.isEmpty()) return
        val deletingCurrent = _currentSessionId.value in sessionIds
        // Before the list moves, so a run in flight finalizes into the session being deleted rather
        // than appending its last turns to whichever one replaces it.
        if (deletingCurrent) endRunBeforeSessionChange("sessions deleted")
        // Resolved against the list as it still stands, which is what makes "the row next to this
        // one" answerable at all.
        val successor = if (deletingCurrent) successorTo(sessionIds) else null
        _sessions.value = _sessions.value.filterNot { it.id in sessionIds }
        when {
            // Persists on its own, and binds the replacement to this project.
            _sessions.value.isEmpty() -> createNewSession()
            // Left alone, the deleted conversations' context stays live under the surviving one.
            successor != null -> adoptSession(successor)
            else -> persistState()
        }
    }

    /**
     * Which conversation to land on when the live one is among those being deleted: the nearest
     * survivor to it in the order the history list shows, [newestFirst] — the next older, or the
     * next newer when everything below it is going too.
     *
     * The neighbour rather than simply the newest, so deleting leaves the user where they were in
     * the list instead of at the top of it.
     *
     * @param deletedIds the sessions about to go, all still present in [_sessions].
     * @return the session to make current, or null when none of them survives.
     */
    private fun successorTo(deletedIds: Set<String>): ChatSession? {
        val ordered = _sessions.value.newestFirst()
        val survives = { session: ChatSession -> session.id !in deletedIds }
        val index = ordered.indexOfFirst { it.id == _currentSessionId.value }
        if (index < 0) return ordered.firstOrNull(survives)
        return ordered.drop(index + 1).firstOrNull(survives)
            ?: ordered.take(index).lastOrNull(survives)
    }

    /**
     * Drops a delivered [AgentState.Error] back to [AgentState.Idle]. The state now outlives the
     * fragment, so without this every re-attach would raise the same error snackbar again — the
     * transcript already keeps the error as a message.
     */
    fun clearErrorState() {
        if (_agentState.value is AgentState.Error) {
            setState(AgentState.Idle)
        }
    }

    /**
     * Stop any ongoing processing.
     *
     * @param reason who asked, for the trace: a run that ends without a `CANCEL` line ended on its
     *   own, and one that ends with it names the gesture that stopped it.
     */
    fun stopProcessing(reason: String = "unspecified") {
        AgentTrace.stage(
            "CANCEL",
            "reason=$reason wasRunning=${_agentState.value.isRunning} " +
                "state=${_agentState.value.traceLabel}"
        )
        generationEpoch.incrementAndGet()
        setState(AgentState.Cancelling)
        // Cancelling the job alone would strand an open approval dialog with nothing awaiting it.
        approvalManager.cancelPendingApproval()
        cancelGenerationJob()
        stopStateTimer()
        finalizeInProgressMessages()
        setState(AgentState.Idle)
    }

    /**
     * Give any still-streaming agent bubble (status SENT, null `durationMs`) a
     * terminal state so its animated "…" dots stop: drop empty bubbles, mark
     * partial ones [MessageStatus.COMPLETED]. Called on Stop.
     */
    private fun finalizeInProgressMessages() {
        val unfinished = { msg: ChatMessage -> msg.sender == Sender.AGENT && msg.durationMs == null }
        // Through removeMessages, so a stored version that follows a dropped bubble is relinked.
        removeMessages { unfinished(it) && it.text.isBlank() }
        val finalized = _messages.value.map { msg ->
            if (unfinished(msg)) msg.copy(status = MessageStatus.COMPLETED, durationMs = 0L) else msg
        }
        _messages.value = finalized

        // Mirror the change into the current session's transcript.
        replaceCurrentSessionMessages(finalized)
    }

    /**
     * Start a timer that updates the executing state with elapsed time.
     * Updates every 100ms for smooth progress display.
     */
    fun startStateTimer(state: AgentState.Executing) {
        stateUpdateJob?.cancel()
        stateUpdateJob = viewModelScope.launch {
            while (isActive) {
                delay(100)
                val current = _agentState.value
                if (current !is AgentState.Executing) break
                // Straight to the flow, not through setState: ten traced lines a second would
                // bury the run's actual steps.
                _agentState.value = current.copy(
                    elapsedMillis = System.currentTimeMillis() - current.startTime
                )
            }
        }
    }

    /**
     * Stop the state timer.
     */
    fun stopStateTimer() {
        stateUpdateJob?.cancel()
        stateUpdateJob = null
    }

    override fun onCleared() {
        super.onCleared()
        ToolSourceStore.shared.removeChangeListener(toolSourcesChanged)
        // Stopped first, so the turns it finalizes are in the snapshot: the debounced write it
        // schedules cannot run, since viewModelScope is already cancelled by the time we get here.
        stopProcessing(reason = "viewModel cleared")
        persistState()
        stopStateTimer()
    }
}
