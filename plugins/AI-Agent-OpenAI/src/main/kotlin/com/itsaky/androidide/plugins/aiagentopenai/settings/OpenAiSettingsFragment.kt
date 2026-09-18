package com.itsaky.androidide.plugins.aiagentopenai.settings

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.Filter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentopenai.R
import com.itsaky.androidide.plugins.aiagentopenai.plugin.OpenAiPlugin
import com.itsaky.androidide.plugins.aiagentopenai.ui.SecretRevealController
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import com.itsaky.androidide.plugins.services.IdeTooltipService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * This backend's settings pane, mounted by whichever screen offers a backend selector.
 *
 * Named to the host through `OpenAiBackend.getSettingsFragmentClassName()`, loaded with this
 * plugin's own classloader and inflated against this plugin's own resources — so the consumer needs
 * to know nothing about API keys, server URLs or model catalogs.
 */
class OpenAiSettingsFragment : Fragment() {

    private lateinit var viewModel: OpenAiSettingsViewModel
    private var tooltipService: IdeTooltipService? = null

    /**
     * Re-reads the server URL and re-dresses the key section for it. Set once the key section is
     * built, so the server section can call it whenever the URL changes.
     */
    private var onServerChanged: ((String) -> Unit)? = null

    /**
     * Set while this pane is on screen, so [onResume] can nudge the user towards **Save** after
     * they come back from the key page. Cleared when the view is destroyed — it captures views, so
     * holding it any longer would leak them.
     */
    private var onPaneResume: (() -> Unit)? = null

