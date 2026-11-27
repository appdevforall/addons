package org.appdevforall.codeonthego.layouteditor

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.fragment.app.Fragment
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.GravityCompat
import androidx.core.view.isEmpty
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeUIService
import com.itsaky.androidide.utils.getCreatedTime
import com.itsaky.androidide.utils.getLastModifiedTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.appdevforall.codeonthego.layouteditor.LayoutFile
import org.appdevforall.codeonthego.layouteditor.ProjectFile
import org.appdevforall.codeonthego.layouteditor.R
import org.appdevforall.codeonthego.layouteditor.R.string
import org.appdevforall.codeonthego.layouteditor.adapters.LayoutListAdapter
import org.appdevforall.codeonthego.layouteditor.adapters.PaletteListAdapter
import org.appdevforall.codeonthego.layouteditor.databinding.ActivityLayoutEditorBinding
import org.appdevforall.codeonthego.layouteditor.editor.DesignEditor
import org.appdevforall.codeonthego.layouteditor.editor.DeviceConfiguration
import org.appdevforall.codeonthego.layouteditor.editor.DeviceSize
import org.appdevforall.codeonthego.layouteditor.editor.convert.ConvertImportedXml
import org.appdevforall.codeonthego.layouteditor.managers.DrawableManager
import org.appdevforall.codeonthego.layouteditor.managers.IdManager.clear
import org.appdevforall.codeonthego.layouteditor.managers.PreferencesManager
import org.appdevforall.codeonthego.layouteditor.managers.ProjectManager
import org.appdevforall.codeonthego.layouteditor.managers.UndoRedoManager
import org.appdevforall.codeonthego.layouteditor.tools.ValidationResult
import org.appdevforall.codeonthego.layouteditor.tools.XmlLayoutGenerator
import org.appdevforall.codeonthego.layouteditor.utils.BitmapUtil.createBitmapFromView
import org.appdevforall.codeonthego.layouteditor.utils.Constants
import org.appdevforall.codeonthego.layouteditor.utils.FileCreator
import org.appdevforall.codeonthego.layouteditor.utils.FilePicker
import org.appdevforall.codeonthego.layouteditor.utils.FileUtil
import org.appdevforall.codeonthego.layouteditor.utils.SBUtils
import org.appdevforall.codeonthego.layouteditor.utils.SBUtils.Companion.make
import org.appdevforall.codeonthego.layouteditor.utils.Utils
import org.appdevforall.codeonthego.layouteditor.utils.doubleArgSafeLet
import org.appdevforall.codeonthego.layouteditor.views.CustomDrawerLayout
import java.io.File

@SuppressLint("UnsafeOptInUsageError")
class LayoutEditorFragment : Fragment() {
	private var _binding: ActivityLayoutEditorBinding? = null
	private val binding get() = _binding!!

	private lateinit var drawerLayout: DrawerLayout
	private var actionBarDrawerToggle: ActionBarDrawerToggle? = null

	private lateinit var projectManager: ProjectManager
	private lateinit var project: ProjectFile

	private var undoRedo: UndoRedoManager? = null
	private var fileCreator: FileCreator? = null
	private var xmlPicker: FilePicker? = null

	private lateinit var layoutAdapter: LayoutListAdapter

	private val updateMenuIconsState: Runnable = Runnable { undoRedo!!.updateButtons() }
    private var currentLayoutBasePath: String? = null

	private var loadedLayoutFile: LayoutFile? = null

	private val onBackPressedCallback =
		object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				when {
					drawerLayout.isDrawerOpen(GravityCompat.START) ||
						drawerLayout.isDrawerOpen(
							GravityCompat.END,
						) -> {
						drawerLayout.closeDrawers()
					}

					!isProjectReady() -> {
						requireActivity().finish()
					}

					binding.editorLayout.isLayoutModified() -> {
						showSaveChangesDialog()
					}

