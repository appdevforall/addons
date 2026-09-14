package com.itsaky.androidide.plugins.stt

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLifecycleListener
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.ShowAsAction
import com.itsaky.androidide.plugins.extensions.ToolbarAction
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.IdeUIService
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Speech-to-Text Plugin provides voice-to-code capabilities.
 *
 * Features:
 * - Voice capture via Android [SpeechRecognizer] (on-device when available)
 * - Optional LLM-based code generation from the transcript
 * - Editor integration for inserting the result at the cursor
 *
 * The plugin surfaces a "Voice to Code" action in the editor toolbar by
 * implementing [UIExtension.getToolbarActions]. The action is only visible
 * while a file is open in the editor.
 */
class SpeechToTextPlugin : IPlugin, UIExtension, DocumentationExtension {

    /** Cancelled by [teardown], so [activate] rebuilds it when the plugin is re-enabled. */
    @Volatile
    private var scope = CoroutineScope(Dispatchers.IO)
    private lateinit var context: PluginContext
    /** Populated on the main thread by activate(), read from [scope]'s IO threads. */
    @Volatile
    private var llmService: LlmInferenceService? = null

    /** Orders a [resolveLlmService] write against the clear-and-cancel in [teardown]. */
    private val serviceLock = Any()
    private var editorService: IdeEditorService? = null
    private var uiService: IdeUIService? = null

    /** Held only between startListening() and the terminal result/error callback. */
    private var speechRecognizer: SpeechRecognizer? = null

    /** Drives the toolbar icon via [ToolbarAction.iconProvider]. */
    private enum class RecordingState { IDLE, RECORDING, PROCESSING }

    @Volatile
    private var recordingState = RecordingState.IDLE

    /**
     * Updates the recording state and asks the IDE to rebuild the toolbar so the
     * icon provider is re-evaluated and the new (possibly animated) icon is shown.
     */
    private fun setState(state: RecordingState) {
        recordingState = state
        uiService?.refreshToolbarActions()
    }

    private fun currentIconRes(): Int = when (recordingState) {
        RecordingState.IDLE -> R.drawable.ic_mic
        RecordingState.RECORDING -> R.drawable.ic_waves
        RecordingState.PROCESSING -> R.drawable.ic_processing
    }

