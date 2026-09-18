package com.itsaky.androidide.plugins.aiagentgemini.settings

import java.io.IOException

/**
 * What a live check of a Gemini API key established.
 *
 * [Rejected] is a confirmed verdict from Google and blocks the save; [Unreachable] and [Unknown]
 * establish nothing, so collapsing them together would save bad keys or block offline setup.
 */
sealed interface KeyVerification {

    /**
     * Google accepted the key and returned [modelCount] chat-capable models.
     *
     * [modelCount] proves the catalog was non-empty and is logged, but stays out of the UI: the
     * user saved a key, not asked for a catalog.
     */
    data class Verified(val modelCount: Int) : KeyVerification

    /**
     * Google accepted the key but is rate-limiting (HTTP 429). The credential is valid; the quota
     * is not. Treated as confirmed on purpose — calling this "rejected" would send users off to
     * mint a second key that behaves identically.
     */
    data object RateLimited : KeyVerification

    /** Google refused the request (any 4xx bar 429). The only state that blocks a save. */
    data object Rejected : KeyVerification

    /** The request never got an answer — no network, DNS failure, timeout, or a 5xx from Google. */
    data object Unreachable : KeyVerification

    /** Nothing could be checked: ai-core or the Gemini backend absent, or the contract broke. */
    data object Unknown : KeyVerification

    /**
     * True when Google confirmed the key. This is the save rule in one place: a key is written to
     * disk only when this is true, or when the user explicitly overrides an *inconclusive* check.
     */
    val isConfirmedValid: Boolean
        get() = this is Verified || this is RateLimited
}

/**
 * Interpret a catalog lookup as a verdict on the key that produced it.
 *
 * Pure: no Android framework state and no logging of its own — the failure itself is already
 * reported by the catalog gateway — so every row of the mapping is unit-testable
 * without a device or a live backend.
 */
fun CatalogResult.toKeyVerification(): KeyVerification = when (this) {
    is CatalogResult.Success ->
        // A valid key always lists something; zero models is unchecked, not a pass.
        if (models.isEmpty()) KeyVerification.Unknown else KeyVerification.Verified(models.size)

    CatalogResult.NoBackend -> KeyVerification.Unknown

    is CatalogResult.Failed -> classifyFailure(cause)
}

/**
 * Map a lookup failure onto a verdict, using the HTTP status the backend embeds in its
 * `ListModels HTTP <code>: <body>` message. Any 4xx is the client's fault and rejects the key;
 * with no status at all, an [IOException] is transport trouble and anything else is unchecked.
 */
private fun classifyFailure(cause: Throwable): KeyVerification =
    when (failureStatusOf(cause)) {
        null -> if (cause is IOException) KeyVerification.Unreachable else KeyVerification.Unknown
        // Ordered before the 4xx range: a throttled key is valid, and must not read as refused.
        429 -> KeyVerification.RateLimited
        in 400..499 -> KeyVerification.Rejected
        // Google's fault, not the key's: a 5xx says nothing about the credential.
        in 500..599 -> KeyVerification.Unreachable
        // A status outside 4xx/5xx on a failure says nothing: unchecked, never rejected.
        else -> KeyVerification.Unknown
    }

/**
 * Matches the status in the backend's `ListModels HTTP 403: {...}` failure message.
 *
 * Anchored on the whole prefix, not a bare `HTTP \d{3}`: Google's error body is appended to that
 * message, and a looser pattern could read a verdict on the key out of server-supplied text.
 */
private val LIST_MODELS_FAILURE_STATUS = Regex("""ListModels HTTP (\d{3})""")

/** Depth cap: a malformed cause chain can be self-referential, and this runs on user input. */
private const val MAX_CAUSE_DEPTH = 5

/** First failure status found walking [cause] and its causes, or null when there is none. */
private fun failureStatusOf(cause: Throwable): Int? {
    var current: Throwable? = cause
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        LIST_MODELS_FAILURE_STATUS.find(current.message.orEmpty())
            ?.let { return it.groupValues[1].toIntOrNull() }
        current = current.cause
        depth++
    }
    return null
}
