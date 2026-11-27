package org.appdevforall.sqliteexplorer.core

import java.net.SocketTimeoutException

/** What to tell the user when something fails, and whether reconnecting can help. */
class Problem(val message: String, val reconnect: Boolean)

object Problems {
    const val NO_PROJECT_OR_APP =
        "No app of the open project has reported a database debugger. Open the project in the editor, then build and run " +
            "its debug build. The debugger is added when the project's gradle.properties has cogo.sqliteDebugger=true."

    fun describe(e: Throwable): Problem =
        when (e) {
            is UnreachableException ->
                Problem("The app is not running, or it restarted. Start the app's debug build, then reconnect.", reconnect = true)
            is TokenRejectedException ->
                Problem("The app restarted and has a new token. Reconnect to read it from Code on the Go.", reconnect = true)
            is SocketTimeoutException ->
                Problem("The app did not answer in time. It may be paused in a debugger, or the query may be too slow.", reconnect = true)
            is UnsupportedServerException -> Problem(e.message.orEmpty(), reconnect = false)
            // A SQL error or a refused statement: the server's own message says what was wrong.
            is ToolException -> Problem(e.message.orEmpty(), reconnect = false)
            is McpException -> Problem(e.message.orEmpty(), reconnect = true)
            is SecurityException ->
                Problem("SQLite Explorer lacks the debugger.endpoints permission. Reinstall the plugin.", reconnect = false)
            else -> Problem(e.message ?: e.javaClass.simpleName, reconnect = true)
        }
}
