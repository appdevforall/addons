package com.itsaky.androidide.plugins.aiagentclaude.settings

import com.itsaky.androidide.plugins.aiagentclaude.errors.ClaudeErrorFormatter
import com.itsaky.androidide.plugins.aiagentclaude.errors.ClaudeHttpException
import java.io.IOException

/**
 * What a live check against the Claude API established.
 *
 * [Rejected] is a confirmed refusal and blocks a key save; everything else establishes less than
 * that, and collapsing them together would either save bad keys or refuse a good one because the
 * network was down.
 */
sealed interface ConnectionVerification {

    /** The API accepted the key and offers [modelCount] models. */
    data class Verified(val modelCount: Int) : ConnectionVerification

    /**
     * The API accepted the key but listed no models. The key works; the account has nothing it
     * may use yet.
     */
    data object NoModels : ConnectionVerification

    /**
     * The API accepted the key but is rate-limiting (HTTP 429).
     *
     * Treated as confirmed on purpose — calling this "rejected" would send users off to mint a
     * second key that behaves identically.
     */
    data object RateLimited : ConnectionVerification

    /** The API refused the key (HTTP 401/403). Blocks a save. */
    data object Rejected : ConnectionVerification

    /**
     * The key belongs to no workspace, so every request with it is a 400. Blocks a save too: the
     * key is genuine, but chat could never use it, and saving it anyway only moves the failure.
     */
    data object NeedsWorkspace : ConnectionVerification

    /** Nothing answered, or the API is overloaded or failing (no network, DNS, 5xx, 529). */
    data object Unreachable : ConnectionVerification

    /** Nothing could be checked: the backend was not resolvable, or the failure was unrecognised. */
    data object Unknown : ConnectionVerification

    /**
     * True when the API confirmed the key works. This is the save rule in one place: a key is
     * written only when this is true, or when the user overrides an *inconclusive* check.
     */
    val isConfirmedValid: Boolean
        get() = this is Verified || this is RateLimited || this is NoModels
}

/**
 * Interpret a catalog lookup as a verdict on the key that produced it.
 *
 * Pure: no Android state and no logging of its own — the gateway already reported the failure — so
 * every row of the mapping is unit-testable without a device or a live API.
 */
internal fun CatalogResult.toConnectionVerification(): ConnectionVerification = when (this) {
    is CatalogResult.Success ->
        if (models.isEmpty()) {
            ConnectionVerification.NoModels
        } else {
            ConnectionVerification.Verified(models.size)
        }

    CatalogResult.NoBackend -> ConnectionVerification.Unknown

    is CatalogResult.Failed -> classifyFailure(cause)
}

/** Map a lookup failure onto a verdict using the status the transport reports as a field. */
private fun classifyFailure(cause: Throwable): ConnectionVerification =
    when (failureStatusOf(cause)) {
        null -> if (cause is IOException) {
            ConnectionVerification.Unreachable
        } else {
            ConnectionVerification.Unknown
        }
        // Ordered before the other 4xx: a throttled key is valid, and must not read as refused.
        429 -> ConnectionVerification.RateLimited
        401, 403 -> ConnectionVerification.Rejected
        400 -> if (needsWorkspace(cause)) ConnectionVerification.NeedsWorkspace else ConnectionVerification.Unknown
        // The API's fault, not the key's: an overload or a 5xx says nothing about the credential.
        in 500..599 -> ConnectionVerification.Unreachable
        else -> ConnectionVerification.Unknown
    }

/** Whether the 400 in [cause]'s chain is the API asking for a workspace-scoped key. */
private fun needsWorkspace(cause: Throwable): Boolean {
    val http = generateSequence(cause) { it.cause }.take(MAX_CAUSE_DEPTH)
        .filterIsInstance<ClaudeHttpException>().firstOrNull() ?: return false
    return with(ClaudeErrorFormatter) { parse(http.message).needsWorkspace() }
}

/** Depth cap: a malformed cause chain can be self-referential, and this runs on user input. */
private const val MAX_CAUSE_DEPTH = 5

/**
 * First status found walking [cause] and its causes, or null when no HTTP answer was involved.
 *
 * Reads [ClaudeHttpException.statusCode], never message text: a status matched out of a formatted
 * message made a log line's wording a contract, and the API's own error body — which that message
 * carries — could forge one.
 */
private fun failureStatusOf(cause: Throwable): Int? {
    var current: Throwable? = cause
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        (current as? ClaudeHttpException)?.let { return it.statusCode }
        current = current.cause
        depth++
    }
    return null
}
