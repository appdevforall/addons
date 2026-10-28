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
import org.appdevforall.maps.data.DownloadCompletion
import org.appdevforall.maps.data.ProjectRegionCoordinator
import org.appdevforall.maps.data.RegionCache
import org.appdevforall.maps.data.RegionInfo
import org.appdevforall.maps.data.RegionListActions
import org.appdevforall.maps.data.RegionListActions.ToggleResult
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
        const val SNACK_MAX_LINES = 5
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
    private val listActions = RegionListActions(projectRegions)
    private val downloadCompletion = DownloadCompletion(projectRegions)

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
            // The rows carry the same help, but an empty list has no rows to press.
            it.showMapsTooltipOnLongPress(MapsPlugin.TOOLTIP_TAG_REGION_LIST)
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
        showWizardChrome(R.string.maps_wizard_title_source)
        val frag = SourcePickerFragment.newInstance(
            prefillRegionName = step.prefillRegionName,
            prefillRegionId = step.prefillRegionId,
        )
        showStepFragment(frag, SOURCE_PICKER_TAG)
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
        showWizardChrome(R.string.maps_wizard_title_save)
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
        showStepFragment(frag, STEP3_SAVE_TAG)
    }

    private fun showDownloadProgress(step: Step.DownloadProgress) {
        showWizardChrome(R.string.maps_wizard_title_download)
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
        showStepFragment(frag, DOWNLOAD_PROGRESS_TAG)
    }

    /** The title bar and BACK handling every in-panel wizard step shares. */
    private fun showWizardChrome(titleRes: Int) {
        wizardContainer?.visibility = View.VISIBLE
        wizardTitle?.setText(titleRes)
        btnDownloadNew?.visibility = View.GONE
        onBackPressedCallback.isEnabled = true
    }

    private fun showStepFragment(frag: Fragment, tag: String) {
        childFragmentManager.beginTransaction().replace(R.id.picker_container, frag, tag).commit()
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
        // The policy is FirstRegionAutoActivator's and the message mapping is
        // DownloadCompleteMessage's; both are unit-tested, this only renders.
        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = downloadCompletion.complete(regionId)
            render(wizard.onEvent(Event.DownloadDone))
            if (outcome !is DownloadCompletion.Outcome.Finished) {
                snack(getString(R.string.maps_download_done_no_project, regionId), Snackbar.LENGTH_LONG)
                return@launch
            }
            val spec = DownloadCompleteMessage.forResult(outcome.result, regionId)
            // Indefinite iff there's an action to take, so the user is not racing a timeout to Apply.
            val snackbar = makeSnack(
                getString(spec.messageRes, spec.messageArg),
                if (spec.showApplyAction) Snackbar.LENGTH_INDEFINITE else Snackbar.LENGTH_LONG,
            )
            if (spec.showApplyAction) {
                snackbar.setAction(R.string.maps_download_apply_action) {
                    applyDownloadedRegionFromSnackbar(outcome.projectDir, regionId)
                }
            }
            snackbar.show()
        }
    }

    /** The post-download snackbar's "Apply", for a project that already had an active region. */
    private fun applyDownloadedRegionFromSnackbar(projectDir: File, regionId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (downloadCompletion.applyFromSnackbar(projectDir, regionId)) {
                refresh()
                snack(getString(R.string.maps_regions_switched, regionId), Snackbar.LENGTH_SHORT)
            } else {
                snack(getString(R.string.maps_regions_apply_failed), Snackbar.LENGTH_LONG)
            }
        }
    }

    override fun onDownloadCancelled() {
        render(wizard.onEvent(Event.DownloadCancelled))
        snack(getString(R.string.maps_download_cancelled), Snackbar.LENGTH_SHORT)
    }

    override fun onDownloadFailed(message: String) {
        render(wizard.onEvent(Event.DownloadFailed))
        snack(getString(R.string.maps_download_failed, message), Snackbar.LENGTH_LONG)
    }

    private fun snack(message: String, length: Int) {
        makeSnack(message, length).show()
    }

    // Material's default of 2 lines cut "active region is unchanged" off at font scale 2.0.
    private fun makeSnack(message: String, length: Int): Snackbar =
        Snackbar.make(requireView(), message, length).setTextMaxLines(SNACK_MAX_LINES)

    // ----- RegionAdapter.Listener -----

    override fun onRegionToggleActive(info: RegionInfo, newActive: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = listActions.toggleActive(info, newActive)
            when (result) {
                ToggleResult.NoProject ->
                    snack(getString(R.string.maps_regions_no_project), Snackbar.LENGTH_LONG)
                ToggleResult.ApplyFailed ->
                    snack(getString(R.string.maps_regions_apply_failed), Snackbar.LENGTH_LONG)
                is ToggleResult.Switched -> snack(
                    getString(R.string.maps_region_switched_active, result.fromName, result.toName),
                    Snackbar.LENGTH_SHORT,
                )
                is ToggleResult.Activated ->
                    snack(getString(R.string.maps_region_activated, result.name), Snackbar.LENGTH_SHORT)
                is ToggleResult.Deactivated ->
                    snack(getString(R.string.maps_region_deactivated, result.name), Snackbar.LENGTH_SHORT)
            }
            // A failed apply re-renders too, so the switch snaps back to the stored state.
            if (result != ToggleResult.NoProject) refresh()
        }
    }

    override fun onRegionDelete(info: RegionInfo) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.maps_regions_confirm_delete_title)
            .setMessage(getString(R.string.maps_regions_confirm_delete_message))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.maps_regions_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = listActions.delete(info)
                    val msg = getString(
                        if (ok) R.string.maps_regions_deleted else R.string.maps_regions_delete_failed,
                        info.displayName,
                    )
                    snack(msg, Snackbar.LENGTH_SHORT)
                    refresh()
                }
            }
            .show()
    }

    override fun onRegionHelpRequested(anchor: View): Boolean =
        showMapsTooltip(anchor, MapsPlugin.TOOLTIP_TAG_REGION_LIST)

    override fun onRegionRedownload(info: RegionInfo) {
        // Re-opens the wizard on this region's saved name and bounds; the machine validates them.
        render(wizard.onEvent(listActions.redownloadRequest(info)))
    }
}
