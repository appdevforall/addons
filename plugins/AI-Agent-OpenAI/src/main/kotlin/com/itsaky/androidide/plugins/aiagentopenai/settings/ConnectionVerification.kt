package com.itsaky.androidide.plugins.aiagentopenai.settings

import com.itsaky.androidide.plugins.aiagentopenai.errors.OpenAiHttpException
import java.io.IOException

/**
 * What a live check against the configured server established.
 *
 * [Rejected] is a confirmed refusal and blocks a key save; everything else establishes less than
 * that, and collapsing them together would either save bad keys or block a perfectly good local
 * server that simply does not implement `/v1/models`.
 */
sealed interface ConnectionVerification {

    /** The server answered and offers [modelCount] plausible chat models. */
    data class Verified(val modelCount: Int) : ConnectionVerification

    /**
     * The server answered but has no models to offer.
     *
     * Its own state because it is actionable and common: an Ollama install with nothing pulled
     * yet. The URL and credential are fine; there is just nothing to run.
     */
    data object NoModels : ConnectionVerification

    /**
     * The server accepted the credential but is rate-limiting (HTTP 429).
     *
     * Treated as confirmed on purpose — calling this "rejected" would send users off to mint a
     * second key that behaves identically.
     */
    data object RateLimited : ConnectionVerification

    /** The server refused the credential (HTTP 401/403). The only state that blocks a save. */
    data object Rejected : ConnectionVerification

    /**
     * The server answered 404, so it is running but the path is wrong.
     *
     * Almost always a base URL missing its `/v1` suffix, which is the most common setup mistake
     * for every compatible server — hence a distinct verdict with distinct advice.
     */
    data object EndpointNotFound : ConnectionVerification

    /** Nothing answered — no network, nothing listening on that port, DNS failure, or a 5xx. */
    data object Unreachable : ConnectionVerification

    /** Nothing could be checked: the backend was not resolvable, or the failure was unrecognised. */
    data object Unknown : ConnectionVerification

    /**
     * True when the server confirmed the credential works. This is the save rule in one place: a
     * key is written only when this is true, or when the user overrides an *inconclusive* check.
     */
    val isConfirmedValid: Boolean
        get() = this is Verified || this is RateLimited
}

/**
 * Interpret a catalog lookup as a verdict on the server and credential that produced it.
 *
 * Pure: no Android state and no logging of its own — the gateway already reported the failure — so
 * every row of the mapping is unit-testable without a device or a live server.
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

/**
 * Map a lookup failure onto a verdict using the status the transport reports as a field.
 *
 * Note 404 does **not** reject: a compatible server that lacks `/v1/models` answers 404 with a
 * perfectly good key, and rejecting there would make it unconfigurable.
 */
private fun classifyFailure(cause: Throwable): ConnectionVerification =
    when (val status = failureStatusOf(cause)) {
        null -> if (cause is IOException) {
            ConnectionVerification.Unreachable
        } else {
            ConnectionVerification.Unknown
        }
        // Ordered before the 4xx range: a throttled key is valid, and must not read as refused.
        429 -> ConnectionVerification.RateLimited
        404 -> ConnectionVerification.EndpointNotFound
        401, 403 -> ConnectionVerification.Rejected
        // The server's fault, not the credential's: a 5xx says nothing about the key.
        in 500..599 -> ConnectionVerification.Unreachable
        // Any other 4xx is the client's fault, but not necessarily the key's.
        in 400..499 -> if (status == 400) {
            ConnectionVerification.Unknown
        } else {
            ConnectionVerification.Rejected
        }
        else -> ConnectionVerification.Unknown
    }

/** Depth cap: a malformed cause chain can be self-referential, and this runs on user input. */
private const val MAX_CAUSE_DEPTH = 5

/**
 * First status found walking [cause] and its causes, or null when no HTTP answer was involved.
 *
 * Reads [OpenAiHttpException.statusCode], never message text: a status matched out of a formatted
 * message made a log line's wording a contract, and the server's own error body — which that
 * message carries — could forge one.
 */
private fun failureStatusOf(cause: Throwable): Int? {
    var current: Throwable? = cause
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        (current as? OpenAiHttpException)?.let { return it.statusCode }
        current = current.cause
        depth++
    }
    return null
}
