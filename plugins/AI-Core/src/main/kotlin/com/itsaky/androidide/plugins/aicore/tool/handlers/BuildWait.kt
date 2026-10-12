package com.itsaky.androidide.plugins.aicore.tool.handlers

// Shared by every handler that waits on a build, so run_app and run_gradle_task give up together.

/** How long to wait for a build to finish before reporting it still running. */
internal const val BUILD_TIMEOUT_MS = 10 * 60 * 1000L

/**
 * How often to log that the wait is still alive. A build can hold the agent for ten minutes, and
 * without a heartbeat that stretch of logcat is indistinguishable from a hung agent.
 */
internal const val BUILD_PROGRESS_LOG_INTERVAL_MS = 30 * 1000L
