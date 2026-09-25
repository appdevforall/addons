package com.itsaky.androidide.plugins.aiagentgemini.settings

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
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentgemini.plugin.GeminiPlugin
import com.itsaky.androidide.plugins.aiagentgemini.R
import com.itsaky.androidide.plugins.aiagentgemini.ui.SecretRevealController
import com.itsaky.androidide.plugins.aiagentgemini.ui.applyPaneStyling
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import com.itsaky.androidide.plugins.services.IdeTooltipService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The pane's secondary actions, drawn outlined; each section's Save stays filled. */
private val OUTLINED_BUTTON_IDS = setOf(
    R.id.btn_clear_api_key,
    R.id.btn_edit_api_key,
    R.id.btn_get_free_key,
    R.id.btn_refresh_models,
)

/**
 * This backend's settings pane, mounted by whichever screen offers a backend selector.
 *
 * Named to the host through `GeminiBackend.getSettingsFragmentClassName()`, loaded with this
 * plugin's own classloader and inflated against this plugin's own resources — so the consumer needs
 * to know nothing about API keys, AI Studio or Google's model catalog.
 */
class GeminiSettingsFragment : Fragment() {

    private lateinit var viewModel: GeminiSettingsViewModel
    private var tooltipService: IdeTooltipService? = null

    /**
     * Set while this pane is on screen, so [onResume] can nudge the user towards **Paste key**
     * after they come back from AI Studio. Cleared when the view is destroyed — it captures views,
     * so holding it any longer would leak them.
     */
    private var onPaneResume: (() -> Unit)? = null

    /**
     * The API key field's reveal control, held so the key can be re-masked when this pane leaves
     * the foreground. Captures views, so it is dropped in [onDestroyView] like [onPaneResume].
     */
    private var apiKeyReveal: SecretRevealController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // This pane is replaced in and out whenever the backend selector changes, so a theme-default
        // Material transition would be resolved here. Plugin resources are compileOnly, so those
        // transition resources aren't bundled; nulling them keeps the swap from touching them.
        enterTransition = null
        exitTransition = null

