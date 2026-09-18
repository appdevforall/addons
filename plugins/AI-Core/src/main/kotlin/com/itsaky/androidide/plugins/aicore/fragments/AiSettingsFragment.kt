package com.itsaky.androidide.plugins.aicore.fragments

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.itsaky.androidide.plugins.aicore.plugin.AiCorePlugin
import com.itsaky.androidide.plugins.aicore.R
import com.itsaky.androidide.plugins.aicore.backends.BackendFragmentFactory
import com.itsaky.androidide.plugins.aicore.backends.BackendOption
import com.itsaky.androidide.plugins.aicore.backends.BackendRegistry
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeTooltipService

/**
 * The Agent settings screen, reached from Preferences → Configuration → Agent and from the Agent
 * chat's own shortcuts. The host mounts it full-screen in PluginScreenActivity, which provides no
 * toolbar, so this fragment brings its own app bar and closes by finishing that activity.
 *
 * Deliberately knows no backend. It offers whichever backends registered themselves with AI Core
 * and mounts, below the selector, the settings pane the selected backend contributes — so adding a
 * provider means shipping a `.cgp`, not editing this screen.
 */
class AiSettingsFragment : Fragment() {

    private lateinit var settingsToolbar: LinearLayout
    private lateinit var backButton: ImageButton
    private lateinit var backendSpinner: Spinner
    private lateinit var backendPlaceholder: TextView
    private var tooltipService: IdeTooltipService? = null

    /**
     * Backends as of the last read, so the spinner's positions stay valid while it is on screen.
     * Re-read in [onResume] rather than only in [onCreate]: plugins load in parallel, so a backend
     * can register after this screen opened and would otherwise stay invisible until it is
     * recreated.
     */
    private var backends: List<BackendOption> = emptyList()

    /** Backend whose pane is currently mounted, so re-selecting it does not rebuild it. */
    private var shownBackendId: String? = null

    companion object {
        /** Tag for the mounted backend pane, so it can be found across a configuration change. */
        private const val TAG_BACKEND_PANE = "backend_settings_pane"
    }

    /**
     * The window's status-bar icon appearance as this screen found it, so leaving restores it.
     * Null until [onStart] has run once.
     */
    private var hostLightStatusBars: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Read once for the whole screen: the factory below is consulted for every fragment the
        // panes create or restore, and each lookup would otherwise re-walk the registry.
        backends = BackendRegistry.options()

        // Installed before super.onCreate, which is where a saved child-fragment state is restored:
        // the backend pane lives in another plugin's classloader and the default factory cannot see
        // it, so without this a rotation would silently replace the pane with a blank fragment.
        childFragmentManager.fragmentFactory = BackendFragmentFactory(
            childFragmentManager.fragmentFactory,
        ) { className -> BackendRegistry.classLoaderFor(className, backends) }
        super.onCreate(savedInstanceState)

        // Disable Material transitions to avoid resource loading issues
        // Plugin uses compileOnly dependencies, so Material transition resources aren't bundled
        enterTransition = null
        exitTransition = null

