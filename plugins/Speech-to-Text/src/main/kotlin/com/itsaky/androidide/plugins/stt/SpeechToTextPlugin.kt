package com.itsaky.androidide.plugins.stt

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.annotation.RequiresApi
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
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

    /** Orders a [resolveLlmService] write against every clear of [llmService]. */
    private val serviceLock = Any()

    /**
     * Counts how often the cached router has been dropped; guarded by [serviceLock]. A lookup
     * reads it before it starts and writes only if it has not moved, so a resolution overtaken
     * by a clear cannot put the stale router back.
     */
    private var serviceEpoch = 0
    private var editorService: IdeEditorService? = null
    private var uiService: IdeUIService? = null

    /** Held only between startListening() and the terminal result/error callback. */
    private var speechRecognizer: SpeechRecognizer? = null

    /** Locale the in-flight attempt asked for, so a log line can name the failing language. */
    @Volatile
    private var activeLocale: Locale = Locale.getDefault()

    /**
     * The language the user actually configured, kept apart from [activeLocale] because a
     * fallback overwrites that one: naming the region recovery settled on would send the user
     * off to install a pack for a language they never chose.
     */
    private var requestedLocale: Locale = Locale.getDefault()

    /**
     * The recovery budget, one flag per strategy rather than one attempt in total: an offline
     * fallback that fails must still be able to reach the network, which is the only recovery
     * that can serve a language this device has no pack for. Main-thread only, like every other
     * capture field here: the toolbar action, the recognizer callbacks and the recovery
     * runnables all run on the main looper, so no synchronization is needed.
     */
    private var offlineFallbackSpent = false
    private var onlineRetrySpent = false

    /**
     * Identifies the attempt a recognizer callback came from. This service double-delivers -
     * the support check below sees one answer three times - so a callback from an attempt that
     * has been torn down must not end the retry that replaced it.
     */
    private var recognitionAttempt = 0L

    /**
     * Identifies the capture a transcript belongs to, so a generation that outlived its capture
     * cannot drop stale text into the file or reset a newer capture's toolbar state.
     */
    private var captureId = 0L

    /** The in-flight generation, cancelled when the next capture starts. */
    private var generationJob: Job? = null

    /** Held only across a checkRecognitionSupport call. */
    private var supportRecognizer: SpeechRecognizer? = null

    /**
     * Held across a triggerModelDownload request and its [PACK_REQUEST_HOLD_MS] hold. Its own
     * field because the next capture's support check claims [supportRecognizer] within a second
     * or two, and releasing this one with it would cancel the download inside its hold window.
     */
    private var packRecognizer: SpeechRecognizer? = null

    /** Everything recognizer-related is posted here; [SpeechRecognizer] is main-thread only. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Tags the delayed recovery work so teardown can cancel exactly that and nothing else. */
    private val recoveryToken = Any()

    /**
     * When the current phase of a capture started, so a wedged PROCESSING can't kill the button.
     * Restarted at PROCESSING so it bounds the generation alone, and monotonic so a clock
     * correction cannot leave the guard refusing every tap on the capture it exists to escape.
     */
    private var captureStartedAt = 0L

    /** Drives the toolbar icon via [ToolbarAction.iconProvider]. */
    private enum class RecordingState { IDLE, RECORDING, PROCESSING }

    /** Volatile, unlike the fields above: the host may evaluate the icon provider off-main. */
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

        val epoch = synchronized(serviceLock) { serviceEpoch }
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

        // Both clears happen under this lock, so a lookup that overlapped one cannot write AI
        // Core's router back and pin its ClassLoader: teardown() cancels the scope, and
        // forgetLlmService() moves the epoch on without touching it.
        synchronized(serviceLock) {
            if (!scope.isActive || serviceEpoch != epoch) return null
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
        // Deactivating AI Core does not cancel our scope, so the epoch is what tells a lookup
        // already in flight that the router it is holding belongs to an unloaded plugin.
        synchronized(serviceLock) {
            serviceEpoch++
            llmService = null
        }
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
        // Cancelled and cleared together: resolveLlmService takes the same lock and re-checks
        // both guards, so an in-flight resolution cannot cache the router again after this
        // returned - including after activate() has handed the plugin a fresh scope.
        synchronized(serviceLock) {
            serviceEpoch++
            scope.cancel()
            llmService = null
        }
        // A cancelled generation's finally still posts setState, which lands after deactivate()
        // returned; without these a disabled plugin reaches into the host toolbar.
        editorService = null
        uiService = null
        endCapture()
    }

    /**
     * Stops the capture machinery. The pending recovery work is dropped first, so a queued
     * retry cannot build a recognizer for a plugin that is going away, and the state is reset
     * because nothing else will: a teardown mid-recovery would otherwise leave the toolbar
     * spinning on a capture that no longer exists.
     *
     * Bumping [recognitionAttempt] covers what the token cannot reach: a support answer or a
     * recognizer callback already queued on the main looper is not a tokened post, so the only
     * thing that can still stop it is failing the attempt check it carries.
     */
    private fun endCapture() {
        recognitionAttempt++
        mainHandler.removeCallbacksAndMessages(recoveryToken)
        destroyRecognizer()
        destroySupportRecognizer()
        destroyPackRecognizer()
        recordingState = RecordingState.IDLE
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

        // Refuse a second tap mid-generation until the capture goes stale; one while RECORDING
        // instead restarts the capture, since nothing else bounds a recognizer that never settles.
        if (recordingState == RecordingState.PROCESSING &&
            SystemClock.elapsedRealtime() - captureStartedAt < STALE_CAPTURE_MS
        ) {
            logger?.info("Ignoring the tap: a capture is already $recordingState")
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

        // A tap the guard above let through means the previous capture is over, wedged, or
        // still listening, so drop what it left behind: a queued retry would build a recognizer
        // for a capture that is gone, and its generation would insert into the middle of this one.
        mainHandler.removeCallbacksAndMessages(recoveryToken)
        generationJob?.cancel()
        captureId++
        offlineFallbackSpent = false
        onlineRetrySpent = false
        captureStartedAt = SystemClock.elapsedRealtime()
        val requested = recognitionLocale()
        requestedLocale = requested
        if (recordingState == RecordingState.RECORDING) {
            // This tap replaces a live session, so let the platform settle for RESTART_DELAY_MS
            // first, for the reason restartRecognition records. The attempt is retired with it,
            // or the ERROR_CLIENT the dying recognizer reports would end the capture replacing it.
            recognitionAttempt++
            destroyRecognizer()
            mainHandler.postDelayed(
                { guardRecovery { beginListening(requested) } },
                recoveryToken,
                RESTART_DELAY_MS,
            )
            return
        }
        beginListening(requested)
    }

    /** The first attempt of a capture: on-device, and announced as the prompt to speak. */
    private fun beginListening(locale: Locale) = beginRecognition(
        preferOffline = true,
        announcement = str(R.string.stt_listening),
        locale = locale,
    )

    /**
     * Runs one recognition attempt. Pre-flight checks live in [startVoiceCapture]; the
     * language fallbacks re-enter here with another locale or with [preferOffline] false.
     *
     * @param preferOffline true to ask for on-device recognition, false to force the network one
     * @param announcement toast shown once the attempt is about to start listening
     * @param locale language to recognize
     */
    private fun beginRecognition(
        preferOffline: Boolean,
        announcement: String,
        locale: Locale,
    ) {
        val ctx = hostContext()
        // Bumped before anything below can throw: a construction that fails still has to retire
        // the attempt it replaces, or that recognizer's dying ERROR_CLIENT toasts on top of the
        // stt_start_failed the catch already showed.
        val attempt = ++recognitionAttempt
        try {
            destroyRecognizer()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
            // Its own listener, carrying this attempt's id, so the attempt just torn down can
            // no longer speak for the capture.
            recognizer.setRecognitionListener(recognitionListener(attempt))
            speechRecognizer = recognizer

            activeLocale = locale
            toast(announcement)
            recognizer.startListening(recognitionIntent(locale, preferOffline))
            setState(RecordingState.RECORDING)
        } catch (e: Exception) {
            logger?.error("Failed to start speech recognition", e)
            toast(str(R.string.stt_start_failed))
            destroyRecognizer()
            setState(RecordingState.IDLE)
        }
    }

    /**
     * The recognition request, also used to ask about and to download language support so all
     * three questions are asked about the same language.
     *
     * @param locale language to recognize, named so a pack failure reports the language we asked
     *   for rather than whatever the recognizer happened to default to
     * @param preferOffline true to ask for on-device recognition, false to force the network one
     */
    private fun recognitionIntent(locale: Locale, preferOffline: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        }

    /**
     * The listener for one attempt. It is built per attempt rather than shared because a
     * callback carries no session of its own: the first terminal callback of the current
     * attempt wins, and a repeat or a late one from a replaced attempt is dropped.
     *
     * @param attempt the [recognitionAttempt] value this recognizer was started with
     */
    private fun recognitionListener(attempt: Long) = object : RecognitionListener {
        private var settled = false

        /** True for the one terminal callback this attempt is allowed to act on. */
        private fun claim(): Boolean {
            if (settled || attempt != recognitionAttempt) return false
            settled = true
            return true
        }

        override fun onResults(results: Bundle?) {
            if (!claim()) return
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
            // Restart the stale-capture clock here: STALE_CAPTURE_MS covers one generation, and
            // timing it from the tap would spend that budget on the listening phase as well.
            captureStartedAt = SystemClock.elapsedRealtime()
            setState(RecordingState.PROCESSING)
            handleTranscript(transcript)
        }

        override fun onError(error: Int) {
            if (!claim()) return
            destroyRecognizer()
            // A language the recognizer can't serve is recoverable, so spend a recovery
            // strategy rather than ending the dictation session.
            val isLanguageError = error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
            if (isLanguageError && recoverFromLanguageError(error)) return
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
     * Spends the next recovery strategy on a language the recognizer rejected: the on-device
     * options first, then the network. The two are budgeted separately because an offline
     * fallback that is refused in turn still has the network left, and the network is the only
     * recovery that can serve the words already spoken.
     *
     * @param error the recognizer's language error code, for the log
     * @return true when a retry was started, false when the budget is spent and the caller
     *   should report the error to the user
     */
    private fun recoverFromLanguageError(error: Int): Boolean {
        if (!offlineFallbackSpent) {
            offlineFallbackSpent = true
            logger?.info(
                "Recognizer rejected ${activeLocale.toLanguageTag()} (error $error) - " +
                    "asking which languages it does support"
            )
            setState(RecordingState.PROCESSING)
            guardRecovery {
                // checkRecognitionSupport arrived in API 33. The loader gates on
                // plugin.min_ide_version alone and never on a plugin's own minSdk, so the IDE
                // can still run us below 33; that device gets the network retry directly.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    checkLanguageSupport()
                } else {
                    retryOnline()
                }
            }
            return true
        }
        if (!onlineRetrySpent) {
            logger?.info(
                "The offline fallback ${activeLocale.toLanguageTag()} was rejected too " +
                    "(error $error) - trying the network"
            )
            setState(RecordingState.PROCESSING)
            guardRecovery { retryOnline() }
            return true
        }
        return false
    }

    /**
     * Runs one step of the language recovery. The expected failures are handled inside each
     * step, which retries; this is for the rest. A step reaches into the device's speech
     * service, so it can fail with something that is not an [Exception] - an OEM framework can
     * throw a linkage or execution error - and every step runs either from a recognizer
     * callback or from a handler post, where letting that out would take the IDE down and leave
     * the toolbar spinning on a capture that is over. So: end the capture, and say so.
     *
     * [VirtualMachineError] is rethrown: the process is already lost and pretending otherwise
     * only hides it.
     */
    private fun guardRecovery(step: () -> Unit) {
        try {
            step()
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            logger?.error("Language recovery failed", e)
            destroyRecognizer()
            destroySupportRecognizer()
            toast(str(R.string.stt_recovery_failed))
            setState(RecordingState.IDLE)
        }
    }

    /**
     * Asks the recognizer which languages it actually has, because the error code alone can't
     * say: code 13 means "no pack for this language" whether the pack is one download away or
     * the language is unsupported outright.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun checkLanguageSupport() {
        val locale = activeLocale
        // The support answer arrives on the main looper untokened, so it carries this instead.
        val attempt = recognitionAttempt
        val recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(hostContext())
        } catch (e: Exception) {
            logger?.warn("Could not create a recognizer to check language support", e)
            retryOnline()
            return
        }
        destroySupportRecognizer()
        supportRecognizer = recognizer

        // Speech Services by Google answers one query with an error and then the same result
        // twice, and each answer used to start its own recognizer. First answer wins. Every
        // release below names this recognizer: `handled` is a local that teardown cannot set, so
        // a callback that outlived its capture must not destroy the one now in the field.
        var handled = false
        val giveUp = Runnable {
            if (!handled) {
                handled = true
                destroySupportRecognizer(recognizer)
                logger?.info("No support answer in ${SUPPORT_TIMEOUT_MS}ms - retrying online")
                guardRecovery { retryOnline() }
            }
        }

        try {
            recognizer.checkRecognitionSupport(
                recognitionIntent(locale, preferOffline = true),
                ContextCompat.getMainExecutor(hostContext()),
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        if (handled) return
                        handled = true
                        mainHandler.removeCallbacks(giveUp)
                        destroySupportRecognizer(recognizer)
                        // Teardown and the next tap both bump the attempt; this is where a
                        // result that outlived its capture is dropped, since neither can
                        // cancel a callback the executor has already queued.
                        if (attempt != recognitionAttempt) {
                            logger?.info("Dropping a support answer for a capture that is over")
                            return
                        }
                        logger?.info(
                            "Support for ${locale.toLanguageTag()}: " +
                                "installed=${support.installedOnDeviceLanguages}, " +
                                "downloadable=${support.supportedOnDeviceLanguages}, " +
                                "pending=${support.pendingOnDeviceLanguages}, " +
                                "online=${support.onlineLanguages}"
                        )
                        guardRecovery { recoverFromSupport(support) }
                    }

                    override fun onError(code: Int) {
                        // Advisory, not terminal: Speech Services by Google reports 14
                        // (ERROR_CANNOT_CHECK_SUPPORT) and then answers anyway, so let [giveUp]
                        // decide when to stop waiting rather than the code decide it here.
                        logger?.info("Support check reported error $code")
                    }
                },
            )
            mainHandler.postDelayed(giveUp, recoveryToken, SUPPORT_TIMEOUT_MS)
        } catch (e: Exception) {
            handled = true
            destroySupportRecognizer(recognizer)
            logger?.warn("checkRecognitionSupport is not usable here", e)
            retryOnline()
        }
    }

    /**
     * Applies the best recovery [support] allows: an installed pack for the same language is
     * used at once, and a missing one is requested for next time while this capture carries on
     * over the network, which is the only recovery that can serve the words already spoken.
     *
     * @param support what the recognizer reported for [activeLocale]
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun recoverFromSupport(support: RecognitionSupport) {
        val locale = activeLocale
        val requested = languageLabel()

        // Skip the requested tag: it is the one that just errored, so a pack the recognizer
        // lists as installed for it is missing or corrupt and would fail the retry the same way.
        val installed = usableTag(support.installedOnDeviceLanguages, locale, skipRequested = true)
        if (installed != null) {
            val fallback = Locale.forLanguageTag(installed)
            logger?.info("Retrying offline with the installed pack $installed")
            restartRecognition(
                preferOffline = true,
                announcement = str(
                    R.string.stt_language_using_installed,
                    requested,
                    fallback.displayName,
                ),
                locale = fallback,
            )
            return
        }

        val missing = usableTag(support.supportedOnDeviceLanguages, locale)
            ?: usableTag(support.pendingOnDeviceLanguages, locale)
        if (missing != null) {
            requestLanguagePack(Locale.forLanguageTag(missing))
            restartRecognition(
                preferOffline = false,
                announcement = str(R.string.stt_language_downloading_retrying, requested),
                locale = locale,
            )
            return
        }

        retryOnline()
    }

    /**
     * Asks the recognizer to fetch the missing pack for next time. Best effort by contract and
     * unobservable in practice - the device this was written against still reported `pending=[]`
     * 20 s after two requests - so nothing is promised to the user and nothing waits on it.
     *
     * @param locale pack to fetch, built from the normalized tag [usableTag] returned
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestLanguagePack(locale: Locale) {
        val recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(hostContext())
        } catch (e: Exception) {
            logger?.warn("Could not create a recognizer to request the pack", e)
            return
        }
        destroyPackRecognizer()
        packRecognizer = recognizer
        try {
            recognizer.triggerModelDownload(recognitionIntent(locale, preferOffline = true))
            logger?.info("Requested pack download for ${locale.toLanguageTag()}")
        } catch (e: Exception) {
            logger?.warn("Could not request the pack download", e)
            destroyPackRecognizer(recognizer)
            return
        }
        // triggerModelDownload only queues the request: the recognizer binds to the service
        // first and drops everything still queued when it is destroyed, so destroying it in
        // this same tick cancelled the download we just asked for. Hold it a moment instead.
        // Untokened: the next tap clears recoveryToken, which would drop this release and
        // orphan the recognizer. The expected check makes it a no-op once the field moved on.
        mainHandler.postDelayed(
            { destroyPackRecognizer(recognizer) },
            PACK_REQUEST_HOLD_MS,
        )
    }

    /**
     * The network recognizer, tried once the on-device options are exhausted. It is never gated
     * on [RecognitionSupport.getOnlineLanguages], which this service reports empty even though
     * its network path works; a language it really cannot serve fails on the next error instead.
     *
     * It asks for [requestedLocale], not [activeLocale]: this runs after an offline fallback may
     * have moved the latter to a region picked for a pack that then failed, and the announcement
     * names the requested language either way.
     */
    private fun retryOnline() {
        restartRecognition(
            preferOffline = false,
            announcement = str(R.string.stt_error_language_retrying, languageLabel()),
            locale = requestedLocale,
        )
    }

    /**
     * Starts the next attempt off the current callback and after the failed session is torn
     * down: restarting straight from [RecognitionListener.onError] reached the platform while
     * it still held the old session open, and failed within milliseconds.
     */
    private fun restartRecognition(preferOffline: Boolean, announcement: String, locale: Locale) {
        // Recorded here so the budget reflects what was actually attempted, whichever route
        // through the recovery picked this attempt.
        if (preferOffline) offlineFallbackSpent = true else onlineRetrySpent = true
        mainHandler.postDelayed(
            { guardRecovery { beginRecognition(preferOffline, announcement, locale) } },
            recoveryToken,
            RESTART_DELAY_MS,
        )
    }

    /**
     * Finds the entry in [tags] that can recognize [locale]: the same tag when it is there, and
     * otherwise another region of the same language, which still understands the user (an es-US
     * pack transcribes es-ES speech).
     *
     * @param skipRequested drops [locale]'s own tag from the candidates, for the caller that
     *   has already watched it fail; that makes the exact match below moot, so such a caller
     *   is always answered by the region fallback
     * @return the tag normalized, so callers can hand it to [Locale.forLanguageTag], or null
     *   when the language is absent
     */
    private fun usableTag(
        tags: List<String>?,
        locale: Locale,
        skipRequested: Boolean = false,
    ): String? {
        if (tags.isNullOrEmpty()) return null
        val wanted = normalizeTag(locale.toLanguageTag())
        // Taken from the tag, not from Locale.getLanguage(), which still answers with the
        // legacy ISO-639 codes (iw, in, ji) that no recognizer lists.
        val language = wanted.substringBefore('-')
        val normalized = tags.map(::normalizeTag)
        val candidates = if (skipRequested) normalized.filterNot { it == wanted } else normalized
        return candidates.firstOrNull { it == wanted }
            ?: candidates.firstOrNull { it.substringBefore('-') == language }
    }

    /**
     * Services spell tags inconsistently (`es_ES`, `es-es`), so normalize before comparing them
     * or building a [Locale]: `Locale.forLanguageTag` reads an underscore as ill-formed and
     * answers `und`, which would put an undetermined language in the retry intent.
     */
    private fun normalizeTag(tag: String): String = tag.replace('_', '-').lowercase(Locale.ROOT)

    /**
     * Takes the recognized text, optionally runs it through the LLM to produce
     * code, and inserts the result at the cursor.
     */
    private fun handleTranscript(transcript: String) {
        logger?.debug("Transcript received (${transcript.length} chars)")
        // Read here: onResults is a main-thread callback, and the IO dispatcher below is not.
        val language = currentLanguageId()
        val capture = captureId
        generationJob = scope.launch {
            try {
                // Resolved once per transcript: AI Core may have finished activating after we did.
                val service = resolveLlmService()
                // Generate code when AI Core is present; fall back to the raw transcript so speech is never dropped.
                val generated = service?.let { generateCodeFromVoice(it, transcript, language) }
                val generationFailed = service != null && generated == null
                val output = generated ?: transcript
                withContext(Dispatchers.Main) {
                    // A generation that outlived its capture has nothing left to say: the user
                    // has moved the cursor on and a newer capture owns the toolbar.
                    if (capture != captureId) {
                        logger?.info("Dropped a result from a capture that is already over")
                        return@withContext
                    }
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
                    if (capture == captureId && recordingState == RecordingState.PROCESSING) {
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
            // It bounds the await, not the call that starts the request: a backend that blocks in
            // generateCompletion itself runs past it, and the capture id checked before insertion
            // is what keeps that generation out of the capture that replaced it.
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
                response.text?.let { stripCodeFences(it, language) }?.takeIf { it.isNotBlank() }
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
     * @param language the open file's language id, which decides whether an unfenced sentence is
     *   prose to drop or content to keep
     * @return the fenced block's body, the code of an unfenced reply, or empty when the reply
     *   held no code at all
     */
    private fun stripCodeFences(raw: String, language: String): String {
        val lines = raw.trim().lines()
        // The fence can open on any line: a model often writes "Here is the code:" first.
        val opening = lines.indexOfFirst { it.trimStart().startsWith(FENCE) }
        if (opening < 0) return dropProse(lines, language)

        // The opening line can carry code after its info string whether or not it also closes.
        val afterFence = lines[opening].trim().removePrefix(FENCE)
        val closesInline = afterFence.endsWith(FENCE)
        val opener = afterFence.removeSuffix(FENCE).trim()
        // An opening line that does not close carries an info string, never code - unless the
        // model put code there anyway, which is what [isInfoString] tells apart.
        val firstCodeLine =
            if (!closesInline && isInfoString(opener)) "" else stripLanguageInfo(opener)

        val rest = lines.drop(opening + 1)
        val closing = rest.indexOfFirst { it.trimStart().startsWith(FENCE) }
        // An inline close ends the block on the opening line, so what follows is the model's own
        // explanation. An unclosed fence (a reply truncated at maxTokens) keeps what did arrive.
        val body = when {
            closesInline -> emptyList()
            closing >= 0 -> rest.take(closing)
            else -> rest
        }

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
     * Skipped for the languages whose real content is sentence-shaped. [currentLanguageId] feeds
     * the open file's own id into the prompt, so a Markdown or YAML request comes back as the
     * prose this would delete - `## Setup` and `Description: ...` are the file, not framing.
     * Elsewhere a drop is logged, because losing a line silently reads as a short answer from
     * the model rather than as an edit made here.
     *
     * @param lines the trimmed reply, split into lines
     * @param language the open file's language id
     * @return the reply without its prose framing, or empty when it holds nothing else
     */
    private fun dropProse(lines: List<String>, language: String): String {
        if (language.lowercase() in PROSE_LANGUAGES) return lines.joinToString("\n").trim()

        val kept = lines.dropWhile { it.isBlank() || isProse(it.trim()) }
            .dropLastWhile { it.isBlank() || isProse(it.trim()) }
        if (kept.size != lines.size) {
            logger?.info("Dropped ${lines.size - kept.size} prose line(s) from an unfenced reply")
        }
        return kept.joinToString("\n").trim()
    }

    /**
     * Tells an info string from code a model wrote on the opening fence line. Markdown says
     * everything after the fence is the info string, and F18's `kotlin fun main() {` is why this
     * cannot simply be believed, so the shape decides: a lone token names a language, and
     * `{1,3}` or `title="Foo.kt"` after one are attributes. Anything else is kept as code.
     *
     * @param opener the opening fence line, fence markers removed and trimmed
     * @return true when the line carries no code
     */
    private fun isInfoString(opener: String): Boolean {
        val tokens = opener.split(' ', '\t').filter { it.isNotBlank() }
        if (tokens.size <= 1) return true
        val attributes = tokens.drop(1)
        // A brace group may hold spaces of its own, so it is matched across the whole remainder.
        if (attributes.first().startsWith('{') && attributes.last().endsWith('}')) return true
        return attributes.all {
            '=' in it || it.startsWith('.') || it.startsWith('#') ||
                it.startsWith('{') || it.endsWith('}')
        }
    }

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
     * Releases the short-lived recognizer used for support checks.
     *
     * @param expected when given, does nothing unless this is still the recognizer being held,
     *   so a delayed release cannot destroy the one a later capture has since put in its place
     */
    private fun destroySupportRecognizer(expected: SpeechRecognizer? = null) {
        supportRecognizer = release(supportRecognizer, expected, "support")
    }

    /**
     * Releases the recognizer holding a pack download request.
     *
     * @param expected as for [destroySupportRecognizer]
     */
    private fun destroyPackRecognizer(expected: SpeechRecognizer? = null) {
        packRecognizer = release(packRecognizer, expected, "pack download")
    }

    /**
     * Destroys [held] and answers what its field should hold afterwards. The destroy is posted,
     * so this can be called from inside that recognizer's own callback without tearing it down
     * mid-dispatch, and [held] is kept when [expected] shows the field has already moved on.
     */
    private fun release(
        held: SpeechRecognizer?,
        expected: SpeechRecognizer?,
        role: String,
    ): SpeechRecognizer? {
        if (held == null) return null
        if (expected != null && expected !== held) return held
        mainHandler.post {
            try {
                held.destroy()
            } catch (e: Exception) {
                logger?.warn("Failed to destroy the $role SpeechRecognizer", e)
            }
        }
        return null
    }

    /**
     * Maps a [SpeechRecognizer] error code to a full, user-facing message that says
     * what went wrong and what the user can do about it.
     */
    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> str(R.string.stt_error_audio)
        SpeechRecognizer.ERROR_CLIENT -> str(R.string.stt_error_client)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> str(R.string.stt_error_permissions)
        // Error 12/13 without these two branches is what surfaced as "unknown error 12". They
        // reach here only once recoverFromLanguageError has spent both strategies, so the
        // _unavailable wording can state that the network was tried too.
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            str(R.string.stt_error_language_not_supported, languageLabel())
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            str(R.string.stt_error_language_unavailable, languageLabel())
        SpeechRecognizer.ERROR_NETWORK -> str(R.string.stt_error_network)
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> str(R.string.stt_error_network_timeout)
        SpeechRecognizer.ERROR_NO_MATCH -> str(R.string.stt_error_no_match)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> str(R.string.stt_error_busy)
        SpeechRecognizer.ERROR_SERVER -> str(R.string.stt_error_server)
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> str(R.string.stt_error_speech_timeout)
        else -> str(R.string.stt_error_unknown, error)
    }

    /**
     * The language recognition should run in: the host's configured locale, which honours a
     * per-app language the process-wide default would miss. Its Unicode `-u-` extensions - the
     * user's regional preferences - are dropped, or they ride into the tags and into every toast.
     */
    private fun recognitionLocale(): Locale = try {
        val configured = hostContext().resources.configuration.locales
            .takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
        Locale.Builder().setLocale(configured).clearExtensions().build()
    } catch (e: Exception) {
        logger?.warn("Could not read the host locale", e)
        Locale.getDefault()
    }

    /**
     * Names the language the user asked for, pairing the display name with the BCP-47 tag they
     * will see in Android's on-device recognition settings. Deliberately not [activeLocale]: a
     * fallback moves that to a region the user never chose and cannot be told to go install.
     *
     * @return e.g. "English (United States) (en-US)"
     */
    private fun languageLabel(): String {
        val locale = requestedLocale
        val display = locale.displayName.takeIf { it.isNotBlank() } ?: locale.toLanguageTag()
        return "$display (${locale.toLanguageTag()})"
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
            mainHandler.post(block)
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

        /** Lets the failed session finish tearing down before the next attempt starts. */
        private const val RESTART_DELAY_MS = 400L

        /**
         * How long to wait for a support answer before falling back to the network. Generous
         * because the service has to bind first and a cold bind can take seconds: too short and
         * the installed-pack and download paths below would almost never run. The cost of the
         * wait is only that a device with no answer reaches the network retry later.
         */
        private const val SUPPORT_TIMEOUT_MS = 4_000L

        /** How long a pack-download request keeps its recognizer alive so the request survives. */
        private const val PACK_REQUEST_HOLD_MS = 5_000L

        /**
         * Slack over one whole generation, so the guard never expires before what it guards.
         * It covers the generation alone: [captureStartedAt] is restarted when the capture
         * reaches PROCESSING, so the listening phase does not eat into it.
         */
        private const val STALE_CAPTURE_HEADROOM_MS = 15_000L

        /**
         * A capture idle this long is assumed dead, so the microphone button works again.
         * Derived from [GENERATION_TIMEOUT_SECONDS] because a guard that expires first lets a
         * second capture start while the first generation is still on its way to the cursor.
         */
        private const val STALE_CAPTURE_MS =
            GENERATION_TIMEOUT_SECONDS * MILLIS_PER_SECOND + STALE_CAPTURE_HEADROOM_MS

        /** Used only when the host cannot name the open file's language. */
        private const val DEFAULT_LANGUAGE = "kotlin"

        /** Ends a sentence; a code line that reaches one of these also holds code punctuation. */
        private const val SENTENCE_TERMINATORS = ".:!?"

        /** Marks a line as code however sentence-like it otherwise reads. */
        private const val CODE_PUNCTUATION = "(){}[];=<>"

        /** Three words or more, matching the sibling's isPreamble; shorter lines stay. */
        private const val MIN_PROSE_SPACES = 2

        /**
         * Host language ids whose files are sentence-shaped, so prose filtering would delete the
         * answer instead of its framing. Matched against [currentLanguageId], which is the
         * editor's id (`md`, `txt`), not the fence tag the model writes.
         */
        private val PROSE_LANGUAGES = setOf(
            "markdown", "md", "text", "plaintext", "txt", "yaml", "yml", "properties",
        )

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
