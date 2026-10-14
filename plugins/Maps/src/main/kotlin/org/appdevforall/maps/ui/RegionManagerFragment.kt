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
import org.appdevforall.maps.domain.RegionWizardStateMachine
import org.appdevforall.maps.domain.RegionWizardStateMachine.Event
import org.appdevforall.maps.domain.RegionWizardStateMachine.Step
import org.appdevforall.maps.domain.SourceKind
import org.appdevforall.maps.domain.TileEstimate
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import org.appdevforall.maps.data.DownloadCompleteMessage
import org.appdevforall.maps.data.FirstRegionAutoActivator
import org.appdevforall.maps.data.ProjectRegionCoordinator
import org.appdevforall.maps.data.RegionCache
import org.appdevforall.maps.data.RegionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
 * The step *sequencing* (which step follows which event, with what state)
 * lives in the pure, unit-tested [RegionWizardStateMachine]; this Fragment is
 * the executor — each listener callback routes an [Event] into the machine and
 * renders the returned [Step] by swapping fragments into `picker_container`
 * via `childFragmentManager` and flipping container visibility.
 *
 * **Active region per project.** Each project's
 * `app/src/main/assets/maps/active.txt` names which cached region is bundled into
 * builds of that project. The first download into an empty cache auto-activates;
 * subsequent regions stay inactive until the user toggles them on (which silently
 * deactivates the previously-active one — only one active per project). The
 * project-facing file I/O behind all of that is owned by [ProjectRegionCoordinator].
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
            render(wizard.onEvent(Event.Exit))
        }
    }
    private val adapter = RegionAdapter(this)

    /** The wizard's step-sequencing brain; see the class KDoc's split of responsibilities. */
    private val wizard = RegionWizardStateMachine()

    /**
     * Project/active-region facade — resolves the open project and owns the
     * active.txt + region-copy file I/O this Fragment used to route itself.
     * Resolves the live [MapsPlugin.pluginContext] on every call (volatile) so
     * a plugin reload doesn't leave it holding a stale reference.
     */
    private val projectRegions = ProjectRegionCoordinator(
        pluginContextProvider = { MapsPlugin.pluginContext },
    )

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater =
        themedPluginInflater(super.onGetLayoutInflater(savedInstanceState))

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
                render(wizard.onEvent(Event.NewDownloadRequested))
            }
        }
        view.findViewById<android.widget.ImageButton>(R.id.wizard_close)?.setOnClickListener {
            render(wizard.onEvent(Event.Exit))
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
                val activeId = projectRegions.readActiveRegionId()
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

    // ----- Wizard step rendering (the machine decides; this Fragment executes) -----

    /** Turn the machine's next [Step] into the matching fragment transaction / visibility flip. */
    private fun render(step: Step) {
        when (step) {
            is Step.RegionList -> showList()
            is Step.SourcePicker -> showSourcePicker(step)
            is Step.BboxPicker -> showBboxPicker(step)
            is Step.SaveStep -> showStep3Save(step)
            is Step.DownloadProgress -> showDownloadProgress(step)
        }
    }

    private fun showSourcePicker(step: Step.SourcePicker) {
        val list = listContainer ?: return
        list.visibility = View.GONE
        wizardContainer?.visibility = View.VISIBLE
        wizardTitle?.setText(R.string.maps_wizard_title_source)
        btnDownloadNew?.visibility = View.GONE
        onBackPressedCallback.isEnabled = true
        val frag = SourcePickerFragment.newInstance(
            prefillRegionName = step.prefillRegionName,
            prefillRegionId = step.prefillRegionId,
        )
        childFragmentManager.beginTransaction()
            .replace(R.id.picker_container, frag, SOURCE_PICKER_TAG)
            .commit()
    }

    private fun showBboxPicker(step: Step.BboxPicker) {
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
            prefillRegionId = step.prefillRegionId,
            prefillDisplayName = step.prefillRegionName,
            prefillBbox = step.prefillBbox?.toBoundsArray(),
            sourceKind = step.sourceKind,
            sourceHost = step.sourceHost,
        )
        frag.show(childFragmentManager, BBOX_PICKER_TAG)
    }

    private fun showStep3Save(step: Step.SaveStep) {
        wizardContainer?.visibility = View.VISIBLE
        wizardTitle?.setText(R.string.maps_wizard_title_save)
        btnDownloadNew?.visibility = View.GONE
        onBackPressedCallback.isEnabled = true
        val bbox = step.bbox ?: return
        // estimate is null when the user tapped Next before the slicer
        // returned; Step 3 handles that as "Calculating download size…".
        val frag = Step3SaveFragment.newInstance(
            sourceKind = step.sourceKind,
            sourceHost = step.sourceHost,
            bbox = bbox,
            estimate = step.estimate,
            prefillRegionId = step.prefillRegionId,
            prefillDisplayName = step.prefillRegionName,
        )
        childFragmentManager.beginTransaction()
            .replace(R.id.picker_container, frag, STEP3_SAVE_TAG)
            .commit()
    }

    private fun showDownloadProgress(step: Step.DownloadProgress) {
        wizardContainer?.visibility = View.VISIBLE
        wizardTitle?.setText(R.string.maps_wizard_title_download)
        btnDownloadNew?.visibility = View.GONE
        onBackPressedCallback.isEnabled = true
        val bbox = step.bbox ?: return
        val frag = DownloadProgressFragment.newInstance(
            regionId = step.regionId,
            displayName = step.displayName,
            bbox = bbox,
            sourceKind = step.sourceKind,
            sourceHost = step.sourceHost,
            // The machine threaded the picker's auto-capped zoom range through
            // (falling back to the downloader's z=6..14 default).
            zoomMin = step.zoomMin,
            zoomMax = step.zoomMax,
        )
        childFragmentManager.beginTransaction()
            .replace(R.id.picker_container, frag, DOWNLOAD_PROGRESS_TAG)
            .commit()
    }

    private fun showList() {
        val list = listContainer ?: return
        onBackPressedCallback.isEnabled = false
        // Tear down any wizard fragments so their lifecycles end. (The wizard
        // *state* reset already happened inside the machine's Exit/Download*
        // handling.)
        listOf(BBOX_PICKER_TAG, SOURCE_PICKER_TAG, STEP3_SAVE_TAG, DOWNLOAD_PROGRESS_TAG)
            .forEach { tag ->
                val frag = childFragmentManager.findFragmentByTag(tag)
                if (frag != null) {
                    childFragmentManager.beginTransaction().remove(frag).commit()
                }
            }
        wizardContainer?.visibility = View.GONE
        list.visibility = View.VISIBLE
        btnDownloadNew?.visibility = View.VISIBLE
        refresh()
    }

    // ----- SourcePickerFragment.Listener (Step 1 → Step 2) -----

    override fun onSourcePickerConfirmed(
        sourceKind: SourceKind,
        sourceHost: String?,
    ) = render(wizard.onEvent(Event.SourceConfirmed(sourceKind, sourceHost)))

    override fun onSourcePickerCancelled() = render(wizard.onEvent(Event.Exit))

    // ----- BboxPickerFragment.Listener (Step 2 → Step 3 / Back to list) -----

    override fun onBboxPickerNext(
        bbox: Bbox,
        estimate: TileEstimate?,
        prefillRegionId: String?,
        prefillRegionName: String?,
    ) {
        dismissBboxPickerDialog()
        render(wizard.onEvent(Event.BboxPicked(bbox, estimate, prefillRegionId, prefillRegionName)))
    }

    override fun onBboxPickerBack() {
        dismissBboxPickerDialog()
        // Source step is bypassed (Internet default), so Back returns to the list.
        render(wizard.onEvent(Event.Exit))
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
    ) = render(wizard.onEvent(Event.SaveConfirmed(displayName, regionId)))

    override fun onSaveRegionBack() = render(wizard.onEvent(Event.SaveBackRequested))

    // ----- DownloadProgressFragment.Listener (download completion / cancel) -----

    override fun onDownloadComplete(regionId: String) {
        // Auto-activation for the FIRST region in this project. See
        // [FirstRegionAutoActivator] for the policy + the tested path, and
        // [DownloadCompleteMessage] for the (also tested) result → Snackbar
        // mapping this Fragment renders.
        viewLifecycleOwner.lifecycleScope.launch {
            // currentProjectRoot() reaches IdeProjectService, which does disk I/O —
            // keep it off the Main dispatcher (StrictMode DiskReadViolation otherwise,
            // same root cause as the toggle/delete paths).
            val projectDir = withContext(Dispatchers.IO) { projectRegions.currentProjectRoot() }
            if (projectDir == null) {
                render(wizard.onEvent(Event.DownloadDone))
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
                    mapsSubpath = ProjectRegionCoordinator.DEFAULT_PROJECT_MAPS_SUBPATH,
                    regionsCacheRoot = RegionCache.rootDir(),
                    downloadedRegionId = regionId,
                    applyRegionToProject = projectRegions::applyRegionToProject,
                    writeActiveRegion = projectRegions::writeActiveRegionId,
                )
            }
            render(wizard.onEvent(Event.DownloadDone))
            val spec = DownloadCompleteMessage.forResult(result, regionId)
            val snackbar = Snackbar.make(
                requireView(),
                spec.message,
                // Indefinite iff there's an action to take — give the user time
                // to tap "Apply" instead of racing a timeout.
                if (spec.showApplyAction) Snackbar.LENGTH_INDEFINITE else Snackbar.LENGTH_LONG,
            )
            if (spec.showApplyAction) {
                snackbar.setAction("Apply") {
                    applyDownloadedRegionFromSnackbar(projectDir, regionId)
                }
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
                projectRegions.applyAndActivate(info, projectDir)
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
        render(wizard.onEvent(Event.DownloadCancelled))
        Snackbar.make(
            requireView(),
            "Download cancelled — partial files removed",
            Snackbar.LENGTH_SHORT,
        ).show()
    }

    override fun onDownloadFailed(message: String) {
        render(wizard.onEvent(Event.DownloadFailed))
        Snackbar.make(
            requireView(),
            "Download failed: $message",
            Snackbar.LENGTH_LONG,
        ).show()
    }

    // ----- RegionAdapter.Listener -----

    override fun onRegionToggleActive(info: RegionInfo, newActive: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val projectDir = withContext(Dispatchers.IO) { projectRegions.currentProjectRoot() }
            if (projectDir == null) {
                Snackbar.make(
                    requireView(),
                    R.string.maps_regions_no_project,
                    Snackbar.LENGTH_LONG,
                ).show()
                return@launch
            }
            val priorActive = withContext(Dispatchers.IO) {
                projectRegions.readActiveRegionId(projectDir)
            }
            val priorName = priorActive?.let { activeId ->
                withContext(Dispatchers.IO) { runCatching { RegionCache.list() }.getOrNull() }
                    ?.firstOrNull { it.regionId == activeId }?.displayName
            }
            val ok = withContext(Dispatchers.IO) {
                if (newActive) {
                    // Activate this region, deactivating any previously-active one
                    // implicitly (write-and-replace).
                    projectRegions.applyAndActivate(info, projectDir)
                } else {
                    // Deactivate: clear active.txt.
                    projectRegions.clearActiveRegionId(projectDir)
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
                            val projectDir = projectRegions.currentProjectRoot()
                            if (projectDir != null &&
                                projectRegions.readActiveRegionId(projectDir) == info.regionId
                            ) {
                                projectRegions.clearActiveRegionId(projectDir)
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
        // existing name + bbox, so the user re-picks a source. The machine
        // parses (and validates) the raw bounds tuple.
        render(
            wizard.onEvent(
                Event.RedownloadRequested(
                    regionId = info.regionId,
                    displayName = info.displayName,
                    bbox = info.bbox,
                ),
            ),
        )
    }
}
