package org.appdevforall.maps.ui

import android.content.Context
import android.net.TrafficStats
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import org.appdevforall.maps.MapsPlugin
import org.appdevforall.maps.R
import org.appdevforall.maps.domain.SourceKind
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Wizard step 1 — Pick Map Data Source.
 *
 * Title is "Map Data Source" (not "IIAB Data Source") since users may not know
 * what IIAB means up front. The radio rows are "Local Network" vs "Internet
 * (iiab.switnet.org)"; the LAN hostname field shows only when Local Network is
 * selected. A binary reachability indicator ("● Reachable" / "● Not reachable" /
 * "● Checking…") sits under the radios; the probe loop ticks every 30s internally.
 *
 * Persistence: LAN host in SharedPreferences (`maps_plugin_prefs / lan_host`).
 *
 * Default selection is **Internet** regardless of any saved LAN host — the
 * primary path is iiab.switnet.org; LAN is for advanced users at an IIAB box.
 */
class SourcePickerFragment : Fragment() {

    interface Listener {
        /**
         * Step 1 complete. Host should advance to Step 2 (bbox picker). Region
         * name is collected in [Step3SaveFragment], not here.
         */
        fun onSourcePickerConfirmed(
            sourceKind: SourceKind,
            sourceHost: String?,
        )

        fun onSourcePickerCancelled()
    }

    companion object {
        const val ARG_PREFILL_REGION_NAME = "prefillRegionName"
        const val ARG_PREFILL_REGION_ID = "prefillRegionId"

        fun newInstance(
            prefillRegionName: String? = null,
            prefillRegionId: String? = null,
        ): SourcePickerFragment = SourcePickerFragment().apply {
            arguments = Bundle().apply {
                if (prefillRegionName != null) putString(ARG_PREFILL_REGION_NAME, prefillRegionName)
                if (prefillRegionId != null) putString(ARG_PREFILL_REGION_ID, prefillRegionId)
            }
        }

        /** SharedPreferences file name. Plugin-namespaced so it doesn't collide. */
        private const val PREFS_NAME = "maps_plugin_prefs"
        private const val KEY_LAN_HOST = "lan_host"

        /** Re-probe cadence when the last result was OK. */
        private const val PROBE_INTERVAL_MS = 30_000L

        /**
         * Re-probe cadence after a failure — 5s instead of 30s so a transient
         * blip (cold-start DNS+TLS to iiab.switnet.org can exceed
         * [PROBE_TIMEOUT_MS]) doesn't strand the UI on "Not reachable" for half a
         * minute; the next probe usually succeeds in under a second once warm.
         */
        private const val PROBE_RETRY_INTERVAL_MS = 5_000L

        /**
         * Per-probe HTTP timeout. 6s covers cold-start DNS resolution + TLS
         * handshake + first request on a slow path; a warm path returns in
         * ~200-500ms.
         */
        private const val PROBE_TIMEOUT_MS = 6_000L

        /**
         * Traffic-stats tag for the reachability HEAD probe. Tagging the thread
         * before the HttpURLConnection call keeps StrictMode's
         * UntaggedSocketViolation quiet and attributes the probe traffic to Maps.
         */
        private const val PROBE_STATS_TAG = 0x4D41_5053 // "MAPS"

        private const val INTERNET_PROBE_URL = "https://iiab.switnet.org/maps/2/"
    }

    // ----- View references -----
    private lateinit var rowLan: LinearLayout
    private lateinit var rowInternet: LinearLayout
    private lateinit var radioLan: RadioButton
    private lateinit var radioInternet: RadioButton
    private lateinit var lanHostLayout: TextInputLayout
    private lateinit var edtLanHost: TextInputEditText
    private lateinit var reachText: TextView
    private lateinit var btnNext: MaterialButton

    // ----- State -----
    private var lanHost: String = ""
    private var selectedSource: SourceKind = SourceKind.UNKNOWN

