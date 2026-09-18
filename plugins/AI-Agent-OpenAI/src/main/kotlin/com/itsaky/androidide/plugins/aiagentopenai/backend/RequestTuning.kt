package com.itsaky.androidide.plugins.aiagentopenai.backend

import com.itsaky.androidide.plugins.aiagentopenai.errors.OpenAiErrorFormatter

/**
 * Which optional parameters a `chat/completions` request should carry.
 *
 * Reasoning models reject `max_tokens` and several reject `temperature`, while older and
 * third-party servers only understand `max_tokens` — so this is a per-request decision, not a
 * constant. Pure, so every rule is unit-testable without a network.
 *
 * @param tokenParam the JSON key carrying the output-token cap
 * @param sendTemperature false to omit `temperature` entirely
 */
internal data class RequestTuning(
    val tokenParam: String,
    val sendTemperature: Boolean,
) {

    /**
     * The same tuning with [param] no longer sent, for the retry after a server rejected it.
     *
     * `max_completion_tokens` degrades to `max_tokens` rather than dropping the cap, because a
     * server that refuses the new name is an older or third-party one that wants the old name.
     *
     * @param param the parameter the server named in its 400
     * @return the adjusted tuning, or null when nothing about [param] can be changed
     */
    fun without(param: String): RequestTuning? = when (param) {
        TEMPERATURE -> if (sendTemperature) copy(sendTemperature = false) else null
        MAX_COMPLETION_TOKENS ->
            if (tokenParam == MAX_COMPLETION_TOKENS) copy(tokenParam = MAX_TOKENS) else null
        MAX_TOKENS ->
            if (tokenParam == MAX_TOKENS) copy(tokenParam = MAX_COMPLETION_TOKENS) else null
        else -> null
    }

    companion object {
        const val TEMPERATURE = "temperature"
        const val MAX_TOKENS = "max_tokens"
        const val MAX_COMPLETION_TOKENS = "max_completion_tokens"

        /**
         * Model id prefixes whose models are reasoning models on `chat/completions`.
         *
         * Matched on the id's leading segment, so a vendor-prefixed OpenRouter id such as
         * `openai/gpt-5.1` is recognised too.
         */
        private val REASONING_PREFIXES = listOf("gpt-5", "o1", "o3", "o4")

        /**
         * The tuning to start with for [model] on [baseUrl].
         *
         * OpenAI's own endpoint gets the modern `max_completion_tokens`; any other server gets
         * `max_tokens`, which is what Ollama, LM Studio and llama-server implement. Either way an
         * unsupported-parameter 400 is recovered from by [without], so this only has to be right
         * often enough to avoid a wasted round trip.
         *
         * @param model the model id as configured
         * @param requiresApiKey true when [baseUrl] is OpenAI's own API — see `BaseUrlPolicy`
         */
        fun forModel(model: String, requiresApiKey: Boolean): RequestTuning = RequestTuning(
            tokenParam = if (requiresApiKey) MAX_COMPLETION_TOKENS else MAX_TOKENS,
            sendTemperature = !isReasoningModel(model),
        )

        /**
         * True when [model] names a reasoning model, which may reject `temperature`.
         *
         * Conservative by design: a false negative costs one retry, while a false positive would
         * silently ignore the user's temperature on an ordinary model.
         */
        fun isReasoningModel(model: String): Boolean {
            val id = model.trim().lowercase().substringAfterLast('/')
            return REASONING_PREFIXES.any { prefix ->
                // Guards against `o1ntel-chat`: a prefix match must end the id or a segment.
                id == prefix || id.startsWith("$prefix-") || id.startsWith("$prefix.")
            }
        }
    }
}

/**
 * Finds the parameter an OpenAI-compatible server refused, so the request can be retried without
 * it. Pure; the server bodies it reads are the ones a 400 carries.
 */
internal object UnsupportedParameter {

    /** Parameters worth retrying without; anything else is a real request error. */
    private val ADJUSTABLE = listOf(
        RequestTuning.MAX_COMPLETION_TOKENS,
        RequestTuning.MAX_TOKENS,
        RequestTuning.TEMPERATURE,
    )

    /**
     * Reads OpenAI's `error.param`, falling back to naming a parameter found in the message text.
     *
     * Both paths are gated on wording that says the parameter is unsupported, so a server
     * complaining about a *value* does not trigger a pointless retry.
     *
     * @param body the response body of a 400
     * @return the parameter to stop sending, or null when the failure is not about one
     */
    fun nameIn(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val text = body.lowercase()
        if (!saysUnsupported(text)) return null

        // The structured field is authoritative when the server supplies one.
        paramField(body)?.let { param -> if (param in ADJUSTABLE) return param }

        // Earliest mention, not longest match: OpenAI's own wording names the offender first and
        // the replacement second ("'max_tokens' is not supported… Use 'max_completion_tokens'").
        return ADJUSTABLE
            .mapNotNull { param -> text.indexOf(param).takeIf { it >= 0 }?.let { it to param } }
            .minByOrNull { it.first }
            ?.second
    }

    /** The server's own `error.param`, lowercased, or null when there is no parseable one. */
    private fun paramField(body: String): String? =
        OpenAiErrorFormatter.errorObjectIn(body)?.optString("param")
            ?.takeIf { it.isNotBlank() }?.lowercase()

}

/**
 * Whether a server refused the request over the `tools` declaration itself.
 *
 * Its own detector rather than another [UnsupportedParameter] entry: dropping `tools` changes which
 * protocol the run uses, so it is recovered from once per server instead of once per request.
 */
internal object UnsupportedTools {

    /** Statuses a compatible server uses to refuse a parameter outright. */
    private val REFUSAL_STATUSES = setOf(400, 404, 422)

    /** Request fields that only exist to carry tools, in either the modern or legacy spelling. */
    private val TOOL_FIELDS = listOf("tools", "tool_choice", "functions", "function_call")

    /**
     * True when [body] says this server will not take a tool declaration.
     *
     * A rejected *schema* lands here too and degrades the same way, which is a turn that still
     * answers rather than a lost one — see `OpenAiBackend.withToolRetry` for what that costs.
     *
     * @param statusCode the HTTP status the server answered with
     * @param body the response body of that failure
     * @return true to retry the request with no tools declared
     */
    fun rejectedIn(statusCode: Int, body: String?): Boolean {
        if (statusCode !in REFUSAL_STATUSES) return false
        if (body.isNullOrBlank()) return false
        val text = body.lowercase()
        if (!saysUnsupported(text)) return false
        return TOOL_FIELDS.any { text.contains(it) }
    }
}

/**
 * True when an error body says a request field is not accepted, rather than that its value is bad.
 *
 * @param text the response body, lowercased
 */
private fun saysUnsupported(text: String): Boolean = listOf(
    "unsupported", "not supported", "unrecognized", "unknown", "unexpected",
    "is not permitted", "instead", "deprecated", "extra inputs",
).any { text.contains(it) }
