package com.itsaky.androidide.plugins.aiagentlocal.logging

/**
 * Prefix on every logcat tag this plugin writes, so a line names the plugin that emitted it — every
 * AI feature shares the host IDE's process, where a bare `GgufFileInspector` tag names no `.cgp`.
 * Tags read `"$LOG_PREFIX.ClassName"`, so `adb logcat -s AiAgentLocal.*` is this plugin's whole log.
 */
internal const val LOG_PREFIX = "AiAgentLocal"
