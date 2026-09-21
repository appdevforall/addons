package com.itsaky.androidide.plugins.aiagentopenai.settings

/**
 * Outcome of normalizing a base URL the user typed.
 *
 * A closed hierarchy so a caller cannot treat "rejected" and "accepted, but cleartext" alike — the
 * second is a warning the user may proceed through, the first must block the save.
 */
sealed interface BaseUrlResult {

    /**
     * The URL is usable. [url] is the normalized form that should be stored.
     *
     * @param cleartext true when the URL is plain `http`, so the caller can warn once on save
     * @param loopback true when the host is this device, where cleartext carries no LAN exposure
     */
    data class Accepted(
        val url: String,
        val cleartext: Boolean,
        val loopback: Boolean,
    ) : BaseUrlResult

    /** The URL cannot be used. [reason] says which rule it broke. */
    data class Rejected(val reason: Reason) : BaseUrlResult

    /** Why a URL was refused. Carries no text; the wording lives in `strings.xml`. */
    enum class Reason {
        /** Nothing was entered. */
        BLANK,

        /** Not a `http`/`https` URL at all, or unparseable. */
        MALFORMED,

        /** Parsed, but carries no host — `http://` or `https:///v1`. */
        NO_HOST,

        /** Plain `http` to a host that is neither loopback nor a private LAN range. */
        CLEARTEXT_PUBLIC,
    }
}

/**
 * Whether the configured server needs an API key, as far as the URL can tell.
 *
 * Three states, not a boolean, because the settings pane has three things to say: demand a key,
 * expect one, or tell the user plainly that none is needed. A boolean forced the pane to show the
 * same mandatory-looking field for a local Ollama as for OpenAI.
 */
enum class KeyRequirement {
    /** OpenAI's own API: no anonymous access, so the backend is unusable without a key. */
    REQUIRED,

    /** Another server on the internet — OpenRouter, Groq. Usually needs a key; only it knows. */
    EXPECTED,

    /** Loopback or a private address: Ollama, LM Studio and llama-server want no credential. */
    NOT_NEEDED,
}

/**
 * Normalizes and vets the OpenAI-compatible base URL.
 *
 * Pure and free of Android types, so every rule below is unit-testable without a device. The
 * policy: `https` anywhere, `http` only to loopback or a private range (ADFA-3017 §4.6).
 */
internal object BaseUrlPolicy {

    /** Default server, so an untouched install is the plain ChatGPT case ADFA-3017 asked for. */
    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

    /** Host that means "OpenAI itself", which is the only case where a key is mandatory. */
    private const val OPENAI_HOST = "api.openai.com"

    /**
     * Path suffixes a user pastes from OpenAI's docs instead of the base URL. Stripped so
     * `.../v1/chat/completions` does not become `.../v1/chat/completions/chat/completions`.
     */
    private val PASTED_ENDPOINT_SUFFIXES = listOf(
        "/chat/completions",
        "/completions",
        "/models",
    )

    /** Matches `scheme://host[:port][/path]`, the only shape this backend can call. */
    private val URL_SHAPE = Regex("""^(https?)://([^/?#\s]*)([^?#\s]*)$""", RegexOption.IGNORE_CASE)

    /** Matches an IPv4 address, so its octets can be tested against the private ranges. */
    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /**
     * Normalizes [input] and applies the cleartext rule.
     *
     * Trims, drops a trailing slash and a pasted endpoint path, then decides whether plain `http`
     * is acceptable for that host.
     *
     * @param input the URL as typed
     * @return [BaseUrlResult.Accepted] carrying the form to store, or the rule it broke
     */
    fun normalize(input: String?): BaseUrlResult {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isEmpty()) return BaseUrlResult.Rejected(BaseUrlResult.Reason.BLANK)

        val match = URL_SHAPE.matchEntire(trimmed)
            ?: return BaseUrlResult.Rejected(BaseUrlResult.Reason.MALFORMED)

        val scheme = match.groupValues[1].lowercase()
        val authority = match.groupValues[2]
        if (authority.isBlank()) return BaseUrlResult.Rejected(BaseUrlResult.Reason.NO_HOST)

        val host = hostOf(authority)
        if (host.isBlank()) return BaseUrlResult.Rejected(BaseUrlResult.Reason.NO_HOST)

        val path = match.groupValues[3].trimEnd('/').let(::stripPastedEndpoint)
        val cleartext = scheme == "http"
        val loopback = isLoopback(host)
        if (cleartext && !loopback && !isPrivateRange(host)) {
            return BaseUrlResult.Rejected(BaseUrlResult.Reason.CLEARTEXT_PUBLIC)
        }

