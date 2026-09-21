package com.itsaky.androidide.plugins.aiagentmcp.security

import java.io.IOException

/**
 * A stored credential that this device's Keystore can no longer open.
 *
 * Its own type rather than an empty value, because the two lead to opposite advice: a request sent
 * without a credential earns a 401, which reads to the user as "the server refused your token" —
 * about a token that is still stored and still correct. The only useful thing to say is that the
 * stored copy can no longer be read here and has to be entered again.
 *
 * @param detail what could not be read, for logcat; the user sees the formatted sentence instead.
 */
class UnreadableSecretException(detail: String) : IOException(detail)
