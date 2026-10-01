package com.itsaky.androidide.plugins.aicore.prompt

import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.SystemPromptRequest

/**
 * What system-prompt assembly needs from the active backend, and nothing else.
 *
 * Narrow on purpose: [SystemPromptFactory] depends on these two questions rather than on the whole
 * inference service, which is what lets it be tested with a fake instead of a live backend.
 */
interface BackendPrompts {

    /**
     * Whether the backend carries tool calls in its provider's own function-calling API rather than
     * in the reply text.
     *
     * Decides both halves of the protocol at once — the schemas sent with the request and the
     * envelope the prompt teaches — so the two can never disagree about which one is live.
     *
     * @return true when the backend declares [LlmInferenceService.ToolCallingBackend].
     */
    fun callsToolsNatively(): Boolean

    /**
     * The backend's own system prompt.
     *
     * @param request the tool list, envelope syntax and example path to build it from.
     * @return the prompt, or null when the backend has none, is unreachable, or throws.
     */
    fun systemPrompt(request: SystemPromptRequest): String?
}

/**
 * [BackendPrompts] answered by the live inference service.
 *
 * Every question is asked of the backend resolved at call time, because the user can switch
 * backends between runs. A backend that throws answers null rather than propagating: one bad
 * `.cgp` must degrade to the default prompt, not break every message.
 *
 * @param backendId the backend the current run is for.
 * @param getService supplies the inference service, or null when it is unavailable.
 * @param logWarn records a backend that could not answer.
 */
class ServiceBackendPrompts(
    private val backendId: () -> String,
    private val getService: () -> LlmInferenceService?,
    private val logWarn: (String, Throwable) -> Unit,
) : BackendPrompts {

    override fun callsToolsNatively(): Boolean =
        backend() is LlmInferenceService.ToolCallingBackend

    override fun systemPrompt(request: SystemPromptRequest): String? {
        val backend = backend() ?: return null
        return try {
            backend.getSystemPrompt(request)?.takeIf { it.isNotBlank() }
        } catch (e: Throwable) {
            logWarn("backend '${backendId()}' supplied no system prompt; using the default", e)
            null
        }
    }

    /**
     * Resolves the active backend.
     *
     * @return the backend, or null when it cannot be reached.
     */
    private fun backend(): LlmInferenceService.LlmBackend? = try {
        getService()?.getBackend(backendId())
    } catch (e: Throwable) {
        logWarn("could not resolve backend '${backendId()}'", e)
        null
    }
}
