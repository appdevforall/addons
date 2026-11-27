package org.appdevforall.sqliteexplorer

import com.itsaky.androidide.plugins.services.DebuggerEndpointService
import org.appdevforall.sqliteexplorer.core.Endpoint
import org.appdevforall.sqliteexplorer.core.Explorer
import org.appdevforall.sqliteexplorer.core.McpClient

/**
 * The app being explored. It outlives the screen, so reopening it keeps the connection; disabling the plugin drops it.
 * Read and written on the main thread; only [endpoints] and the [Explorer] calls run on a background thread.
 */
object Connection {
    @Volatile
    var active = true

    var endpoint: Endpoint? = null
        private set

    var explorer: Explorer? = null
        private set

    /** The open project's apps that have reported a debugger. Blocks: never on the main thread. */
    fun endpoints(): List<Endpoint> {
        check(active) { "SQLite Explorer is disabled. Enable it in the Plugin Manager." }
        val context = SqliteExplorerPlugin.context ?: throw IllegalStateException("SQLite Explorer is not loaded.")
        val service = context.services.get(DebuggerEndpointService::class.java)
            ?: throw IllegalStateException("This Code on the Go does not offer the database debugger's endpoints. Update Code on the Go.")
        return service.endpoints.map { Endpoint(it.applicationId, it.port, it.token, it.updatedAtMillis) }
    }

    fun use(endpoint: Endpoint) {
        this.endpoint = endpoint
        explorer = Explorer(McpClient(endpoint.port, endpoint.token))
    }

    fun forget() {
        endpoint = null
        explorer = null
    }
}

