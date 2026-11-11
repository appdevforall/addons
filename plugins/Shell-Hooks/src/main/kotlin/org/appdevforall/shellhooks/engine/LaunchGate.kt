package org.appdevforall.shellhooks.engine

/**
 * Decides whether ide-started belongs to this launch of the IDE. The host activates a plugin at
 * every IDE start, but also when the user re-enables or updates it mid-session, so the plugin
 * remembers the launch it last ran ide-started for. [launchId] names the IDE process, e.g. its
 * pid and start time: it is the same for every activation within one launch, and differs after
 * the IDE restarts.
 */
class LaunchGate(
    private val store: KeyValueStore,
) {
    /** True when ide-started has not yet run for [launchId]. */
    fun isNewLaunch(launchId: String): Boolean = store.getString(KEY) != launchId

    /** Records that ide-started ran for [launchId]. */
    fun record(launchId: String) = store.putString(KEY, launchId)

    companion object {
        const val KEY = "ide_started.launch"
    }
}
