package com.itsaky.androidide.plugins.aiagentgemini.security

import com.itsaky.androidide.plugins.security.KeystoreSecretStore

/** Unique to this plugin and fixed across releases; see [KeystoreSecretStore] for why both matter. */
private const val ALIAS = "cotg_ai_gemini_key_v1"

/**
 * This plugin's binding of [KeystoreSecretStore]: its API key, encrypted under this plugin's own
 * Keystore alias.
 *
 * The store is the IDE's, from plugin-api, and callers use it directly. A forwarding object per
 * method would only be a second copy of its contract to keep in step — and one that had to pick a
 * single answer for "absent" and "no longer decryptable", which callers here do not share. The
 * thing this file owns is the alias.
 */
val secureApiKeyStore = KeystoreSecretStore(ALIAS)
