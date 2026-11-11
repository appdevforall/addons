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
    IDE_STARTED("ide-started", "IDE started", "no arguments"),
    PROJECT_OPENED("project-opened", "Project opened", "\$1 = the project folder"),
    PROJECT_CLOSED("project-closed", "Project closed", "\$1 = the project folder"),
    FILE_OPENED("file-opened", "File opened", "\$1 = the file"),
    FILE_CLOSED("file-closed", "File closed", "\$1 = the file"),
    FILE_ACTIVATED("file-activated", "Switched to a file", "\$1 = the file, or empty when no file is open"),
    FILE_SAVED("file-saved", "File saved", "\$1 = the file"),
    BUILD_STARTED("build-started", "Build started", "no arguments"),
    BUILD_SUCCEEDED("build-succeeded", "Build succeeded", "no arguments"),
    BUILD_FAILED("build-failed", "Build failed", "\$1 = the error, or \"cancelled\""),
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
        const val BUILD_CANCELLED = "cancelled"

        fun ideStarted(projectDir: File?) = HookEvent(Hook.IDE_STARTED, emptyList(), projectDir)

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

        fun buildFailed(error: String?, projectDir: File?) =
            HookEvent(Hook.BUILD_FAILED, listOf(error ?: BUILD_CANCELLED), projectDir)
    }
}