        // Resolve the IDE tooltip service so the settings controls can offer in-app help.
        try {
            tooltipService = PluginFragmentHelper.getServiceRegistry(AiCorePlugin.PLUGIN_ID)
                ?.get(IdeTooltipService::class.java)
        } catch (e: Exception) {
            // Tooltip help is optional; long-press simply shows nothing when it's unavailable.
            AiCorePlugin.getContext()?.logger
                ?.warn("AiSettingsFragment: tooltip service unavailable", e)
        }
    }

    /** Shows this plugin's tooltip for [tag] when [view] is long-pressed (Tier 1/2 + guide button). */
    private fun wireTooltip(view: View, tag: String) {
        view.setOnLongClickListener { anchor ->
            val service = tooltipService ?: return@setOnLongClickListener false
            service.showTooltip(anchor, AiCorePlugin.TOOLTIP_CATEGORY, tag)
            true
        }
    }

    /**
     * Route inflation through the host so this screen's views resolve against a Context whose
     * Configuration tracks the IDE's day/night setting (DayNight PluginTheme + values-night/
     * colors); the raw fragment inflater pins the screen to light mode.
     *
     * Only this screen's own views: a backend pane inflates against *its* plugin's resources and
     * overrides this for itself.
     */
    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(AiCorePlugin.PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ai_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupToolbar()
        setupBackendSelector(restoring = savedInstanceState != null)
    }

    private fun initializeViews(view: View) {
        settingsToolbar = view.findViewById(R.id.settings_toolbar)
        backButton = view.findViewById(R.id.toolbar_back_button)
        backendSpinner = view.findViewById(R.id.backend_autocomplete)
        // The pane container itself is addressed by id only; its children belong to the child
        // FragmentManager, so nothing here holds a reference that could touch them.
        backendPlaceholder = view.findViewById(R.id.backend_placeholder)
    }

    /**
     * Picks up a backend that registered after this screen opened — the ordering this whole design
     * assumes cannot happen. Only rebuilds when the installed set actually changed, so returning
     * from a pane does not disturb a mounted one.
     */
    override fun onResume() {
        super.onResume()
        val current = BackendRegistry.options()
        if (current.map { it.id } == backends.map { it.id }) return
        backends = current
        setupBackendSelector(restoring = false)
    }

    override fun onStart() {
        super.onStart()
        applySystemBarAppearance()
    }

    override fun onStop() {
        // The window outlives this screen, so what was changed for it is handed back.
        hostLightStatusBars?.let(::setLightSystemBars)
        hostLightStatusBars = null
        super.onStop()
    }

    /**
     * Darkens the system bar icons while this screen is up in light mode.
     *
     * The screen fills the window with `colorSurface`, but the activity hosting a plugin screen
     * never sets the light-appearance flag, so on a light theme the clock and the status icons stay
     * white on a near-white bar and disappear (ADFA-3017 QA). Read from the fragment's own
     * resources, which are the activity's and track the IDE's day/night setting.
     */
    private fun applySystemBarAppearance() {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val lightBackground = nightMode != Configuration.UI_MODE_NIGHT_YES
        val controller = systemBarController() ?: return
        if (hostLightStatusBars == null) {
            hostLightStatusBars = controller.isAppearanceLightStatusBars
        }
        setLightSystemBars(lightBackground)
    }

    private fun setLightSystemBars(light: Boolean) {
        val controller = systemBarController() ?: return
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }

    private fun systemBarController(): WindowInsetsControllerCompat? {
        val window = activity?.window ?: return null
        return WindowInsetsControllerCompat(window, window.decorView)
    }

    private fun setupToolbar() {
        backButton.setOnClickListener {
            // This screen owns the whole activity, so closing it means finishing that activity.
            requireActivity().finish()
        }
        wireTooltip(backButton, AiCorePlugin.TOOLTIP_TAG_SETTINGS_BACK)
    }

    /**
     * Fills the selector from the live backend registry and mounts the selected backend's pane.
     *
     * @param restoring true when the framework already rebuilt the pane from saved state, in which
     *   case the initial selection must not replace it and lose whatever the user had typed
     */
    private fun setupBackendSelector(restoring: Boolean) {
        if (backends.isEmpty()) {
            backendSpinner.visibility = View.GONE
            // Reached on a refresh too, when the last backend was uninstalled under the screen.
            clearPane()
            shownBackendId = null
            showPlaceholder(getString(R.string.backend_none_installed))
            return
        }

        backendSpinner.visibility = View.VISIBLE
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            backends.map { it.displayName }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        backendSpinner.adapter = adapter

        wireTooltip(backendSpinner, AiCorePlugin.TOOLTIP_TAG_SETTINGS_BACKEND)

        // Nothing stored, or a stored selection whose backend was uninstalled, resolves the same way
        // the chat does — and without persisting anything, so opening this screen cannot enrol the
        // user in a backend they never picked.
        val selected = BackendRegistry.preferred(backends) ?: backends.first()

        if (restoring && childFragmentManager.findFragmentByTag(TAG_BACKEND_PANE) != null) {
            shownBackendId = selected.id
        }

        backendSpinner.setSelection(backends.indexOf(selected))
        showBackendPane(selected)

        backendSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val backend = backends.getOrNull(position) ?: return
                // With an accessibility service running, AdapterView posts the programmatic
                // selection above and delivers it here once the listener exists; writing on that
                // would persist a choice the user never made.
                if (backend.id == shownBackendId) return
                BackendRegistry.select(backend.id)
                showBackendPane(backend)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * Mounts [backend]'s own settings pane in the container below the selector.
     *
     * The pane is a Fragment packaged in the backend's `.cgp`; it is loaded by that plugin's
     * classloader (see [BackendFragmentFactory]) and inflates against that plugin's resources, so
     * nothing about the provider is known here beyond the class name it declared.
     */
    private fun showBackendPane(backend: BackendOption) {
        if (shownBackendId == backend.id) return
        shownBackendId = backend.id

        val className = backend.settingsFragmentClassName
        if (className == null) {
            // A backend configured entirely from its own defaults is legitimate, so this is a
            // statement about that backend rather than an error.
            clearPane()
            showPlaceholder(getString(R.string.backend_no_settings, backend.displayName))
            return
        }

        val paneClass = loadPaneClass(backend, className)
        if (paneClass == null) {
            clearPane()
            showPlaceholder(getString(R.string.backend_pane_unavailable))
            return
        }

        hidePlaceholder()
        childFragmentManager.commit {
            setReorderingAllowed(true)
            // replace() removes the outgoing pane's view itself; clearing the container by hand
            // would only race that transaction and defeat the exit animation.
            replace(R.id.backend_specific_settings_container, paneClass, null, TAG_BACKEND_PANE)
        }
    }

    /**
     * Loads the pane class with the backend plugin's own loader.
     *
     * @return the class, or null when the backend named a class its own plugin does not contain or
     *   that is not a Fragment — a broken backend, reported rather than crashing this screen
     */
    @Suppress("UNCHECKED_CAST")
    private fun loadPaneClass(backend: BackendOption, className: String): Class<out Fragment>? {
        val loader = backend.classLoader ?: return null
        return try {
            val loaded = loader.loadClass(className)
            if (!Fragment::class.java.isAssignableFrom(loaded)) {
                AiCorePlugin.getContext()?.logger?.error(
                    "AiSettingsFragment: backend '${backend.id}' declared '$className', " +
                        "which is not a Fragment"
                )
                return null
            }
            loaded as Class<out Fragment>
        } catch (e: Throwable) {
            AiCorePlugin.getContext()?.logger?.error(
                "AiSettingsFragment: backend '${backend.id}' declared '$className', " +
                    "which its plugin does not contain",
                e
            )
            null
        }
    }

    /** Removes a mounted pane, so a placeholder is not drawn behind the previous backend's UI. */
    private fun clearPane() {
        val pane = childFragmentManager.findFragmentByTag(TAG_BACKEND_PANE) ?: return
        childFragmentManager.commit {
            setReorderingAllowed(true)
            remove(pane)
        }
    }

    /**
     * Shows [message] for the states where there is no pane. A sibling of the pane container, so
     * the child FragmentManager keeps sole ownership of that container's children and a pending
     * `remove()` cannot run against a view something else already detached.
     */
    private fun showPlaceholder(message: String) {
        backendPlaceholder.text = message
        backendPlaceholder.visibility = View.VISIBLE
    }

    /** Hides the placeholder; a mounted pane speaks for itself. */
    private fun hidePlaceholder() {
        backendPlaceholder.visibility = View.GONE
    }
}
