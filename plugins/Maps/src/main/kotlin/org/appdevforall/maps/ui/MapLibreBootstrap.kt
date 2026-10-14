package org.appdevforall.maps.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import org.appdevforall.maps.MapsPlugin
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer

/**
 * Process-wide MapLibre singleton bootstrap, extracted from
 * [BboxPickerFragment.onCreateView] — initializing a process-life native
 * library is not one dialog's lifecycle job, and this is exactly the kind of
 * subtle glue any future in-process map surface must copy correctly.
 *
 * MapLibre.getInstance must run before a `MapView` is inflated, and must use
 * the 3-arg form — the 1-arg variant is a deprecated no-op (the .so loads
 * but the Java-side INSTANCE stays null, so MapView.initialize() crashes).
 *
 * CRITICAL: pass a context whose getResources() resolves the PLUGIN's
 * resources, not the host app's. MapLibre stores this context and on a
 * single map tap does `getApplicationContext().getResources()
 * .getDimension(R.dimen.maplibre_eight_dp)` inside AnnotationManager's
 * hit-test. The host Resources have no plugin resource package attached,
 * so that lookup throws Resources$NotFoundException and crashes the whole
 * host on a misclick. The plugin inflater's context resolves plugin
 * resources; wrap it so getApplicationContext() returns itself. (An
 * addOnMapClickListener{true} mitigation can't help — it fires AFTER
 * AnnotationManager.onTap, where the crash already happened.)
 *
 * MapLibre.INSTANCE is a process-life singleton (first getInstance wins),
 * so this wrapper is pinned for the process — a bounded one-object
 * retention, not a per-open leak. The picker is the only in-process
 * MapLibre user so it reliably wins; if a future path inits MapLibre
 * first, this bootstrap must run there instead.
 */
internal object MapLibreBootstrap {

    /**
     * Initialize the MapLibre process singleton with a plugin-resource-aware
     * context. Safe to call before every MapView inflation — the first call
     * wins and later calls are cheap.
     *
     * @param inflaterContext the themed plugin inflater's context, whose
     *   `resources` resolve the plugin's resource package
     * @param appContext the host application context the wrapper delegates
     *   everything except resources to
     */
    fun ensureInitialized(inflaterContext: Context, appContext: Context) {
        val pluginResources = inflaterContext.resources
        val mapLibreContext = object : ContextWrapper(appContext) {
            override fun getResources(): Resources = pluginResources
            override fun getApplicationContext(): Context = this
        }
        try {
            MapLibre.getInstance(
                mapLibreContext,
                null,
                WellKnownTileServer.MapLibre,
            )
        } catch (t: Throwable) {
            android.util.Log.e("MapsPlugin", "MapLibre.getInstance failed", t)
            MapsPlugin.pluginContext?.logger?.warn(
                "MapLibre.getInstance failed: ${t.message}"
            )
        }
    }
}