    /**
     * The API key field's reveal control, held so the key can be re-masked when this pane leaves
     * the foreground. Captures views, so it is dropped in [onDestroyView] like [onPaneResume].
     */
    private var apiKeyReveal: SecretRevealController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            tooltipService = PluginFragmentHelper.getServiceRegistry(OpenAiPlugin.PLUGIN_ID)
                ?.get(IdeTooltipService::class.java)
        } catch (e: Exception) {
            // Tooltip help is optional; long-press simply shows nothing when it's unavailable.
            OpenAiPlugin.getContext()?.logger
                ?.warn("OpenAiSettingsFragment: tooltip service unavailable", e)
        }
    }

    /**
     * Route inflation through the host so this pane resolves against *this* plugin's resources and
     * a Context whose Configuration tracks the IDE's day/night setting. The inflater inherited from
     * the hosting screen belongs to that plugin and cannot see this one's layouts.
     */
    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(OpenAiPlugin.PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_openai_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(
            this,
            OpenAiSettingsViewModelFactory { OpenAiPlugin.getContext() }
        )[OpenAiSettingsViewModel::class.java]

        // The key section publishes onServerChanged, so it is built before the server section that
        // fires it, and before the first call below that dresses the pane for the saved server.
        setupApiKeyUi(view)
        setupServerUi(view)
        setupModelPicker(view, chatModelPicker())
        setupModelPicker(view, embeddingModelPicker())
        setupConnectionTest(view)
        onServerChanged?.invoke(viewModel.getBaseUrl())
    }

    override fun onResume() {
        super.onResume()
        onPaneResume?.invoke()
    }

    /**
     * Re-mask a revealed key on the way out of the foreground.
     *
     * Re-masking rather than only clearing [WindowManager.LayoutParams.FLAG_SECURE]: the flag has
     * to go, since the window outlives this pane and nothing else would clear it, and dropping it
     * over a legible key is what would let the recents thumbnail keep a copy of it.
     */
    override fun onPause() {
        apiKeyReveal?.mask()
        super.onPause()
    }

    override fun onDestroyView() {
        // Drops the captured pane views along with the callbacks.
        onPaneResume = null
        onServerChanged = null
        apiKeyReveal = null
        setSecureWindow(false)
        super.onDestroyView()
    }

    /** Shows this plugin's tooltip for [tag] when [view] is long-pressed (Tier 1/2 + guide button). */
    private fun wireTooltip(view: View, tag: String) {
        view.setOnLongClickListener { anchor ->
            val service = tooltipService ?: return@setOnLongClickListener false
            service.showTooltip(anchor, OpenAiPlugin.TOOLTIP_CATEGORY, tag)
            true
        }
    }

    /**
     * Long-press on [box]'s end icon shows [tag]'s tooltip.
     *
     * Separate from [wireTooltip] because the end icon is a clickable child that consumes the
     * long-press before the box sees it — without this every end icon on the pane, the reveal
     * control and the dropdown chevrons alike, would be a contributed element with no tooltip of
     * its own.
     */
    private fun wireEndIconTooltip(box: TextInputLayout, tag: String) {
        box.setEndIconOnLongClickListener { icon ->
            val service = tooltipService ?: return@setEndIconOnLongClickListener false
            service.showTooltip(icon, OpenAiPlugin.TOOLTIP_CATEGORY, tag)
            true
        }
    }

    /**
     * Show [message] on [target] with an optional leading status icon.
     *
     * @param icon leading status drawable, or 0 for the states that don't warrant one
     */
    private fun showStatus(target: TextView, message: String, @DrawableRes icon: Int = 0) {
        target.text = message
        // Relative (not left/right) so the icon follows the layout direction in RTL locales.
        target.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        target.visibility = View.VISIBLE
    }

    /** Sets [view]'s top margin to [dimenRes], for a gap that depends on what else is showing. */
    private fun setTopMargin(view: View, @DimenRes dimenRes: Int) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val margin = resources.getDimensionPixelSize(dimenRes)
        if (params.topMargin == margin) return
        params.topMargin = margin
        view.layoutParams = params
    }

    /** Drop a status line that no longer describes what is on screen. */
    private fun hideStatus(target: TextView) {
        target.visibility = View.GONE
        target.text = ""
        target.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
    }

    /**
     * Put the dropdown chevron on [box]'s end icon.
     *
     * The drawable is set here rather than in the layout because Material 1.10 never reads
     * `app:endIconDrawable`, so a custom end icon declared in XML draws blank — the same reason
     * [SecretRevealController] sets the reveal icon in code. Without a visible chevron the field
     * reads as a plain, read-only text box rather than a list to open.
     */
    private fun setupDropdownEndIcon(box: TextInputLayout) {
        // Already the mode declared in the layout; set again so the drawable below cannot be the
        // one a later mode switch discards.
        box.endIconMode = TextInputLayout.END_ICON_CUSTOM
        box.setEndIconDrawable(R.drawable.ic_dropdown)
        // Nothing ever moves the icon's checked state, so TalkBack would read "not checked" over
        // a control that is not a toggle.
        box.isEndIconCheckable = false
    }

    // --- Server -------------------------------------------------------------------------------

    private fun setupServerUi(view: View) {
        val presetBox = view.findViewById<TextInputLayout>(R.id.openai_preset_box)
        val presetInput = view.findViewById<AutoCompleteTextView>(R.id.openai_preset_input)
        val urlInput = view.findViewById<EditText>(R.id.openai_base_url_input)
        val saveButton = view.findViewById<Button>(R.id.btn_save_server)
        val statusText = view.findViewById<TextView>(R.id.openai_server_status_text)
        val serverLabel = view.findViewById<TextView>(R.id.openai_server_label)

        setupDropdownEndIcon(presetBox)

        listOf<View>(urlInput, saveButton, serverLabel, statusText)
            .forEach { wireTooltip(it, OpenAiPlugin.TOOLTIP_TAG_SETTINGS_SERVER) }
        wireTooltip(presetBox, OpenAiPlugin.TOOLTIP_TAG_SETTINGS_PRESET)

        urlInput.setText(viewModel.getBaseUrl())

        val presetLabels = ServerPresets.ALL.map { getString(it.labelRes) }
        // The field's own Context, not the activity's: the row layout is one of this plugin's
        // resources, and it is the plugin Context that resolves those and tracks the IDE's theme.
        presetInput.setAdapter(DropdownAdapter(presetInput.context, presetLabels))
        // A picker, not a text field: the list is the only way to change it.
        presetInput.keyListener = null
        // Both dropdowns are repopulated from the saved settings on every view creation, so there is
        // nothing for the framework to restore — and its replayed setText() is a filtering one,
        // which is what left the list holding only the selected entry after a day/night switch.
        presetInput.isSaveEnabled = false

        /** Shows [url]'s preset without announcing a pick, so restoring never fills the URL field. */
        fun showPresetFor(url: String) {
            val label = presetLabels.getOrNull(ServerPresets.indexOf(url)) ?: return
            presetInput.setText(label, false)
        }

        showPresetFor(viewModel.getBaseUrl())

        // Tapping anywhere in the field opens the list; the end icon is only a second way in.
        presetInput.setOnClickListener { presetInput.showDropDown() }
        presetBox.setEndIconOnClickListener { presetInput.showDropDown() }
        wireEndIconTooltip(presetBox, OpenAiPlugin.TOOLTIP_TAG_SETTINGS_PRESET)
        presetInput.setOnItemClickListener { _, _, position, _ ->
            // A preset only fills the field; the user still has to save it.
            ServerPresets.ALL.getOrNull(position)?.url?.let { urlInput.setText(it) }
        }

        // Re-dresses the key section as the URL is typed or a preset fills it, so picking Ollama
        // stops asking for a key immediately rather than after a save.
        urlInput.doAfterTextChanged { text ->
            // The saved-server line described the previous URL, so it cannot stay under a
            // different one — QA read a stale line as the state of the server now in the field.
            hideStatus(statusText)
            onServerChanged?.invoke(text?.toString().orEmpty())
        }

        saveButton.setOnClickListener {
            when (val result = viewModel.saveBaseUrl(urlInput.text.toString())) {
                is BaseUrlResult.Accepted -> {
                    urlInput.setText(result.url)
                    showPresetFor(result.url)
                    showStatus(
                        statusText,
                        getString(R.string.msg_server_saved, result.url),
                        R.drawable.ic_key_verified
                    )
                    if (result.cleartext && !result.loopback && !viewModel.isCleartextAcknowledged()) {
                        warnAboutCleartext()
                    }
                }

                is BaseUrlResult.Rejected -> showStatus(
                    statusText,
                    getString(rejectionMessage(result.reason)),
                    R.drawable.ic_key_rejected
                )
            }
        }
    }

    /** The string explaining why a URL was refused. */
    private fun rejectionMessage(reason: BaseUrlResult.Reason): Int = when (reason) {
        BaseUrlResult.Reason.BLANK -> R.string.msg_server_blank
        BaseUrlResult.Reason.MALFORMED -> R.string.msg_server_malformed
        BaseUrlResult.Reason.NO_HOST -> R.string.msg_server_no_host
        BaseUrlResult.Reason.CLEARTEXT_PUBLIC -> R.string.msg_server_cleartext_public
    }

    /**
     * Warn once that traffic to a LAN address is unencrypted.
     *
     * Allowed rather than blocked: reaching Ollama on the user's own PC is the point of the URL
     * field, and that traffic never leaves the local network.
     */
    private fun warnAboutCleartext() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.title_cleartext_server)
            .setMessage(R.string.msg_cleartext_server)
            .setPositiveButton(R.string.action_understood) { _, _ ->
                viewModel.markCleartextAcknowledged()
            }
            .show()
    }

    // --- API key ------------------------------------------------------------------------------

    @SuppressLint("SetTextI18n")
    private fun setupApiKeyUi(view: View) {
        val apiKeyLayout = view.findViewById<LinearLayout>(R.id.openai_api_key_layout)
        val apiKeyBox = view.findViewById<TextInputLayout>(R.id.openai_api_key_box)
        val apiKeyInput = view.findViewById<EditText>(R.id.openai_api_key_input)
        val saveButton = view.findViewById<Button>(R.id.btn_save_api_key)
        val editButton = view.findViewById<Button>(R.id.btn_edit_api_key)
        val clearButton = view.findViewById<Button>(R.id.btn_clear_api_key)
        val statusTextView = view.findViewById<TextView>(R.id.openai_api_key_status_text)
        val getKeyButton = view.findViewById<Button>(R.id.btn_get_key)
        val verificationText = view.findViewById<TextView>(R.id.openai_key_verification_text)
        val keyLabel = view.findViewById<TextView>(R.id.openai_api_key_label)
        val keySection = view.findViewById<LinearLayout>(R.id.openai_key_section)
        val keyNotNeededText = view.findViewById<TextView>(R.id.openai_key_not_needed_text)

        // Not on apiKeyInput: long-press there is the paste menu, and a key is pasted.
        listOf<View>(
            apiKeyBox, saveButton, editButton, clearButton, statusTextView,
            verificationText, keyLabel
        ).forEach { wireTooltip(it, OpenAiPlugin.TOOLTIP_TAG_SETTINGS_KEY) }
        wireTooltip(getKeyButton, OpenAiPlugin.TOOLTIP_TAG_SETTINGS_GET_KEY)

        // Whether the *currently typed* server needs a key, so Save can judge a blank field
        // against the server the user is configuring rather than the one last saved.
        var keyRequirement = BaseUrlPolicy.keyRequirement(viewModel.getBaseUrl())

        // Whether the field is open for a new key. Held here because the key section is re-dressed
        // on every server change too, and both inputs decide the same set of visibilities.
        var isEditingKey = true

        /**
         * Applies [keyRequirement] and [isEditingKey] to the whole key block.
         *
         * A server that needs no credential collapses the block to one muted line, *whether or not
         * a key happens to be stored*: an empty, mandatory-looking key field beside a local Ollama
         * is the single most confusing thing this pane can show. Only Remove survives, so a key
         * saved for another server can still be cleared from here.
         */
        fun dressKeySection() {
            val notNeeded = keyRequirement == KeyRequirement.NOT_NEEDED
            val hasStoredKey = viewModel.hasStoredApiKey()
            keySection.visibility = if (!notNeeded || hasStoredKey) View.VISIBLE else View.GONE
            keyNotNeededText.visibility = if (notNeeded) View.VISIBLE else View.GONE
            keyNotNeededText.setText(
                if (hasStoredKey) R.string.msg_key_not_needed_but_saved else R.string.msg_key_not_needed
            )
            keyLabel.setText(
                if (keyRequirement == KeyRequirement.REQUIRED) {
                    R.string.label_openai_api_key_required
                } else {
                    R.string.label_openai_api_key_optional
                }
            )
            // Entering a key this server will never be asked for only invites the "couldn't check
            // this key" dialog QA ran into, so the whole entry path goes away for it — including a
            // field the user had already opened when they switched servers.
            val editing = isEditingKey && !notNeeded
            apiKeyLayout.visibility = if (editing) View.VISIBLE else View.GONE
            saveButton.visibility = if (editing) View.VISIBLE else View.GONE
            editButton.visibility = if (!editing && !notNeeded) View.VISIBLE else View.GONE
            clearButton.visibility = if (!editing && hasStoredKey) View.VISIBLE else View.GONE
            statusTextView.visibility = if (!editing && hasStoredKey) View.VISIBLE else View.GONE
            // Only OpenAI's own page is linked, so the button is meaningless elsewhere.
            getKeyButton.visibility =
                if (keyRequirement == KeyRequirement.REQUIRED) View.VISIBLE else View.GONE
            // The muted line already carries the section's gap when it is up, so the block tucks
            // under it instead of stacking a second one.
            setTopMargin(keySection, if (notNeeded) R.dimen.space_sm else R.dimen.space_xl)
        }

        /** Dress the key section for [serverUrl], which may not be saved yet. */
        onServerChanged = { serverUrl ->
            keyRequirement = BaseUrlPolicy.keyRequirement(serverUrl)
            // The verdict described the previous server. Left up, it contradicts the new one —
            // QA saw "No API key needed" under an OpenAI URL that requires one.
            hideStatus(verificationText)
            dressKeySection()
        }

        fun updateUiState(isEditing: Boolean) {
            isEditingKey = isEditing
            dressKeySection()
        }

        /**
         * Report a request the server refused for credential reasons, if there is one. Read on
         * resume as well, since a refusal can land while this pane is already open and that does
         * not rebuild the view.
         */
        fun showCredentialFailure() {
            viewModel.credentialFailure()?.let { failure ->
                showStatus(
                    verificationText,
                    getString(R.string.msg_key_chat_failure, getString(failure.messageRes)),
                    R.drawable.ic_key_rejected
                )
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val stored = viewModel.getApiKey()
            val savedApiKey = (stored as? KeystoreSecretStore.Stored.Value)?.plain
            val hasKey = !savedApiKey.isNullOrBlank()
            // A keystore that would not answer this time leaves the key on disk and intact, so the
            // pane stays dressed as configured. Opening edit mode instead would make it identical
            // to a fresh install, and for a server that needs no key a blank Save from there runs
            // clearApiKey() over the key this same read just called recoverable.
            val keptConfigured =
                !hasKey &&
                    stored is KeystoreSecretStore.Stored.Unavailable &&
                    viewModel.hasStoredApiKey()
            updateUiState(isEditing = !hasKey && !keptConfigured)
            if (hasKey || keptConfigured) {
                statusTextView.text = savedApiKeyStatusText()
            }
            if (!hasKey) {
                apiKeyInput.setText("")
                // Only for a key that is there and will not decrypt; an empty box alone looks like
                // data loss. Nothing stored at all is the ordinary first run and says nothing.
                if (stored is KeystoreSecretStore.Stored.Unreadable) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.msg_api_key_unreadable),
                        Toast.LENGTH_LONG
                    ).show()
                } else if (stored is KeystoreSecretStore.Stored.Unavailable) {
                    // Said differently from the above: the key is still there and intact, so this
                    // must not send the user off to find and type it again.
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.msg_api_key_unavailable),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            // This pane is where the key gets fixed, and the transcript naming the reason is gone.
            showCredentialFailure()
        }

        // Not saved either, so a recreate cannot park a typed key in plain text in the state
        // Bundle; a stored one is read back from the encrypted store above.
        apiKeyInput.isSaveEnabled = false

        // Not endIconMode="password_toggle": the window has to be flagged secure for as long as the
        // key is legible, and the built-in toggle gives no hook for that.
        val reveal = SecretRevealController(apiKeyBox, apiKeyInput) { legible ->
            setSecureWindow(legible)
        }
        reveal.attach()
        apiKeyReveal = reveal
        wireEndIconTooltip(apiKeyBox, OpenAiPlugin.TOOLTIP_TAG_SETTINGS_KEY)

        getKeyButton.setOnClickListener { openKeyPage() }

        // Coming back from the browser, point at the next step; the clipboard is never read.
        onPaneResume = {
            // Kept on the ViewModel so a rotation while the browser is in front doesn't lose it.
            if (viewModel.sentUserToKeyPage) {
                viewModel.sentUserToKeyPage = false
                // With a key already stored the field is hidden, so the next tap is Edit.
                showStatus(
                    verificationText,
                    if (apiKeyLayout.visibility == View.VISIBLE) {
                        getString(R.string.msg_key_hint_paste_into_field)
                    } else {
                        getString(R.string.msg_key_hint_edit_first)
                    }
                )
            } else {
                showCredentialFailure()
            }
        }

        /** Enable or disable everything that would race the in-flight check. */
        fun setKeyEntryEnabled(enabled: Boolean) {
            saveButton.isEnabled = enabled
            getKeyButton.isEnabled = enabled
            apiKeyInput.isEnabled = enabled
        }

        /**
         * Encrypt and store [apiKey], then reflect the outcome. Only ever reached for a key the
         * server confirmed, or one the user chose to keep after an inconclusive check.
         */
        suspend fun persistKey(
            apiKey: String,
            verified: Boolean,
            resultText: String,
            @DrawableRes resultIcon: Int
        ) {
            if (!viewModel.saveApiKey(apiKey, verified)) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.msg_api_key_save_failed),
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            Toast.makeText(
                requireContext(),
                getString(R.string.msg_api_key_saved),
                Toast.LENGTH_SHORT
            ).show()
            // Collapsing the field only hides a revealed key: unmasked, the window stays secure.
            reveal.mask()
            updateUiState(isEditing = false)
            statusTextView.text = savedApiKeyStatusText()
            showStatus(verificationText, resultText, resultIcon)
            // A different key can reach a different set of models, so the picker is re-fetched.
            viewModel.fetchModels()
        }

        /**
         * Offer to keep a key that could not be checked. Distinct from a rejection: refusing a good
         * key because the server is offline would leave the plugin unconfigurable, so this gets the
         * muted "unchecked" icon and a key the server actually refused never reaches here.
         */
        fun confirmSaveUnverified(apiKey: String, reason: String) {
            showStatus(verificationText, reason, R.drawable.ic_key_unchecked)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.title_save_unverified_key)
                .setMessage(getString(R.string.msg_save_unverified_key, reason))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save_anyway) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        persistKey(
                            apiKey,
                            verified = false,
                            resultText = reason,
                            resultIcon = R.drawable.ic_key_unchecked
                        )
                    }
                }
                .show()
        }

        saveButton.setOnClickListener {
            val apiKey = apiKeyInput.text.toString().trim()
            if (apiKey.isBlank()) {
                if (keyRequirement == KeyRequirement.REQUIRED) {
                    // Said on the pane, not in a toast: this is a rule about the field, so it
                    // belongs beside the field and has to survive being read twice.
                    showStatus(
                        verificationText,
                        getString(R.string.msg_api_key_required_for_openai),
                        R.drawable.ic_key_rejected
                    )
                    apiKeyInput.requestFocus()
                } else {
                    // Blank is a legitimate configuration: the server is then called anonymously.
                    viewModel.clearApiKey()
                    dressKeySection()
                    showStatus(verificationText, getString(R.string.msg_key_left_empty))
                }
                return@setOnClickListener
            }
            setKeyEntryEnabled(false)
            showStatus(verificationText, getString(R.string.msg_verifying_key))
            viewLifecycleOwner.lifecycleScope.launch {
                val verdict = try {
                    viewModel.verifyConnection(apiKey)
                } finally {
                    setKeyEntryEnabled(true)
                }
                when (verdict) {
                    // Model count omitted: the user saved a key, not asked for a catalog.
                    is ConnectionVerification.Verified -> persistKey(
                        apiKey,
                        verified = true,
                        resultText = getString(R.string.msg_key_verified),
                        resultIcon = R.drawable.ic_key_verified
                    )

                    // A rate-limited key is a working key, so it gets the same icon as a clean pass.
                    ConnectionVerification.RateLimited -> persistKey(
                        apiKey,
                        verified = true,
                        resultText = getString(R.string.msg_key_verified_rate_limited),
                        resultIcon = R.drawable.ic_key_verified
                    )

                    // Nothing is written: a definitive refusal would only resurface mid-chat. Said
                    // aloud, because a user who is told the key was refused and then sees chat fail
                    // concludes the attempt destroyed the key they had, and re-buys a credential
                    // they never lost.
                    ConnectionVerification.Rejected -> {
                        // "Kept, and chat is still using it" only for a key chat can actually
                        // send and that is not the one just refused: getApiKeyFor applies the
                        // backend's own origin rule, a key the Keystore will not open is not sent
                        // either, and Edit prefills the stored key, so re-saving it unchanged
                        // refuses the very credential chat is still sending.
                        val stored = viewModel.getApiKeyFor(viewModel.getBaseUrl())
                        val keptKeyInUse = stored is KeystoreSecretStore.Stored.Value &&
                            stored.plain.trim() != apiKey
                        showStatus(
                            verificationText,
                            getString(
                                if (keptKeyInUse) {
                                    R.string.msg_key_rejected_kept
                                } else {
                                    R.string.msg_key_rejected
                                }
                            ),
                            R.drawable.ic_key_rejected
                        )
                        apiKeyInput.requestFocus()
                    }

                    // The server answered, so the key travelled fine; it just has no catalog.
                    ConnectionVerification.NoModels -> persistKey(
                        apiKey,
                        verified = true,
                        resultText = getString(R.string.msg_server_no_models),
                        resultIcon = R.drawable.ic_key_unchecked
                    )

                    ConnectionVerification.EndpointNotFound ->
                        confirmSaveUnverified(apiKey, getString(R.string.msg_server_endpoint_404))

                    ConnectionVerification.Unreachable ->
                        confirmSaveUnverified(apiKey, getString(R.string.msg_server_unreachable))

                    ConnectionVerification.Unknown ->
                        confirmSaveUnverified(apiKey, getString(R.string.msg_key_uncheckable))
                }
            }
        }

        // Reveal the (already-fetched) key in an editable, focused field.
        fun revealEditMode(apiKey: String) {
            apiKeyInput.setText(apiKey)
            apiKeyInput.setSelection(apiKey.length)
            // The old verdict described the stored key, which is about to change.
            hideStatus(verificationText)
            updateUiState(isEditing = true)
            // Opened masked: the key is loaded, not being read back.
            reveal.mask()
            apiKeyInput.requestFocus()
        }

        editButton.setOnClickListener {
            editButton.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val stored = try {
                    viewModel.getApiKey()
                } finally {
                    editButton.isEnabled = true
                }
                // A key that is stored and will not decrypt; an empty box alone looks like data
                // loss. Told apart from "nothing stored" here, which this button rarely sees but
                // must not report as a lost Keystore entry when it does.
                if (stored is KeystoreSecretStore.Stored.Unavailable) {
                    // Said differently from an unreadable key: this one is still there and intact,
                    // so the pane stays as it is rather than opening an empty field the user would
                    // Save over it — it must not send them off to find and type it again.
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.msg_api_key_unavailable),
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                if (stored is KeystoreSecretStore.Stored.Unreadable) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.msg_api_key_unreadable),
                        Toast.LENGTH_LONG
                    ).show()
                }
                revealEditMode((stored as? KeystoreSecretStore.Stored.Value)?.plain?.trim().orEmpty())
            }
        }

        clearButton.setOnClickListener {
            viewModel.clearApiKey()
            Toast.makeText(
                requireContext(),
                getString(R.string.msg_api_key_cleared),
                Toast.LENGTH_SHORT
            ).show()
            hideStatus(verificationText)
            updateUiState(isEditing = true)
            apiKeyInput.setText("")
        }
    }

    /**
     * Status line for a stored key: dated when the save time is known, generic otherwise, and
     * saying "verified" only for a key the server actually confirmed — a key kept through the
     * save-anyway path was never checked and must not claim otherwise.
     */
    private fun savedApiKeyStatusText(): String {
        val timestamp = viewModel.getApiKeySaveTimestamp()
        val verified = viewModel.isKeyVerified()
        if (timestamp <= 0) return getString(R.string.msg_api_key_is_saved)
        val savedDate = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
        return if (verified) {
            getString(R.string.msg_api_key_verified_on, savedDate)
        } else {
            getString(R.string.msg_api_key_saved_on, savedDate)
        }
    }

    // --- Model pickers -------------------------------------------------------------------------

    /**
     * Everything one editable model dropdown needs, so the chat and embedding pickers are one
     * implementation rather than two that drift.
     *
     * @param tooltipTag long-press help shared by the picker's label, field and hint
     * @param helpHint hint shown while no catalog is offered, i.e. free text only
     * @param liveHint hint shown once there is a list to tap
     * @param switchedMessage toast shown when this server retires the saved model
     * @param read the currently saved model
     * @param write persists a model the user typed or picked
     * @param options the catalog to offer
     * @param selected the model the field must show, republished when the saved one is retired
     */
    private class ModelPicker(
        @IdRes val boxId: Int,
        @IdRes val inputId: Int,
        @IdRes val labelId: Int,
        @IdRes val hintId: Int,
        val tooltipTag: String,
        @StringRes val helpHint: Int,
        @StringRes val liveHint: Int,
        @StringRes val switchedMessage: Int,
        val read: () -> String,
        val write: (String) -> Unit,
        val options: LiveData<OpenAiModelOptions>,
        val selected: LiveData<String>,
    )

    /** The chat model: what a turn is generated with. */
    private fun chatModelPicker() = ModelPicker(
        boxId = R.id.openai_model_box,
        inputId = R.id.openai_model_input,
        labelId = R.id.openai_model_label,
        hintId = R.id.openai_model_hint_text,
        tooltipTag = OpenAiPlugin.TOOLTIP_TAG_SETTINGS_MODEL,
        helpHint = R.string.hint_openai_model_help,
        liveHint = R.string.hint_openai_model_live,
        switchedMessage = R.string.msg_model_switched,
        read = viewModel::getModel,
        write = viewModel::saveModel,
        options = viewModel.models,
        selected = viewModel.selectedModel,
    )

    /** The embedding model: what semantic search indexes and queries with. */
    private fun embeddingModelPicker() = ModelPicker(
        boxId = R.id.openai_embedding_model_box,
        inputId = R.id.openai_embedding_model_input,
        labelId = R.id.openai_embedding_model_label,
        hintId = R.id.openai_embedding_model_hint_text,
        tooltipTag = OpenAiPlugin.TOOLTIP_TAG_SETTINGS_EMBEDDING_MODEL,
        helpHint = R.string.hint_openai_embedding_model_help,
        liveHint = R.string.hint_openai_embedding_model_live,
        switchedMessage = R.string.msg_embedding_model_switched,
        read = viewModel::getEmbeddingModel,
        write = viewModel::saveEmbeddingModel,
        options = viewModel.embeddingModels,
        selected = viewModel.selectedEmbeddingModel,
    )

    /**
     * One editable dropdown, not a field beside a spinner.
     *
     * Free text has to work — a local server's model is whatever the user pulled, and plenty of
     * compatible servers do not implement `/v1/models` at all — but the discovered list belongs in
     * the same control rather than a second one. The value is saved on pick, on IME Done and on
     * focus loss, so there is no Save button either.
     */
    private fun setupModelPicker(view: View, picker: ModelPicker) {
        val modelBox = view.findViewById<TextInputLayout>(picker.boxId)
        val modelInput = view.findViewById<AutoCompleteTextView>(picker.inputId)
        val modelLabel = view.findViewById<TextView>(picker.labelId)
        val modelHint = view.findViewById<TextView>(picker.hintId)

        setupDropdownEndIcon(modelBox)

        listOf<View>(modelLabel, modelInput, modelHint)
            .forEach { wireTooltip(it, picker.tooltipTag) }

        modelInput.isSaveEnabled = false
        // Typing searches, so the first keystroke has to replace the model id already in the field
        // rather than append to it — "gpt-4o-mini" + "claude" matches nothing, by construction.
        modelInput.setSelectAllOnFocus(true)
        // The suppressing overload throughout: a filtering write would narrow the list.
        modelInput.setText(picker.read(), false)

        /** Persist what is typed, ignoring a blank field rather than storing an unusable model. */
        fun commitTypedModel() {
            val typed = modelInput.text.toString().trim()
            if (typed.isEmpty() || typed == picker.read()) return
            picker.write(typed)
        }

        modelInput.setOnEditorActionListener { _, _, _ ->
            commitTypedModel()
            false
        }
        modelInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitTypedModel()
        }
        // Tapping the field opens the list; completionThreshold=0 alone waits for a keystroke.
        modelInput.setOnClickListener { modelInput.showDropDown() }
        modelBox.setEndIconOnClickListener { modelInput.showDropDown() }
        wireEndIconTooltip(modelBox, picker.tooltipTag)
        modelInput.setOnItemClickListener { _, _, _, _ -> commitTypedModel() }

        // A server that does not offer the saved model retires it; the field must show what will
        // actually be requested, and silently keeping the old id is what 404s on the first message.
        picker.selected.observe(viewLifecycleOwner) { model ->
            val shown = modelInput.text.toString()
            if (shown == model || modelInput.hasFocus()) return@observe
            modelInput.setText(model, false)
            if (shown.isNotBlank()) {
                Toast.makeText(
                    requireContext(),
                    getString(picker.switchedMessage, model),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        picker.options.observe(viewLifecycleOwner) { options ->
            // Cleared, not left stale: an empty list means the server changed and the old catalog
            // no longer describes it, so offering it would suggest models that will 404.
            modelInput.setAdapter(
                if (options.models.isEmpty()) {
                    null
                } else {
                    DropdownAdapter(
                        modelInput.context,
                        options.models,
                        noMatchLabel = getString(R.string.msg_no_model_found),
                    )
                }
            )
            // Keyed on whether there is a list, not on whether it is live: a remembered list is
            // still a list to tap, and telling the user to test the connection would be wrong.
            modelHint.text =
                getString(if (options.models.isEmpty()) picker.helpHint else picker.liveHint)
        }
    }

    // --- Connection test ----------------------------------------------------------------------

    /**
     * The one server round trip this pane makes.
     *
     * Testing the connection and listing the models were two buttons issuing the identical
     * `GET {baseUrl}/models`, so they are one: the verdict is reported and, when the server
     * answered with a catalog, it fills the model dropdown.
     */
    private fun setupConnectionTest(view: View) {
        val testButton = view.findViewById<Button>(R.id.btn_test_connection)
        val statusText = view.findViewById<TextView>(R.id.openai_connection_status_text)
        val urlInput = view.findViewById<EditText>(R.id.openai_base_url_input)
        val apiKeyInput = view.findViewById<EditText>(R.id.openai_api_key_input)
        val apiKeyLayout = view.findViewById<LinearLayout>(R.id.openai_api_key_layout)

        listOf<View>(testButton, statusText)
            .forEach { wireTooltip(it, OpenAiPlugin.TOOLTIP_TAG_SETTINGS_TEST) }

        viewModel.modelsLoading.observe(viewLifecycleOwner) { isLoading ->
            testButton.isEnabled = !isLoading
            testButton.text =
                if (isLoading) getString(R.string.loading) else getString(R.string.btn_test_connection)
        }

        testButton.setOnClickListener {
            testButton.isEnabled = false
            showStatus(statusText, getString(R.string.msg_testing_connection))
            viewLifecycleOwner.lifecycleScope.launch {
                // Tests what is on screen: a typo is worth catching before it is saved.
                val url = urlInput.text.toString().trim().ifEmpty { viewModel.getBaseUrl() }
                val typedKey = apiKeyInput.text.toString().trim()
                val useTyped = apiKeyLayout.visibility == View.VISIBLE && typedKey.isNotEmpty()
                // Scoped to the URL under test: probing a LAN server must not hand it the key the
                // user entered for OpenAI.
                val stored = if (useTyped) null else viewModel.getApiKeyFor(url)
                // A keystore that would not answer is not "no key stored": the key is intact and
                // the pane above still reads "saved on ...". Testing without it would render the
                // 401 as a refused key, or ask for one in a field that is not even shown.
                if (stored is KeystoreSecretStore.Stored.Unavailable) {
                    showStatus(
                        statusText,
                        getString(R.string.msg_api_key_unavailable_for_test),
                        R.drawable.ic_key_unchecked
                    )
                    testButton.isEnabled = true
                    return@launch
                }
                // Absent and Unreadable do share one answer here — no key to send — and the read
                // that opened the pane has already said which of the two it was.
                val key = if (useTyped) {
                    typedKey
                } else {
                    (stored as? KeystoreSecretStore.Stored.Value)?.plain?.trim().orEmpty()
                }
                // A server with no anonymous access can only answer 401 without a key, and
                // reporting that as "the server refused this key" when there is no key sends the
                // user off to mint a replacement for a key they never entered.
                if (key.isEmpty() && BaseUrlPolicy.keyRequirement(url) == KeyRequirement.REQUIRED) {
                    showStatus(
                        statusText,
                        getString(R.string.msg_api_key_needed_for_test),
                        R.drawable.ic_key_rejected
                    )
                    testButton.isEnabled = true
                    apiKeyInput.requestFocus()
                    return@launch
                }
                val verdict = try {
                    viewModel.verifyConnection(key, url)
                } finally {
                    testButton.isEnabled = true
                }
                val (message, icon) = describe(verdict, keySent = key.isNotEmpty())
                showStatus(statusText, message, icon)
                // Same request either way, so a successful test has already earned the catalog.
                if (verdict is ConnectionVerification.Verified) viewModel.fetchModels()
            }
        }
    }

    /**
     * One line and one icon for a connection verdict.
     *
     * @param keySent whether a credential actually accompanied the request, so a refusal is
     *   reported as the wrong key only when there was one to be wrong
     */
    private fun describe(verdict: ConnectionVerification, keySent: Boolean): Pair<String, Int> = when (verdict) {
        is ConnectionVerification.Verified -> resources.getQuantityString(
            R.plurals.msg_connection_ok,
            verdict.modelCount,
            verdict.modelCount
        ) to R.drawable.ic_key_verified

        ConnectionVerification.RateLimited ->
            getString(R.string.msg_key_verified_rate_limited) to R.drawable.ic_key_verified

        ConnectionVerification.NoModels ->
            getString(R.string.msg_server_no_models) to R.drawable.ic_key_unchecked

        ConnectionVerification.Rejected -> if (keySent) {
            getString(R.string.msg_key_rejected) to R.drawable.ic_key_rejected
        } else {
            getString(R.string.msg_server_needs_key) to R.drawable.ic_key_rejected
        }

        ConnectionVerification.EndpointNotFound ->
            getString(R.string.msg_server_endpoint_404) to R.drawable.ic_key_rejected

        ConnectionVerification.Unreachable ->
            getString(R.string.msg_server_unreachable) to R.drawable.ic_key_rejected

        ConnectionVerification.Unknown ->
            getString(R.string.msg_key_uncheckable) to R.drawable.ic_key_unchecked
    }

    // --- Window and browser -------------------------------------------------------------------

    /**
     * Add or clear [WindowManager.LayoutParams.FLAG_SECURE] on the host activity's window.
     *
     * Set while the key is in clear text, or screenshots and the recents thumbnail would capture
     * it. Cleared in [onDestroyView], since the window outlives this fragment's view.
     *
     * @param secure true to block capture, false to allow it again
     */
    private fun setSecureWindow(secure: Boolean) {
        val window = activity?.window ?: return
        if (secure) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /**
     * Open OpenAI's API keys page in the *system* browser.
     *
     * A real browser, not a WebView: sign-in is blocked in embedded WebViews, and the user should
     * see OpenAI's own URL bar. With no browser at all, the URL is copied instead.
     */
    private fun openKeyPage() {
        val url = OpenAiKeyOnboarding.API_KEYS_URL
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { startActivity(intent) }
            .onSuccess { viewModel.sentUserToKeyPage = true }
            .onFailure { error ->
                OpenAiPlugin.getContext()?.logger
                    ?.warn("OpenAiSettingsFragment: no browser could open the key page", error)
                val message = if (copyToClipboard(url)) {
                    R.string.msg_no_browser_for_key
                } else {
                    R.string.msg_key_link_copy_failed
                }
                Toast.makeText(requireContext(), getString(message, url), Toast.LENGTH_LONG).show()
            }
    }

    /**
     * Put [text] on the clipboard.
     *
     * Only ever used for the public key-page URL — never for a key, which would put the secret
     * somewhere every app on the device can read it.
     *
     * @return true when the clipboard accepted the value
     */
    private fun copyToClipboard(text: String): Boolean {
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.openai_clip_label), text))
        }.isSuccess
    }
}

