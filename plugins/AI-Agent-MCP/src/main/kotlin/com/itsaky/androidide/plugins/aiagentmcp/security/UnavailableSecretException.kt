package com.itsaky.androidide.plugins.aiagentmcp.security

import java.io.IOException

/**
 * A stored credential this device's Keystore would not open just now.
 *
 * Its own type rather than an [UnreadableSecretException], because the two lead to opposite advice:
 * the credential here is very likely intact — the Keystore was not ready, or a binder call failed —
 * and the only useful thing to say is to try again, not to enter the credential over.
 *
 * @param detail what could not be read, for logcat; the user sees the formatted sentence instead.
 */
class UnavailableSecretException(detail: String) : IOException(detail)
