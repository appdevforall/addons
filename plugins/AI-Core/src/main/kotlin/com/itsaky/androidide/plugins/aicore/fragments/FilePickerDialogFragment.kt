package com.itsaky.androidide.plugins.aicore.fragments

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.plugins.aicore.plugin.AiCorePlugin
import com.itsaky.androidide.plugins.aicore.R
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeTooltipService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dialog for picking project files to add as chat context.
 *
 * The dialog is created once and refreshed **in place** as the user navigates
 * or toggles selection. It never dismisses-and-recreates itself: doing so
 * detaches the fragment from its context, after which a follow-up
 * `requireContext()` throws `IllegalStateException: not attached to a context`
 * (previously crashed on the second "Toggle All").
 *
 * Navigation is confined to [startPath] — the open project root, which the
 * caller must supply — so the picker can't be used to reach arbitrary files on
 * the device. There is deliberately no fallback root: an unresolvable path
 * shows the not-found message rather than defaulting to somewhere broader.
 * All disk I/O runs off the main thread; see [computeListing].
 */
class FilePickerDialogFragment : DialogFragment() {

    private var onFilesSelected: ((List<File>) -> Unit)? = null
    private val selectedFiles = mutableSetOf<File>()

    /** Root the picker is confined to; navigation can never go above this. */
    private lateinit var rootDirectory: File

    // Reused across in-place refreshes so we never rebuild the Dialog/Fragment.
    private val rows = mutableListOf<FileRow>()
    private lateinit var listAdapter: FileRowAdapter
    private var alertDialog: AlertDialog? = null
    private var tooltipService: IdeTooltipService? = null

