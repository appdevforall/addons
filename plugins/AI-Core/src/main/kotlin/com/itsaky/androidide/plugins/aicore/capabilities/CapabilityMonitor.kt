package com.itsaky.androidide.plugins.aicore.capabilities

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.itsaky.androidide.plugins.aicore.backends.BackendRegistry
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices
import com.itsaky.androidide.plugins.services.ToolSourceRegistry
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "$LOG_PREFIX.CapabilityMonitor"

/**
 * What the agent is connected to, as a live row of [CapabilityTag]s.
 *
 * Nothing here polls: the router, the tool registry and the connectivity service each say when
 * something changed, and every signal only schedules a re-read. The re-read runs on IO, because
 * a backend's `isAvailable` may touch the Keystore and a source is another plugin's code.
 *
 * @param context any Context; only its application Context is kept.
 */
class CapabilityMonitor(context: Context) {

    companion object {
        /** Counts AI Core's publishes of its services; [changes] re-attaches on each. */
        private val published = MutableStateFlow(0)

        /**
         * Called by AI Core once it has registered its services, so a row already on screen moves
         * its listeners to the new instances. `SharedServices` announces nothing itself.
         */
        fun onServicesPublished() {
            published.update { it + 1 }
        }
    }

    private val appContext = context.applicationContext

    /** Last answer from the connectivity service; see [networkCallback]. */
    private val online = AtomicBoolean(true)

    /**
     * The tags, re-read on subscription and after every change signal. Bursts — an MCP refresh
     * reporting server after server — collapse into one re-read, and an unchanged row is dropped.
     *
     * @return a cold flow; the listeners live exactly as long as a collector does.
     */
    fun tags(): Flow<List<CapabilityTag>> =
        changes()
            .conflate()
            .map { read() }
            .flowOn(Dispatchers.IO)
            .distinctUntilChanged()

    /** Reads every tag now. */
    private fun read(): List<CapabilityTag> {
        val service = llmService()
        val backend = CapabilityReaders.backend(BackendRegistry.selected(), service)
        val sources = try {
            toolRegistry()?.toolSources.orEmpty()
        } catch (e: Throwable) {
            Log.w(TAG, "Could not list the tool sources", e)
            emptyList()
        }
        return capabilityRow(backend, CapabilityTag.Web(online.get()), CapabilityReaders.tools(sources))
    }

    /**
     * One signal per change anywhere the row reads from, starting with one for the first read.
     * The listeners re-attach on every [onServicesPublished], so a router replaced by an AI Core
     * restart, or one absent when the row started, is still heard.
     */
    private fun changes(): Flow<Unit> = callbackFlow {
        val backendListener = LlmInferenceService.BackendChangeListener { trySend(Unit) }
        val sourceListener = ToolSourceRegistry.ToolSourceListener { trySend(Unit) }
        val lock = Any()
        var service: LlmInferenceService? = null
        var registry: ToolSourceRegistry? = null

        fun detach() = synchronized(lock) {
            service?.let {
                guard("remove the backend listener") { it.removeBackendChangeListener(backendListener) }
            }
            registry?.let {
                guard("remove the tool-source listener") { it.removeToolSourceListener(sourceListener) }
            }
            service = null
            registry = null
        }

        fun attach() = synchronized(lock) {
            service = llmService()?.also {
                guard("add the backend listener") { it.addBackendChangeListener(backendListener) }
            }
            registry = toolRegistry()?.also {
                guard("add the tool-source listener") { it.addToolSourceListener(sourceListener) }
            }
        }

        val network = networkCallback { trySend(Unit) }
        // A StateFlow replays its value, so this attaches once at once and again on each publish.
        val republished = launch {
            published.collect {
                detach()
                attach()
                trySend(Unit)
            }
        }
        awaitClose {
            republished.cancel()
            detach()
            network?.let { callback ->
                guard("unregister the network callback") {
                    connectivity()?.unregisterNetworkCallback(callback)
                }
            }
        }
    }

    /**
     * Tracks whether the default network reaches the internet, updating [online] and calling
     * [onChange] whenever that answer flips.
     *
     * @return the registered callback, or null when the connectivity service would not take one;
     *   the tag then keeps its last reading rather than claiming the device is offline.
     */
    private fun networkCallback(onChange: () -> Unit): ConnectivityManager.NetworkCallback? {
        val manager = connectivity() ?: return null
        guard("read the current network") {
            online.set(hasInternet(manager.getNetworkCapabilities(manager.activeNetwork)))
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val now = hasInternet(capabilities)
                if (online.getAndSet(now) != now) onChange()
            }

            override fun onLost(network: Network) {
                if (online.getAndSet(false)) onChange()
            }
        }
        return try {
            manager.registerDefaultNetworkCallback(callback)
            callback
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch the network; the web tag will not update", e)
            null
        }
    }

    private fun hasInternet(capabilities: NetworkCapabilities?): Boolean =
        capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private fun connectivity(): ConnectivityManager? =
        appContext.getSystemService(ConnectivityManager::class.java)

    private fun llmService(): LlmInferenceService? =
        guard("resolve the inference service") { SharedServices.get(LlmInferenceService::class.java) }

    /** Throwable-guarded like the registry's own registration, for an IDE without the contract. */
    private fun toolRegistry(): ToolSourceRegistry? =
        guard("resolve the tool registry") { SharedServices.get(ToolSourceRegistry::class.java) }

    private inline fun <T> guard(what: String, block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        Log.w(TAG, "Could not $what", e)
        null
    }
}
