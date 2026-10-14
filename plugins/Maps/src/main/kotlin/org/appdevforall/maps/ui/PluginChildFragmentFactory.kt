package org.appdevforall.maps.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory

/**
 * [FragmentFactory] that resolves plugin-class names via the plugin's
 * DexClassLoader rather than the host's. Set on [RegionManagerFragment]'s
 * `childFragmentManager` before super.onCreate so that ViewPager2's
 * FragmentStateAdapter state-restore path can instantiate the wizard's child
 * fragments without throwing `ClassNotFoundException`.
 *
 * Without this, tab-swiping with the Maps tab active throws
 * `ClassNotFoundException: org.appdevforall.maps.ui.BboxPickerFragment`
 * because the host's default factory uses the host's class loader.
 *
 * Falls through to the host's no-arg default behavior on failure rather than
 * propagating exceptions — defense-in-depth so a future renamed-or-removed
 * fragment class triggers a generic "fragment not found" path instead of a
 * full IDE crash.
 */
internal class PluginChildFragmentFactory(
    private val pluginClassLoader: ClassLoader,
) : FragmentFactory() {

    override fun instantiate(classLoader: ClassLoader, className: String): Fragment {
        return try {
            val cls = pluginClassLoader.loadClass(className)
            cls.getDeclaredConstructor().newInstance() as Fragment
        } catch (t: Throwable) {
            // Fall through to the AndroidX default which uses the passed-in
            // classLoader; if that also fails, AndroidX will throw a clear
            // Fragment$InstantiationException for the host to handle.
            super.instantiate(classLoader, className)
        }
    }
}
