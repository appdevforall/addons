package org.appdevforall.maps.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.appdevforall.maps.MapsPlugin
import org.appdevforall.maps.R
import org.appdevforall.maps.domain.Bbox
import org.appdevforall.maps.domain.SourceKind
import org.appdevforall.maps.domain.TileEstimate
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeProjectService
import org.appdevforall.maps.data.ActiveRegionStore
import org.appdevforall.maps.data.FirstRegionAutoActivator
import org.appdevforall.maps.data.RegionCache
import org.appdevforall.maps.data.RegionDownloader
import org.appdevforall.maps.data.RegionInfo
import org.appdevforall.maps.data.RegionInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Host-resolved Fragment for the "Maps" panel. Registered as a
 * [com.itsaky.androidide.plugins.extensions.TabItem] in [MapsPlugin.getEditorTabs].
 *
 * **Wizard orchestration.** Three titled steps:
 *  - Step 1 [SourcePickerFragment] — pick LAN box / Internet
 *  - Step 2 [BboxPickerFragment] — choose region on a MapLibre map
 *  - Step 3 [Step3SaveFragment] — region name + summary + Save
 *  - then [DownloadProgressFragment] runs the actual download
 *
 * Each step swaps into `picker_container` via `childFragmentManager`; the
 * back/cancel listeners pop back to the previous step.
 *
 * **Active region per project.** Each project's
 * `app/src/main/assets/maps/active.txt` names which cached region is bundled into
 * builds of that project. The first download into an empty cache auto-activates;
 * subsequent regions stay inactive until the user toggles them on (which silently
 * deactivates the previously-active one — only one active per project).
 */
