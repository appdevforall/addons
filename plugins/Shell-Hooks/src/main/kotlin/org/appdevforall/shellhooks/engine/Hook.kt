package org.appdevforall.shellhooks.engine

import java.io.File

/**
 * The moments a script can hook. [id] is the script's file name in a hooks folder, so it never
 * changes once shipped. [argsDoc] says what arrives in `$*`, for the samples and the settings
 * screen.
 */
enum class Hook(
    val id: String,
    val title: String,
    val argsDoc: String,
) {
    IDE_STARTED("ide-started", "IDE started", "no arguments; runs once per launch of Code on the Go, the first time hooks are on"),
    PROJECT_OPENED("project-opened", "Project opened", "\$1 = the project folder"),
    PROJECT_CLOSED("project-closed", "Project closed", "\$1 = the project folder"),
    FILE_OPENED("file-opened", "File opened", "\$1 = the file"),
    FILE_CLOSED("file-closed", "File closed", "\$1 = the file"),
    FILE_ACTIVATED("file-activated", "Switched to a file", "\$1 = the file, or empty when no file is open"),
    FILE_SAVED("file-saved", "File saved", "\$1 = the file"),
    BUILD_STARTED("build-started", "Build started", "no arguments; a project sync is a build too"),
    BUILD_SUCCEEDED("build-succeeded", "Build succeeded", "no arguments; a project sync is a build too"),
    BUILD_FAILED("build-failed", "Build failed", "\$1 = the IDE's message, such as BUILD FAILED in 12s, also when you cancel"),
    ;

    companion object {
        fun fromId(id: String): Hook? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One moment to report: which [hook], the positional arguments its scripts get, and the
 * project it concerns ([projectDir], null when none is open).
 */
data class HookEvent(
    val hook: Hook,
    val args: List<String>,
    val projectDir: File?,
) {
    /** A short label for the run log: the file or project the event is about, if any. */
    val target: String? get() = args.firstOrNull()?.takeIf { it.isNotEmpty() }

    companion object {
        // The IDE always passes its own message, cancels included; this only covers a host that
        // passes none, and is not part of the documented contract.
        const val BUILD_CANCELLED = "cancelled"

        // No project: the event is the IDE starting, and at launch the IDE's current project is
        // the last one opened, which need not be open.
        fun ideStarted() = HookEvent(Hook.IDE_STARTED, emptyList(), null)

        fun projectOpened(projectDir: File) = HookEvent(Hook.PROJECT_OPENED, listOf(projectDir.path), projectDir)

        fun projectClosed(projectDir: File) = HookEvent(Hook.PROJECT_CLOSED, listOf(projectDir.path), projectDir)

        fun fileOpened(file: File, projectDir: File?) = HookEvent(Hook.FILE_OPENED, listOf(file.path), projectDir)

        fun fileClosed(file: File, projectDir: File?) = HookEvent(Hook.FILE_CLOSED, listOf(file.path), projectDir)

        // Always one argument, so a script can test "$1" without checking $# first.
        fun fileActivated(file: File?, projectDir: File?) =
            HookEvent(Hook.FILE_ACTIVATED, listOf(file?.path ?: ""), projectDir)

        fun fileSaved(file: File, projectDir: File?) = HookEvent(Hook.FILE_SAVED, listOf(file.path), projectDir)

        fun buildStarted(projectDir: File?) = HookEvent(Hook.BUILD_STARTED, emptyList(), projectDir)

        fun buildSucceeded(projectDir: File?) = HookEvent(Hook.BUILD_SUCCEEDED, emptyList(), projectDir)

        // The IDE's message ends in a newline, which a script would otherwise get in $1.
        fun buildFailed(error: String?, projectDir: File?) =
            HookEvent(Hook.BUILD_FAILED, listOf(error?.trimEnd() ?: BUILD_CANCELLED), projectDir)
    }
}
