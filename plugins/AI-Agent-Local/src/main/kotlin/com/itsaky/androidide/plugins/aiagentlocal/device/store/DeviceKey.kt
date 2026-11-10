package com.itsaky.androidide.plugins.aiagentlocal.device.store

import java.security.MessageDigest

/**
 * The device key (plan 3.1): lower-case hex sha256 of `<Build.FINGERPRINT>|<engine id>|<schema version>`, the same text
 * the descriptor generator hashes for `run.device_key`. It decides whether stored rows belong to this device and
 * this engine.
 */
object DeviceKey {
    /** Schema version of the descriptor (`DescriptorGenerator.SCHEMA_VERSION`). */
    const val SCHEMA_VERSION = "0.2"

    fun of(fingerprint: String, engineId: String): String {
        val text = "$fingerprint|$engineId|$SCHEMA_VERSION"
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private val KEY = Regex("[0-9a-f]{64}")

    fun isKey(s: String) = KEY.matches(s)
}
