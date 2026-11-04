package org.appdevforall.maps.testing

import android.content.Context
import android.content.SharedPreferences
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLifecycleListener
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.ResourceManager
import com.itsaky.androidide.plugins.ServiceRegistry
import java.io.File

/**
 * A [PluginContext] whose every member throws. Test fakes delegate to it (`PluginContext by
 * UnusedPluginContext`) and override only what the code under test reads, so a new host API
 * member does not break every fake.
 */
object UnusedPluginContext : PluginContext {
  private fun unused(): Nothing = throw UnsupportedOperationException("not used by this test")

  override val androidContext: Context get() = unused()
  override val services: ServiceRegistry get() = unused()
  override val eventBus: Any get() = unused()
  override val logger: PluginLogger get() = unused()
  override val resources: ResourceManager get() = unused()
  override val pluginId: String get() = unused()

  override fun <T> getPluginService(pluginId: String, serviceClass: Class<T>): T? = unused()

  override fun isPluginActive(pluginId: String): Boolean = unused()

  override fun getPluginVersion(pluginId: String): String? = unused()

  override fun <T> registerService(serviceClass: Class<T>, serviceImpl: T) = unused()

  override fun <T> unregisterService(serviceClass: Class<T>) = unused()

  override fun getProvidedServices(): List<String> = unused()

  override fun getPluginDataDir(): File = unused()

  override fun getAppFilesDir(): File = unused()

  override fun getPluginFilesDir(): File = unused()

  override fun getAppSharedPreferences(name: String): SharedPreferences = unused()

  override fun getPluginSharedPreferences(name: String): SharedPreferences = unused()

  override fun addPluginLifecycleListener(listener: PluginLifecycleListener) = unused()

  override fun removePluginLifecycleListener(listener: PluginLifecycleListener) = unused()
}