					else -> {
						requireActivity().finish()
					}
				}
			}
		}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		projectManager = ProjectManager.instance
		projectManager.initManger(context = requireContext())
		defineFileCreator()
		defineXmlPicker()
		requireActivity().onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		_binding = ActivityLayoutEditorBinding.inflate(
			PluginFragmentHelper.getPluginInflater(LayoutEditorPlugin.PLUGIN_ID, inflater),
			container,
			false,
		)
		return binding.root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		binding.editorLayout.setHostActivity(requireActivity())
		setupToolbarMenu()
		setupDrawerLayout()
		setupStructureView()
		setupDrawerNavigationRail()
		setToolbarButtonOnClickListener(binding)
		actionBarDrawerToggle?.syncState()

		// todo extract file_key to layouteditor constants and use it in both modules.
		// todo extract replace 0 date with actual value.
		// todo extract replace activity_main date with actual value.
		doubleArgSafeLet(
			LayoutEditorState.filePath,
			LayoutEditorState.layoutFileName,
		) { filePath, fileName ->
			binding.topAppBar.title = getString(string.loading_project)
			viewLifecycleOwner.lifecycleScope.launch {
				val createdAt = getCreatedTime(filePath).toString()
				val modifiedAt = getLastModifiedTime(filePath).toString()

				try {
					projectManager.openProject(
						ProjectFile(
							filePath,
							createdAt,
							modifiedAt,
							requireContext(),
							mainLayoutName = fileName
						)
					)
					project = projectManager.openedProject!!
					updateMenuState()
					openLayout(project.mainLayout)

					binding.topAppBar.title = project.name
					layoutAdapter = LayoutListAdapter(project)
					binding.editorLayout.setBackgroundColor(
						Utils.getSurfaceColor(requireContext())
					)
				} catch (e: Exception) {
					Log.e("LayoutEditorFragment", "Error loading project", e)
					Toast.makeText(
						requireContext(),
						getString(string.msg_error_opening_project),
						Toast.LENGTH_SHORT
					).show()
					requireActivity().finish()
				}
			}
		} ?: showNothingDialog()
	}

	private fun defineXmlPicker() {
		xmlPicker =
			object : FilePicker(this) {
				override fun onPickFile(uri: Uri?) {
					val path = uri?.path
					if (path != null && path.endsWith(".xml")) {
						androidToDesignConversion(uri)
					} else {
						Toast
							.makeText(
								requireContext(),
								getString(string.error_invalid_xml_file),
								Toast.LENGTH_SHORT,
							).show()
					}
				}
			}
	}

	private fun androidToDesignConversion(uri: Uri?) {
		if (uri == null) {
			Toast.makeText(
				requireContext(),
				getString(string.error_invalid_xml_file),
				Toast.LENGTH_SHORT
			).show()
			return
		}

		lifecycleScope.launch {
			try {
				val fileName = FileUtil.getLastSegmentFromPath(uri.path ?: "")

				if (!fileName.endsWith(".xml", ignoreCase = true)) {
					Toast.makeText(
						requireContext(),
						getString(string.error_invalid_xml_file),
						Toast.LENGTH_SHORT
					).show()
					return@launch
				}

				val xml = withContext(Dispatchers.IO) {
					FileUtil.readFromUri(uri, requireContext())
				} ?: run {
					make(binding.root, getString(string.error_failed_to_import))
						.setSlideAnimation()
						.showAsError()
					return@launch
				}

				val xmlConverted = withContext(Dispatchers.Default) {
					ConvertImportedXml(xml).getXmlConverted(requireContext())
				}

				if (xmlConverted == null) {
					make(binding.root, getString(string.error_failed_to_import))
						.setSlideAnimation()
						.showAsError()
					return@launch
				}

				val productionPath = project.layoutPath + fileName
				val designPath = project.layoutDesignPath + fileName
				withContext(Dispatchers.IO) {
					FileUtil.writeFile(productionPath, xml)
					FileUtil.writeFile(designPath, xmlConverted)
				}

				openLayout(LayoutFile(productionPath, designPath))
				make(binding.root, getString(string.success_imported))
					.setFadeAnimation()
					.showAsSuccess()
			} catch (t: Throwable) {
				make(binding.root, getString(string.error_failed_to_import))
					.setSlideAnimation()
					.showAsError()
			}
		}
	}

	private fun defineFileCreator() {
		fileCreator =
			object : FileCreator(this) {
				override fun onCreateFile(uri: Uri) {
					val result = XmlLayoutGenerator().generate(binding.editorLayout, true)

					if (FileUtil.saveFile(requireContext(), uri, result)) {
						make(
							binding.root,
							getString(string.success_saved),
						).setSlideAnimation()
							.showAsSuccess()
					} else {
						make(binding.root, getString(string.error_failed_to_save))
							.setSlideAnimation()
							.showAsError()
						FileUtil.deleteFile(FileUtil.convertUriToFilePath(requireContext(), uri))
					}
				}
			}
	}

	private fun setupDrawerLayout() {
		drawerLayout = binding.drawer
		actionBarDrawerToggle =
			ActionBarDrawerToggle(
				requireActivity(),
				drawerLayout,
				binding.topAppBar,
				string.palette,
				string.palette,
			)

		(drawerLayout as CustomDrawerLayout).addDrawerListener(actionBarDrawerToggle!!)
		actionBarDrawerToggle!!.syncState()
		(drawerLayout as CustomDrawerLayout).addDrawerListener(
			object : DrawerLayout.SimpleDrawerListener() {
				override fun onDrawerStateChanged(state: Int) {
					super.onDrawerStateChanged(state)
					undoRedo!!.updateButtons()
				}

				override fun onDrawerSlide(
					v: View,
					slideOffset: Float,
				) {
					super.onDrawerSlide(v, slideOffset)
					undoRedo!!.updateButtons()
				}

				override fun onDrawerClosed(v: View) {
					super.onDrawerClosed(v)
					undoRedo!!.updateButtons()
				}

				override fun onDrawerOpened(v: View) {
					super.onDrawerOpened(v)
					undoRedo!!.updateButtons()
				}
			},
		)
	}

	private fun setupStructureView() {
		binding.editorLayout.setStructureView(binding.structureView)

		binding.structureView.apply {
			onItemClickListener = {
				binding.editorLayout.showDefinedAttributes(it)
				drawerLayout.closeDrawer(GravityCompat.END)
			}
			onItemLongClickListener = { view ->
				view.javaClass.superclass?.name?.let { LayoutEditorDocs.showWidget(view, it) }
			}
		}
	}

	@SuppressLint("SetTextI18n")
	private fun setupDrawerNavigationRail() {
		val navHeader = PluginFragmentHelper
			.getPluginInflater(LayoutEditorPlugin.PLUGIN_ID, layoutInflater)
			.inflate(R.layout.layout_navigation_header, binding.paletteNavigation, false)
		binding.paletteNavigation.addHeaderView(navHeader)
		val helpFab = navHeader.findViewById<FloatingActionButton>(R.id.help_fab)
		helpFab.setOnClickListener { LayoutEditorDocs.show(it, LayoutEditorDocs.TAG_HELP) }
		LayoutEditorDocs.bindLongPress(helpFab, LayoutEditorDocs.TAG_HELP)

		val paletteMenu = binding.paletteNavigation.menu
		paletteMenu
			.add(Menu.NONE, 0, Menu.NONE, Constants.TAB_TITLE_COMMON)
			.setIcon(R.drawable.android)
		paletteMenu
			.add(Menu.NONE, 1, Menu.NONE, Constants.TAB_TITLE_TEXT)
			.setIcon(R.mipmap.ic_palette_text_view)
		paletteMenu
			.add(Menu.NONE, 2, Menu.NONE, Constants.TAB_TITLE_BUTTONS)
			.setIcon(R.mipmap.ic_palette_button)
		paletteMenu
			.add(Menu.NONE, 3, Menu.NONE, Constants.TAB_TITLE_WIDGETS)
			.setIcon(R.mipmap.ic_palette_view)
		paletteMenu
			.add(Menu.NONE, 4, Menu.NONE, Constants.TAB_TITLE_LAYOUTS)
			.setIcon(R.mipmap.ic_palette_relative_layout)
		paletteMenu
			.add(Menu.NONE, 5, Menu.NONE, Constants.TAB_TITLE_CONTAINERS)
			.setIcon(R.mipmap.ic_palette_view_pager)

		binding.listView.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false)

		val adapter = PaletteListAdapter(binding.drawer)
		try {
			adapter.submitPaletteList(projectManager.getPalette(0))

			binding.paletteNavigation.setOnItemSelectedListener { item: MenuItem ->
				try {
					adapter.submitPaletteList(projectManager.getPalette(item.itemId))
					binding.paletteText.text = getString(string.label_palette)
					binding.title.text = item.title
					replaceListViewAdapter(adapter)
				} catch (e: Exception) {
					Toast
						.makeText(
							requireContext(),
							"${getString(string.error_failed_to_load_palette)}: ${e.message}",
							Toast.LENGTH_SHORT,
						).show()
				}
				true
			}
			replaceListViewAdapter(adapter)
		} catch (e: Exception) {
			Toast
				.makeText(
					requireContext(),
					"${getString(string.error_failed_to_initialize_palette)}: ${e.message}",
					Toast.LENGTH_SHORT,
				).show()
		}

		clear()
	}

	private fun replaceListViewAdapter(adapter: RecyclerView.Adapter<*>) {
		binding.listView.adapter = adapter
	}

	private fun isProjectReady(): Boolean {
		if (!::project.isInitialized) {
			showPluginToast(requireContext(), getString(R.string.loading_project))
			return false
		}
		return true
	}

	private fun onMenuItemSelected(item: MenuItem): Boolean {
		val id = item.itemId
		undoRedo!!.updateButtons()

		when (id) {
			R.id.resources_manager,
			R.id.preview,
			R.id.export_xml,
			R.id.export_as_image,
			R.id.save_xml,
			R.id.exit_editor,
			R.id.edit_xml -> {
				if (!isProjectReady()) return false
			}
		}

		when (id) {
			android.R.id.home -> {
				drawerLayout.openDrawer(GravityCompat.START)
				return true
			}

			R.id.undo -> {
				binding.editorLayout.undo()
				return true
			}

			R.id.redo -> {
				binding.editorLayout.redo()
				return true
			}

			R.id.show_structure -> {
				drawerLayout.openDrawer(GravityCompat.END)
				return true
			}

			R.id.edit_xml -> {
				showXml()
				return true
			}

			R.id.resources_manager -> {
				openSubScreen(
					ResourceManagerFragment::class.java.name,
					getString(string.res_manager),
				)
				return true
			}

			R.id.preview -> {
				val result = XmlLayoutGenerator().generate(binding.editorLayout, true)
				if (result.isEmpty()) {
					showNothingDialog()
				} else {
					viewLifecycleOwner.lifecycleScope.launch {
						saveXml()
						EditorSubScreenState.previewLayoutFile = project.currentLayout
						openSubScreen(
							PreviewLayoutFragment::class.java.name,
							getString(string.preview_layout),
						)
					}
				}
				return true
			}

			R.id.export_xml -> {
				val uri = Uri.fromFile(File(project.currentLayout.path))
				val result = XmlLayoutGenerator().generate(binding.editorLayout, true)

				if (FileUtil.saveFile(requireContext(), uri, result)) {
					binding.editorLayout.markAsSaved()
					make(binding.root, getString(string.success_saved))
						.setSlideAnimation()
						.showAsSuccess()
				} else {
					make(binding.root, getString(string.error_failed_to_save))
						.setSlideAnimation()
						.showAsError()
					FileUtil.deleteFile(FileUtil.convertUriToFilePath(requireContext(), uri))
				}

				return true
			}

			R.id.export_as_image -> {
				if (binding.editorLayout.getChildAt(0) != null) {
					showSaveMessage(
						Utils.saveBitmapAsImageToGallery(
							requireContext(),
							createBitmapFromView(binding.editorLayout),
							project.name,
						),
					)
				} else {
					make(binding.root, getString(string.info_add_some_views))
						.setFadeAnimation()
						.setType(SBUtils.Type.INFO)
						.show()
				}
				return true
			}

			R.id.save_xml -> {
				MaterialAlertDialogBuilder(requireContext())
					.setTitle(string.save)
					.setMessage(string.save_layout_message)
					.setCancelable(false)
					.setNeutralButton(string.cancel) { d, _ ->
						d.cancel()
					}.setNegativeButton(string.export_as_image) { d, _ ->
						if (binding.editorLayout.getChildAt(0) != null) {
							showSaveMessage(
								Utils.saveBitmapAsImageToGallery(
									requireContext(),
									createBitmapFromView(binding.editorLayout),
									project.name,
								),
							)
						} else {
							make(binding.root, getString(string.info_add_some_views))
								.setFadeAnimation()
								.setType(SBUtils.Type.INFO)
								.show()
						}
					}.setPositiveButton(string.export_layout) { _, _ ->
						val uri = Uri.fromFile(File(project.currentLayout.path))
						val result = XmlLayoutGenerator().generate(binding.editorLayout, true)

						if (FileUtil.saveFile(requireContext(), uri, result)) {
							binding.editorLayout.markAsSaved()
							make(binding.root, getString(string.success_saved))
								.setSlideAnimation()
								.showAsSuccess()
						} else {
							make(binding.root, getString(string.error_failed_to_save))
								.setSlideAnimation()
								.showAsError()
							FileUtil.deleteFile(
								FileUtil.convertUriToFilePath(
									requireContext(),
									uri,
								),
							)
						}
					}.show()
				return true
			}

			R.id.exit_editor -> {
				if (binding.editorLayout.isLayoutModified()) {
					showSaveChangesDialog()
				} else {
					requireActivity().finish()
				}
				return true
			}

			else -> return false
		}
	}

	private fun updateMenuState() {
		val menu = binding.topAppBar.menu
		val isReady = ::project.isInitialized
		menu.findItem(R.id.resources_manager)?.isEnabled = isReady
		menu.findItem(R.id.preview)?.isEnabled = isReady
		menu.findItem(R.id.export_xml)?.isEnabled = isReady
		menu.findItem(R.id.export_as_image)?.isEnabled = isReady
		menu.findItem(R.id.save_xml)?.isEnabled = isReady
		menu.findItem(R.id.edit_xml)?.isEnabled = isReady
		menu.findItem(R.id.exit_editor)?.isEnabled = isReady
	}

	private fun openSubScreen(fragmentClassName: String, title: String) {
		PluginFragmentHelper.getServiceRegistry(LayoutEditorPlugin.PLUGIN_ID)
			?.get(IdeUIService::class.java)
			?.openPluginScreen(LayoutEditorPlugin.PLUGIN_ID, fragmentClassName, title)
	}

	override fun onConfigurationChanged(config: Configuration) {
		super.onConfigurationChanged(config)
		actionBarDrawerToggle!!.onConfigurationChanged(config)
		undoRedo!!.updateButtons()
	}

	override fun onResume() {
		super.onResume()
		if (::project.isInitialized) {
			viewLifecycleOwner.lifecycleScope.launch {
				val drawables = withContext(Dispatchers.IO) { currentProjectDrawables() }
				DrawableManager.loadFromFiles(drawables)
			}
		}
		if (undoRedo != null) undoRedo!!.updateButtons()
	}

	private fun currentProjectDrawables(): Array<File> {
		val ideProject = PluginFragmentHelper.getServiceRegistry(LayoutEditorPlugin.PLUGIN_ID)
			?.get(IdeProjectService::class.java)
			?.getCurrentProject()
		if (ideProject != null) {
			val fromApi = ideProject.getModules()
				.flatMap { it.getSourceSets() }
				.flatMap { it.resourceDirs }
				.map { File(it, "drawable") }
				.filter { it.isDirectory }
				.flatMap { it.listFiles()?.asList() ?: emptyList() }
			if (fromApi.isNotEmpty()) return fromApi.toTypedArray()
		}
		return project.drawables
	}

	override fun onDestroyView() {
		super.onDestroyView()
		_binding = null
	}

	override fun onDestroy() {
		super.onDestroy()
		projectManager.closeProject()
	}

	private fun showXml() {
		val result = XmlLayoutGenerator().generate(binding.editorLayout, true)
		if (result.isEmpty()) {
			showNothingDialog()
		} else {
			viewLifecycleOwner.lifecycleScope.launch {
				saveXml()
				requireActivity().finish()
			}
		}
	}

	private fun showNothingDialog() {
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(string.nothing)
			.setMessage(string.msg_add_some_widgets)
			.setPositiveButton(string.okay) { d, _ -> d.cancel() }
			.show()
	}

	@SuppressLint("RestrictedApi")
	private fun setupToolbarMenu() {
		binding.topAppBar.inflateMenu(R.menu.menu_editor)
		val menu = binding.topAppBar.menu
		if (menu is MenuBuilder) menu.setOptionalIconsVisible(true)

		intArrayOf(R.id.undo, R.id.redo, R.id.show_structure, R.id.exit_editor)
			.forEach { menu.findItem(it)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS) }
		intArrayOf(R.id.edit_xml, R.id.preview, R.id.resources_manager, R.id.save_xml)
			.forEach { menu.findItem(it)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER) }
		val undo = menu.findItem(R.id.undo)
		val redo = menu.findItem(R.id.redo)
		undoRedo = UndoRedoManager(undo, redo)
		binding.editorLayout.bindUndoRedoManager(undoRedo)
		binding.editorLayout.updateUndoRedoHistory()
		updateUndoRedoBtnState()
		binding.topAppBar.setOnMenuItemClickListener { item -> onMenuItemSelected(item) }
		updateMenuState()
	}

	private fun updateUndoRedoBtnState() {
		Handler(Looper.getMainLooper()).postDelayed(updateMenuIconsState, 10)
	}

	private fun showSaveMessage(success: Boolean) {
		if (success) {
			make(binding.root, getString(string.success_saved_to_gallery))
				.setFadeAnimation()
				.setType(SBUtils.Type.INFO)
				.show()
		} else {
			make(binding.root, getString(string.error_failed_to_save_gallery))
				.setFadeAnimation()
				.setType(SBUtils.Type.ERROR)
				.show()
		}
	}

	private fun setToolbarButtonOnClickListener(binding: ActivityLayoutEditorBinding) {
		LayoutEditorDocs.bindLongPress(binding.viewType, LayoutEditorDocs.TAG_VIEW_TYPE)
		LayoutEditorDocs.bindLongPress(binding.deviceSize, LayoutEditorDocs.TAG_DEVICE_SIZE)
		binding.viewType.setOnClickListener { view ->
			val popupMenu = PopupMenu(view.context, view)
			popupMenu.inflate(R.menu.menu_view_type)
			popupMenu.setOnMenuItemClickListener {
				val id = it.itemId
				when (id) {
					R.id.view_type_design -> {
						binding.editorLayout.viewType = DesignEditor.ViewType.DESIGN
					}

					R.id.view_type_blueprint -> {
						binding.editorLayout.viewType = DesignEditor.ViewType.BLUEPRINT
					}
				}
				true
			}
			popupMenu.show()
		}
		binding.deviceSize.setOnClickListener {
			val popupMenu = PopupMenu(it.context, it)
			popupMenu.inflate(R.menu.menu_device_size)
			popupMenu.setOnMenuItemClickListener { item ->
				val id = item.itemId
				when (id) {
					R.id.device_size_small -> {
						binding.editorLayout.resizeLayout(DeviceConfiguration(DeviceSize.SMALL))
					}

					R.id.device_size_medium -> {
						binding.editorLayout.resizeLayout(DeviceConfiguration(DeviceSize.MEDIUM))
					}

					R.id.device_size_large -> {
						binding.editorLayout.resizeLayout(DeviceConfiguration(DeviceSize.LARGE))
					}
				}
				true
			}
			popupMenu.show()
		}
	}

  private suspend fun openLayout(layoutFile: LayoutFile) {
    val production = withContext(Dispatchers.IO) { layoutFile.readLayoutFile() }
    if (production.isNullOrBlank()) {
      showLoadFailure(getString(string.xml_error_empty_file, layoutFile.name))
      return
    }

    val design = withContext(Dispatchers.Default) {
      ConvertImportedXml(production).getXmlConverted(requireContext())
    }
    if (design.isNullOrBlank()) {
      showLoadFailure(getString(string.xml_error_not_well_formed))
      return
    }
    withContext(Dispatchers.IO) { layoutFile.saveDesignFile(design) }

      currentLayoutBasePath = File(layoutFile.path).parent
      val loadResult = binding.editorLayout.loadLayoutFromParser(design, currentLayoutBasePath)

		if (loadResult is ValidationResult.Error) {
			showLoadFailure(loadResult.formattedMessage)
			return
		}

		val layoutName = layoutFile.name

		loadedLayoutFile = layoutFile
		project.currentLayout = layoutFile
		binding.topAppBar.subtitle = layoutName

		if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
			drawerLayout.closeDrawer(GravityCompat.START)
		}

		binding.editorLayout.post {
			binding.editorLayout.requestLayout()
			binding.editorLayout.invalidate()
			binding.editorLayout.updateUndoRedoHistory()
			binding.editorLayout.markAsSaved()
		}

		make(binding.root, getString(string.success_loaded))
			.setFadeAnimation()
			.setType(SBUtils.Type.INFO)
			.show()
	}

	private fun currentLayoutFileOrNull(): LayoutFile? = loadedLayoutFile

	private fun showLoadFailure(message: String) {
		loadedLayoutFile = null
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.xml_error_title)
			.setMessage(message)
			.setPositiveButton(android.R.string.ok) { _, _ -> requireActivity().finish() }
			.setCancelable(false)
			.show()
	}

	/**
	 * Writes the current editor state to disk.
	 * - Generates XML on the current thread (UI-safe)
	 * - Performs file I/O on Dispatchers.IO
	 * - No UI side-effects (no toast / no markAsSaved) to keep it reusable
	 */
	private suspend fun persistEditorLayout(layoutFile: LayoutFile): Boolean {
		if (binding.editorLayout.isEmpty()) {
			return false
		}

		return runCatching {
			val generator = XmlLayoutGenerator()
			val productionXml = generator.generate(binding.editorLayout, true)
			val designXml = generator.generate(binding.editorLayout, false)

			withContext(Dispatchers.IO) {
				layoutFile.saveLayout(productionXml)
				layoutFile.saveDesignFile(designXml)
			}
		}.isSuccess
	}

	private suspend fun saveXml() {
		val layoutFile = currentLayoutFileOrNull() ?: run {
			withContext(Dispatchers.Main) {
				showPluginToast(requireContext(), getString(string.xml_error_not_loaded))
			}
			return
		}

		if (binding.editorLayout.isEmpty()) {
			withContext(Dispatchers.Main) {
				showPluginToast(requireContext(), getString(string.xml_error_nothing_to_save))
			}
			return
		}

		val success = persistEditorLayout(layoutFile)
		if (!success) {
			withContext(Dispatchers.Main) {
				showPluginToast(requireContext(), getString(string.failed_to_save_layout))
			}
			return
		}

		binding.editorLayout.markAsSaved()
		showPluginToast(requireContext(), getString(string.layout_saved))
	}

	private fun showSaveChangesDialog() {
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.save_changes)
			.setMessage(R.string.msg_save_changes_to_layout)
			.setPositiveButton(R.string.save_changes_and_exit) { _, _ ->
				lifecycleScope.launch {
					saveXml()
					requireActivity().finish()
				}
			}.setNegativeButton(R.string.discard_changes_and_exit) { _, _ ->
				requireActivity().finish()
			}.setNeutralButton(R.string.cancel_and_stay_in_editor) { dialog, _ ->
				dialog.dismiss()
			}.setCancelable(false)
			.show()
	}

}
