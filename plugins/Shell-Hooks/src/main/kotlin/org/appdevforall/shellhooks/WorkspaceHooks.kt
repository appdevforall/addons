package org.appdevforall.shellhooks

import com.itsaky.androidide.plugins.services.FileSaveListener
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.ProjectLifecycleListener
import org.appdevforall.shellhooks.engine.HookEvent
import java.io.File

/**
 * The listeners for the IDE events added in 26.47: project opened and closed, and file saved.
 * They live in their own class so that [ShellHooksPlugin] never names those types itself. On an
 * older IDE only this class fails to load, inside the plugin's guarded `listen` blocks, and the
 * plugin runs with the other hooks instead of failing to load.
 */
internal class WorkspaceHooks(
    private val submit: (HookEvent) -> Unit,
    private val currentProjectDir: () -> File?,
) {
    private val projectListener =
        object : ProjectLifecycleListener {
            override fun onProjectOpened(projectDir: File) = submit(HookEvent.projectOpened(projectDir))

            override fun onProjectClosed(projectDir: File) = submit(HookEvent.projectClosed(projectDir))
        }

    private val saveListener = FileSaveListener { file -> submit(HookEvent.fileSaved(file, currentProjectDir())) }

    fun addTo(service: IdeProjectService) = service.addProjectLifecycleListener(projectListener)

    fun removeFrom(service: IdeProjectService) = service.removeProjectLifecycleListener(projectListener)

    fun addTo(service: IdeEditorService) = service.addFileSaveListener(saveListener)

    fun removeFrom(service: IdeEditorService) = service.removeFileSaveListener(saveListener)
}
