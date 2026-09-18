package com.itsaky.androidide.plugins.aicore.backends

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import com.itsaky.androidide.plugins.aicore.plugin.AiCorePlugin
import com.itsaky.androidide.plugins.aicore.R
import kotlin.math.roundToInt

/**
 * Instantiates a backend's settings pane with the loader that can actually see it.
 *
 * Each plugin gets its own `DexClassLoader`, so a class packaged in a backend's `.cgp` is invisible
 * to this plugin's loader — including to the FragmentManager, which rebuilds a restored pane from
 * its class name alone. Installing this on the child FragmentManager is what makes the pane survive
 * rotation and process death.
 *
 * @param delegate the factory to fall back on for this plugin's own fragments
 * @param resolve maps a fragment class name to the backend plugin's loader; null if none claims it
 */
class BackendFragmentFactory(
    private val delegate: FragmentFactory,
    private val resolve: (String) -> ClassLoader?,
) : FragmentFactory() {

    override fun instantiate(classLoader: ClassLoader, className: String): Fragment {
        val backendLoader = resolve(className)
            ?: return instantiateOwn(classLoader, className)

        return try {
            backendLoader.loadClass(className)
                .getDeclaredConstructor()
                .newInstance() as Fragment
        } catch (e: Throwable) {
            // A pane that will not construct is the backend's bug, but it must not take the
            // settings screen down with it.
            logError("backend settings pane '$className' could not be constructed", e)
            MissingBackendFragment()
        }
    }

    /**
     * Falls back to the default factory, standing in for a pane whose backend is gone rather than
     * throwing: an uninstalled backend is exactly the state a restored selection lands in.
     */
    private fun instantiateOwn(classLoader: ClassLoader, className: String): Fragment = try {
        delegate.instantiate(classLoader, className)
    } catch (e: Throwable) {
        logError("no installed backend provides '$className'", e)
        MissingBackendFragment()
    }

    private fun logError(message: String, error: Throwable) {
        AiCorePlugin.getContext()?.logger?.error("BackendFragmentFactory: $message", error)
    }
}

/**
 * Stands in for a settings pane that could not be built — most often a backend uninstalled while
 * its pane was in the saved state. Says so on screen rather than leaving a blank area that reads as
 * a broken settings screen.
 */
class MissingBackendFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val padding = (16 * resources.displayMetrics.density).roundToInt()
        return TextView(inflater.context).apply {
            setText(R.string.backend_pane_unavailable)
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
        }
    }
}
