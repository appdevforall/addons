package com.itsaky.androidide.plugins.aiagentmcp.transport

import com.itsaky.androidide.plugins.net.NetworkLocality
import com.itsaky.androidide.plugins.net.ServerUrl
import com.itsaky.androidide.plugins.net.ServerUrlParseResult

/**
 * Which endpoint URLs this plugin will talk to.
 *
 * Cleartext only to this device: an `http://` request to anything else crosses a network the user
 * does not control, and every request carries the session id and any credential. Judged on the URL
 * as typed, never on a DNS answer, so a name that resolves to `127.0.0.1` still counts as remote.
 */
object McpUrlPolicy {

    /** What [verdict] made of a URL. */
    sealed interface Verdict {

        /** Safe to connect to. */
        data object Usable : Verdict

        /** Not a URL the host's parser accepts; [reason] says how. */
        data class Invalid(val reason: ServerUrlParseResult.Invalid.Reason) : Verdict

        /** A well-formed `http://` URL to a server that is not on this device. */
        data object CleartextOffDevice : Verdict
    }

    /**
     * Judges [url] without any network I/O, so it is safe on the main thread.
     * @param url the endpoint as the user typed it.
     * @return the verdict.
     */
    fun verdict(url: String): Verdict = when (val parsed = ServerUrl.parse(url)) {
        is ServerUrlParseResult.Invalid -> Verdict.Invalid(parsed.reason)
        is ServerUrlParseResult.Valid -> parsed.url.let {
            if (it.isCleartext && it.locality != NetworkLocality.LOOPBACK) Verdict.CleartextOffDevice
            else Verdict.Usable
        }
    }
}
