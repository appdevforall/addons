package org.appdevforall.customidebuilder

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import org.appdevforall.customidebuilder.core.AddonKind
import org.appdevforall.customidebuilder.core.AppNameCheck
import org.appdevforall.customidebuilder.core.BrandingGate
import org.appdevforall.customidebuilder.core.Catalog
import org.appdevforall.customidebuilder.core.IconVerdict
import org.appdevforall.customidebuilder.core.MinSdkCheck
import org.appdevforall.customidebuilder.core.NameVerdict

/**
 * The Custom IDE Builder screen. Views are built in code, so there is no layout to inflate against the
 * host's resources, and every text size is in sp and every gap in dp, inside a ScrollView, so the screen
 * holds up at 2x font scale. State that must outlive the screen is in [Session].
 */
class BuilderFragment : Fragment() {
    private lateinit var prefs: SharedPreferences
    private lateinit var itemsBox: LinearLayout
    private lateinit var nameField: EditText
    private lateinit var nameStatus: TextView
    private lateinit var iconPreview: ImageView
    private lateinit var iconLabel: TextView
    private lateinit var iconStatus: TextView
    private lateinit var clearIconButton: Button
    private lateinit var confirmBox: CheckBox
    private var refreshing = false
    private lateinit var keystoreLabel: TextView
    private lateinit var storePassword: EditText
    private lateinit var keyPassword: EditText
    private lateinit var aliasSpinner: Spinner
    private lateinit var fingerprint: TextView
    private lateinit var folderLabel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var stageLabel: TextView
    private lateinit var messageLabel: TextView
    private lateinit var cancelButton: Button
    private val actionButtons = ArrayList<Button>()
    private var shownAliases: List<String>? = null
    private var seenBuildsEnded = Session.buildsEnded
    private val listener: () -> Unit = { refresh() }

