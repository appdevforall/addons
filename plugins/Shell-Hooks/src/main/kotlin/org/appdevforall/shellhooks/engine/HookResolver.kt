package org.appdevforall.shellhooks.engine

import java.io.File
import java.io.IOException

/**
 * A script ready to run. [executable] is what runs; for a project hook it is a private copy of
 * the bytes the user trusted, so the file cannot change between the check and the run.
 * [original] is the script the user sees and edits.
 */
data class ResolvedScript(
    val executable: File,
    val original: File,
    val source: ScriptSource,
    val hooksDir: File,
)

/** Turns an event into the scripts to run for it. */
fun interface ScriptResolver {
    fun resolve(event: HookEvent): List<ResolvedScript>
}

/** What the user turned on; read on every event, so a change applies to the next one. */
interface HookSwitches {
    val enabled: Boolean
    val projectHooksEnabled: Boolean

    fun isHookEnabled(hook: Hook): Boolean
}

/**
 * Finds the user's global scripts for an event, then the project's own, which run only when
 * project hooks are on and the user trusted their exact content. An untrusted project script is
 * skipped and listed in [log] for review.
 */
class HookResolver(
    private val paths: HookPaths,
    private val switches: HookSwitches,
    private val trust: TrustStore,
    private val log: HookLog,
    private val verifiedDir: File,
    private val warn: (String) -> Unit = {},
) : ScriptResolver {
    override fun resolve(event: HookEvent): List<ResolvedScript> {
        if (!switches.enabled || !switches.isHookEnabled(event.hook)) return emptyList()
        val global =
            HookDiscovery.discover(paths.globalHooks, event.hook.id).map {
                ResolvedScript(it, it, ScriptSource.GLOBAL, paths.globalHooks)
            }
        val projectDir = event.projectDir
        if (!switches.projectHooksEnabled || projectDir == null) return global
        val root = HookPaths.projectHooks(projectDir)
        val project = HookDiscovery.discover(root, event.hook.id).mapNotNull { verify(projectDir, root, it) }
        return global + project
    }

    private fun verify(
        projectDir: File,
        root: File,
        script: File,
    ): ResolvedScript? {
        val bytes =
            try {
                script.readBytes()
            } catch (e: IOException) {
                warn("Shell Hooks: cannot read ${script.path}: ${e.message}")
                return null
            }
        val sha = TrustStore.sha256(bytes)
        val relativePath = script.relativeTo(root).path
        if (!trust.isTrusted(projectDir, relativePath, sha)) {
            log.addPending(PendingTrust(projectDir, relativePath, script, sha))
            return null
        }
        log.removePending(projectDir, relativePath)
        val copy = verifiedCopy(bytes, sha) ?: return null
        return ResolvedScript(copy, script, ScriptSource.PROJECT, root)
    }

    // Named by content hash, so one copy serves every run of the same bytes. Written to a temporary
    // name first: a half-written copy must never be executable under the final name.
    private fun verifiedCopy(
        bytes: ByteArray,
        sha: String,
    ): File? =
        try {
            verifiedDir.mkdirs()
            val copy = File(verifiedDir, sha)
            if (!copy.isFile || TrustStore.sha256(copy.readBytes()) != sha) {
                val temp = File(verifiedDir, "$sha.tmp")
                temp.writeBytes(bytes)
                temp.setExecutable(true, true)
                if (!temp.renameTo(copy)) throw IOException("rename failed")
            }
            copy.setExecutable(true, true)
            copy
        } catch (e: IOException) {
            warn("Shell Hooks: cannot stage a trusted copy: ${e.message}")
            null
        }
}
