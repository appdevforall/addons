package com.itsaky.androidide.plugins.aiagentlocal.feedback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.itsaky.androidide.plugins.aiagentlocal.model.ModelLoadDiagnostics

/**
 * Surfaces actionable LLM errors to the user as a Toast, from anywhere in the process.
 *
 * This backend serves every AI plugin through ai-core's router: chat, code-suggestions and
 * code-review all funnel their generation through it. When something is misconfigured (e.g. no
 * model selected), each consumer
 * would otherwise fail silently in its own way — code-suggestions in particular retries on every
 * keystroke, so the same error can arrive many times per second. This helper is the single place
 * that turns those into one visible, throttled message for the user.
 *
 * Throttling is keyed by message text: an identical message shown within [COOLDOWN_MS] is
 * suppressed, so typing doesn't stack a wall of toasts while still re-notifying later if the
 * problem persists after the user has had a chance to act.
 */
object UserFeedback {

    private const val COOLDOWN_MS = 10_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastMessage: String? = null
    @Volatile private var lastShownAt = 0L

    /**
     * Shows [message] as a long Toast, unless the identical message was already shown within the
     * cooldown window. Safe to call from any thread.
     */
    fun notify(context: Context, message: String) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (message == lastMessage && now - lastShownAt < COOLDOWN_MS) return
            lastMessage = message
            lastShownAt = now
        }
        val appContext = context.applicationContext
        mainHandler.post {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * Base type for LLM failures the user can act on; backends catch this one type to route it to
 * [UserFeedback], so a new actionable subtype surfaces without touching the catch sites.
 * @param message user-facing, display-ready text
 */
sealed class UserActionableLlmException(message: String) : IllegalStateException(message)

/**
 * Thrown when the selected model is the wrong kind for the request (e.g. an embedding model for
 * chat, which would abort native inference). See ADFA-4388.
 * @param message user-facing, display-ready text
 */
class IncompatibleModelException(message: String) : UserActionableLlmException(message)

/**
 * Thrown when the native loader rejects the selected .gguf.
 *
 * [message] is display-ready text because the exception crosses the plugin boundary, where
 * consumers cannot see [ModelLoadDiagnostics.Diagnosis]; [diagnosis] carries the structured cause
 * so callers inside this plugin can branch on it or render it their own way instead of parsing
 * the text.
 * @param message user-facing, display-ready text (see [ModelLoadMessages])
 * @param diagnosis the classified cause, or null if the failure wasn't classified
 */
class ModelLoadException(
    message: String,
    val diagnosis: ModelLoadDiagnostics.Diagnosis? = null,
) : UserActionableLlmException(message)
