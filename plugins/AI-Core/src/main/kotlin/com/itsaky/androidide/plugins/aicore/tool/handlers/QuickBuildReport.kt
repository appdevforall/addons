package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.services.QuickBuildAvailability
import com.itsaky.androidide.plugins.services.QuickBuildResult
import com.itsaky.androidide.plugins.services.QuickBuildUnavailableReason
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/**
 * What the model reads back from `quick_build`: the host's availability and result, each turned into
 * a [ToolResult] that says what happened and what to call next. Pure, so every outcome is testable.
 */
internal object QuickBuildReport {

    /** The result for a project where Quick Build cannot start, with the host's reason and the way round it. */
    fun unavailable(availability: QuickBuildAvailability.Unavailable): ToolResult =
        ToolResult.failure(
            "Quick Build is not available: ${availability.message}",
            "Quick Build did not start. ${adviceFor(availability.reason)}"
        )

    /** The result for a Quick Build the host would refuse because another build is running. */
    val BUILD_IN_PROGRESS: ToolResult = ToolResult.failure(
        "Quick Build did not start: a build is already in progress",
        "No build ran. Wait for the running build to finish, then call quick_build again."
    )

    /**
     * The result for [outcome], carrying the build output window on a failure so the compiler
     * errors reach the model without a second call.
     */
    // The else is for a result a newer host adds; without it that result throws at runtime.
    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    fun resultFor(outcome: QuickBuildResult): ToolResult = when (outcome) {
        is QuickBuildResult.Reloaded -> {
            val how = if (outcome.restarted) "restarted" else "reloaded"
            ToolResult.success(
                message = "Quick Build updated the app: $how in ${secondsOf(outcome.durationMillis)}",
                data = if (outcome.restarted) RESTARTED_NOTE else RELOADED_NOTE
            )
        }
        QuickBuildResult.UpToDate -> ToolResult.success(
            message = "Quick Build found nothing to update; the app already runs the latest code",
            data = UP_TO_DATE_NOTE
        )
        // A failure: the user asked for a running app with the change, and the change is not applied.
        is QuickBuildResult.AppNotRunning -> ToolResult.failure(
            "Quick Build built the app, but the app is not running",
            "${outcome.message}\n\n$APP_NOT_RUNNING_NOTE"
        )
        // The reason holds the host's own summary, the first compile errors included.
        is QuickBuildResult.Failed -> ToolResult.failure(
            "Quick Build failed: ${headlineOf(outcome.reason)}",
            "${outcome.reason}\n\n${BuildOutputWindowCalculator.windowOrNull(outcome.output) ?: NO_OUTPUT_NOTE}"
        )
        is QuickBuildResult.Refused -> ToolResult.failure(
            "Quick Build did not start: ${outcome.reason}",
            REFUSED_NOTE
        )
        QuickBuildResult.Declined -> ToolResult.failure(
            "The user declined to replace the installed app, so Quick Build did not run",
            DECLINED_NOTE
        )
        QuickBuildResult.Cancelled -> ToolResult.failure("Quick Build was cancelled")
        else -> ToolResult.failure("Quick Build ended with an unknown result: $outcome")
    }

    /** What to do instead when Quick Build cannot start for [reason]. */
    // The else is for a reason a newer host adds; without it that reason throws at runtime.
    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    private fun adviceFor(reason: QuickBuildUnavailableReason): String = when (reason) {
        // Also a host with the API whose check threw, so the advice must not blame the IDE version.
        QuickBuildUnavailableReason.NOT_SUPPORTED ->
            "Quick Build is not available in the IDE right now. Call run_app to build and install the app instead."
        QuickBuildUnavailableReason.EXPERIMENTS_DISABLED ->
            "Quick Build is an experimental feature and experiments are turned off in the IDE settings. " +
                "Tell the user, or call run_app for a normal install."
        QuickBuildUnavailableReason.NO_PROJECT ->
            "No project is open. Ask the user to open one."
        QuickBuildUnavailableReason.PLUGIN_PROJECT ->
            "A plugin project cannot be Quick Built. Call run_gradle_task to build it instead."
        QuickBuildUnavailableReason.NO_APP_MODULE ->
            "The project has no app module to run. Call run_gradle_task to build its modules instead."
        QuickBuildUnavailableReason.RELEASE_VARIANT ->
            "Quick Build works only with a debug build variant. Ask the user to select a debug " +
                "variant, or call run_app for a normal install."
        QuickBuildUnavailableReason.PROJECT_SYNCING ->
            "The project is still syncing. Wait for the sync to finish, then call quick_build again."
        else -> "Call run_app for a normal install instead."
    }

    /** The first line of [reason] with text on it, as a host summary can open with a blank line. */
    private fun headlineOf(reason: String): String =
        reason.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "no reason given"

    /** [millis] as seconds to one decimal, e.g. "2.4s". */
    private fun secondsOf(millis: Long): String = millis.milliseconds.toString(DurationUnit.SECONDS, 1)

    private const val RELOADED_NOTE =
        "The running app now shows the change, and its state was kept. " +
            "If the app crashes or misbehaves, call read_app_logs."

    private const val RESTARTED_NOTE =
        "The app was relaunched with the change, as on a first run or for a change a live reload " +
            "cannot apply, so its state was reset. " +
            "If the app crashes or misbehaves, call read_app_logs."

    private const val UP_TO_DATE_NOTE =
        "Nothing changed since the last Quick Build, so nothing was rebuilt."

    private const val APP_NOT_RUNNING_NOTE =
        "The change was not applied. Call quick_build again to launch the app with it."

    private const val NO_OUTPUT_NOTE = "(No build output) Call read_build_output for the full build output."

    private const val REFUSED_NOTE =
        "No build ran. If another build is in progress, wait for it to finish and call quick_build again."

    private const val DECLINED_NOTE =
        "Do not call quick_build again unless the user asks. Call run_app instead only if the user " +
            "wants a normal install."
}