    private var probeJob: Job? = null

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(MapsPlugin.PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_source_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rowLan = view.findViewById(R.id.row_lan)
        rowInternet = view.findViewById(R.id.row_internet)
        radioLan = view.findViewById(R.id.radio_lan)
        radioInternet = view.findViewById(R.id.radio_internet)
        lanHostLayout = view.findViewById(R.id.lan_host_layout)
        edtLanHost = view.findViewById(R.id.edt_lan_host)
        reachText = view.findViewById(R.id.reach_text)
        btnNext = view.findViewById(R.id.btn_next)

        selectedSource = SourceKind.INTERNET

        // Restore the previously-typed LAN host text so user doesn't have to
        // re-enter it if they switch from Internet → LAN. But always start the
        // wizard on Internet — see class doc. getSharedPreferences()/getString()
        // do a synchronous disk read on first access, so read off the main thread
        // (StrictMode DiskReadViolation otherwise) and apply on Main.
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val savedHost = withContext(Dispatchers.IO) {
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_LAN_HOST, "").orEmpty()
            }
            if (savedHost.isNotBlank()) {
                edtLanHost.setText(savedHost)
                lanHost = savedHost
            }
        }

        edtLanHost.doAfterTextChanged {
            lanHost = it?.toString().orEmpty().trim()
            persistLanHost()
            refreshNextEnabled()
            scheduleImmediateProbe()
        }

        rowLan.setOnClickListener { selectSource(SourceKind.IIAB_LAN) }
        rowInternet.setOnClickListener { selectSource(SourceKind.INTERNET) }

        btnNext.setOnClickListener { confirm() }

        renderSelection()
        refreshNextEnabled()
    }

    override fun onResume() {
        super.onResume()
        startProbeLoop()
    }

    override fun onPause() {
        super.onPause()
        probeJob?.cancel(); probeJob = null
    }

    // ----- Source selection -----

    private fun selectSource(kind: SourceKind) {
        selectedSource = kind
        renderSelection()
        refreshNextEnabled()
        scheduleImmediateProbe()
    }

    private fun renderSelection() {
        radioLan.isChecked = selectedSource == SourceKind.IIAB_LAN
        radioInternet.isChecked = selectedSource == SourceKind.INTERNET
        lanHostLayout.visibility =
            if (selectedSource == SourceKind.IIAB_LAN) View.VISIBLE else View.GONE
    }

    // ----- Probes -----

    /**
     * Probe the currently-selected source every [PROBE_INTERVAL_MS]. Binary reach
     * state only. Falls back to "enter a hostname above" when LAN is selected with
     * no host yet.
     */
    private fun startProbeLoop() {
        probeJob?.cancel()
        probeJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val source = selectedSource
                val hostSnapshot = lanHost
                if (source == SourceKind.IIAB_LAN && hostSnapshot.isBlank()) {
                    showReach(getString(R.string.maps_source_probe_lan_empty))
                    delay(PROBE_INTERVAL_MS)
                    continue
                }
                showReach(getString(R.string.maps_source_probe_checking))
                val url = when (source) {
                    SourceKind.IIAB_LAN -> buildLanProbeUrl(hostSnapshot)
                    SourceKind.INTERNET -> INTERNET_PROBE_URL
                    else -> INTERNET_PROBE_URL
                }
                val reachable = withContext(Dispatchers.IO) { probeHead(url) }
                if (view == null) return@launch
                showReach(
                    if (reachable) getString(R.string.maps_source_probe_reachable)
                    else getString(R.string.maps_source_probe_unreachable)
                )
                // Faster retry after failure — most failures are transient
                // (cold-start DNS / first TLS handshake) and recover on the
                // next attempt.
                delay(if (reachable) PROBE_INTERVAL_MS else PROBE_RETRY_INTERVAL_MS)
            }
        }
    }

    /**
     * Force an immediate re-probe (used when the source selection or LAN
     * host changes). Cancels the running loop and restarts it.
     */
    private fun scheduleImmediateProbe() {
        if (probeJob?.isActive == true) {
            probeJob?.cancel()
            probeJob = null
        }
        if (!isResumed) return
        startProbeLoop()
    }

    private fun showReach(message: String) {
        reachText.text = message
    }

    private suspend fun probeHead(url: String): Boolean = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
        // Tag the socket so the HEAD probe doesn't trip StrictMode's
        // UntaggedSocketViolation (same fix as RangeFetcher's range fetch).
        TrafficStats.setThreadStatsTag(PROBE_STATS_TAG)
        try {
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "HEAD"
                    conn.connectTimeout = PROBE_TIMEOUT_MS.toInt()
                    conn.readTimeout = PROBE_TIMEOUT_MS.toInt()
                    conn.instanceFollowRedirects = true
                    val code = conn.responseCode
                    code in 200..399
                } finally {
                    conn.disconnect()
                }
            }.getOrDefault(false)
        } finally {
            TrafficStats.clearThreadStatsTag()
        }
    } ?: false

    /**
     * LAN probe URL = `http://<host>/maps/extracts.json`. The `extracts.json`
     * file is the canonical IIAB inventory; HEAD on it is the lightest probe
     * that confirms the maps role is reachable. If the host already includes
     * a scheme (`http://` / `https://`) we honour it; otherwise default to
     * plain http (IIAB boxes don't typically run TLS on the LAN).
     */
    private fun buildLanProbeUrl(host: String): String {
        val base = when {
            host.startsWith("http://") || host.startsWith("https://") -> host
            else -> "http://$host"
        }
        return base.trimEnd('/') + "/maps/extracts.json"
    }

    // ----- LAN host persistence -----

    private fun persistLanHost() {
        runCatching {
            // Use applicationContext (same as the off-main read in onViewCreated) so
            // both share the one process-wide SharedPreferencesImpl — no first-access
            // disk read can land on Main here. The write itself is async via apply().
            requireContext().applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAN_HOST, lanHost)
                .apply()
        }.onFailure {
            MapsPlugin.pluginContext?.logger?.warn(
                "Failed to persist LAN host preference: ${it.message}"
            )
        }
    }

    // ----- Next-button gating -----

    private fun refreshNextEnabled() {
        val sourceOk = when (selectedSource) {
            SourceKind.IIAB_LAN -> lanHost.isNotBlank()
            SourceKind.INTERNET -> true
            else -> false
        }
        btnNext.isEnabled = sourceOk
    }

    // ----- Confirm -----

    private fun confirm() {
        if (!btnNext.isEnabled) return
        val host = if (selectedSource == SourceKind.IIAB_LAN) lanHost else null
        (parentFragment as? Listener)?.onSourcePickerConfirmed(
            selectedSource, host
        ) ?: defaultPopBack()
    }

    private fun defaultPopBack() {
        if (parentFragmentManager.backStackEntryCount > 0) {
            parentFragmentManager.popBackStack()
        }
    }
}