    companion object {
        private const val ARG_START_PATH = "start_path"
        private const val PARENT_NAME = ".."

        /**
         * @param startPath the project root to browse; navigation is confined to it.
         */
        fun newInstance(
            startPath: String,
            onSelected: (List<File>) -> Unit
        ): FilePickerDialogFragment {
            return FilePickerDialogFragment().apply {
                arguments = Bundle().apply { putString(ARG_START_PATH, startPath) }
                onFilesSelected = onSelected
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            tooltipService = PluginFragmentHelper.getServiceRegistry(AiCorePlugin.PLUGIN_ID)
                ?.get(IdeTooltipService::class.java)
        } catch (e: Exception) {
            // Tooltip help is optional; long-press simply shows nothing when it's unavailable.
            AiCorePlugin.getContext()?.logger
                ?.warn("FilePickerDialogFragment: tooltip service unavailable", e)
        }
    }

    /** Shows this plugin's context-files tooltip when [view] is long-pressed (Tier 1/2 + guide). */
    private fun wireTooltip(view: View) {
        view.setOnLongClickListener { anchor -> showTooltip(anchor) }
    }

    private fun showTooltip(anchor: View): Boolean {
        val service = tooltipService ?: return false
        service.showTooltip(
            anchor,
            AiCorePlugin.TOOLTIP_CATEGORY,
            AiCorePlugin.TOOLTIP_TAG_CONTEXT_FILES
        )
        return true
    }

    /** One navigable entry: a directory to descend into or a selectable file. */
    private data class FileRow(
        val file: File,
        val displayName: String,
        val isDirectory: Boolean,
    )

    /** Result of a directory scan, computed off the main thread. */
    private data class Listing(val rows: List<FileRow>, val directory: File)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = ContextThemeWrapper(requireContext(), R.style.PluginTheme)
        listAdapter = FileRowAdapter(context)
        val listView = ListView(context).apply {
            adapter = listAdapter
            setOnItemClickListener { _, _, position, _ -> onItemClicked(position) }
            setOnItemLongClickListener { _, itemView, _, _ -> showTooltip(itemView) }
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.file_picker_title)
            .setView(listView)
            .setPositiveButton(R.string.file_picker_add_selected) { _, _ ->
                onFilesSelected?.invoke(selectedFiles.toList())
            }
            .setNegativeButton(android.R.string.cancel, null)
            // Wired in setOnShowListener so the click doesn't auto-dismiss the dialog.
            .setNeutralButton(R.string.file_picker_toggle_all, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                toggleAllInCurrentDirectory()
            }
            listOf(
                AlertDialog.BUTTON_POSITIVE,
                AlertDialog.BUTTON_NEGATIVE,
                AlertDialog.BUTTON_NEUTRAL
            ).mapNotNull { dialog.getButton(it) }.forEach(::wireTooltip)
        }
        alertDialog = dialog
        loadInitial()
        return dialog
    }

    /**
     * Resolve the confined root from the start path and load its listing.
     * The start path passed by the caller IS the confinement root — the picker
     * confines navigation to the open project and starts there.
     *
     * Fails closed: a missing, blank, or non-directory path leaves [rootDirectory]
     * unset and shows the not-found message, so the picker can never silently widen
     * to a broader root (`/` in particular) when the project can't be resolved.
     */
    private fun loadInitial() {
        val rootPath = arguments?.getString(ARG_START_PATH)
        lifecycleScope.launch {
            val listing = withContext(Dispatchers.IO) {
                if (rootPath.isNullOrBlank()) return@withContext null
                val root = File(rootPath).canonicalOrAbsolute()
                if (!root.isDirectory) return@withContext null
                rootDirectory = root
                computeListing(root)
            }
            if (listing == null) {
                alertDialog?.setTitle(getString(R.string.file_picker_error_not_found))
            } else {
                applyListing(listing)
            }
        }
    }

    /** Recompute the listing for [directory] off-thread and apply it in place. */
    private fun populate(directory: File) {
        lifecycleScope.launch {
            val listing = withContext(Dispatchers.IO) { computeListing(directory) }
            applyListing(listing)
        }
    }

    /** Pure disk I/O — must run on a background dispatcher. */
    private fun computeListing(directory: File): Listing {
        val newRows = mutableListOf<FileRow>()

        // Offer ".." only while the parent is still inside the confined root.
        val parent = directory.parentFile
        if (parent != null && parent.exists() && isWithinRoot(parent) &&
            canonical(directory) != canonical(rootDirectory)
        ) {
            newRows.add(FileRow(parent, PARENT_NAME, isDirectory = true))
        }

        directory.listFiles()
            ?.filter { isWithinRoot(it) }
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?.forEach { file -> newRows.add(FileRow(file, file.name, file.isDirectory)) }

        return Listing(newRows, directory)
    }

    /** Swap in a freshly computed [listing] — main thread only. */
    private fun applyListing(listing: Listing) {
        rows.clear()
        rows.addAll(listing.rows)
        listAdapter.notifyDataSetChanged()
        alertDialog?.setTitle(titleFor(listing.directory))
    }

    private fun onItemClicked(position: Int) {
        val row = rows[position]
        if (row.isDirectory) {
            populate(row.file)
        } else {
            if (!selectedFiles.remove(row.file)) selectedFiles.add(row.file)
            listAdapter.notifyDataSetChanged()
        }
    }

    private fun toggleAllInCurrentDirectory() {
        val currentFiles = rows.filter { !it.isDirectory }.map { it.file }
        if (currentFiles.isEmpty()) return
        if (selectedFiles.containsAll(currentFiles)) {
            selectedFiles.removeAll(currentFiles.toSet())
        } else {
            selectedFiles.addAll(currentFiles)
        }
        listAdapter.notifyDataSetChanged()
    }

    private fun titleFor(dir: File): String =
        getString(R.string.file_picker_title_current, dir.name)

    /** True if [file] resolves (symlinks included) to a path at or below [rootDirectory]. */
    private fun isWithinRoot(file: File): Boolean = try {
        val root = rootDirectory.canonicalFile.toPath().normalize()
        file.canonicalFile.toPath().normalize().startsWith(root)
    } catch (e: Exception) {
        false
    }

    private fun canonical(f: File): String =
        try { f.canonicalPath } catch (e: Exception) { f.absolutePath }

    private fun File.canonicalOrAbsolute(): File =
        try { canonicalFile } catch (e: Exception) { absoluteFile }

    override fun onDestroyView() {
        super.onDestroyView()
        onFilesSelected = null
        alertDialog = null
    }

    /** Binds each [FileRow] to [R.layout.item_file_picker]; the check reflects selection. */
    private inner class FileRowAdapter(context: Context) :
        ArrayAdapter<FileRow>(context, 0, rows) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_file_picker, parent, false)
            val row = getItem(position) ?: return view

            view.findViewById<ImageView>(R.id.file_picker_row_icon)
                .setImageResource(if (row.isDirectory) R.drawable.ic_folder else R.drawable.ic_file)
            view.findViewById<TextView>(R.id.file_picker_row_name).text = row.displayName
            view.findViewById<ImageView>(R.id.file_picker_row_check).isVisible =
                selectedFiles.contains(row.file)
            return view
        }
    }
}
