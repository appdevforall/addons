package com.itsaky.androidide.plugins.aicore.backends

import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.plugin.AiCorePlugin
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices

/**
 * A backend the settings selector can offer.
 *
 * @param id the backend's own id; this is what gets persisted as the selection
 * @param displayName the backend's own label, shown in the selector
 * @param settingsFragmentClassName the settings pane the backend contributes, or null if it has none
 * @param classLoader the loader that can see [settingsFragmentClassName] — the backend plugin's own,
 *   since this plugin's loader cannot see classes packaged in another `.cgp`
 */
data class BackendOption(
    val id: String,
    val displayName: String,
    val settingsFragmentClassName: String?,
    val classLoader: ClassLoader?,
)

/**
 * What the stored backend selection resolves to against what is installed right now.
 *
 * The three cases are three different fixes for the user, so callers that report the selection have
 * to tell them apart: configure the chosen backend, install the plugin it came from, or install any
 * backend at all.
 */
sealed interface SelectedBackend {

    /** The selected backend, installed and registered. */
    data class Installed(val option: BackendOption) : SelectedBackend

    /** A selection is stored, but no installed backend answers to it. */
    data object Missing : SelectedBackend

    /** No backend is installed to select. */
    data object None : SelectedBackend
}

/**
 * AI Core's own view of the backends currently installed: the registry the settings selector, the
 * chat's status line and its availability check all read.
 *
 * Everything here comes from the live registry backends register themselves with, so no provider is
 * named in any of it: a backend that is not installed is simply absent from [options], and one that
 * ships in a `.cgp` written by someone else appears without a line of code changing here.
 *
 * Two onboarding surfaces name the first-party pair on purpose, and are the only ones allowed to:
 * the manifest's plugin description, which has to tell someone browsing the Plugin Manager what
 * else to install, and the Tier-3 guide, whose API-key walkthrough cannot be written for a provider
 * it does not know. Every runtime string — tooltips, selector states — stays provider-neutral.
 */
object BackendRegistry {

    private const val TAG = "$LOG_PREFIX.BackendRegistry"

    /**
     * Every registered backend, sorted by label so the selector's order is stable across restarts
     * (the underlying registry is a hash map).
     *
     * @return the installed backends; empty when no backend has registered yet
     */
    fun options(): List<BackendOption> {
        val backends = try {
            service()?.availableBackends.orEmpty()
        } catch (e: Exception) {
            logError("could not list the registered backends", e)
            emptyList()
        }
        return backends.mapNotNull(::describe).sortedBy { it.displayName }
    }

    /**
     * The backend to act on out of [options], resolved the same way everywhere — see
     * [AiBackend.preferredId]. Reads the stored selection but never writes one, so merely opening a
     * screen cannot opt the user into a backend they did not choose.
     *
     * @param options the installed backends, normally from [options]
     * @param storedId the persisted selection; defaults to the stored one
     * @return the backend to act on, or null when nothing is installed
     */
    fun preferred(options: List<BackendOption>, storedId: String? = selectedId()): BackendOption? {
        val id = AiBackend.preferredId(storedId, options.map { it.id }) ?: return null
        return options.firstOrNull { it.id == id }
    }

    /**
     * The selection resolved against what is installed, as the one question a caller has to ask.
     *
     * Resolved from a single snapshot: asking [preferred] and [selectedId] as two separate
     * questions reads the registry and the preference twice, and a backend registering in between
     * let the two answers disagree — reporting "not installed" about a backend that just appeared.
     *
     * @param storedId the persisted selection; defaults to the stored one
     * @return which of the three [SelectedBackend] cases holds now
     */
    @JvmOverloads
    fun selected(storedId: String? = selectedId()): SelectedBackend {
        preferred(options(), storedId)?.let { return SelectedBackend.Installed(it) }
        return if (storedId != null) SelectedBackend.Missing else SelectedBackend.None
    }

    /**
     * The loader that can instantiate [fragmentClassName], found by asking each backend plugin's
     * loader whether it can see the class. Used by [BackendFragmentFactory] to rebuild a restored
     * fragment.
     *
     * Deliberately not keyed on [BackendOption.settingsFragmentClassName]: a pane shows dialogs and
     * child fragments of its own, and those are restored through this same factory. Matching only
     * the pane's own name would leave every sibling to fall through to the host loader, which
     * cannot see it, and be silently replaced by a blank fragment.
     *
     * @param installed the backends to search; pass a snapshot to keep the FragmentManager, which
     *   calls this once per fragment created *and* restored, off a fresh cross-classloader walk
     * @return the owning backend plugin's loader, or null if no installed backend can see the class
     */
    @JvmOverloads
    fun classLoaderFor(
        fragmentClassName: String,
        installed: List<BackendOption> = options(),
    ): ClassLoader? =
        installed.asSequence()
            .mapNotNull { it.classLoader }
            .distinct()
            .firstOrNull { loader -> canSee(loader, fragmentClassName) }

    /** Whether [loader] can resolve [className] without initializing it. */
    private fun canSee(loader: ClassLoader, className: String): Boolean = try {
        Class.forName(className, false, loader)
        true
    } catch (e: ClassNotFoundException) {
        false
    } catch (e: LinkageError) {
        // Present but unloadable — still this plugin's class, so stop looking for another owner.
        true
    }

    /**
     * The backend the user selected, as a backend id.
     *
     * @return the stored id, migrating a legacy value in passing; null when nothing is stored
     */
    fun selectedId(): String? {
        val stored = prefs()?.getString(AiBackend.PREFERENCE_KEY, null)?.trim()
        if (stored.isNullOrEmpty()) return null
        // Migrates a value written before the stored form *was* the backend id.
        return AiBackend.idFromPreference(stored)
    }

    /**
     * Persists [backendId] as the selection. Stores the backend's own id, so a backend AI Core has
     * never heard of routes correctly without anything mapping it.
     */
    fun select(backendId: String) {
        prefs()?.edit()?.putString(AiBackend.PREFERENCE_KEY, backendId)?.apply()
    }

    /**
     * Describes one backend, tolerating a backend that throws from its own accessors: one bad
     * `.cgp` must cost the user that entry in the list, not the whole settings screen.
     */
    private fun describe(backend: LlmInferenceService.LlmBackend): BackendOption? = try {
        BackendOption(
            id = backend.id,
            displayName = backend.name,
            // Only a ConfigurableBackend names a pane; anything else simply has no settings screen.
            settingsFragmentClassName = (backend as? LlmInferenceService.ConfigurableBackend)
                ?.settingsFragmentClassName?.takeIf { it.isNotBlank() },
            // The backend object is constructed by its own plugin, so its loader is by construction
            // the one that can see the pane it names.
            classLoader = backend.javaClass.classLoader,
        )
    } catch (e: Throwable) {
        logError("a registered backend could not describe itself; omitting it", e)
        null
    }

    private fun service(): LlmInferenceService? =
        SharedServices.get(LlmInferenceService::class.java)

    private fun prefs() =
        AiCorePlugin.getContext()?.getPluginSharedPreferences(AiBackend.PREFERENCE_FILE)

    private fun logError(message: String, error: Throwable) {
        AiCorePlugin.getContext()?.logger?.error("$TAG: $message", error)
    }
}
