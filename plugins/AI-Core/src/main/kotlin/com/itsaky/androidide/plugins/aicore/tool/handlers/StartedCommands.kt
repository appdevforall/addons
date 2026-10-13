package com.itsaky.androidide.plugins.aicore.tool.handlers

/**
 * What [RunShellCommandHandler] left running under each command id it reported, so the stop
 * approval shows the command instead of an id the host minted. Bounded: the oldest ids go first.
 */
class StartedCommands {

    /** A command left running: its text as the user approved it, and the Terminal session it runs in. */
    data class Started(val command: String, val sessionName: String)

    private val byId = object : LinkedHashMap<String, Started>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Started>) = size > MAX_IDS
    }

    /** Records that [command] was left running in [sessionName] under [commandId]. */
    @Synchronized
    fun record(commandId: String, command: String, sessionName: String) {
        byId[commandId] = Started(command, sessionName)
    }

    /** What was started under [commandId]; null for an id this handler set did not report. */
    @Synchronized
    fun find(commandId: String): Started? = byId[commandId]

    private companion object {
        const val MAX_IDS = 64
    }
}