/**
 * Dropdown adapter for the pane's two pickers.
 *
 * Matches on **substring**, case-insensitively, and offers everything for a blank query: an
 * OpenRouter catalog is ~400 ids named `vendor/model`, where the stock prefix filter finds nothing
 * for "claude" and a list that cannot be narrowed is unusable. So the model field doubles as a
 * search box over what the server reported.
 *
 * Only user typing ever reaches the filter. Every programmatic write goes through
 * `setText(value, false)`, and the fields do not save their own state, so the text the framework
 * would otherwise replay on a day/night switch cannot narrow the list to the entry already selected.
 *
 * @param items the full list, kept so a query can always be re-run against it
 * @param noMatchLabel row to show when a search matches nothing, or null to just close the popup —
 *   only the searchable field needs it
 */
private class DropdownAdapter(
    context: Context,
    private val items: List<String>,
    private val noMatchLabel: String? = null,
) : ArrayAdapter<String>(context, R.layout.item_dropdown, items.toMutableList()) {

    /** True while the only row is [noMatchLabel], which is a message rather than a choice. */
    private var showingNoMatch = false

    private val substringFilter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val query = constraint?.toString()?.trim().orEmpty()
            val matches = if (query.isEmpty()) {
                items
            } else {
                items.filter { it.contains(query, ignoreCase = true) }
            }
            // The message needs a row of its own to be seen at all: a count of 0 dismisses the
            // popup, which is indistinguishable from the dropdown being broken.
            val rows = matches.ifEmpty { listOfNotNull(noMatchLabel) }
            return FilterResults().apply {
                values = rows
                count = rows.size
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            val rows = results?.values as? List<String> ?: items
            // Identity, not equality: a server is free to offer a model called "No model found".
            showingNoMatch = noMatchLabel != null && rows.size == 1 && rows[0] === noMatchLabel
            clear()
            addAll(rows)
            notifyDataSetChanged()
        }
    }

    override fun getFilter(): Filter = substringFilter

    /** The message row is not a choice, so the list must not let it be clicked or selected. */
    override fun isEnabled(position: Int): Boolean = !showingNoMatch

    override fun areAllItemsEnabled(): Boolean = !showingNoMatch

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        // Set on every bind rather than only for the message: these rows are recycled.
        (view as? TextView)?.setTextColor(
            context.getColor(
                if (showingNoMatch) R.color.plugin_text_muted else R.color.plugin_on_surface
            )
        )
        return view
    }
}

/**
 * Factory for creating [OpenAiSettingsViewModel] with its PluginContext dependency.
 */
class OpenAiSettingsViewModelFactory(
    private val getContext: () -> PluginContext?
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OpenAiSettingsViewModel::class.java)) {
            return OpenAiSettingsViewModel(getContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
