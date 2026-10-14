package org.appdevforall.maps.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.LocationServices
import org.appdevforall.maps.MapsPlugin
import org.appdevforall.maps.R
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * Owns the GPS-plumbing for [BboxPickerFragment] — the location permission check, the
 * permanently-denied recovery dialog, the MapLibre location component (blue dot), and
 * resolving a last-known fix (fused provider → system [LocationManager] fallback).
 *
 * The controller does NOT mutate wizard/bbox state. When it resolves a location it calls
 * [onLocationFix]`(lat, lon)`, and the host Fragment is responsible for the state-mutating
 * tail (anchor lat/lon, the real-GPS-fix flag, and repositioning the camera). This keeps
 * the GPS-resolution concern separate from the wizard's selection state.
 *
 * @param fragment the host Fragment, used for `Context`, permission requests, and the
 *   `view == null` lifecycle guard inside [onLocationFix]'s implementation.
 * @param mapProvider supplies the currently-loaded [MapLibreMap], or null before the map
 *   is ready. Used after a permission grant to enable the blue dot.
 * @param onLocationFix invoked with the resolved latitude/longitude whenever a fix is
 *   found. The host applies it to wizard state and the camera.
 */
class MapLocationController(
    private val fragment: Fragment,
    private val mapProvider: () -> MapLibreMap?,
    private val onLocationFix: (lat: Double, lon: Double) -> Unit,
) {

    /** Guard: only fire the permission ask once per controller instance. */
    private var permissionAsked: Boolean = false

    /**
     * Enable the MapLibre location component (GPS blue dot) using the given
     * [style]. Safe to call after the style is loaded AND location permission
     * is granted. Failures are swallowed — the dot is cosmetic; the bbox
     * picker still works without it.
     *
     * [CameraMode.NONE] keeps the camera on the user-chosen anchor rather than
     * tracking to the GPS fix (we want the user to stay in control of the
     * viewport). [RenderMode.NORMAL] draws the standard blue dot + accuracy
     * circle without the compass bearing arrow.
     */
    @SuppressLint("MissingPermission")
    fun enableLocationComponent(map: MapLibreMap, style: Style) {
        runCatching {
            val locationComponent = map.locationComponent
            locationComponent.activateLocationComponent(
                LocationComponentActivationOptions.builder(fragment.requireContext(), style)
                    .useDefaultLocationEngine(true)
                    .build()
            )
            locationComponent.isLocationComponentEnabled = true
            locationComponent.cameraMode = CameraMode.NONE
            locationComponent.renderMode = RenderMode.NORMAL
        }.onFailure {
            MapsPlugin.pluginContext?.logger?.warn(
                "LocationComponent activation failed: ${it.message}"
            )
        }
    }

    @Suppress("DEPRECATION") // Fragment.requestPermissions is deprecated in
    // favor of ActivityResultContracts.RequestPermission, but the contract API
    // needs ComponentActivity registration that races our plugin-fragment
    // lifecycle. Stick with the legacy API; it still works on target SDK 34.
    fun maybeRequestLocation() {
        val ctx = fragment.context ?: return
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            fetchLastKnownLocation()
            return
        }
        // Detect "permanently denied": the user previously tapped Deny (or "Don't
        // ask again"), so any new requestPermissions call is silently denied with
        // no dialog. Route them to Settings instead of repeating our rationale,
        // which would leave the GPS dot invisible with no recovery.
        @Suppress("DEPRECATION")
        val rationaleAllowed = fragment.shouldShowRequestPermissionRationale(
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (!rationaleAllowed && permissionAsked) {
            // permissionAsked guard: shouldShowRequestPermissionRationale is
            // also false on the very first request before the user has ever
            // answered; we only treat it as "permanently denied" once we've
            // asked at least once this session.
            showPermissionPermanentlyDeniedDialog(ctx)
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.maps_location_perm_title)
            .setMessage(R.string.maps_location_perm_rationale)
            .setPositiveButton(R.string.maps_location_perm_allow) { _, _ ->
                fragment.requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                    BboxPickerFragment.REQUEST_LOCATION_PERMISSION,
                )
            }
            .setNegativeButton(R.string.maps_location_perm_deny) { _, _ ->
                // Stay on the fallback centroid; user can pan/zoom manually.
            }
            .show()
    }

    /** Open the host CoGo app's system Settings page so the user can flip
     *  Location on. After they grant + come back, the Fragment's `onResume` will
     *  re-evaluate and fetch the GPS fix. */
    private fun showPermissionPermanentlyDeniedDialog(ctx: Context) {
        AlertDialog.Builder(ctx)
            .setTitle("Location is off for Code on the Go")
            .setMessage(
                "The wizard uses your current location to center the region " +
                    "you're about to download. Turn Location on for Code on the " +
                    "Go in Settings, then come back."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                runCatching {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", ctx.packageName, null)
                    )
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }.onFailure {
                    android.util.Log.w("BboxPicker", "Open Settings failed: ${it.message}")
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    /**
     * Re-evaluate after a permission result. Call from the Fragment's
     * `onRequestPermissionsResult` when the location permission was just granted:
     * enables the GPS blue dot (style is already loaded by now) and fetches a fix.
     */
    fun onLocationPermissionGranted() {
        // Enable the GPS blue dot now that the style is already loaded and
        // permission has just been granted.
        mapProvider()?.let { map ->
            map.style?.let { style -> enableLocationComponent(map, style) }
        }
        fetchLastKnownLocation()
    }

    @SuppressLint("MissingPermission")
    fun fetchLastKnownLocation() {
        val ctx = fragment.context ?: return
        val client = LocationServices.getFusedLocationProviderClient(ctx)

        // Step 1: fused getLastLocation — fast path. Returns null when no client
        // has recently subscribed to updates (a known fused-provider quirk), even
        // if Android's LocationManager holds an aged fix. Fall back below.
        client.lastLocation
            .addOnSuccessListener { loc: Location? ->
                if (loc != null) {
                    android.util.Log.i("BboxPicker", "fused.lastLocation: ${loc.latitude},${loc.longitude}")
                    onLocationFix(loc.latitude, loc.longitude)
                } else {
                    android.util.Log.i("BboxPicker", "fused.lastLocation returned null; falling back to LocationManager")
                    fallbackToLocationManager()
                }
            }
            .addOnFailureListener {
                android.util.Log.w("BboxPicker", "fused.lastLocation failed: ${it.message}; falling back to LocationManager")
                fallbackToLocationManager()
            }
    }

    /**
     * Permission-tolerant fallback when GMS' fused provider hands back null.
     * Walks the system [LocationManager]'s passive → network → gps providers
     * in order. Passive is cheapest (returns whatever any other app last cached);
     * gps is the most stale-resistant but slowest to a first fix. Any non-null
     * result wins.
     */
    @SuppressLint("MissingPermission")
    private fun fallbackToLocationManager() {
        val ctx = fragment.context ?: return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val providers = listOf(
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        )
        val loc = providers.firstNotNullOfOrNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
                ?.also { android.util.Log.i("BboxPicker", "LocationManager.$provider: ${it.latitude},${it.longitude}") }
        }
        if (loc != null) onLocationFix(loc.latitude, loc.longitude)
        else android.util.Log.i("BboxPicker", "all LocationManager providers returned null — staying at fallback")
    }
}
