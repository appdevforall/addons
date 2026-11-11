package org.appdevforall.localstore.server

import java.security.SecureRandom

/** The owner's choices for the Wi-Fi Direct hotspot's name and password, checked against Android's rules. */
object HotspotSettings {

    /** Android requires "DIRECT-" and two letters or digits; "LS" marks it as Local Store's. */
    const val PREFIX = "DIRECT-LS-"
    /** Android's limit for the whole name, in UTF-8 bytes. */
    const val MAX_NAME_BYTES = 32

    fun name(suffix: String) = PREFIX + suffix.trim()

    /** Why [suffix] can't be used, or null if it can. */
    fun nameProblem(suffix: String): String? {
        val s = suffix.trim()
        val room = MAX_NAME_BYTES - PREFIX.length
        return when {
            s.isEmpty() -> "Enter something after $PREFIX."
            s.any { it.isISOControl() } -> "The name can't contain control characters."
            name(s).toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES ->
                "That's too long: after $PREFIX there's room for $room bytes (accented letters and emoji take more than one)."
            else -> null
        }
    }

    /** Why [password] can't be used, or null if it can: 8–63 ordinary keyboard characters, as Wi-Fi requires. */
    fun passwordProblem(password: String): String? = when {
        password.length < 8 -> "The password needs at least 8 characters."
        password.length > 63 -> "The password can be at most 63 characters."
        password.any { it.code !in 32..126 } -> "Use only letters, digits, spaces and ordinary punctuation."
        else -> null
    }

    /** 16 characters, leaving out 0/O and 1/l/I, since buyers may type it. */
    fun randomPassword(): String {
        val chars = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        return (1..16).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }
}