    /**
     * The host logger, or null before [initialize] ran. [context] is `lateinit`, and the host
     * still calls [deactivate]/[dispose] after a failed or skipped load.
     */
    private val logger: PluginLogger?
        get() = if (::context.isInitialized) context.logger else null

    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        logger?.info("Plugin initialized")
        return true
    }

    override fun activate(): Boolean {
        // deactivate() cancels the scope, so a re-enabled plugin needs a fresh one.
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.IO)
        }
        // The cancelled generation's finally never reached the toolbar, so the field can still
        // read PROCESSING here and would strand a re-enabled plugin on the spinner.
        recordingState = RecordingState.IDLE
        context.addPluginLifecycleListener(aiCoreLifecycleListener)

        // Get services from plugin context
        editorService = context.services.get(IdeEditorService::class.java)
        uiService = context.services.get(IdeUIService::class.java)

        // AI Core loads in parallel with us, so a miss here is retried on every use.
        resolveLlmService()

        if (editorService == null) {
            logger?.warn("IdeEditorService not available - editor integration disabled")
        }
        if (uiService == null) {
            logger?.warn("IdeUIService not available - toolbar icon will not animate between states")
        }

        logger?.info("Plugin activated")
        return true
    }

    /**
     * Resolves AI Core's router, caching only a successful lookup so a later call retries.
     * Hosts differ in where they publish it: the process-global [SharedServices], AI Core's
     * per-plugin export, or the plugin-local registry.
     *
     * @return the service, or null while AI Core is absent or has not activated yet
     */
    private fun resolveLlmService(): LlmInferenceService? {
        llmService?.let { return it }

        val service = try {
            SharedServices.get(LlmInferenceService::class.java)
                ?: context.getPluginService(AI_CORE_PLUGIN_ID, LlmInferenceService::class.java)
                ?: context.services.get(LlmInferenceService::class.java)
        } catch (e: Exception) {
            logger?.warn("Error resolving LlmInferenceService", e)
            null
        }

        if (service == null) {
            logger?.info("LlmInferenceService not available yet - install/activate the AI Core plugin")
            return null
        }

        // teardown() cancels the scope and clears the field under this lock, so a lookup that
        // overlapped a disable cannot write AI Core's router back and pin its ClassLoader.
        synchronized(serviceLock) {
            if (!scope.isActive) return null
            llmService = service
        }
        logger?.info("LlmInferenceService resolved - voice generation enabled")
        return service
    }

    /**
     * Drops the cached router when AI Core goes away, so the next transcript re-resolves rather
     * than calling into an unloaded plugin's object and pinning its ClassLoader for the process.
     */
    private val aiCoreLifecycleListener = object : PluginLifecycleListener {
        override fun onPluginActivated(pluginId: String) = Unit
        override fun onPluginDeactivated(pluginId: String) = forgetLlmService(pluginId)
        override fun onPluginUninstalled(pluginId: String) = forgetLlmService(pluginId)
    }

    private fun forgetLlmService(pluginId: String) {
        if (pluginId != AI_CORE_PLUGIN_ID) return
        llmService = null
        logger?.info("AI Core went away - dropped the cached LlmInferenceService")
    }

    override fun deactivate(): Boolean {
        logger?.info("Plugin deactivating")
        teardown()
        return true
    }

    override fun dispose() {
        logger?.info("Plugin disposed")
        teardown()
    }

    /**
     * Releases everything that could outlive a disabled plugin: an in-flight generation that
     * would still write into the user's file, host callbacks to a dead instance, and the
     * cached router that pins AI Core's ClassLoader. Idempotent - dispose() follows deactivate().
     */
    private fun teardown() {
        if (::context.isInitialized) {
            runCatching { context.removePluginLifecycleListener(aiCoreLifecycleListener) }
        }
        // Cancelled and cleared together: resolveLlmService takes the same lock, so an in-flight
        // resolution cannot cache the router again after this returned.
        synchronized(serviceLock) {
            scope.cancel()
            llmService = null
        }
        // A cancelled generation's finally still posts setState, which lands after deactivate()
        // returned; without these a disabled plugin reaches into the host toolbar.
        editorService = null
        uiService = null
        destroyRecognizer()
    }

    /**
     * Contributes the "Voice to Code" button to the editor toolbar. The IDE
     * registers this via [UIExtension] and shows it while an editor is open.
     */
    override fun getToolbarActions(): List<ToolbarAction> = listOf(
        ToolbarAction(
            id = TOOLBAR_ACTION_ID,
            title = str(R.string.stt_action_title),
            // Static fallback for hosts that don't support iconProvider.
            icon = R.drawable.ic_mic,
            showAsAction = ShowAsAction.IF_ROOM,
            order = 100,
            action = { startVoiceCapture() }
        ).apply {
            // Dynamic icon: mic (idle) -> animated waves (recording) -> spinner (processing).
            iconProvider = { currentIconRes() }
            // Voice-to-code inserts into the active editor, so the button is only usable
            // while a file is open. The host greys it out and blocks taps when this is false,
            // and re-evaluates it whenever the toolbar is rebuilt (including editor changes).
            isEnabledProvider = { hasOpenFile() }
        }
    )

    /** True when there is a file open in the editor to insert transcribed text into. */
    private fun hasOpenFile(): Boolean = try {
        editorService?.getCurrentFile() != null
    } catch (e: Exception) {
        false
    }
    override fun getTooltipCategory(): String = "plugin_$PLUGIN_ID"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLBAR_ACTION_ID,
            summary = "Voice to Code: tap, speak, and insert the transcript — or code generated from it — at the cursor.",
            detail = """
                <p>The <b>microphone</b> button in the editor toolbar records a
                short voice command and inserts the result at the cursor.</p>
                <p>Recognition uses Android's on-device recognizer when available.
                If the <b>AI Core</b> plugin is installed, the transcript is turned
                into code by the model; otherwise the raw transcript is inserted.</p>
                <p>The button is enabled only while a file is open, and microphone
                permission is requested on first use.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Speech to Text guide",
                    uri = "index.html",
                    order = 0
                )
            )
        )
    )

    /** Subdirectory under src/main/assets/ holding the Tier 3 offline docs. */
    override fun getTier3DocsAssetPath(): String = "docs"

    /**
     * Entry point for the toolbar action. Runs on the UI thread (the toolbar
     * action item requires it), which is also required to construct and drive
     * [SpeechRecognizer].
     */
    private fun startVoiceCapture() {
        val ctx = hostContext()

        // A second tap during recognition or generation would insert twice and leave the icon
        // describing whichever run finished last.
        if (recordingState != RecordingState.IDLE) {
            toast(str(R.string.stt_busy))
            return
        }

        // Belt-and-suspenders: the toolbar already disables the button when no file is open,
        // but guard here too so a stale enabled state can't start a pointless recording.
        if (!hasOpenFile()) {
            toast(str(R.string.stt_need_open_file))
            return
        }

        if (!hasMicrophonePermission()) {
            requestMicrophonePermission()
            toast(str(R.string.stt_need_mic_permission))
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            toast(str(R.string.stt_recognition_unavailable))
            return
        }

        try {
            destroyRecognizer()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
            recognizer.setRecognitionListener(recognitionListener)
            speechRecognizer = recognizer

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                // Prefer on-device recognition; falls back to network if unsupported.
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            toast(str(R.string.stt_listening))
            recognizer.startListening(intent)
            setState(RecordingState.RECORDING)
        } catch (e: Exception) {
            logger?.error("Failed to start speech recognition", e)
            toast(str(R.string.stt_start_failed))
            destroyRecognizer()
            setState(RecordingState.IDLE)
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val transcript = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            destroyRecognizer()
            if (transcript.isNullOrBlank()) {
                toast(str(R.string.stt_no_words))
                setState(RecordingState.IDLE)
                return
            }
            setState(RecordingState.PROCESSING)
            handleTranscript(transcript)
        }

        override fun onError(error: Int) {
            destroyRecognizer()
            toast(describeError(error))
            setState(RecordingState.IDLE)
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * Takes the recognized text, optionally runs it through the LLM to produce
     * code, and inserts the result at the cursor.
     */
    private fun handleTranscript(transcript: String) {
        logger?.debug("Transcript received (${transcript.length} chars)")
        // Read here: onResults is a main-thread callback, and the IO dispatcher below is not.
        val language = currentLanguageId()
        scope.launch {
            try {
                // Resolved once per transcript: AI Core may have finished activating after we did.
                val service = resolveLlmService()
                // Generate code when AI Core is present; fall back to the raw transcript so speech is never dropped.
                val generated = service?.let { generateCodeFromVoice(it, transcript, language) }
                val generationFailed = service != null && generated == null
                val output = generated ?: transcript
                withContext(Dispatchers.Main) {
                    val inserted = insertCodeAtCursor(output)
                    toast(
                        when {
                            !inserted -> str(R.string.stt_recognized_no_file, transcript)
                            generationFailed -> str(R.string.stt_generation_failed_inserted_raw)
                            else -> str(R.string.stt_inserted)
                        }
                    )
                }
            } finally {
                // Posted, not dispatched: a cancelled coroutine can no longer suspend, and the
                // toolbar must leave the spinner even then. Only our own PROCESSING is cleared.
                runOnMain {
                    if (recordingState == RecordingState.PROCESSING) {
                        setState(RecordingState.IDLE)
                    }
                }
            }
        }
    }

    /**
     * Generates code from a voice command using the LLM.
     *
     * @param service AI Core's router, resolved by the caller so one transcript resolves it once
     * @param voiceText The transcribed text from speech-to-text
     * @param language Programming language of the open file, read by the caller on the main thread
     * @return Generated code snippet, or null if generation failed (details are logged).
     */
    suspend fun generateCodeFromVoice(
        service: LlmInferenceService,
        voiceText: String,
        language: String,
    ): String? {
        return try {
            val prompt = "Write $language code for this request: $voiceText"

            // AI Core routes to the user-selected backend; we don't pick one here.
            val config = LlmInferenceService.LlmConfig(AUTO_BACKEND_ID).apply {
                systemPrompt = GENERATION_SYSTEM_PROMPT
                temperature = GENERATION_TEMPERATURE
                maxTokens = MAX_GENERATION_TOKENS
                // No FENCE stop sequence, unlike the inline-completion sibling: a block answer
                // opens with a fence, so stopping there would truncate it to nothing.
            }
            // withTimeoutOrNull reports a timeout as null, and generateCompletion is platform-typed,
            // so record a future that completed with null rather than reading it as a slow model.
            var completedWithoutResponse = false
            // await() is cancellation-aware, so dispose() unwinds this instead of leaving an IO
            // thread parked in Future.get for the rest of the timeout.
            // The caller already launched this on [scope]'s IO dispatcher, so there is nothing
            // to switch to here.
            val response = withTimeoutOrNull(GENERATION_TIMEOUT_SECONDS * MILLIS_PER_SECOND) {
                service.generateCompletion(prompt, config).await()
                    .also { if (it == null) completedWithoutResponse = true }
            }
            if (response == null) {
                if (completedWithoutResponse) {
                    logger?.warn("Backend completed the request without a response")
                } else {
                    // Only maxTokens bounds the backend: cancelling the future does not stop
                    // decoding, and cancelGeneration() is router-wide and would abort other
                    // plugins' requests.
                    logger?.warn("Code generation timed out after ${GENERATION_TIMEOUT_SECONDS}s")
                }
                return null
            }
            if (response.success) {
                response.text?.let { stripCodeFences(it) }?.takeIf { it.isNotBlank() }
            } else {
                logger?.warn("Code generation failed: ${response.error}")
                null
            }
        } catch (e: CancellationException) {
            // A plugin unload cancels the scope; that is teardown, not a generation error.
            if (!coroutineContext.isActive) throw e
            // AI Core's cancelGeneration() is router-wide, so another plugin's Stop button can
            // cancel our future while we are alive; that is a failed generation, not a teardown.
            logger?.warn("Code generation was cancelled by the backend", e)
            null
        } catch (e: Exception) {
            logger?.error("Error generating code from voice", e)
            null
        }
    }

    /**
     * The language of the file being edited, so the model is asked for the language actually
     * in front of the user rather than the plugin's own default. Call on the main thread.
     *
     * @return the host's language id for the open file, or [DEFAULT_LANGUAGE] when unknown
     */
    private fun currentLanguageId(): String = try {
        editorService?.getCurrentLanguageId()?.takeIf { it.isNotBlank() } ?: DEFAULT_LANGUAGE
    } catch (e: Exception) {
        logger?.warn("Could not resolve the editor language", e)
        DEFAULT_LANGUAGE
    }

    /**
     * Unwraps the markdown code fence a chat-tuned model puts around its answer, so the editor
     * receives code rather than a lead-in line, backticks and a language tag.
     *
     * @param raw the backend's response text
     * @return the fenced block's body, the code of an unfenced reply, or empty when the reply
     *   held no code at all
     */
    private fun stripCodeFences(raw: String): String {
        val lines = raw.trim().lines()
        // The fence can open on any line: a model often writes "Here is the code:" first.
        val opening = lines.indexOfFirst { it.trimStart().startsWith(FENCE) }
        if (opening < 0) return dropProse(lines)

        // The opening line can carry code after its info string whether or not it also closes.
        val afterFence = lines[opening].trim().removePrefix(FENCE)
        val closesInline = afterFence.endsWith(FENCE)
        val opener = afterFence.removeSuffix(FENCE).trim()
        // A lone token on an opening line that does not close is an info string by definition,
        // so it goes whether or not LANGUAGE_TAGS names it - `dart`, `kts` and `rust` were all
        // reaching the file as a bare identifier. A tag followed by code still keeps the code.
        val firstCodeLine =
            if (!closesInline && !opener.contains(' ')) "" else stripLanguageInfo(opener)

        val rest = lines.drop(opening + 1)
        val closing = rest.indexOfFirst { it.trimStart().startsWith(FENCE) }
        // An unclosed fence (a reply truncated at maxTokens) keeps everything that did arrive.
        val body = if (closing >= 0) rest.take(closing) else rest

        return (if (firstCodeLine.isEmpty()) body else listOf(firstCodeLine) + body)
            .joinToString("\n")
            .trim()
            .removeSuffix(FENCE)
            .trimEnd()
    }

    /**
     * Drops the natural-language lead-in and trailing explanation of an unfenced reply, so
     * neither is written into the open file. Interior lines are kept whatever they read like:
     * a docstring, a comment or a line of Markdown is content the user asked for. An all-prose
     * reply yields empty, which the caller reports as a failed generation and answers with the
     * raw transcript.
     *
     * @param lines the trimmed reply, split into lines
     * @return the reply without its prose framing, or empty when it holds nothing else
     */
    private fun dropProse(lines: List<String>): String =
        lines.dropWhile { it.isBlank() || isProse(it.trim()) }
            .dropLastWhile { it.isBlank() || isProse(it.trim()) }
            .joinToString("\n")
            .trim()

    /**
     * Detects a natural-language line: a lead-in ("Here is the code:") or a refusal ("I cannot
     * write that code."). Deliberately narrow, because the system prompt asks for bare code and
     * a false positive throws real output away.
     *
     * @param line a trimmed, non-empty line
     * @return true when the line reads as an English sentence rather than as code
     */
    private fun isProse(line: String): Boolean =
        line.first().isUpperCase() &&
            line.last() in SENTENCE_TERMINATORS &&
            line.none { it in CODE_PUNCTUATION } &&
            line.count { it == ' ' } >= MIN_PROSE_SPACES

    /**
     * Drops the language info from a fence's opening line, so `kotlin println()` (the remains
     * of ` ```kotlin println()``` `) yields just the code.
     *
     * @param fenceLine the opening fence line with its fence markers already removed
     * @return the line without its language tag, empty when the tag was all it held
     */
    private fun stripLanguageInfo(fenceLine: String): String {
        val tag = fenceLine.substringBefore(' ')
        if (tag.lowercase() !in LANGUAGE_TAGS) return fenceLine

        val rest = fenceLine.substringAfter(' ', "").trim()
        // A tag on its own is always an info string, whatever language it names.
        if (rest.isEmpty()) return ""
        // With code behind it the tag may be code itself, so require a remainder that starts a
        // name - `c = a + b` and `bash -c "..."` are code, not an info string plus code.
        val startsName = rest.first().isLetter() || rest.first() == '_' || rest.first() == '@'
        return if (startsName) rest else fenceLine
    }

    /**
     * Inserts generated code at the cursor position.
     */
    fun insertCodeAtCursor(code: String): Boolean {
        // insertTextAtCursor throws SecurityException if the plugin lacks FILESYSTEM_WRITE
        // (declared in the manifest). Never let a service error crash the host process.
        return try {
            editorService?.insertTextAtCursor(code) ?: false
        } catch (e: Exception) {
            logger?.error("Failed to insert text at cursor", e)
            false
        }
    }

    /**
     * Checks if microphone permission is granted.
     * @return true if RECORD_AUDIO permission is granted, false otherwise
     */
    fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            hostContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Requests microphone permission from the user.
     * Returns immediately if already granted.
     */
    fun requestMicrophonePermission() {
        if (hasMicrophonePermission()) {
            logger?.debug("Microphone permission already granted")
            return
        }

        logger?.debug("Requesting microphone permission")
        try {
            // Must be the host Activity: the plugin's androidContext is a ContextThemeWrapper,
            // never an Activity, and RECORD_AUDIO is owned by the host app's UID.
            val activity = hostActivity()
            if (activity != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                activity.requestPermissions(
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    PERMISSION_REQUEST_CODE
                )
            } else {
                logger?.warn("No host Activity available to request microphone permission")
            }
        } catch (e: Exception) {
            logger?.error("Error requesting microphone permission", e)
        }
    }

    private fun destroyRecognizer() {
        val recognizer = speechRecognizer ?: return
        speechRecognizer = null
        runOnMain {
            try {
                recognizer.destroy()
            } catch (e: Exception) {
                logger?.warn("Failed to destroy SpeechRecognizer", e)
            }
        }
    }

    /**
     * Maps a [SpeechRecognizer] error code to a full, user-facing message that says
     * what went wrong and what the user can do about it.
     */
    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> str(R.string.stt_error_audio)
        SpeechRecognizer.ERROR_CLIENT -> str(R.string.stt_error_client)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> str(R.string.stt_error_permissions)
        SpeechRecognizer.ERROR_NETWORK -> str(R.string.stt_error_network)
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> str(R.string.stt_error_network_timeout)
        SpeechRecognizer.ERROR_NO_MATCH -> str(R.string.stt_error_no_match)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> str(R.string.stt_error_busy)
        SpeechRecognizer.ERROR_SERVER -> str(R.string.stt_error_server)
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> str(R.string.stt_error_speech_timeout)
        else -> str(R.string.stt_error_unknown, error)
    }

    /**
     * The foreground host Activity, if any. Required for anything that talks to the
     * window manager or requests runtime permissions — the plugin's own
     * [PluginContext.androidContext] reports the plugin package while running under the
     * host UID, which the framework rejects (SecurityException: package not in UID).
     */
    private fun hostActivity(): Activity? = uiService?.getCurrentActivity()

    /**
     * A host-owned Context (package + UID match the running process). Prefer the
     * foreground Activity; fall back to the host application context, which the
     * plugin resource context delegates to.
     */
    private fun hostContext(): Context =
        hostActivity() ?: context.androidContext.applicationContext

    /**
     * Resolves a plugin string resource. Uses [PluginContext.androidContext] (which carries the
     * plugin's own resources) rather than the host Context, whose resources don't include ours.
     */
    private fun str(resId: Int, vararg formatArgs: Any): String =
        context.androidContext.getString(resId, *formatArgs)

    private fun toast(message: String) = runOnMain {
        try {
            Toast.makeText(hostContext(), message, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            logger?.warn("Failed to show toast", e)
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post(block)
        }
    }

    companion object {
        // Must match plugin.id in AndroidManifest.xml; used for the tooltip category.
        private const val PLUGIN_ID = "com.itsaky.androidide.plugins.stt"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val TOOLBAR_ACTION_ID = "stt_voice_to_code"

        /** Sentinel backend id: AI Core resolves the user-selected backend for us. */
        private const val AUTO_BACKEND_ID = "auto"

        /** AI Core's plugin id: its per-plugin service export, and the cache-invalidation key. */
        private const val AI_CORE_PLUGIN_ID = "com.itsaky.androidide.plugins.aicore"

        private const val FENCE = "```"

        private const val MILLIS_PER_SECOND = 1000L

        /** Low, because a voice command wants the obvious code rather than a creative answer. */
        private const val GENERATION_TEMPERATURE = 0.2f

        /** A voice command asks for a snippet, not a file; this also bounds the timeout below. */
        private const val MAX_GENERATION_TOKENS = 512

        /** Floor throughput of a phone-class llama.cpp backend, used only to size the timeout. */
        private const val SLOWEST_TOKENS_PER_SECOND = 5

        /** Headroom for loading the model and evaluating the prompt before the first token. */
        private const val PROMPT_EVAL_SECONDS = 30

        /**
         * Bounds one generation so a wedged backend can't strand the toolbar on the spinner.
         * Sized to [MAX_GENERATION_TOKENS] so a slow device finishes rather than being cut off:
         * 512 / 5 + 30 works out to 132 s, which is what the toolbar spinner's worst case is.
         */
        private const val GENERATION_TIMEOUT_SECONDS =
            MAX_GENERATION_TOKENS / SLOWEST_TOKENS_PER_SECOND + PROMPT_EVAL_SECONDS

        /** Keeps fences and prose out of the reply, so stripCodeFences is only a safety net. */
        private const val GENERATION_SYSTEM_PROMPT =
            "You are a code generator inside a code editor. Reply with the raw code that " +
                "fulfils the request and nothing else. Do not explain. Do not use markdown or " +
                "code fences ($FENCE). Do not name the language."

        /** Used only when the host cannot name the open file's language. */
        private const val DEFAULT_LANGUAGE = "kotlin"

        /** Ends a sentence; a code line that reaches one of these also holds code punctuation. */
        private const val SENTENCE_TERMINATORS = ".:!?"

        /** Marks a line as code however sentence-like it otherwise reads. */
        private const val CODE_PUNCTUATION = "(){}[];=<>"

        /** Three words or more, matching the sibling's isPreamble; shorter lines stay. */
        private const val MIN_PROSE_SPACES = 2

        /** Bare fence infos ("java", "kotlin", ...) that are never code. */
        private val LANGUAGE_TAGS = setOf(
            "java", "kotlin", "kt", "kts", "python", "py", "xml", "json", "gradle", "groovy",
            "javascript", "js", "typescript", "ts", "c", "cpp", "c++", "sh", "bash",
            "dart", "rust", "rs", "go", "swift", "html", "css", "yaml", "yml", "toml",
            "sql", "cmake", "properties", "markdown", "md",
            "text", "plaintext", "code",
        )
    }
}

/**
 * Suspends until this future completes, cancelling it if the coroutine is cancelled.
 * @receiver the future to await
 * @return the future's completed value
 */
private suspend fun <T> CompletableFuture<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        whenComplete { value, error ->
            if (error == null) cont.resume(value) else cont.resumeWithException(error)
        }
        cont.invokeOnCancellation { cancel(true) }
    }