    private val pickAddons = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) Session.addFromDevice(requireContext().applicationContext, uris)
    }
    private val pickIcon = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val host = hostOrNull()
            if (host == null) {
                Session.message = "Could not read the installed app's details."
                refresh()
            } else {
                Session.pickIcon(requireContext().applicationContext, uri, host.packageName, host.apk)
            }
        }
    }
    private val pickKeystore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            Session.keystoreUri = uri
            Session.keystoreName = AndroidBits.displayName(requireContext(), uri) ?: uri.lastPathSegment
            Session.aliases = emptyList()
            Session.fingerprints = emptyMap()
            Session.selectedAlias = null
            refresh()
        }
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(uri, flags)
            prefs.edit().putString(PREF_TREE, uri.toString()).apply()
            refresh()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        prefs = prefsOf()
        actionButtons.clear()
        shownAliases = null
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        column.addView(text(ctx, "Custom IDE Builder", 22f, bold = true, tooltip = Help.MENU))
        column.addView(text(ctx, "Makes a copy of the Code on the Go installed on this device with the add-ons you choose bundled inside, signed with your own key.", 14f, tooltip = Help.MENU))

        column.addView(heading(ctx, "1. Add-ons to bundle", Help.ADDONS))
        itemsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        column.addView(itemsBox)
        column.addView(action(ctx, "Add from this device", Help.ADDONS) {
            pickAddons.launch(arrayOf("*/*"))
        })
        column.addView(action(ctx, "Add from the website", Help.WEBSITE) { confirmWebsite() })

        column.addView(heading(ctx, "2. Name and icon (optional)", Help.NAME))
        column.addView(text(ctx, "Leave both empty to keep the name and icon Code on the Go has. A name or icon you choose replaces them everywhere the app shows them.", 14f, tooltip = Help.NAME))
        nameField = EditText(ctx).apply {
            hint = "App name"
            withTooltip(this, Help.APP_NAME, consume = false)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    if (!refreshing) Session.setName(s?.toString().orEmpty())
                }
            })
        }
        column.addView(nameField)
        nameStatus = text(ctx, "", 14f).apply { withTooltip(this, Help.NAME) }
        column.addView(nameStatus)
        iconPreview = ImageView(ctx).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Preview of the chosen icon"
            withTooltip(this, Help.ICON)
        }
        column.addView(iconPreview, LinearLayout.LayoutParams(dp(96), dp(96)).apply { topMargin = dp(8) })
        iconLabel = text(ctx, "", 14f).apply { withTooltip(this, Help.ICON) }
        column.addView(iconLabel)
        iconStatus = text(ctx, "", 14f).apply { withTooltip(this, Help.ICON) }
        column.addView(iconStatus)
        column.addView(action(ctx, "Choose icon", Help.ICON) { pickIcon.launch(arrayOf("image/*")) })
        clearIconButton = action(ctx, "Remove icon", Help.ICON) { Session.clearIcon() }
        column.addView(clearIconButton)
        confirmBox = CheckBox(ctx).apply {
            text = BrandingGate.CONFIRMATION
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnCheckedChangeListener { _, checked -> if (!refreshing) Session.confirmedOwn = checked }
            withTooltip(this, Help.CONFIRM)
        }
        column.addView(confirmBox)

        column.addView(heading(ctx, "3. Signing key", Help.KEYSTORE))
        keystoreLabel = text(ctx, "", 14f, tooltip = Help.KEYSTORE)
        column.addView(keystoreLabel)
        column.addView(action(ctx, "Choose keystore file", Help.KEYSTORE) { pickKeystore.launch(arrayOf("*/*")) })
        storePassword = passwordField(ctx, "Store password", Help.STORE_PASSWORD)
        column.addView(storePassword)
        column.addView(action(ctx, "Read keys", Help.KEYSTORE) {
            val chars = charsOf(storePassword)
            if (chars.isEmpty()) Session.message = "Enter the store password first." else Session.loadKeystore(requireContext().applicationContext, chars)
            refresh()
        })
        aliasSpinner = Spinner(ctx)
        withTooltip(aliasSpinner, Help.KEY_ALIAS)
        aliasSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                Session.selectedAlias = Session.aliases.getOrNull(position)
                fingerprint.text = Session.selectedAlias?.let { "Certificate SHA-256: ${Session.fingerprints[it].orEmpty()}" }.orEmpty()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        column.addView(aliasSpinner)
        fingerprint = text(ctx, "", 12f, tooltip = Help.KEY_ALIAS)
        column.addView(fingerprint)
        keyPassword = passwordField(ctx, "Key password (blank: same as store password)", Help.KEY_PASSWORD)
        column.addView(keyPassword)

        column.addView(heading(ctx, "4. Output folder", Help.FOLDER))
        folderLabel = text(ctx, "", 14f, tooltip = Help.FOLDER)
        column.addView(folderLabel)
        column.addView(action(ctx, "Choose folder", Help.FOLDER) { pickFolder.launch(null) })

        column.addView(heading(ctx, "5. Build", Help.BUILD))
        column.addView(action(ctx, "Build APK", Help.BUILD) { startBuild() })
        stageLabel = text(ctx, "", 14f, tooltip = Help.STATUS)
        column.addView(stageLabel)
        progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            withTooltip(this, Help.STATUS)
        }
        column.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        cancelButton = Button(ctx).apply {
            this.text = "Cancel"
            setOnClickListener { Session.cancel() }
            withTooltip(this, Help.CANCEL)
        }
        column.addView(cancelButton)
        // Selectable, so the long-press must also reach the text selection: consume = false.
        messageLabel = text(ctx, "", 14f, tooltip = Help.RESULT, consume = false).apply { setTextIsSelectable(true) }
        column.addView(messageLabel)

        return ScrollView(ctx).apply {
            isFillViewport = true
            addView(column)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Session.listen(listener)
        refresh()
        if (!prefs.getBoolean(PREF_WARNED, false)) {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle("Sign every build with the same key")
                .setMessage(
                    "Android installs an update only if it is signed with the same key as the version already installed. " +
                        "Every later build you make here must use this same key, or a device that has an earlier build " +
                        "must uninstall it first, and uninstalling Code on the Go erases the projects stored in its data.",
                )
                .setPositiveButton("I understand") { _, _ -> prefs.edit().putBoolean(PREF_WARNED, true).apply() }
                .setCancelable(false)
                .show()
            tooltipOnDialog(dialog, Help.SAME_KEY)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        Session.unlisten(listener)
        // Passwords live only in these fields. Clearing the text drops the plaintext with the screen.
        storePassword.text.clear()
        keyPassword.text.clear()
        super.onDestroyView()
    }

    private fun refresh() {
        if (view == null) return
        val busy = Session.busy
        refreshing = true
        itemsBox.removeAllViews()
        if (Session.items.isEmpty()) {
            itemsBox.addView(text(requireContext(), "Nothing chosen: the copy will carry no add-ons.", 14f, tooltip = Help.ADDONS))
        }
        for (chosen in Session.items) {
            val kind = if (AddonKind.PLUGIN.extension == chosen.picked.fileName.substringAfterLast('.').lowercase()) "plugin" else "template"
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            row.addView(text(requireContext(), "${chosen.picked.label} ($kind, from ${chosen.source})", 14f, tooltip = Help.ADDONS))
            row.addView(Button(requireContext()).apply {
                this.text = "Remove"
                isEnabled = !busy
                setOnClickListener { Session.remove(chosen) }
                withTooltip(this, Help.REMOVE)
            })
            val warning = MinSdkCheck.warnings(hostMinSdk(), listOf(chosen.picked.label to chosen.minSdk), Build.VERSION.SDK_INT).firstOrNull()
            if (warning != null) {
                row.addView(text(requireContext(), "Warning: ${warning.message} Keep it, or tap Remove.", 14f).apply { withTooltip(this, Help.MINSDK) })
            }
            itemsBox.addView(row)
        }
        refreshBranding(busy)
        keystoreLabel.text = Session.keystoreName?.let { "Keystore: $it" } ?: "No keystore chosen."
        val aliases = Session.aliases
        if (aliases != shownAliases) {
            shownAliases = aliases
            aliasSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, aliases).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
        // Every refresh: Session.loadKeystore resets the selection even when the list is the same.
        val index = aliases.indexOf(Session.selectedAlias)
        if (index >= 0 && aliasSpinner.selectedItemPosition != index) aliasSpinner.setSelection(index)
        fingerprint.text = Session.selectedAlias?.let { "Certificate SHA-256: ${Session.fingerprints[it].orEmpty()}" }.orEmpty()
        if (seenBuildsEnded != Session.buildsEnded) {
            // A build has ended, however it ended: the passwords are not needed on screen any more.
            seenBuildsEnded = Session.buildsEnded
            storePassword.text.clear()
            keyPassword.text.clear()
        }
        aliasSpinner.visibility = if (aliases.isEmpty()) View.GONE else View.VISIBLE
        fingerprint.visibility = if (aliases.isEmpty()) View.GONE else View.VISIBLE
        val tree = prefs.getString(PREF_TREE, null)?.let(Uri::parse)
        folderLabel.text = tree?.let { "Saving to: ${AndroidBits.treeLabel(it)}" } ?: "No folder chosen."
        actionButtons.forEach { it.isEnabled = !busy }
        cancelButton.visibility = if (busy) View.VISIBLE else View.GONE
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        progress.progress = Session.progress
        stageLabel.text = Session.stage
        messageLabel.text = Session.message

        refreshing = false

        // A dialog needs a visible screen: if the list arrives while the screen is away, onResume shows it.
        val offer = Session.offer
        if (offer != null && isResumed) {
            Session.offer = null
            showOffer(offer)
        }
    }

    private fun refreshBranding(busy: Boolean) {
        if (nameField.text.toString() != Session.appName) nameField.setText(Session.appName)
        nameStatus.text =
            when (val v = AppNameCheck.check(Session.appName)) {
                is NameVerdict.Ok -> "This name can be used."
                is NameVerdict.Rejected -> if (Session.appName.isBlank()) "Blank keeps the name Code on the Go has." else v.reason
            }
        val icon = Session.icon
        iconPreview.visibility = if (icon == null) View.GONE else View.VISIBLE
        if (icon != null) iconPreview.setImageBitmap(icon.preview)
        iconLabel.text = Session.iconName?.let { "Icon: $it" } ?: "No icon chosen: the one Code on the Go has is kept."
        iconStatus.text =
            when (val v = Session.iconVerdict) {
                null -> if (icon != null) "Checking the picture." else ""
                is IconVerdict.TooSimilar -> "Too close to Code on the Go's own icon. Choose a picture of your own."
                is IconVerdict.Unchecked -> "Not compared with Code on the Go's icon: ${v.reason}"
                is IconVerdict.Different -> "Different from Code on the Go's icon."
            }
        iconStatus.visibility = if (iconStatus.text.isEmpty()) View.GONE else View.VISIBLE
        clearIconButton.visibility = if (icon == null) View.GONE else View.VISIBLE
        val custom = BrandingGate.isCustom(Session.appName, icon != null)
        confirmBox.isChecked = Session.confirmedOwn && custom
        confirmBox.isEnabled = custom && !busy
        nameField.isEnabled = !busy
    }

    private fun hostOrNull(): HostInfo? =
        try {
            AndroidBits.hostInfo(CustomIdeBuilderPlugin.context?.androidContext, requireContext())
        } catch (e: Exception) {
            null
        }

    /** The lowest Android this copy installs on; 0 (so nothing is flagged) if the app's details cannot be read. */
    private fun hostMinSdk(): Int = hostOrNull()?.minSdk ?: 0

    private fun confirmWebsite() {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Download from the internet?")
            .setMessage(
                "This reads the list of add-ons and downloads the ones you choose from addons.appdevforall.org, " +
                    "which is off this device. Nothing else is sent.",
            )
            .setPositiveButton("Continue") { _, _ -> Session.fetchOffer(hostVersionName()) }
            .setNegativeButton("Cancel", null)
            .show()
        tooltipOnDialog(dialog, Help.WEBSITE)
    }

    private fun hostVersionName(): String? =
        try {
            AndroidBits.hostInfo(CustomIdeBuilderPlugin.context?.androidContext, requireContext()).versionName
        } catch (e: Exception) {
            null
        }

    private fun showOffer(offer: Catalog.Offer) {
        if (offer.addons.isEmpty()) {
            Session.message = "The website has no add-ons that work with this version of Code on the Go."
            refresh()
            return
        }
        val labels = offer.addons.map { "${it.name} ${it.version} (${it.kind.name.lowercase()}, ${megabytes(it.size)})" }.toTypedArray()
        val checked = BooleanArray(labels.size)
        if (!offer.hostVersionKnown) {
            Session.message = "This app's version could not be read, so every add-on is listed."
            messageLabel.text = Session.message
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Add-ons on the website")
            .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
            .setPositiveButton("Add") { _, _ ->
                val wanted = offer.addons.filterIndexed { i, _ -> checked[i] }
                if (wanted.isNotEmpty()) Session.addFromWebsite(requireContext().applicationContext, wanted)
            }
            .setNegativeButton("Cancel", null)
            .show()
        tooltipOnDialog(dialog, Help.WEBSITE)
    }

    private fun startBuild() {
        val ctx = requireContext()
        val tree = prefs.getString(PREF_TREE, null)?.let(Uri::parse)
        val writable = tree != null && ctx.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        val store = charsOf(storePassword)
        val key = charsOf(keyPassword)
        val problem =
            when {
                Session.aliases.isEmpty() || Session.selectedAlias == null -> "Choose a keystore, enter its password and tap Read keys."
                store.isEmpty() -> "Enter the store password."
                !writable -> "Choose the output folder."
                else -> null
            }
        if (problem != null) {
            store.fill('\u0000'); key.fill('\u0000')
            Session.message = problem
            refresh()
            return
        }
        val host =
            try {
                AndroidBits.hostInfo(CustomIdeBuilderPlugin.context?.androidContext, ctx)
            } catch (e: Exception) {
                store.fill('\u0000'); key.fill('\u0000')
                Session.message = "Could not read the installed app's details: ${e.message}"
                refresh()
                return
            }
        Session.build(ctx.applicationContext, host, tree!!, store, key)
    }

    private fun prefsOf(): SharedPreferences =
        CustomIdeBuilderPlugin.context?.getPluginSharedPreferences("custom_ide_builder")
            ?: requireContext().getSharedPreferences("custom_ide_builder", Context.MODE_PRIVATE)

    private fun charsOf(field: EditText): CharArray = CharArray(field.length()).also { field.text.getChars(0, it.size, it, 0) }

    private fun megabytes(bytes: Long): String = if (bytes < 1024 * 1024) "${(bytes + 1023) / 1024} KB" else "%.1f MB".format(bytes / 1048576.0)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun text(ctx: Context, content: String, sp: Float, bold: Boolean = false, tooltip: String? = null, consume: Boolean = true) = TextView(ctx).apply {
        text = content
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
        if (tooltip != null) withTooltip(this, tooltip, consume)
    }

    private fun heading(ctx: Context, content: String, tooltip: String) =
        text(ctx, content, 18f, bold = true, tooltip = tooltip).apply { setPadding(0, dp(20), 0, dp(4)) }

    private fun action(ctx: Context, label: String, tooltip: String, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        setOnClickListener { onClick() }
        withTooltip(this, tooltip)
        actionButtons.add(this)
    }

    /**
     * Long-press shows the tooltip. [consume] false lets the press carry on to the view's own
     * long-press (a text field's paste menu).
     */
    private fun withTooltip(view: View, tag: String, consume: Boolean = true) {
        view.setOnLongClickListener { v ->
            CustomIdeBuilderPlugin.tooltips()?.showTooltip(v, Help.CATEGORY, tag)
            consume
        }
    }

    /**
     * Long-press on a dialog's message (or on a row of its list) shows the tooltip. Call it after
     * `show()`, when the dialog's views exist.
     */
    private fun tooltipOnDialog(dialog: AlertDialog, tag: String) {
        dialog.findViewById<TextView>(android.R.id.message)?.let { withTooltip(it, tag) }
        dialog.listView?.setOnItemLongClickListener { _, row, _, _ ->
            CustomIdeBuilderPlugin.tooltips()?.showTooltip(row, Help.CATEGORY, tag)
            true
        }
    }

    private fun passwordField(ctx: Context, hintText: String, tooltip: String) = EditText(ctx).apply {
        withTooltip(this, tooltip, consume = false)
        hint = hintText
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        // Never let the system put a password in saved instance state.
        isSaveEnabled = false
    }

    private companion object {
        const val PREF_WARNED = "warned_same_key"
        const val PREF_TREE = "output_tree_uri"
    }
}
