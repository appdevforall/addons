package com.itsaky.androidide.plugins.aicore.viewmodel

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
import com.itsaky.androidide.plugins.aicore.models.MessageStatus
import com.itsaky.androidide.plugins.aicore.models.newestFirst
import com.itsaky.androidide.plugins.aicore.models.Sender
import com.itsaky.androidide.plugins.aicore.models.isRunning
import com.itsaky.androidide.plugins.aicore.models.traceLabel
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.AgentLoop
import com.itsaky.androidide.plugins.aicore.tool.AgentTools
import com.itsaky.androidide.plugins.aicore.tool.ApprovalRequest
import com.itsaky.androidide.plugins.aicore.tool.ApprovalResult
import com.itsaky.androidide.plugins.aicore.tool.ToolApprovalManager
import com.itsaky.androidide.plugins.aicore.tool.ToolCall
import com.itsaky.androidide.plugins.aicore.tool.ToolCallExtractor
import com.itsaky.androidide.plugins.aicore.tool.ToolExecutionTracker
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema
import com.itsaky.androidide.plugins.aicore.tool.pathsIn
import com.itsaky.androidide.plugins.aicore.tool.sources.ToolSourceStore
import com.itsaky.androidide.plugins.aicore.tool.handlers.BuiltInToolHandlers
import com.itsaky.androidide.plugins.aicore.tool.handlers.PathGuard
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

        /** Quiet period a debounced persist waits out; see [schedulePersist]. */
        private const val PERSIST_DEBOUNCE_MS = 1_000L

        /** Max open files named in the prompt's IDE-context block. */
        private const val MAX_CONTEXT_OPEN_FILES = 8

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

        /**
         * Path used in the tool-call examples when the IDE has nothing open, so there is no real one
         * to show. A concrete path is what a small model needs to copy the *shape* from — a
         * placeholder like "path/to/File.ext" measurably degrades its calls — so this is the
         * dominant CoGo project layout rather than a language-neutral token. Whenever a file *is*
         * open, [IdeSnapshot.exampleFilePath] uses that instead and this is never seen.
         */
        private const val FALLBACK_EXAMPLE_PATH = "app/src/main/java/com/example/MainActivity.kt"
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
    private val approvalManager = ToolApprovalManager()
    private val agentLoop = AgentLoop(terminalTool = RESPOND_TOOL)
    val toolExecutionTracker = ToolExecutionTracker()

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
    private val isGenerating = AtomicBoolean(false)

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

    /** The prompt the last run was started with, for [retryLastRun]; null before the first send. */
    @Volatile
    private var lastRunPrompt: String? = null

    /**
     * How long [history] was before the last run began appending to it, so [retryLastRun] can drop
     * that run's turns rather than retrying behind them.
     */
    @Volatile
    private var historySizeBeforeLastRun = 0

    /** The tool awaiting approval, straight from [approvalManager] — no polling in between. */
    val pendingApprovalRequest: StateFlow<ApprovalRequest?> = approvalManager.currentApprovalRequest

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
        builtInHandlers = getContext()?.let(BuiltInToolHandlers::create).orEmpty()
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
        _messages.value = _messages.value.filter { it.id != messageId }
        val session = currentSessionOrNull() ?: return
        replaceCurrentSessionMessages(session.messages.filter { it.id != messageId })
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
     * @param text the error text to show.
     */
    private fun emitSystemError(text: String) {
        val errorMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = text,
            sender = Sender.SYSTEM,
            status = MessageStatus.ERROR
        )
        _messages.value = _messages.value + errorMessage
        syncMessageToSession(errorMessage)
        setState(AgentState.Error(text))
    }

    /**
     * Build context string from selected files.
     */
    private fun buildContextString(): String {
        if (contextFiles.isEmpty()) return ""

        val contextBuilder = StringBuilder()
        contextBuilder.append("\n\nCONTEXT FILES:\n\n")

        contextFiles.forEach { file ->
            if (file.exists() && file.isFile) {
                try {
                    val content = file.readText()
                    contextBuilder.append("=== ${file.name} ===\n")
                    contextBuilder.append(content)
                    contextBuilder.append("\n\n")
                } catch (e: Exception) {
                    logWarn("could not read context file ${file.name}", e)
                }
            }
        }

        return contextBuilder.toString()
    }

    /**
     * Builds the system prompt for the active backend.
     *
     * The wording comes from the backend, which knows its own model; this side supplies the tool
     * contract and appends the IDE context. A backend with no prompt of its own gets
     * [buildDefaultSystemPrompt], so a third-party `.cgp` works without shipping prompt text.
     *
     * @param tools the snapshot this run is using; the prompt must describe those tools and no others.
     */
    private suspend fun buildSystemPrompt(tools: AgentTools): String {
        // One editor read serves both the IDE CONTEXT block and the paths in the examples.
        val ide = readIdeSnapshot()
        val modules = withContext(Dispatchers.IO) {
            ProjectLayout.describe(File(PathGuard.projectRoot()))
        }
        // Paths, not a count: an empty or wrong one here is what sends the agent walking the tree,
        // and these are project-relative directory names rather than the user's content.
        AgentTrace.stage(
            "LAYOUT",
            "modules=${modules.size}" + modules.joinToString("") {
                " ${it.name}[src=${it.sourceDir} layout=${it.layoutDir} manifest=${it.manifest}]"
            },
        )
        val examplePath = ide.exampleFilePath()
        val base = backendSystemPrompt(tools, examplePath)
            ?: buildDefaultSystemPrompt(tools, examplePath)
        return base + ide.contextBlock(modules)
    }

    /**
     * Asks the active backend for its system prompt.
     *
     * @param tools the snapshot this run is using.
     * @param examplePath the path the tool-call examples should use.
     * @return the backend's prompt, or null when it has none, is unreachable, or throws — one bad
     *   backend must degrade to the default prompt, not break every message
     */
    private fun backendSystemPrompt(tools: AgentTools, examplePath: String): String? {
        val backend = try {
            getLlmService()?.getBackend(currentBackendId)
        } catch (e: Throwable) {
            logWarn("could not resolve backend '$currentBackendId'", e)
            null
        } ?: return null

        return try {
            backend.getSystemPrompt(
                LlmInferenceService.SystemPromptRequest(
                    promptToolDefinitions(tools),
                    // Null tells the backend this side parses no envelope; see SystemPromptRequest.
                    TOOL_CALL_SYNTAX.takeUnless { callsToolsNatively() },
                    examplePath,
                )
            )?.takeIf { it.isNotBlank() }
        } catch (e: Throwable) {
            logWarn(
                "backend '$currentBackendId' supplied no system prompt; using the default",
                e
            )
            null
        }
    }

    /**
     * Whether the active backend carries tool calls in its provider's own function-calling API
     * rather than in the reply text.
     *
     * Decides both halves of the protocol at once — the schemas sent with the request and the
     * envelope the prompt teaches — so the two can never disagree about which one is live.
     *
     * @return true when the backend declares [LlmInferenceService.ToolCallingBackend]
     */
    private fun callsToolsNatively(): Boolean = try {
        getLlmService()?.getBackend(currentBackendId) is LlmInferenceService.ToolCallingBackend
    } catch (e: Throwable) {
        logWarn("could not resolve backend '$currentBackendId'", e)
        false
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

    /**
     * The tools to present in the system prompt: the snapshot's budgeted list, plus [RESPOND_TOOL],
     * which is not a handler but is how the model addresses the user.
     *
     * The cap is applied when the snapshot is built, not here, so the grammar the local backend is
     * constrained by and the list the prompt describes can never disagree. It lands on this side of
     * the boundary at all because every backend renders the list itself, this repo's or not.
     *
     * @param tools the snapshot this run is using.
     * @return the definitions to hand the backend.
     */
    private fun promptToolDefinitions(tools: AgentTools): List<LlmInferenceService.ToolDefinition> {
        return tools.promptTools.definitions + LlmInferenceService.ToolDefinition(
            RESPOND_TOOL,
            "Send the user your reply or final answer. It MUST carry a \"message\" holding the " +
                "text itself — a respond call with no \"message\" shows the user nothing.",
            // Schema, not emptyMap(): under native calling a parameterless declaration is one the
            // model cannot put the answer in, which is the empty respond the description warns of.
            ToolSchema.objectOf(
                "message" to ToolSchema.string("The reply to show the user."),
                required = listOf("message"),
            ),
        )
    }

    /**
     * Prompt used for a backend that supplies none of its own.
     *
     * Deliberately short: it states the protocol this side parses and nothing about model
     * behaviour, which is the part only the backend can know. A backend that needs more should
     * override `getSystemPrompt`.
     */
    private fun buildDefaultSystemPrompt(tools: AgentTools, examplePath: String): String {
        val toolDescriptions = promptToolDefinitions(tools)
            .joinToString("\n") { "- ${it.name}: ${it.description}" }

        val head = """
        You are a coding assistant inside CodeOnTheGo.

        Reply with exactly ONE tool call and nothing else. After a tool call, stop and wait — the
        real result arrives next turn. Never invent tool output, and never claim an action you did
        not perform through a tool. For a greeting or a question you can answer directly, use
        "$RESPOND_TOOL".

        Tools:
        $toolDescriptions
        """.trimIndent()

        // Under native calling the provider carries the call; teaching an envelope as well invites
        // the model to emit both, and the text one would then run the tool a second time.
        if (callsToolsNatively()) return head

        return head + "\n\n" + """
        TOOL CALL FORMAT — emit a single line in EXACTLY this format and nothing after it:
        $TOOL_CALL_SYNTAX

        Example:
        <tool_call>{"tool":"open_file","args":{"file_path":"$examplePath"}}</tool_call>
        """.trimIndent()
    }

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
     * What the IDE has open, project-relative, read once per prompt.
     * @property currentFile the focused file, or null when nothing is open.
     * @property otherFiles other open tabs, capped at [MAX_CONTEXT_OPEN_FILES].
     */
    private data class IdeSnapshot(val currentFile: String?, val otherFiles: List<String>)

    /**
     * Reads the open-file state from the editor service.
     * @return the snapshot; empty when there is no editor service or the call fails.
     */
    private suspend fun readIdeSnapshot(): IdeSnapshot {
        val editor = getContext()?.services?.get(IdeEditorService::class.java)
            ?: return IdeSnapshot(null, emptyList())
        val root = File(PathGuard.projectRoot())

        // Editor state is read on the main thread, like every other editor-service call here.
        val (current, open) = withContext(Dispatchers.Main) {
            runCatching { editor.getCurrentFile() to editor.getOpenFiles() }
                .getOrDefault(null to emptyList())
        }

        fun relative(file: File): String = runCatching { file.relativeToOrSelf(root).path }
            .getOrDefault(file.name)

        return IdeSnapshot(
            currentFile = current?.let(::relative),
            otherFiles = open.orEmpty()
                .filter { it != current }
                .take(MAX_CONTEXT_OPEN_FILES)
                .map(::relative),
        )
    }

    /**
     * The path the tool-call examples should use: a file the IDE really has open, so the examples
     * carry this project's own language and layout instead of teaching an Android/Java one. Falls
     * back to [FALLBACK_EXAMPLE_PATH] only when nothing is open.
     * @return a project-relative path.
     */
    private fun IdeSnapshot.exampleFilePath(): String =
        currentFile ?: otherFiles.firstOrNull() ?: FALLBACK_EXAMPLE_PATH

    /**
     * Describes what the user is looking at: the focused file and other open tabs, project-relative,
     * plus where [modules] keep their code. Most requests are about the file on screen and the IDE
     * knows that path exactly; without it the model reconstructs one, which is where invented
     * `.java` paths for Kotlin files came from.
     *
     * @param modules the project's modules, so the agent spends no turns rediscovering them.
     * @return a prompt block, or empty when there is nothing to say.
     */
    private fun IdeSnapshot.contextBlock(modules: List<ProjectLayout.Module>): String {
        if (currentFile == null && otherFiles.isEmpty() && modules.isEmpty()) return ""

        return buildString {
            append("\n\nIDE CONTEXT (real paths — use these verbatim, do not rewrite them):\n")
            currentFile?.let { append("- File the user is viewing: ").append(it).append("\n") }
            if (otherFiles.isNotEmpty()) {
                append("- Other open files: ").append(otherFiles.joinToString(", ")).append("\n")
            }
            for (module in modules) {
                module.sourceDir?.let {
                    append("- New classes for module '").append(module.name).append("' go in: ")
                        .append(it).append("\n")
                }
                module.layoutDir?.let {
                    append("- Layouts for module '").append(module.name).append("': ")
                        .append(it).append("\n")
                }
                module.manifest?.let {
                    append("- Manifest for module '").append(module.name).append("': ")
                        .append(it).append("\n")
                }
            }
            if (modules.isNotEmpty()) {
                append(
                    "These directories already exist — do not call list_files to rediscover them.\n"
                )
            }
            append(
                "If the user names a file that appears above, use that exact path and do not " +
                    "guess a different folder or extension."
            )
        }
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

        // Record whether this batch's last tool failed (read by runModelTurn).
        lastToolFailedThisRun = results.lastOrNull()?.success == false

        withContext(Dispatchers.Main) {
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
        _backendStatus.value = transform(_backendStatus.value)
        true
    }

    /**
     * Send a user message and get agent response.
     */
    fun sendMessage(userMessage: String) {
        val llmService = getLlmService()
        if (llmService == null) {
            emitSystemError(str(R.string.error_llm_service_not_available))
            return
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
            return
        }

        if (userMessage.isBlank()) {
            return
        }

        // Reject re-entry while a generation is still in flight.
        if (!isGenerating.compareAndSet(false, true)) {
            return
        }

        AgentTrace.beginRun(currentBackendId, userMessage, contextFiles.size)
        // Reset per-run tool tracking.
        lastToolFailedThisRun = false
        activityMessageId = null
        runToolNames.clear()
        // Where a Retry has to rewind to; read here, on Main, while no run can be appending.
        lastRunPrompt = userMessage
        historySizeBeforeLastRun = _history.value.size
        // Read once: prompt, grammar and executor must all describe the same tool set.
        val tools = agentTools
        val epoch = generationEpoch.incrementAndGet()
        generationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Add user message to the UI.
                val userChatMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    text = userMessage,
                    sender = Sender.USER,
                    status = MessageStatus.SENT
                )
                withContext(Dispatchers.Main) {
                    _messages.value = _messages.value + userChatMessage
                    syncMessageToSession(userChatMessage)
                    setState(AgentState.Processing(str(R.string.msg_generating)))
                }

                val config = LlmInferenceService.LlmConfig(currentBackendId).apply {
                    // The grammar shapes a local tool call but not its values, so paths get sampled.
                    temperature = backendTemperature() ?: DEFAULT_TEMPERATURE
                    maxTokens = 4096  // headroom for complete tool calls
                    systemPrompt = buildSystemPrompt(tools)
                    // Local backend constrains generation to this grammar; cloud ignores it.
                    extraParams = mapOf(EXTRA_PARAM_GRAMMAR to tools.grammar)
                }

                val messageWithContext = buildString {
                    append(userMessage)
                    append(buildContextString())
                }
                val history = _history.value.toMutableList()
                history.add(
                    LlmInferenceService.ChatMessage(
                        LlmInferenceService.ChatMessage.Role.USER,
                        messageWithContext
                    )
                )

                try {
                    // The same list the system prompt describes, so a native declaration and the
                    // prose the model reads can never name different tools.
                    val toolDefinitions = promptToolDefinitions(tools)
                    // Which protocol is live for this run. `native=false` against a backend that
                    // should call natively is the first thing to check when a call reaches the chat
                    // as text instead of running.
                    AgentTrace.stage(
                        "PROTOCOL",
                        "native=${callsToolsNatively()} tools=${toolDefinitions.size} " +
                            toolDefinitions.joinToString(",") { it.name },
                    )
                    val loopResult = agentLoop.run(
                        history = history,
                        generate = { turns ->
                            withContext(Dispatchers.Main) {
                                setState(AgentState.Processing(str(R.string.msg_generating)))
                            }
                            runModelTurn(llmService, turns, config, toolDefinitions, epoch)
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
                        events = AgentRunReporter(runNotices),
                    )
                    AgentTrace.endRun(loopResult.reason.name, loopResult.turns)
                } finally {
                    // Persist history only if this run wasn't superseded (epoch bumped).
                    if (generationEpoch.get() == epoch) {
                        _history.value = history.toList()
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
                isGenerating.set(false)
                // The run can finish with the chat off screen, where nothing else writes.
                // NonCancellable so a Stop still saves what the run produced before it, and so the
                // activity line is closed rather than left reading as a tool still running.
                withContext(NonCancellable + Dispatchers.Main) {
                    finishActivity()
                    persistState()
                }
            }
        }
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
        if (isGenerating.get()) {
            logDebug("retryLastRun: a run is already in flight")
            return
        }
        _history.value = _history.value.take(historySizeBeforeLastRun)
        sendMessage(prompt)
    }

    /**
     * Runs one streaming model turn: creates an agent bubble, streams tokens into it and suspends
     * until completion, throwing on backend error. Sends [turns] structurally to the local backend;
     * Gemini's transport carries one string, so it keeps the flattened transcript.
     * @param llmService the inference service.
     * @param turns the conversation so far; the last entry is the current user turn.
     * @param config the generation config.
     * @param toolDefinitions the tools to offer a natively-calling backend; see [callsToolsNatively].
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

        return deferred.await()
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
        forgetRetryPoint()
        setState(AgentState.Idle)
        // Written now rather than debounced: a clear is deliberate and must survive a force-stop.
        persistState()
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
        generationJob?.cancel()
        generationJob = null
        getLlmService()?.cancelGeneration()
        stopStateTimer()
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
     * The one place those three move together. They were being set side by side at each of the
     * three call sites, which is how restoring a transcript to the screen while handing the model
     * an empty array (ADFA-5584) could be fixed in one of them and not the others.
     *
     * @param session the conversation to make current; it must already be in [_sessions].
     */
    private fun adoptSession(session: ChatSession) {
        _currentSessionId.value = session.id
        // Immutable snapshot, so a later mutation cannot reach collectors behind the StateFlow.
        _messages.value = session.messages.toList()
        // Emptying this is what had the model forget a conversation the user was looking at.
        _history.value = rebuildHistoryFrom(session.messages)
        // The rewind point names a run this transcript does not have, and would truncate it.
        forgetRetryPoint()
        persistState()
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
     * @param name the new name. Blank clears it, so the session falls back to naming itself after
     *   its first user turn — that is the only way back from a rename the user regrets.
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
        // Written now rather than debounced: a rename is deliberate and may be the last thing the
        // user does before leaving the tab, where no streamed token follows to flush it.
        persistState()
    }

    /**
     * Drops the rewind point [retryLastRun] uses, for a conversation that was cleared or swapped:
     * its prompt belongs to a transcript this one no longer has.
     */
    private fun forgetRetryPoint() {
        lastRunPrompt = null
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
        generationJob?.cancel()
        generationJob = null
        getLlmService()?.cancelGeneration()
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
        val finalized = _messages.value.mapNotNull { msg ->
            if (msg.sender == Sender.AGENT && msg.durationMs == null) {
                if (msg.text.isBlank()) null
                else msg.copy(status = MessageStatus.COMPLETED, durationMs = 0L)
            } else {
                msg
            }
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
