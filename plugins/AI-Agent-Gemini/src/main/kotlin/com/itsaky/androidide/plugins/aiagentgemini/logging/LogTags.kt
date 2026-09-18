package com.itsaky.androidide.plugins.aiagentgemini.logging

/**
 * Prefix on every logcat tag this plugin writes, so a line names the plugin that emitted it — every
 * AI feature shares the host IDE's process, where a bare `SecureApiKeyStore` tag names no `.cgp`.
 * Tags read `"$LOG_PREFIX.ClassName"`, so `adb logcat -s AiAgentGemini.*` is this plugin's log.
 */
internal const val LOG_PREFIX = "AiAgentGemini"