class RegionManagerFragment : Fragment(),
    RegionAdapter.Listener,
    BboxPickerFragment.Listener,
    SourcePickerFragment.Listener,
    Step3SaveFragment.Listener,
    DownloadProgressFragment.Listener {

    private companion object {
        const val SOURCE_PICKER_TAG = "maps_source_picker"
        const val BBOX_PICKER_TAG = "maps_bbox_picker"
        const val STEP3_SAVE_TAG = "maps_step3_save"
        const val DOWNLOAD_PROGRESS_TAG = "maps_download_progress"

        /**
         * Default subpath under the open project where this plugin lands map data.
         * Used both for "use in this project" copies and the per-project active.txt
         * sentinel.
         */
        const val DEFAULT_PROJECT_MAPS_SUBPATH = "app/src/main/assets/maps"

        /** Hardcoded Internet tile source. The source-picker UI is bypassed —
         *  users don't see or select a source. */
        const val DEFAULT_INTERNET_HOST = "iiab.switnet.org"
    }

    private var listView: RecyclerView? = null
    private var emptyState: View? = null
    private var btnDownloadNew: MaterialButton? = null
    private var listContainer: View? = null
    private var pickerContainer: View? = null
    private var wizardContainer: View? = null
    private var wizardTitle: android.widget.TextView? = null

    /** OnBackPressedCallback active only while a wizard step is showing, so
     *  Android BACK exits the wizard to the region list rather than collapsing
     *  the whole bottom sheet. */
    private val onBackPressedCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showList()
        }
    }
    private val adapter = RegionAdapter(this)

    // ----- Wizard step-machine state (held across step transitions) -----
    // Source selection isn't user-visible: always download from the Internet IIAB
    // mirror. SourcePickerFragment is bypassed but kept for a possible LAN-select
    // return.
    private var wizardSourceKind: SourceKind = SourceKind.INTERNET
    private var wizardSourceHost: String? = DEFAULT_INTERNET_HOST
    private var wizardBbox: Bbox? = null
    private var wizardEstimate: TileEstimate? = null
    private var wizardPrefillRegionId: String? = null
    private var wizardPrefillRegionName: String? = null

    /**
     * Resolve the live [PluginContext] from [MapsPlugin]'s static. Read on
     * every access (volatile) so a plugin reload doesn't leave us holding a
     * stale reference. Null when the plugin has been disposed.
     */
    private val pluginContext: PluginContext?
        get() = MapsPlugin.pluginContext

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(MapsPlugin.PLUGIN_ID, inflater)
    }

    /**
     * Plugin classes (the wizard's children — [SourcePickerFragment],
     * [BboxPickerFragment], etc.) are loaded by the plugin's DexClassLoader,
     * not the host's. When the host's parent FragmentManager restores its
     * tabs via ViewPager2's FragmentStateAdapter, it indirectly restores
     * the child fragments inside *this* fragment — and `childFragmentManager`
     * defaults to the host's [androidx.fragment.app.FragmentFactory], which
     * uses the host's class loader. That throws `ClassNotFoundException` for
     * every plugin-defined child fragment.
     *
     * Override `childFragmentManager.fragmentFactory` BEFORE
     * `super.onCreate(savedInstanceState)` so the factory is set when the
     * FragmentManager calls into it during state restore.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        childFragmentManager.fragmentFactory = PluginChildFragmentFactory(
            this::class.java.classLoader!!,
        )
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_region_manager, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listContainer = view.findViewById(R.id.list_container)
        pickerContainer = view.findViewById(R.id.picker_container)
        wizardContainer = view.findViewById(R.id.wizard_container)
        wizardTitle = view.findViewById(R.id.wizard_title)
        listView = view.findViewById<RecyclerView>(R.id.regions_list).also { rv ->
            rv.layoutManager = LinearLayoutManager(requireContext())
            rv.adapter = adapter
        }
        emptyState = view.findViewById(R.id.empty_state)
        btnDownloadNew = view.findViewById<MaterialButton>(R.id.btn_download_new).also {
            it.setOnClickListener {
                wizardPrefillRegionId = null
                wizardPrefillRegionName = null
                showSourcePicker(prefillFrom = null)
            }
        }
        view.findViewById<android.widget.ImageButton>(R.id.wizard_close)?.setOnClickListener {
            showList()
        }
        // BACK in a wizard step exits the wizard, not the bottom sheet.
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, onBackPressedCallback)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Reload from disk and toggle empty / list visibility accordingly. */
    private fun refresh() {
        val container = listContainer ?: return
        val empty = emptyState ?: return
        val rv = listView ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            data class RefreshState(
                val rows: List<RegionRow>,
                val isEmpty: Boolean,
            )
            val state = withContext(Dispatchers.IO) {
                val items = RegionCache.list()
                val activeId = readActiveRegionId()
                val rows = items.map { info ->
                    RegionRow(info = info, isActiveInProject = info.regionId == activeId)
                }
                RefreshState(rows, items.isEmpty())
            }
            adapter.submit(state.rows)
            if (container.visibility == View.VISIBLE) {
                rv.visibility = if (state.isEmpty) View.GONE else View.VISIBLE
                empty.visibility = if (state.isEmpty) View.VISIBLE else View.GONE
            }
        }
    }

    // ----- Wizard step transitions -----

    private fun showSourcePicker(prefillFrom: RegionInfo?) {
        val list = listContainer ?: return
        list.visibility = View.GONE
        wizardContainer?.visibility = View.VISIBLE
        wizardTitle?.setText(R.string.maps_wizard_title_source)
        btnDownloadNew?.visibility = View.GONE
        onBackPressedCallback.isEnabled = true
        wizardPrefillRegionId = prefillFrom?.regionId
        wizardPrefillRegionName = prefillFrom?.displayName
        val frag = SourcePickerFragment.newInstance(
            prefillRegionName = prefillFrom?.displayName,
            prefillRegionId = prefillFrom?.regionId,
        )
        childFragmentManager.beginTransaction()
            .replace(R.id.picker_container, frag, SOURCE_PICKER_TAG)
            .commit()
    }

    private fun showBboxPicker() {
        // BboxPickerFragment is a DialogFragment so the map UI runs in its own
        // Window above the activity — escapes CoGo's ContentTranslatingDrawerLayout,
        // which ignores setDrawerLockMode and hijacks right-swipes on the map. The
        // dialog floats over the regions list, so we don't touch the containers.
        //
        // Hide the FAB for a clean modal; restored when the dialog dismisses.
        btnDownloadNew?.visibility = View.GONE
        // DialogFragment intercepts BACK itself, so disable our callback while up.
        onBackPressedCallback.isEnabled = false
        val frag = BboxPickerFragment.newInstance(
            prefillRegionId = wizardPrefillRegionId,
            prefillDisplayName = wizardPrefillRegionName,
            prefillBbox = wizardBbox?.toBoundsArray(),
            sourceKind = wizardSourceKind,
            sourceHost = wizardSourceHost,
        )
        frag.show(childFragmentManager, BBOX_PICKER_TAG)
    }

    private fun showStep3Save() {
        wizardContainer?.visibility = View.VISIBLE
        wizardTitle?.setText(R.string.maps_wizard_title_save)
        btnDownloadNew?.visibility = View.GONE
        onBackPressedCallback.isEnabled = true
        val bbox = wizardBbox ?: return
        // wizardEstimate is null when the user tapped Next before the slicer
        // returned; Step 3 handles that as "Calculating download size…".
        val frag = Step3SaveFragment.newInstance(
            sourceKind = wizardSourceKind,
            sourceHost = wizardSourceHost,
            bbox = bbox,
            estimate = wizardEstimate,
            prefillRegionId = wizardPrefillRegionId,
            prefillDisplayName = wizardPrefillRegionName,
        )
        childFragmentManager.beginTransaction()
            .replace(R.id.picker_container, frag, STEP3_SAVE_TAG)
            .commit()
    }

    private fun showDownloadProgress(displayName: String, regionId: String) {
        wizardContainer?.visibility = View.VISIBLE
        wizardTitle?.setText(R.string.maps_wizard_title_download)
        btnDownloadNew?.visibility = View.GONE
        onBackPressedCallback.isEnabled = true
        val bbox = wizardBbox ?: return
        val frag = DownloadProgressFragment.newInstance(
            regionId = regionId,
            displayName = displayName,
            bbox = bbox,
            sourceKind = wizardSourceKind,
            sourceHost = wizardSourceHost,
            // Thread the picker's auto-capped zoom range to the downloader.
            // Without it the downloader's z=6..14 default kicks in, downloading
            // ~16× more tiles per extra zoom level.
            zoomMin = wizardEstimate?.zoomMin ?: 6,
            zoomMax = wizardEstimate?.zoomMax ?: 14,
        )
        childFragmentManager.beginTransaction()
            .replace(R.id.picker_container, frag, DOWNLOAD_PROGRESS_TAG)
            .commit()
    }

    private fun showList() {
        val list = listContainer ?: return
        onBackPressedCallback.isEnabled = false
        // Tear down any wizard fragments so their lifecycles end.
        listOf(BBOX_PICKER_TAG, SOURCE_PICKER_TAG, STEP3_SAVE_TAG, DOWNLOAD_PROGRESS_TAG)
            .forEach { tag ->
                val frag = childFragmentManager.findFragmentByTag(tag)
                if (frag != null) {
                    childFragmentManager.beginTransaction().remove(frag).commit()
                }
            }
        // Reset wizard state.
        wizardSourceKind = SourceKind.UNKNOWN
        wizardSourceHost = null
        wizardBbox = null
        wizardEstimate = null
        wizardPrefillRegionId = null
        wizardPrefillRegionName = null

        wizardContainer?.visibility = View.GONE
        list.visibility = View.VISIBLE
        btnDownloadNew?.visibility = View.VISIBLE
        refresh()
    }

    // ----- SourcePickerFragment.Listener (Step 1 → Step 2) -----

    override fun onSourcePickerConfirmed(
        sourceKind: SourceKind,
        sourceHost: String?,
    ) {
        wizardSourceKind = sourceKind
        wizardSourceHost = sourceHost
        showBboxPicker()
    }

    override fun onSourcePickerCancelled() = showList()

    // ----- BboxPickerFragment.Listener (Step 2 → Step 3 / Back to Step 1) -----

    override fun onBboxPickerNext(
        bbox: Bbox,
        estimate: TileEstimate?,
        prefillRegionId: String?,
        prefillRegionName: String?,
    ) {
        wizardBbox = bbox
        wizardEstimate = estimate
        // Refresh-flow ids/names propagate (won't normally change at Step 2).
        if (prefillRegionId != null) wizardPrefillRegionId = prefillRegionId
        if (prefillRegionName != null) wizardPrefillRegionName = prefillRegionName
        dismissBboxPickerDialog()
        showStep3Save()
    }

    override fun onBboxPickerBack() {
        dismissBboxPickerDialog()
        // Source step is bypassed (Internet default), so Back returns to the list.
        showList()
    }

    /**
     * The bbox picker is a DialogFragment; we must `dismiss()` it explicitly
     * before transitioning to the next wizard step. Other Step fragments
     * remain in the wizard_container FrameLayout, so we also have to bring
     * the wizard container back to visible state.
     */
    private fun dismissBboxPickerDialog() {
        val frag = childFragmentManager.findFragmentByTag(BBOX_PICKER_TAG)
                as? androidx.fragment.app.DialogFragment
        frag?.dismissAllowingStateLoss()
    }

    // ----- Step3SaveFragment.Listener (Step 3 → Download / Back to Step 2) -----

    override fun onSaveRegionConfirmed(
        displayName: String,
        regionId: String,
    ) {
        wizardPrefillRegionName = displayName
        wizardPrefillRegionId = regionId
        showDownloadProgress(displayName, regionId)
    }

    override fun onSaveRegionBack() = showBboxPicker()

    // ----- DownloadProgressFragment.Listener (download completion / cancel) -----

    override fun onDownloadComplete(regionId: String) {
        // Auto-activation for the FIRST region in this project. See
        // [FirstRegionAutoActivator] for the policy + the tested path.
        //
        // Branch behavior:
        //   - First region (no active.txt) → silently apply + activate.
        //   - Subsequent region (active.txt already set) → leave it untouched, but
        //     surface an "Apply" action on the Snackbar for a one-tap switch.
        //   - Apply-failed / region-not-found → surface the reason so the user
        //     knows why nothing happened.
        viewLifecycleOwner.lifecycleScope.launch {
            // currentProjectRoot() reaches IdeProjectService, which does disk I/O —
            // keep it off the Main dispatcher (StrictMode DiskReadViolation otherwise,
            // same root cause as the toggle/delete paths).
            val projectDir = withContext(Dispatchers.IO) { currentProjectRoot() }
            if (projectDir == null) {
                showList()
                Snackbar.make(
                    requireView(),
                    "Region downloaded: $regionId (no project open — open one to use it)",
                    Snackbar.LENGTH_LONG,
                ).show()
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                FirstRegionAutoActivator.maybeAutoActivate(
                    projectDir = projectDir,
                    mapsSubpath = DEFAULT_PROJECT_MAPS_SUBPATH,
                    regionsCacheRoot = RegionCache.rootDir(),
                    downloadedRegionId = regionId,
                    applyRegionToProject = ::applyRegionToProject,
                    writeActiveRegion = ::writeActiveRegionId,
                )
            }
            showList()
            val snackbar = when (result) {
                is FirstRegionAutoActivator.Result.Activated -> Snackbar.make(
                    requireView(),
                    "Region downloaded and applied to project: ${result.displayName}",
                    Snackbar.LENGTH_LONG,
                )
                is FirstRegionAutoActivator.Result.NoOpAlreadyActive -> {
                    Snackbar.make(
                        requireView(),
                        "Region downloaded: $regionId. Project's active region is unchanged.",
                        Snackbar.LENGTH_INDEFINITE,
                    ).setAction("Apply") {
                        applyDownloadedRegionFromSnackbar(projectDir, regionId)
                    }
                }
                is FirstRegionAutoActivator.Result.NoOpRegionNotFound -> Snackbar.make(
                    requireView(),
                    "Region downloaded but couldn't be located in cache: $regionId",
                    Snackbar.LENGTH_LONG,
                )
                is FirstRegionAutoActivator.Result.ApplyFailed -> Snackbar.make(
                    requireView(),
                    "Region downloaded; apply failed: ${result.reason}",
                    Snackbar.LENGTH_LONG,
                )
            }
            snackbar.show()
        }
    }

    /**
     * "Apply" action on the post-download Snackbar when the project already
     * had an active region. Mirrors the toggle-on path in
     * [onRegionToggleActive] — copies files, writes active.txt, refreshes
     * the list. No-op if the regionId isn't in the cache (shouldn't happen,
     * we just downloaded it).
     */
    private fun applyDownloadedRegionFromSnackbar(projectDir: File, regionId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val applied = withContext(Dispatchers.IO) {
                val info = runCatching { RegionCache.list() }.getOrNull()
                    ?.firstOrNull { it.regionId == regionId } ?: return@withContext false
                val ok = applyRegionToProject(info, projectDir)
                if (ok) writeActiveRegionId(projectDir, info.regionId)
                ok
            }
            if (applied) {
                refresh()
                Snackbar.make(
                    requireView(),
                    "Switched active region to: $regionId",
                    Snackbar.LENGTH_SHORT,
                ).show()
            } else {
                Snackbar.make(
                    requireView(),
                    getString(R.string.maps_regions_apply_failed),
                    Snackbar.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onDownloadCancelled() {
        showList()
        Snackbar.make(
            requireView(),
            "Download cancelled — partial files removed",
            Snackbar.LENGTH_SHORT,
        ).show()
    }

    override fun onDownloadFailed(message: String) {
        showList()
        Snackbar.make(
            requireView(),
            "Download failed: $message",
            Snackbar.LENGTH_LONG,
        ).show()
    }

    // ----- RegionAdapter.Listener -----

    override fun onRegionToggleActive(info: RegionInfo, newActive: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val projectDir = withContext(Dispatchers.IO) { currentProjectRoot() }
            if (projectDir == null) {
                Snackbar.make(
                    requireView(),
                    R.string.maps_regions_no_project,
                    Snackbar.LENGTH_LONG,
                ).show()
                return@launch
            }
            val priorActive = withContext(Dispatchers.IO) { readActiveRegionId(projectDir) }
            val priorName = priorActive?.let { activeId ->
                withContext(Dispatchers.IO) { runCatching { RegionCache.list() }.getOrNull() }
                    ?.firstOrNull { it.regionId == activeId }?.displayName
            }
            val ok = withContext(Dispatchers.IO) {
                if (newActive) {
                    // Activate this region, deactivating any previously-active one
                    // implicitly (write-and-replace).
                    val applied = applyRegionToProject(info, projectDir)
                    if (applied) writeActiveRegionId(projectDir, info.regionId)
                    applied
                } else {
                    // Deactivate: clear active.txt.
                    clearActiveRegionId(projectDir)
                    true
                }
            }
            if (!ok) {
                Snackbar.make(
                    requireView(),
                    getString(R.string.maps_regions_apply_failed),
                    Snackbar.LENGTH_LONG,
                ).show()
                refresh()
                return@launch
            }
            val statusMsg = when {
                newActive && priorActive != null && priorActive != info.regionId ->
                    getString(
                        R.string.maps_region_switched_active,
                        priorName ?: priorActive,
                        info.displayName,
                    )
                newActive -> getString(R.string.maps_region_activated, info.displayName)
                else -> getString(R.string.maps_region_deactivated, info.displayName)
            }
            Snackbar.make(requireView(), statusMsg, Snackbar.LENGTH_SHORT).show()
            refresh()
        }
    }

    override fun onRegionDelete(info: RegionInfo) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.maps_regions_confirm_delete_title)
            .setMessage(getString(R.string.maps_regions_confirm_delete_message))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.maps_regions_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { RegionCache.delete(info.regionId) }
                    if (ok) {
                        // If the deleted region is the project's active one, clear
                        // the sentinel so a subsequent download can auto-activate
                        // and the on-disk state stays consistent.
                        withContext(Dispatchers.IO) {
                            val projectDir = currentProjectRoot()
                            if (projectDir != null &&
                                readActiveRegionId(projectDir) == info.regionId
                            ) {
                                clearActiveRegionId(projectDir)
                            }
                        }
                    }
                    val msg = if (ok) "Deleted ${info.displayName}"
                    else "Couldn't delete ${info.displayName}"
                    Snackbar.make(requireView(), msg, Snackbar.LENGTH_SHORT).show()
                    refresh()
                }
            }
            .show()
    }

    override fun onRegionRedownload(info: RegionInfo) {
        // Refresh re-opens the wizard pre-filled with this region's
        // existing name + bbox, so the user re-picks a source.
        wizardPrefillRegionId = info.regionId
        wizardPrefillRegionName = info.displayName
        wizardBbox = info.bbox?.takeIf { it.size == 4 }?.let {
            runCatching { Bbox(it[0], it[1], it[2], it[3]) }.getOrNull()
        }
        showSourcePicker(prefillFrom = info)
    }

    // ----- Project / asset wiring -----

    /**
     * Resolve the current project root via [IdeProjectService]. Returns null
     * when no project is open or the service isn't available (running in
     * standalone test mode).
     */
    private fun currentProjectRoot(): File? {
        val ctx = pluginContext ?: return null
        val project = ctx.services.get(IdeProjectService::class.java)?.getCurrentProject()
        return project?.rootDir
    }

    /**
     * Read the active regionId for the currently-open project, or null when no
     * project is open. Thin delegate to [ActiveRegionStore.read].
     */
    private fun readActiveRegionId(): String? {
        val projectDir = currentProjectRoot() ?: return null
        return readActiveRegionId(projectDir)
    }

    private fun readActiveRegionId(projectDir: File): String? =
        ActiveRegionStore.read(projectDir, DEFAULT_PROJECT_MAPS_SUBPATH)

    private fun writeActiveRegionId(projectDir: File, regionId: String) =
        ActiveRegionStore.write(projectDir, DEFAULT_PROJECT_MAPS_SUBPATH, regionId)

    private fun clearActiveRegionId(projectDir: File) =
        ActiveRegionStore.clear(projectDir, DEFAULT_PROJECT_MAPS_SUBPATH)

    /**
     * Copy the cached region's data files into [projectDir]'s fixed flat maps
     * assets, overwriting any previous region. Pure data copy — the project ships
     * its own MapLibre wiring (see [org.appdevforall.maps.templates.MapTemplateBuilder]).
     * Fails if the project isn't a Maps project (no `MapRegionActivity`).
     *
     * The per-project active state (active.txt) is written separately via
     * [writeActiveRegionId] so the toggle flow can decouple "copy data" from
     * "mark active".
     */
    private suspend fun applyRegionToProject(
        info: RegionInfo,
        projectDir: File,
    ): Boolean {
        // Capture the calling coroutine's context so the (potentially 100+ MB)
        // tiles.pmtiles copy checks for cancellation between chunks — closing the
        // bottom sheet / switching projects mid-copy aborts promptly.
        val ctx = coroutineContext
        return RegionInstaller.apply(
            info = info,
            projectDir = projectDir,
            logError = { msg, t -> pluginContext?.logger?.error(msg, t) },
            onChunk = { ctx.ensureActive() },
        )
    }

}
