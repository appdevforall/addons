package org.appdevforall.shellhooks.engine

import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * Builds the runner the plugin uses, so how its parts are wired can be tested: notably that the
 * runner knows, per run, whether the Terminal has `timeout` ([HookPaths.hasTimeout]), without which
 * an exit 124 or 137 is the script's own.
 */
object HookRunners {
    fun create(
        scope: CoroutineScope,
        paths: HookPaths,
        switches: HookSwitches,
        trust: TrustStore,
        log: HookLog,
        verifiedDir: File,
        commands: CommandRunner,
        wrapper: () -> File?,
        timeoutMs: () -> Long,
        ideVersion: String?,
        warn: (String) -> Unit,
        elapsedClock: () -> Long = { System.nanoTime() / 1_000_000 },
    ): HookRunner =
        HookRunner(
            scope = scope,
            resolver = HookResolver(paths, switches, trust, log, verifiedDir, warn),
            commands = commands,
            log = log,
            wrapper = wrapper,
            timeoutMs = timeoutMs,
            timeoutAvailable = paths::hasTimeout,
            ideVersion = ideVersion,
            elapsedClock = elapsedClock,
            warn = warn,
        )
}
