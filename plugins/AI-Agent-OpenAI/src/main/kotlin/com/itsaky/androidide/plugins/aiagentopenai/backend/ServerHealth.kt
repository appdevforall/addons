package com.itsaky.androidide.plugins.aiagentopenai.backend

import com.itsaky.androidide.plugins.services.CapabilityStatus

/** Why a server check failed; each is different advice for the user. */
internal enum class ServerProblem {
    /** Nothing answered: the server is not running, or this device cannot reach it. */
    UNREACHABLE,

    /** The server answered and refused the stored key. */
    KEY_REFUSED,

    /** The server answered with a 5xx. */
    SERVER_ERROR,
}

/**
 * One check of one server.
 *
 * @property baseUrl the server checked, so a reading never describes a server the setting has left.
 * @property problem why it is not available, or null when it is.
 * @property httpStatus the status a [ServerProblem.SERVER_ERROR] answered with.
 */
internal data class HealthReading(
    val baseUrl: String,
    val status: CapabilityStatus,
    val problem: ServerProblem? = null,
    val httpStatus: Int? = null,
) {
    companion object {
        fun available(baseUrl: String) = HealthReading(baseUrl, CapabilityStatus.AVAILABLE)

        fun unreachable(baseUrl: String) =
            HealthReading(baseUrl, CapabilityStatus.DEGRADED, ServerProblem.UNREACHABLE)

        /**
         * Reads a non-2xx answer to `GET /models`. Any answer at all means the server is up, so
         * only a refused key and a server-side failure count against it; a 404 is just a server
         * that does not implement the catalog, which many compatible ones do not.
         */
        fun ofHttpStatus(baseUrl: String, code: Int): HealthReading = when {
            code == 401 || code == 403 ->
                HealthReading(baseUrl, CapabilityStatus.DEGRADED, ServerProblem.KEY_REFUSED)
            code >= 500 ->
                HealthReading(baseUrl, CapabilityStatus.DEGRADED, ServerProblem.SERVER_ERROR, code)
            else -> available(baseUrl)
        }
    }
}

/**
 * The last thing learned about the configured server, for the Agent's backend tag.
 *
 * Read on the UI thread, written from checks and requests on IO, so it only ever holds one
 * immutable reading.
 *
 * @param onChange told after a reading that differs from the last, so the tag re-reads.
 */
internal class ServerHealth(private val onChange: () -> Unit) {

    @Volatile
    private var last: HealthReading? = null

    /**
     * @param baseUrl the server the setting names now.
     * @return its reading; a server not checked yet is [CapabilityStatus.CONNECTING].
     */
    fun readingFor(baseUrl: String): HealthReading =
        last?.takeIf { it.baseUrl == baseUrl } ?: HealthReading(baseUrl, CapabilityStatus.CONNECTING)

    /** Stores [reading], telling [onChange] only when it differs from the last. */
    fun record(reading: HealthReading) {
        val previous = last
        last = reading
        if (previous != reading) onChange()
    }
}