        return BaseUrlResult.Accepted(
            url = "$scheme://${authority.lowercase()}$path",
            cleartext = cleartext,
            loopback = loopback,
        )
    }

    /**
     * True when [a] and [b] name the same server — same scheme, host and port.
     *
     * The path is ignored: `/v1` versus `/v1/` is the same endpoint, and a credential is issued by
     * an origin rather than by a path.
     *
     * @return false when either URL is unusable, so an unparseable value never authorises a send
     */
    fun sameOrigin(a: String?, b: String?): Boolean {
        val first = (normalize(a) as? BaseUrlResult.Accepted)?.url ?: return false
        val second = (normalize(b) as? BaseUrlResult.Accepted)?.url ?: return false
        return originOf(first) == originOf(second)
    }

    /** The `scheme://authority` part of an already-normalized URL. */
    private fun originOf(url: String): String {
        val scheme = url.substringBefore("://")
        val authority = url.substringAfter("://").substringBefore('/')
        return "$scheme://$authority"
    }

    /**
     * True when [url] points at OpenAI's own API, which has no anonymous access.
     *
     * This is the whole of the "when is a key mandatory" rule: everywhere else — Ollama, LM Studio,
     * llama-server — a key is optional, and demanding one there is the ADFA-3452 regression.
     */
    fun requiresApiKey(url: String?): Boolean =
        keyRequirement(url) == KeyRequirement.REQUIRED

    /**
     * How the settings pane should present the key field for [url].
     *
     * Derived from the URL alone, so it updates the moment the user picks a preset — no request is
     * made. An unusable URL yields [KeyRequirement.REQUIRED], failing closed.
     */
    fun keyRequirement(url: String?): KeyRequirement {
        val accepted = normalize(url) as? BaseUrlResult.Accepted
            ?: return KeyRequirement.REQUIRED
        val host = hostOf(accepted.url.substringAfter("://"))
        return when {
            host.equals(OPENAI_HOST, ignoreCase = true) -> KeyRequirement.REQUIRED
            // Loopback and LAN servers are the ones that run unauthenticated by default.
            accepted.loopback || isPrivateRange(host) -> KeyRequirement.NOT_NEEDED
            else -> KeyRequirement.EXPECTED
        }
    }

    /**
     * Host part of an `host[:port]` authority, with any IPv6 brackets unwrapped.
     *
     * Drops a trailing path too, so callers may hand it either a bare authority or the
     * `host/path` remainder of a full URL.
     */
    private fun hostOf(authority: String): String {
        val withoutPath = authority.substringBefore('/')
        val withoutUserInfo = withoutPath.substringAfterLast('@')
        if (withoutUserInfo.startsWith("[")) {
            return withoutUserInfo.substringBefore(']').removePrefix("[")
        }
        return withoutUserInfo.substringBefore(':')
    }

    /** Drops an endpoint path the user pasted instead of the base URL. */
    private fun stripPastedEndpoint(path: String): String {
        for (suffix in PASTED_ENDPOINT_SUFFIXES) {
            if (path.endsWith(suffix, ignoreCase = true)) {
                return path.dropLast(suffix.length)
            }
        }
        return path
    }

    /** True for this device's own addresses, where cleartext never leaves the machine. */
    private fun isLoopback(host: String): Boolean =
        host.equals("localhost", ignoreCase = true) ||
            host == "::1" ||
            host.startsWith("127.")

    /**
     * True for the RFC 1918 / RFC 4193 ranges plus link-local, i.e. a LAN box.
     *
     * Cleartext here still crosses the local network, which is why the caller warns; it is allowed
     * because that is exactly the "my PC runs Ollama" case ADFA-3452 was filed for.
     */
    private fun isPrivateRange(host: String): Boolean {
        // Android's emulator maps the developer machine to these, so they behave as LAN hosts.
        if (host == "10.0.2.2" || host == "10.0.3.2") return true
        // Unique-local and link-local IPv6.
        if (host.startsWith("fd", ignoreCase = true) || host.startsWith("fe80:", ignoreCase = true)) {
            return true
        }
        // A bare hostname (no dots) is a LAN name such as `raspberrypi` or a Termux-local alias.
        if (!host.contains('.') && !host.contains(':')) return true

        val octets = IPV4.matchEntire(host)?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: -1 }
            ?: return false
        if (octets.any { it !in 0..255 }) return false
        val (first, second) = octets
        return when {
            first == 10 -> true
            first == 192 && second == 168 -> true
            first == 172 && second in 16..31 -> true
            first == 169 && second == 254 -> true
            else -> false
        }
    }
}