        try {
            tooltipService = PluginFragmentHelper.getServiceRegistry(GeminiPlugin.PLUGIN_ID)
                ?.get(IdeTooltipService::class.java)
        } catch (e: Exception) {
            // Tooltip help is optional; long-press simply shows nothing when it's unavailable.
            GeminiPlugin.getContext()?.logger
                ?.warn("GeminiSettingsFragment: tooltip service unavailable", e)
        }
    }

    /**
     * Route inflation through the host so this pane resolves against *this* plugin's resources and
     * a Context whose Configuration tracks the IDE's day/night setting. The inflater inherited from
     * the hosting screen belongs to that plugin and cannot see this one's layouts.
     */
    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(GeminiPlugin.PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_gemini_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(
            this,
            GeminiSettingsViewModelFactory { GeminiPlugin.getContext() }
        )[GeminiSettingsViewModel::class.java]

        view.applyPaneStyling(OUTLINED_BUTTON_IDS)
        setupApiKeyUi(view)
        setupModelPicker(view, chatModelPicker())
        setupModelPicker(view, embeddingModelPicker())
        setupModelRefresh(view)
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
        // Drops the captured pane views along with the callback.
        onPaneResume = null
        apiKeyReveal = null
        setSecureWindow(false)
        super.onDestroyView()
    }

    /** Shows this plugin's tooltip for [tag] when [view] is long-pressed (Tier 1/2 + guide button). */
    private fun wireTooltip(view: View, tag: String) {
        view.setOnLongClickListener { anchor ->
            val service = tooltipService ?: return@setOnLongClickListener false
            service.showTooltip(anchor, GeminiPlugin.TOOLTIP_CATEGORY, tag)
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
            service.showTooltip(icon, GeminiPlugin.TOOLTIP_CATEGORY, tag)
            true
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupApiKeyUi(view: View) {
        val apiKeyLayout = view.findViewById<LinearLayout>(R.id.gemini_api_key_layout)
        val apiKeyLabel = view.findViewById<TextView>(R.id.gemini_api_key_label)
        val apiKeyBox = view.findViewById<TextInputLayout>(R.id.gemini_api_key_box)
        val apiKeyInput = view.findViewById<EditText>(R.id.gemini_api_key_input)
        val saveButton = view.findViewById<Button>(R.id.btn_save_api_key)
        val editButton = view.findViewById<Button>(R.id.btn_edit_api_key)
        val clearButton = view.findViewById<Button>(R.id.btn_clear_api_key)
        val statusTextView = view.findViewById<TextView>(R.id.gemini_api_key_status_text)
        val getKeyButton = view.findViewById<Button>(R.id.btn_get_free_key)
        val verificationText = view.findViewById<TextView>(R.id.gemini_key_verification_text)

        // Not on apiKeyInput: long-press there is the paste menu, and a key is pasted.
        listOf<View>(
            apiKeyLabel, apiKeyBox, saveButton, editButton, clearButton, verificationText
        ).forEach { wireTooltip(it, GeminiPlugin.TOOLTIP_TAG_SETTINGS_GEMINI_KEY) }
        wireTooltip(getKeyButton, GeminiPlugin.TOOLTIP_TAG_SETTINGS_GET_KEY)

        // The status line takes the whole-plugin entry rather than a sixth copy of the key one:
        // this pane is the only UI this plugin draws, so the guide button that hangs off that entry
        // is otherwise unreachable.
        wireTooltip(statusTextView, GeminiPlugin.TOOLTIP_TAG_PLUGIN)

        /**
         * Show the outcome of (or progress of) the live key check.
         *
         * @param message the user-facing line; carries no status glyph of its own
         * @param icon leading status drawable, or 0 for the states that don't warrant one
         *   (in-progress, and the hints shown on returning from AI Studio)
         */
        fun showVerification(message: String, @DrawableRes icon: Int = 0) {
            verificationText.text = message
            // Relative (not left/right) so the icon follows the layout direction in RTL locales.
            verificationText.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            verificationText.visibility = View.VISIBLE
        }

        /** Drop a verdict that no longer describes what is in the field. */
        fun hideVerification() {
            verificationText.visibility = View.GONE
            verificationText.text = ""
            verificationText.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        }

        // "Get API Key" is absent here on purpose: it stays visible while this pane is shown.
        fun updateUiState(isEditing: Boolean) {
            if (isEditing) {
                statusTextView.visibility = View.GONE
                apiKeyLayout.visibility = View.VISIBLE
                saveButton.visibility = View.VISIBLE
                editButton.visibility = View.GONE
                clearButton.visibility = View.GONE
            } else {
                statusTextView.visibility = View.VISIBLE
                apiKeyLayout.visibility = View.GONE
                saveButton.visibility = View.GONE
                editButton.visibility = View.VISIBLE
                clearButton.visibility = View.VISIBLE
            }
        }

        /**
         * Report a request Google refused for credential reasons, if there is one. Read on resume
         * as well, since a refusal can land while this pane is already open and that does not
         * rebuild the view.
         */
        fun showCredentialFailure() {
            viewModel.credentialFailure()?.let { failure ->
                showVerification(
                    getString(R.string.msg_key_chat_failure, getString(failure.messageRes)),
                    R.drawable.ic_key_rejected
                )
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val stored = viewModel.getGeminiApiKey()
            val savedApiKey = (stored as? KeystoreSecretStore.Stored.Value)?.plain
            val hasKey = !savedApiKey.isNullOrBlank()
            // A keystore that would not answer this time leaves the key on disk and intact, so the
            // pane stays dressed as configured — status line, Edit, Remove. Opening edit mode
            // instead would make it identical to a fresh install, contradicting the toast below.
            val keptConfigured =
                !hasKey &&
                    stored is KeystoreSecretStore.Stored.Unavailable &&
                    viewModel.hasStoredGeminiApiKey()
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

        // Not saved, so a recreate cannot park a typed or revealed key in plain text in the state
        // Bundle; a stored one is read back from the encrypted store instead.
        apiKeyInput.isSaveEnabled = false

        // The window is flagged secure for exactly as long as the key is legible, which is why the
        // click is owned here rather than left to endIconMode="password_toggle".
        val reveal = SecretRevealController(apiKeyBox, apiKeyInput) { legible ->
            setSecureWindow(legible)
        }
        reveal.attach()
        apiKeyReveal = reveal

        wireEndIconTooltip(apiKeyBox, GeminiPlugin.TOOLTIP_TAG_SETTINGS_GEMINI_KEY)

        getKeyButton.setOnClickListener { openAiStudio() }

        // Coming back from AI Studio, point at the next step; the clipboard is never read.
        onPaneResume = {
            // Kept on the ViewModel so a rotation while AI Studio is in front doesn't lose the hint.
            if (viewModel.sentUserToAiStudio) {
                viewModel.sentUserToAiStudio = false
                // With a key already stored the field is hidden, so the next tap is Edit.
                showVerification(
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

        /** Enable or disable everything that would race the in-flight key check. */
        fun setKeyEntryEnabled(enabled: Boolean) {
            saveButton.isEnabled = enabled
            getKeyButton.isEnabled = enabled
            apiKeyInput.isEnabled = enabled
        }

        /**
         * Encrypt and store [apiKey], then reflect the outcome. Only ever reached for a key Google
         * confirmed, or one the user chose to keep after an inconclusive check.
         */
        suspend fun persistKey(
            apiKey: String,
            verified: Boolean,
            resultText: String,
            @DrawableRes resultIcon: Int
        ) {
            if (!viewModel.saveGeminiApiKey(apiKey, verified)) {
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
            showVerification(resultText, resultIcon)
            // A different key can reach a different set of models, so the picker is re-fetched.
            viewModel.fetchGeminiModels()
        }

        /**
         * Offer to keep a key that could not be checked. Distinct from a rejection: refusing a good
         * key because the device is offline would leave the plugin unconfigurable, so this gets the
         * muted "unchecked" icon and a key Google actually refused never reaches here.
         */
        fun confirmSaveUnverified(apiKey: String, reason: String) {
            showVerification(reason, R.drawable.ic_key_unchecked)
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
            // Blankness is the only shape rule: AI Studio keys need not match the AIza… form.
            if (apiKey.isBlank()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.msg_api_key_empty),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            setKeyEntryEnabled(false)
            showVerification(getString(R.string.msg_verifying_key))
            viewLifecycleOwner.lifecycleScope.launch {
                val verdict = try {
                    viewModel.verifyGeminiKey(apiKey)
                } finally {
                    setKeyEntryEnabled(true)
                }
                when (verdict) {
                    // Model count omitted: the user saved a key, not asked for a catalog.
                    is KeyVerification.Verified -> persistKey(
                        apiKey,
                        verified = true,
                        resultText = getString(R.string.msg_key_verified),
                        resultIcon = R.drawable.ic_key_verified
                    )

                    // A rate-limited key is a working key, so it gets the same icon as a clean pass.
                    KeyVerification.RateLimited -> persistKey(
                        apiKey,
                        verified = true,
                        resultText = getString(R.string.msg_key_verified_rate_limited),
                        resultIcon = R.drawable.ic_key_verified
                    )

                    // Nothing is written: a definitive refusal would only resurface mid-chat. Said
                    // aloud, because a user who is told the key was refused and then sees chat fail
                    // concludes the attempt destroyed the key they had, and re-buys a credential
                    // they never lost.
                    KeyVerification.Rejected -> {
                        // "Kept, and chat is still using it" only for a key chat can actually
                        // send and that is not the one just refused: a key the Keystore will no
                        // longer open is unusable, and Edit prefills the stored key, so re-saving
                        // it unchanged refuses the very credential chat is still sending.
                        val stored = viewModel.getGeminiApiKey()
                        val keptKeyInUse = stored is KeystoreSecretStore.Stored.Value &&
                            stored.plain.trim() != apiKey
                        showVerification(
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

                    KeyVerification.Unreachable ->
                        confirmSaveUnverified(apiKey, getString(R.string.msg_key_unreachable))

                    KeyVerification.Unknown ->
                        confirmSaveUnverified(apiKey, getString(R.string.msg_key_uncheckable))
                }
            }
        }

        // Reveal the (already-fetched) key in an editable, focused field.
        fun revealEditMode(apiKey: String) {
            apiKeyInput.setText(apiKey)
            apiKeyInput.setSelection(apiKey.length)
            // The old verdict described the stored key, which is about to change.
            hideVerification()
            updateUiState(isEditing = true)
            // Opened masked: the key is loaded, not being read back.
            reveal.mask()
            apiKeyInput.requestFocus()
        }

        editButton.setOnClickListener {
            editButton.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val stored = try {
                    viewModel.getGeminiApiKey()
                } finally {
                    editButton.isEnabled = true
                }
                // A key that is stored and will not decrypt; an empty box alone looks like data
                // loss. Told apart from "nothing stored" here, which this button rarely sees but
                // must not report as a lost Keystore entry when it does.
                if (stored is KeystoreSecretStore.Stored.Unavailable) {
                    // Said differently from an unreadable key: this one is still there and intact,
                    // so the pane stays as it is rather than emptying the field under a status line
                    // that just said the key is saved — it must not be re-typed to be recovered.
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
            viewModel.clearGeminiApiKey()
            Toast.makeText(
                requireContext(),
                getString(R.string.msg_api_key_cleared),
                Toast.LENGTH_SHORT
            ).show()
            hideVerification()
            updateUiState(isEditing = true)
            apiKeyInput.setText("")
        }
    }

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
     * Status line for a stored key: dated when the save time is known, generic otherwise, and
     * saying "verified" only for a key Google actually confirmed — a key kept through the
     * save-anyway path was never checked and must not claim otherwise.
     */
    private fun savedApiKeyStatusText(): String {
        val timestamp = viewModel.getGeminiApiKeySaveTimestamp()
        val verified = viewModel.isGeminiKeyVerified()
        if (timestamp <= 0) return getString(R.string.msg_api_key_is_saved)
        val savedDate = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
        return if (verified) {
            getString(R.string.msg_api_key_verified_on, savedDate)
        } else {
            getString(R.string.msg_api_key_saved_on, savedDate)
        }
    }

    /**
     * Open Google AI Studio's key page in the *system* browser.
     *
     * A real browser, not a WebView: Google blocks sign-in in embedded WebViews, and the user
     * should see Google's own URL bar. With no browser at all, the URL is copied instead.
     */
    private fun openAiStudio() {
        val url = GeminiKeyOnboarding.AI_STUDIO_URL
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { startActivity(intent) }
            .onSuccess { viewModel.sentUserToAiStudio = true }
            .onFailure { error ->
                GeminiPlugin.getContext()?.logger
                    ?.warn("GeminiSettingsFragment: no browser could open AI Studio", error)
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
     * Only ever used for the public AI Studio URL — never for a key, which would put the secret
     * somewhere every app on the device can read it.
     *
     * @return true when the clipboard accepted the value
     */
    private fun copyToClipboard(text: String): Boolean {
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.gemini_clip_label), text))
        }.isSuccess
    }


    /**
     * Put the dropdown chevron on [box]'s end icon.
     *
     * The drawable is set here rather than in the layout because an end icon declared as
     * `app:endIconDrawable` draws blank inside the host — the same reason [SecretRevealController]
     * sets the reveal icon in code. Without a visible chevron the field
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

    // --- Model pickers -------------------------------------------------------------------------

    /**
     * Everything one model dropdown needs, so the chat and embedding pickers are one
     * implementation rather than two that drift.
     *
     * @param tooltipTag long-press help shared by the picker's label, field, hint and chevron
     * @param helpHint hint shown while no catalog is offered
     * @param liveHint hint shown once there is a list to tap
     * @param read the currently saved model
     * @param write persists a model the user picked
     * @param options the catalog to offer
     */
    private class ModelPicker(
        @IdRes val boxId: Int,
        @IdRes val inputId: Int,
        @IdRes val labelId: Int,
        @IdRes val hintId: Int,
        val tooltipTag: String,
        @StringRes val helpHint: Int,
        @StringRes val liveHint: Int,
        val read: () -> String,
        val write: (String) -> Unit,
        val options: LiveData<GeminiModelOptions>,
    )

    /** The chat model: what a turn is generated with. */
    private fun chatModelPicker() = ModelPicker(
        boxId = R.id.gemini_model_box,
        inputId = R.id.gemini_model_input,
        labelId = R.id.gemini_model_label,
        hintId = R.id.gemini_model_hint_text,
        tooltipTag = GeminiPlugin.TOOLTIP_TAG_SETTINGS_GEMINI_MODEL,
        helpHint = R.string.hint_gemini_model_help,
        liveHint = R.string.hint_gemini_model_live,
        read = viewModel::getGeminiModel,
        write = viewModel::saveGeminiModel,
        options = viewModel.geminiModels,
    )

    /** The embedding model: what semantic search indexes and queries with. */
    private fun embeddingModelPicker() = ModelPicker(
        boxId = R.id.gemini_embedding_model_box,
        inputId = R.id.gemini_embedding_model_input,
        labelId = R.id.gemini_embedding_model_label,
        hintId = R.id.gemini_embedding_model_hint_text,
        tooltipTag = GeminiPlugin.TOOLTIP_TAG_SETTINGS_GEMINI_EMBEDDING_MODEL,
        helpHint = R.string.hint_gemini_embedding_model_help,
        liveHint = R.string.hint_gemini_embedding_model_live,
        read = viewModel::getGeminiEmbeddingModel,
        write = viewModel::saveGeminiEmbeddingModel,
        options = viewModel.geminiEmbeddingModels,
    )

    /**
     * One model dropdown, built as the same control the OpenAI pane carries rather than a
     * framework Spinner: one box, one chevron, one list.
     *
     * Pick-only — `keyListener = null` — because the catalog is whatever the key can reach, so
     * unlike the OpenAI pane there is no free-text model to type. The field itself is what shows
     * the model in use, which is why there is no separate "current model" line any more.
     */
    private fun setupModelPicker(view: View, picker: ModelPicker) {
        val modelBox = view.findViewById<TextInputLayout>(picker.boxId)
        val modelInput = view.findViewById<AutoCompleteTextView>(picker.inputId)
        val modelLabel = view.findViewById<TextView>(picker.labelId)
        val modelHint = view.findViewById<TextView>(picker.hintId)

        setupDropdownEndIcon(modelBox)

        listOf<View>(modelLabel, modelInput, modelHint)
            .forEach { wireTooltip(it, picker.tooltipTag) }

        // A picker, not a text field: the list is the only way to change it.
        modelInput.keyListener = null
        // The list is rebuilt from the catalog on every view creation, so there is nothing for the
        // framework to restore — and its replayed setText() is a filtering one, which is what left
        // the list holding only the selected entry after a day/night switch.
        modelInput.isSaveEnabled = false
        // The suppressing overload throughout: a filtering write would narrow the list.
        modelInput.setText(picker.read(), false)

        // Tapping anywhere in the field opens the list; the end icon is only a second way in.
        modelInput.setOnClickListener { modelInput.showDropDown() }
        modelBox.setEndIconOnClickListener { modelInput.showDropDown() }
        wireEndIconTooltip(modelBox, picker.tooltipTag)
        // Only a real pick reaches here, so unlike the Spinner this replaced there is no
        // programmatic selection to tell apart from a user's.
        modelInput.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as? String
            if (selected == null || selected == picker.read()) {
                return@setOnItemClickListener
            }
            picker.write(selected)
            Toast.makeText(
                requireContext(),
                getString(R.string.model_changed, selected),
                Toast.LENGTH_SHORT
            ).show()
        }

        picker.options.observe(viewLifecycleOwner) { options ->
            // Cleared, not left stale: an empty list means there is no catalog to offer, and a
            // remembered one would suggest models this key may no longer reach.
            modelInput.setAdapter(
                if (options.models.isEmpty()) {
                    null
                } else {
                    DropdownAdapter(modelInput.context, options.models)
                }
            )
            modelHint.setText(if (options.models.isEmpty()) picker.helpHint else picker.liveHint)

            // Migrate off a retired saved model only for a live catalog, never for the fallback:
            // the field has to show what will actually be requested.
            if (!options.isLive || options.models.contains(picker.read())) {
                return@observe
            }
            val migrated = options.models.firstOrNull() ?: return@observe
            picker.write(migrated)
            modelInput.setText(migrated, false)
        }
    }

    /**
     * The one Refresh button, shared by both pickers since one catalog walk answers them both.
     *
     * The initial fetch is guarded on the chat picker alone: the two are published together, so a
     * non-empty chat list means the fetch has already happened.
     */
    private fun setupModelRefresh(view: View) {
        val refreshButton = view.findViewById<Button>(R.id.btn_refresh_models)
        wireTooltip(refreshButton, GeminiPlugin.TOOLTIP_TAG_SETTINGS_GEMINI_MODEL)

        viewModel.geminiModelsLoading.observe(viewLifecycleOwner) { isLoading ->
            refreshButton.isEnabled = !isLoading
            refreshButton.text =
                if (isLoading) getString(R.string.loading) else getString(R.string.refresh_models)
        }

        refreshButton.setOnClickListener { viewModel.fetchGeminiModels() }

        if (viewModel.geminiModels.value?.models.isNullOrEmpty()) {
            viewModel.fetchGeminiModels()
        }
    }
}

/**
 * The model dropdown's rows and, more importantly, its filter.
 *
 * [ArrayAdapter]'s own filter narrows the list to whatever is already in the field, so after a
 * day/night switch — which replays the field's text — the only row left to pick is the one already
 * chosen. Substring matching over the full list is what a picker needs; identical to the OpenAI
 * pane's adapter so the two can be lifted into `plugin-api` together.
 */
private class DropdownAdapter(
    context: Context,
    private val items: List<String>,
) : ArrayAdapter<String>(context, R.layout.item_dropdown, items.toMutableList()) {

    private val substringFilter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val query = constraint?.toString()?.trim().orEmpty()
            val matches = if (query.isEmpty()) {
                items
            } else {
                items.filter { it.contains(query, ignoreCase = true) }
            }
            return FilterResults().apply {
                values = matches
                count = matches.size
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            val rows = results?.values as? List<String> ?: items
            clear()
            addAll(rows)
            notifyDataSetChanged()
        }
    }

    override fun getFilter(): Filter = substringFilter

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        // Set on every bind rather than once: these rows are recycled.
        (view as? TextView)?.setTextColor(context.getColor(R.color.plugin_on_surface))
        return view
    }
}

/**
 * Factory for creating [GeminiSettingsViewModel] with its PluginContext dependency.
 */
class GeminiSettingsViewModelFactory(
    private val getContext: () -> PluginContext?
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GeminiSettingsViewModel::class.java)) {
            return GeminiSettingsViewModel(getContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
