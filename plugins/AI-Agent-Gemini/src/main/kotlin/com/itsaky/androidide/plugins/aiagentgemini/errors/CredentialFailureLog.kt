package com.itsaky.androidide.plugins.aiagentgemini.errors

import android.content.SharedPreferences
import com.itsaky.androidide.plugins.aiagentgemini.preferences.GeminiPreferences

/**
 * Why the last request was refused for credential reasons, kept for the settings pane to report.
 *
 * A refused request is otherwise only legible in the transcript, which the user has already left
 * by the time they open the pane to fix the key — so the one screen that can fix it said nothing
 * (ADFA-5491). Holds the classified failure, never the credential and never a rendered sentence.
 *
 * @param prefs this plugin's settings store; re-read on every call, and null before `initialize()`
 */
internal class CredentialFailureLog(private val prefs: () -> SharedPreferences?) {

    /**
     * Records the credential problem [failure] describes, replacing any earlier one. A failure
     * about anything else is not recorded — [CredentialFailure.of] is the same test the caller's
     * [isCredentialProblem] applies, so this drops nothing a caller meant to record.
     *
     * @param keyStamp when the key the refused request carried was saved, taken where the request
     *   read that key rather than here: a refusal can land after the user has already saved a
     *   replacement, and this stamp is what keeps it from being reported against the new key
     */
    fun record(failure: GeminiFailure, keyStamp: Long) {
        val credential = CredentialFailure.of(failure) ?: return
        prefs()?.edit()
            ?.putString(GeminiPreferences.KEY_CREDENTIAL_FAILURE, credential.tag)
            ?.putLong(GeminiPreferences.KEY_CREDENTIAL_FAILURE_KEY_STAMP, keyStamp)
            ?.apply()
    }

    /**
     * The recorded failure, or null unless it describes the credential that is on disk now.
     *
     * Equal stamps mean nothing was saved or cleared between the refused request reading the key
     * and now, so a refusal that landed after either is dropped rather than reported.
     */
    fun read(): CredentialFailure? {
        val prefs = prefs() ?: return null
        val tag = prefs.getString(GeminiPreferences.KEY_CREDENTIAL_FAILURE, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val keyStamp = prefs.getLong(GeminiPreferences.KEY_CREDENTIAL_FAILURE_KEY_STAMP, 0L)
        // Clear removes the key's timestamp too, so "not older than" would keep a keyless refusal.
        if (keyStamp != prefs.getLong(GeminiPreferences.KEY_API_KEY_TIMESTAMP, 0L)) return null
        return CredentialFailure.ofTag(tag)
    }

    /** Forgets the reason: the credential changed, or a request has since gone through on it. */
    fun clear() {
        prefs()?.edit()
            ?.remove(GeminiPreferences.KEY_CREDENTIAL_FAILURE)
            ?.remove(GeminiPreferences.KEY_CREDENTIAL_FAILURE_KEY_STAMP)
            ?.apply()
    }
}
