package com.itsaky.androidide.plugins.aicore.logging

/**
 * Prefix on every logcat tag this plugin writes, so a line names the plugin that emitted it — every
 * AI feature shares the host IDE's process, where a bare `ToolRouter` tag names no `.cgp`. Tags read
 * `"$LOG_PREFIX.ClassName"`. `logcat -s` matches a tag exactly, so the whole plugin log is
 * `adb logcat | grep AiCore.`; one class is `adb logcat -s AiCore.ChatFragment:V`, and the whole
 * agent run on one stream is `adb logcat -s AiCore.AgentTrace:V`.
 */
internal const val LOG_PREFIX = "AiCore"
