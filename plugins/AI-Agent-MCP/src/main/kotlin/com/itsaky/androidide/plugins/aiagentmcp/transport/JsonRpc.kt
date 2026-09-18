package com.itsaky.androidide.plugins.aiagentmcp.transport

import org.json.JSONObject

/**
 * JSON-RPC 2.0 framing, which is all MCP puts on the wire.
 *
 * Pure and separate from the socket work, so the framing — where a mis-shaped envelope silently
 * becomes "the server returned nothing" — is unit-testable without a server.
 */
object JsonRpc {

    private const val VERSION = "2.0"

    /** JSON-RPC's own code for a method the server does not implement. */
    const val METHOD_NOT_FOUND = -32601

    /**
     * One reply, already unwrapped.
     * @property id the request id it answers, or null for a malformed envelope.
     * @property result the result object, or null when the reply is an error.
     * @property errorCode the JSON-RPC error code, or null on success.
     * @property errorMessage the server's error text, or null on success.
     */
    data class Reply(
        val id: String?,
        val result: JSONObject?,
        val errorCode: Int? = null,
        val errorMessage: String? = null,
    ) {
        val isError: Boolean get() = errorCode != null
    }

    /**
     * Builds a request envelope.
     * @param id this call's id, echoed by the server.
     * @param method the MCP method, e.g. `tools/list`.
     * @param params the method's parameters, or null for none.
     * @return the envelope to POST.
     */
    fun request(id: String, method: String, params: JSONObject? = null): JSONObject =
        JSONObject().apply {
            put("jsonrpc", VERSION)
            put("id", id)
            put("method", method)
            params?.let { put("params", it) }
        }

    /**
     * Builds a notification envelope, which carries no id and expects no reply.
     * @param method the MCP method, e.g. `notifications/initialized`.
     * @param params the method's parameters, or null for none.
     * @return the envelope to POST.
     */
    fun notification(method: String, params: JSONObject? = null): JSONObject =
        JSONObject().apply {
            put("jsonrpc", VERSION)
            put("method", method)
            params?.let { put("params", it) }
        }

    /**
     * Reads a reply envelope.
     *
     * A batch — which the spec allows and some servers send even for a single request — is reduced
     * to its first non-notification member, since this client only ever has one call in flight.
     *
     * @param payload one JSON-RPC document, object or array.
     * @return the reply, or null when the payload is not a reply at all (a server-initiated
     *   request or notification, which this client does not answer).
     */
    fun parseReply(payload: String): Reply? {
        val trimmed = payload.trim()
        val json = when {
            trimmed.startsWith("{") -> JSONObject(trimmed)
            trimmed.startsWith("[") -> org.json.JSONArray(trimmed).let { array ->
                (0 until array.length())
                    .mapNotNull { array.optJSONObject(it) }
                    .firstOrNull { it.has("result") || it.has("error") }
                    ?: return null
            }
            else -> throw IllegalArgumentException("not a JSON-RPC document")
        }

        if (!json.has("result") && !json.has("error")) return null

        val id = when {
            json.isNull("id") -> null
            else -> json.get("id").toString()
        }

        // Branching on the member rather than on the cast succeeding: a server that answers
        // `"error": "boom"` still failed, and reading it as an absent error would report an empty
        // success — the model would be told the tool ran and produced nothing.
        if (json.has("error") && !json.isNull("error")) {
            val error = json.optJSONObject("error")
            // A non-object error carries its own text; an object with no `message` has none worth
            // showing, and its JSON is not a sentence.
            val detail = when (error) {
                null -> json.get("error").toString()
                else -> error.optString("message")
            }
            return Reply(
                id = id,
                result = null,
                errorCode = error?.optInt("code", 0) ?: 0,
                errorMessage = detail.takeIf { it.isNotBlank() }
                    ?: "the server reported an unspecified error",
            )
        }

        // `"error": null` beside a result is common and means no error; on its own it is not a
        // reply at all, and reporting it as one would hand the caller an empty success.
        if (!json.has("result")) return null

        return Reply(id = id, result = json.optJSONObject("result") ?: JSONObject())
    }
}
