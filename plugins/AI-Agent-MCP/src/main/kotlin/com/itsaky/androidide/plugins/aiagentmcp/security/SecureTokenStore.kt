package com.itsaky.androidide.plugins.aiagentmcp.security

import com.itsaky.androidide.plugins.security.KeystoreSecretStore

/** Unique to this plugin and fixed across releases; see [KeystoreSecretStore] for why both matter. */
private const val ALIAS = "cotg_ai_mcp_token_v1"

/**
 * This plugin's binding of [KeystoreSecretStore]: the bearer tokens and extra headers of configured
 * MCP servers, encrypted under this plugin's own Keystore alias.
 *
 * The store is the IDE's, from plugin-api, and callers use it directly. A forwarding object per
 * method would only be a second copy of its contract to keep in step — the thing this file owns is
 * the alias.
 */
val secureTokenStore = KeystoreSecretStore(ALIAS)
