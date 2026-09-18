package com.itsaky.androidide.plugins.aiagentmcp.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.plugins.aiagentmcp.R
import com.itsaky.androidide.plugins.aiagentmcp.client.McpTool
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpToolCatalog
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpHeaders
import com.itsaky.androidide.plugins.aiagentmcp.ui.SecretRevealController
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeTooltipService
import kotlinx.coroutines.launch

/**
 * The MCP settings screen, opened from Preferences → Configuration → MCP servers.
 *
 * Loaded by name with this plugin's own classloader and inflated against this plugin's own
 * resources, so the host needs to know nothing about MCP.
 */
class McpSettingsFragment : Fragment() {

    private lateinit var viewModel: McpSettingsViewModel
    private var tooltipService: IdeTooltipService? = null

    /**
     * The dialogs this screen owns, tracked so a rotation takes them down with the view they were
     * anchored to; an untracked one outlives it as a leaked window.
     */
    private var serverDialog: AlertDialog? = null
    private var deleteDialog: AlertDialog? = null

    /**
     * The token field's reveal control, while the add/edit dialog is up. Held so a revealed token
     * can be re-masked when the screen leaves the foreground; captures the dialog's views, so it
     * is dropped with the dialog.
     */
    private var tokenReveal: SecretRevealController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            tooltipService = PluginFragmentHelper.getServiceRegistry(McpPlugin.PLUGIN_ID)
                ?.get(IdeTooltipService::class.java)
        } catch (e: Exception) {
            // Tooltip help is optional; long-press simply shows nothing when it's unavailable.
            McpPlugin.getContext()?.logger?.warn("McpSettingsFragment: no tooltip service", e)
        }
    }

    /**
     * Routes inflation through the host so this screen resolves against *this* plugin's resources
     * and a Context whose Configuration tracks the IDE's day/night setting.
     */
    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(McpPlugin.PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_mcp_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(
            this,
            McpSettingsViewModelFactory { McpPlugin.getContext() }
        )[McpSettingsViewModel::class.java]

        val backButton = view.findViewById<ImageButton>(R.id.mcpBack)
        wireTooltip(backButton, McpPlugin.TOOLTIP_TAG_BACK)
        backButton.setOnClickListener { leaveScreen() }

        val addButton = view.findViewById<Button>(R.id.mcpAddServer)
        wireTooltip(addButton, McpPlugin.TOOLTIP_TAG_ADD_SERVER)
        addButton.setOnClickListener { showServerDialog(null) }

        // viewLifecycleOwner, not the fragment: the collector captures views and must stop with them.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.servers.collect { servers -> renderServers(view, servers) }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.reload()
    }

    /**
     * Re-mask a revealed token on the way out of the foreground.
     *
     * Re-masking rather than only clearing [WindowManager.LayoutParams.FLAG_SECURE]: the flag has
     * to go, since the activity window outlives this screen and nothing else would clear it, and
     * dropping it over a legible token is what would let the recents thumbnail keep a copy of it.
     */
    override fun onPause() {
        tokenReveal?.mask()
        super.onPause()
    }

    /**
     * Leaves this screen.
     *
     * The host mounts a plugin settings pane either on its own back stack or as the whole activity,
     * so both are handled rather than assuming the one this plugin happens to get today.
     */
    private fun leaveScreen() {
        if (parentFragmentManager.backStackEntryCount > 0) {
            parentFragmentManager.popBackStack()
        } else {
            requireActivity().finish()
        }
    }

    /**
     * Rebuilds the server list.
     * @param root the screen's root view.
     * @param servers the servers to show.
     */
    private fun renderServers(root: View, servers: List<McpServer>) {
        val list = root.findViewById<LinearLayout>(R.id.mcpServerList)
        val empty = root.findViewById<TextView>(R.id.mcpEmptyState)
        list.removeAllViews()
        empty.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE

        for (server in servers) {
            val row = layoutInflater.inflate(R.layout.item_mcp_server, list, false)
            row.findViewById<TextView>(R.id.mcpServerName).text = server.name
            row.findViewById<TextView>(R.id.mcpServerUrl).text = server.url
            row.findViewById<TextView>(R.id.mcpServerTools).text = getString(
                R.string.mcp_server_tools_summary,
                server.enabledTools.size,
                server.knownTools.size,
            )

            val enabled = row.findViewById<SwitchMaterial>(R.id.mcpServerEnabled)
            // Set before the listener, or restoring state counts as a user change.
            enabled.isChecked = server.enabled
            enabled.setOnCheckedChangeListener { _, isChecked ->
                viewModel.setEnabled(server.id, isChecked)
            }
            wireTooltip(enabled, McpPlugin.TOOLTIP_TAG_SERVER_ENABLED)

            row.setOnClickListener { showServerDialog(server) }
            wireTooltip(row, McpPlugin.TOOLTIP_TAG_SERVER_ROW)
            list.addView(row)
        }
    }

    /**
     * Opens the add/edit dialog.
     * @param existing the server to edit, or null to add one.
     */
    private fun showServerDialog(existing: McpServer?) {
        val view = layoutInflater.inflate(R.layout.dialog_mcp_server, null)
        val nameField = view.findViewById<EditText>(R.id.mcpName)
        val urlField = view.findViewById<EditText>(R.id.mcpUrl)
        val tokenBox = view.findViewById<TextInputLayout>(R.id.mcpTokenBox)
        val tokenField = view.findViewById<EditText>(R.id.mcpToken)
        val status = view.findViewById<TextView>(R.id.mcpStatus)

        wireTooltip(nameField, McpPlugin.TOOLTIP_TAG_SERVER_NAME)
        wireTooltip(urlField, McpPlugin.TOOLTIP_TAG_SERVER_URL)
        wireTooltip(tokenField, McpPlugin.TOOLTIP_TAG_SERVER_TOKEN)
        wireTooltip(tokenBox, McpPlugin.TOOLTIP_TAG_SERVER_TOKEN)
        wireEndIconTooltip(tokenBox, McpPlugin.TOOLTIP_TAG_SERVER_TOKEN)

        // Not saved, so a recreate cannot park a typed or revealed token in plain text in the
        // state Bundle; a stored one is read back from the encrypted store instead.
        tokenField.isSaveEnabled = false

        // The token was maskable and nothing more before this: it could only be typed blind.
        tokenReveal = SecretRevealController(tokenBox, tokenField) { legible ->
            setSecureWindows(legible)
        }.also { it.attach() }

        var server = existing ?: viewModel.newServer()
        // Unknown, never Absent, until the decrypt answers: guessing is what let an http:// URL
        // be saved over a token that was still stored and still sent.
        var credential = if (existing == null) Credential.ABSENT else Credential.UNKNOWN
        // What the header rows on screen are: the stored set, or not that. Anything but
        // [Headers.KNOWN] means an empty list is "never drawn" rather than "none", and a typed row
        // is no basis for replacing a set the dialog was in no position to show.
        var headersState = if (existing == null) Headers.KNOWN else Headers.UNKNOWN
        nameField.setText(server.name)
        urlField.setText(server.url)

        renderTools(view, server.id, McpToolCatalog.tools(server.id))

        view.findViewById<ImageButton>(R.id.mcpAddHeader).also { button ->
            wireTooltip(button, McpPlugin.TOOLTIP_TAG_ADD_HEADER)
            button.setOnClickListener { addHeaderRow(view) }
        }

        val clearButton = view.findViewById<Button>(R.id.mcpClearCredential)
        wireTooltip(clearButton, McpPlugin.TOOLTIP_TAG_CLEAR_CREDENTIAL)
        clearButton.setOnClickListener {
            clearButton.isEnabled = false
            viewModel.clearCredential(server.id) { cleared ->
                if (cleared) {
                    credential = Credential.ABSENT
                    // Nothing is stored now, so the empty list on screen is the stored truth again.
                    headersState = Headers.KNOWN
                }
                whileDialogShown {
                    clearButton.isEnabled = !cleared
                    clearButton.visibility = if (cleared) View.GONE else View.VISIBLE
                    if (cleared) {
                        tokenField.text = null
                        tokenField.hint = getString(R.string.mcp_hint_token)
                        renderHeaders(view, emptyMap())
                        status.text = getString(R.string.mcp_credential_cleared)
                    }
                }
            }
        }

        // Reading either costs a Keystore decrypt, so both arrive a moment after the dialog does.
        renderHeaders(view, emptyMap())
        if (existing != null) {
            viewModel.loadForm(server.id) { form ->
                // Only while unknown, and from key presence: a late load must neither undo a
                // Refresh nor read headers this device cannot decrypt as no credential at all.
                if (credential == Credential.UNKNOWN) {
                    credential =
                        if (form.hasToken || form.hasHeaders) Credential.PRESENT
                        else Credential.ABSENT
                }
                whileDialogShown {
                    // The stored token is never shown: it is decrypted only to be sent. An empty
                    // field on an existing server means "leave it alone", which the placeholder
                    // says aloud — unless nothing on this device can read it any more. The hint
                    // speaks for the token alone; headers that failed have their own sentence.
                    tokenField.hint = getString(
                        when {
                            form.tokenUnreadable -> R.string.mcp_hint_token_unreadable
                            form.tokenUnavailable -> R.string.mcp_hint_token_unavailable
                            form.hasToken -> R.string.mcp_hint_token_stored
                            else -> R.string.mcp_hint_token
                        }
                    )
                    // The status line covers whichever credential failed. Unreadable first: a
                    // credential that has to be entered again is the worse news.
                    when {
                        form.tokenUnreadable || form.headersUnreadable ->
                            status.text = getString(R.string.mcp_secrets_unreadable)
                        form.tokenUnavailable || form.headersUnavailable ->
                            status.text = getString(R.string.mcp_secrets_unavailable)
                    }
                    // Offered only once something is known to be stored; there is nothing to
                    // clear otherwise, and an unreadable secret is exactly what it is for.
                    clearButton.visibility =
                        if (credential == Credential.PRESENT) View.VISIBLE else View.GONE
                    // Recorded beside the render that draws them, not behind the credential guard
                    // above: these rows appear whatever that guard decided, and a deletion made
                    // once they are on screen must not be dropped as "never drawn".
                    headersState = when {
                        form.headersKnown -> Headers.KNOWN
                        form.headersUnavailable -> Headers.UNAVAILABLE
                        else -> Headers.UNREADABLE
                    }
                    // Only the stored set replaces what is on screen. A failed decrypt has nothing
                    // to draw, and clearing the list would take with it any row typed while it was
                    // in flight — rows the save guard below then refuses to let be retyped.
                    if (form.headersKnown) renderHeaders(view, form.headers)
                }
            }
        }

        view.findViewById<Button>(R.id.mcpConnect).also { button ->
            wireTooltip(button, McpPlugin.TOOLTIP_TAG_CONNECT)
            button.setOnClickListener {
                val candidate = server.copy(
                    name = nameField.text.toString().trim(),
                    url = urlField.text.toString().trim(),
                )
                val headers = collectHeaders(view)
                if (headers == null) {
                    status.text = getString(R.string.mcp_headers_invalid)
                    return@setOnClickListener
                }
                val validation =
                    validate(candidate, tokenField.text.toString(), credential, headers, headersState)
                if (validation != null) {
                    status.text = validation
                    return@setOnClickListener
                }
                // Stored first: connecting reads the URL and credentials from the store.
                status.text = getString(R.string.mcp_status_connecting)
                button.isEnabled = false
                viewModel.save(
                    candidate,
                    viewModel.tokenToStore(tokenField.text.toString()),
                    viewModel.headersToStore(headers, headersState == Headers.KNOWN),
                ) { saved, failure ->
                    server = saved
                    if (tokenField.text.isNotBlank() || headers.isNotEmpty()) {
                        credential = Credential.PRESENT
                    }
                    whileDialogShown {
                        clearButton.visibility =
                            if (credential == Credential.PRESENT) View.VISIBLE else View.GONE
                    }
                    // An unstored credential would go out missing and come back a puzzling 401.
                    if (failure != null) {
                        whileDialogShown {
                            status.text = failure
                            button.isEnabled = true
                        }
                        return@save
                    }
                    viewModel.connect(saved) { message, tools ->
                        whileDialogShown {
                            status.text = message
                            renderTools(view, saved.id, tools)
                            button.isEnabled = true
                        }
                    }
                }
            }
        }

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) R.string.mcp_dialog_add_title else R.string.mcp_dialog_edit_title)
            .setView(view)
            // Bound after show() instead: a positive button set here dismisses whatever the
            // listener decides, which would close the dialog over an unsaved, invalid header.
            .setPositiveButton(R.string.mcp_save, null)
            .setNegativeButton(R.string.mcp_cancel, null)

        if (existing != null) {
            builder.setNeutralButton(R.string.mcp_delete) { _, _ -> confirmDelete(existing) }
        }

        val dialog = builder.create()
        serverDialog = dialog
        dialog.setOnDismissListener {
            // Masked first, so the flag is cleared while the dialog's own window is still reachable.
            tokenReveal?.mask()
            tokenReveal = null
            serverDialog = null
        }
        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE) ?: return@setOnShowListener
            saveButton.setOnClickListener {
                val candidate = server.copy(
                    name = nameField.text.toString().trim(),
                    url = urlField.text.toString().trim(),
                )
                // A malformed header stops the save: storing it would drop it later in silence.
                val headers = collectHeaders(view)
                if (headers == null) {
                    status.text = getString(R.string.mcp_headers_invalid)
                    return@setOnClickListener
                }
                val problem =
                    validate(candidate, tokenField.text.toString(), credential, headers, headersState)
                if (problem != null) {
                    status.text = problem
                    return@setOnClickListener
                }
                saveButton.isEnabled = false
                viewModel.save(
                    candidate,
                    viewModel.tokenToStore(tokenField.text.toString()),
                    viewModel.headersToStore(headers, headersState == Headers.KNOWN),
                ) { _, failure ->
                    // Dismissed only once the credential is stored, as Connect already does: a
                    // keystore that will not encrypt would otherwise lose the token behind a
                    // dialog that closed as though it had saved.
                    if (failure == null) {
                        whileDialogShown { dialog.dismiss() }
                        return@save
                    }
                    whileDialogShown {
                        status.text = failure
                        saveButton.isEnabled = true
                    }
                }
            }
        }
        dialog.show()
    }

    /**
     * Fills the dialog's header list.
     * @param view the dialog's root view.
     * @param headers the headers to show, in entry order.
     */
    private fun renderHeaders(view: View, headers: Map<String, String>) {
        val list = view.findViewById<LinearLayout>(R.id.mcpHeaderList)
        list.removeAllViews()
        headers.forEach { (name, value) -> addHeaderRow(view, name, value) }
        updateHeadersEmptyState(view)
    }

    /**
     * Appends one header row.
     * @param view the dialog's root view.
     * @param name the header name, empty for a row the user is about to fill.
     * @param value the header value.
     */
    private fun addHeaderRow(view: View, name: String = "", value: String = "") {
        val list = view.findViewById<LinearLayout>(R.id.mcpHeaderList)
        val row = layoutInflater.inflate(R.layout.item_mcp_header, list, false)
        row.findViewById<EditText>(R.id.mcpHeaderName).also { field ->
            field.setText(name)
            wireTooltip(field, McpPlugin.TOOLTIP_TAG_HEADER_NAME)
        }
        row.findViewById<EditText>(R.id.mcpHeaderValue).also { field ->
            field.setText(value)
            wireTooltip(field, McpPlugin.TOOLTIP_TAG_HEADER_VALUE)
        }
        row.findViewById<ImageButton>(R.id.mcpHeaderRemove).also { button ->
            wireTooltip(button, McpPlugin.TOOLTIP_TAG_HEADER_REMOVE)
            button.setOnClickListener {
                list.removeView(row)
                updateHeadersEmptyState(view)
            }
        }
        list.addView(row)
        updateHeadersEmptyState(view)
    }

    /** Shows the empty note only while there is no row to look at. */
    private fun updateHeadersEmptyState(view: View) {
        val list = view.findViewById<LinearLayout>(R.id.mcpHeaderList)
        view.findViewById<TextView>(R.id.mcpHeadersEmpty).visibility =
            if (list.childCount == 0) View.VISIBLE else View.GONE
    }

    /**
     * Reads the header rows back, marking any the server could not be sent.
     *
     * A row left entirely blank is not an error — it is a row the user added and thought better of
     * — so it is skipped silently; a half-filled or malformed one stops the save, because saving it
     * would drop it later without telling anyone.
     *
     * @param view the dialog's root view.
     * @return the headers, or null when a row is unusable and has been marked.
     */
    private fun collectHeaders(view: View): Map<String, String>? {
        val list = view.findViewById<LinearLayout>(R.id.mcpHeaderList)
        val headers = LinkedHashMap<String, String>()
        var valid = true

        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index)
            val nameField = row.findViewById<EditText>(R.id.mcpHeaderName)
            val valueField = row.findViewById<EditText>(R.id.mcpHeaderValue)
            val error = row.findViewById<TextView>(R.id.mcpHeaderError)
            val name = nameField.text.toString().trim()
            val value = valueField.text.toString()

            error.visibility = View.GONE
            if (name.isEmpty() && value.isEmpty()) continue

            val problem = McpHeaders.rowProblem(name, value, headers.keys)
            if (problem != null) {
                error.text = getString(headerProblemText(problem))
                error.visibility = View.VISIBLE
                valid = false
                continue
            }
            headers[name] = value
        }
        return if (valid) headers else null
    }

    /**
     * The message for a rejected header row.
     * @param problem what the validator found.
     * @return the string resource to show.
     */
    private fun headerProblemText(problem: McpHeaders.Problem): Int = when (problem) {
        McpHeaders.Problem.EMPTY -> R.string.mcp_hint_header_name
        McpHeaders.Problem.RESERVED -> R.string.mcp_header_name_reserved
        McpHeaders.Problem.ILLEGAL_CHARACTERS -> R.string.mcp_header_name_illegal
        McpHeaders.Problem.TOO_LONG -> R.string.mcp_header_name_too_long
        McpHeaders.Problem.ILLEGAL_VALUE -> R.string.mcp_header_value_illegal
        McpHeaders.Problem.DUPLICATE -> R.string.mcp_header_name_duplicate
    }

    /**
     * Fills the dialog's tool list.
     *
     * Takes the server's id rather than the record: the switches are read back from the store on
     * every render and written straight through by id, so nothing here can hand a snapshot from
     * before the last toggle back to the store.
     *
     * @param view the dialog's root view.
     * @param serverId the server being edited.
     * @param tools the tools it advertises.
     */
    private fun renderTools(view: View, serverId: String, tools: List<McpTool>) {
        val list = view.findViewById<LinearLayout>(R.id.mcpToolList)
        val empty = view.findViewById<TextView>(R.id.mcpToolsEmpty)
        list.removeAllViews()
        empty.visibility = if (tools.isEmpty()) View.VISIBLE else View.GONE

        val enabledTools = viewModel.enabledTools(serverId)

        for (tool in tools) {
            val row = layoutInflater.inflate(R.layout.item_mcp_tool, list, false)
            val toggle = row.findViewById<SwitchMaterial>(R.id.mcpToolSwitch)
            toggle.text = tool.name
            // Set before the listener, or restoring state counts as a user change.
            toggle.isChecked = tool.name in enabledTools
            toggle.setOnCheckedChangeListener { _, isChecked ->
                viewModel.setToolEnabled(serverId, tool.name, isChecked)
            }
            wireTooltip(toggle, McpPlugin.TOOLTIP_TAG_TOOL_TOGGLE)

            row.findViewById<TextView>(R.id.mcpToolDescription).text = tool.description
            list.addView(row)
        }
    }

    /**
     * Asks before removing a server, since the token goes with it.
     * @param server the server to remove.
     */
    private fun confirmDelete(server: McpServer) {
        deleteDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mcp_delete_title)
            .setMessage(getString(R.string.mcp_delete_message, server.name))
            .setPositiveButton(R.string.mcp_delete) { _, _ -> viewModel.delete(server.id) }
            .setNegativeButton(R.string.mcp_cancel, null)
            .setOnDismissListener { deleteDialog = null }
            .create()
            .apply { show() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Both are anchored to the view being destroyed; see the fields' comment.
        serverDialog?.dismiss()
        serverDialog = null
        deleteDialog?.dismiss()
        deleteDialog = null
        tokenReveal = null
        // The activity window outlives this screen, so a flag left on it would outlive it too.
        setSecureWindows(false)
    }

    /**
     * Add or clear [WindowManager.LayoutParams.FLAG_SECURE] on every window that can be showing the
     * token, so screenshots and the recents thumbnail cannot capture it.
     *
     * Both windows, not just the dialog's: the dialog is the window the token is drawn in, and the
     * activity behind it is the one the recents thumbnail is taken of.
     *
     * @param secure true to block capture, false to allow it again
     */
    private fun setSecureWindows(secure: Boolean) {
        for (window in listOfNotNull<Window>(activity?.window, serverDialog?.window)) {
            if (secure) {
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE
                )
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    /**
     * Checks what the user typed.
     *
     * The token is checked here rather than at the socket: a pasted credential often arrives with a
     * stray line break, and the transport refuses to send one, so catching it on Save is the
     * difference between a marked field and a connection that fails for no visible reason.
     *
     * A credential on a cleartext URL is refused outright. The token field's help promises the
     * value is encrypted under the Android Keystore — true at rest, but `http://` hands it to
     * anyone on the same Wi-Fi on the way out, and a phone IDE runs on shared Wi-Fi by definition.
     * A local server with no credential is still allowed.
     *
     * A cleartext URL is also refused while [credential] is still [Credential.UNKNOWN]: the stored
     * token is read on a background thread and the dialog opens before that answers, so treating
     * "not yet known" as "nothing stored" is what allowed an `http://` URL to be saved over a
     * token that was still there and still sent. `https://` needs no such wait.
     *
     * @param server the candidate.
     * @param typedToken the current contents of the token field.
     * @param credential what is known about a stored token or header, which an empty token field
     *   keeps rather than clears.
     * @param headers the header rows as they currently stand.
     * @return the message to show, or null when it is usable.
     */
    private fun validate(
        server: McpServer,
        typedToken: String,
        credential: Credential,
        headers: Map<String, String>,
        headersState: Headers,
    ): String? = when {
        server.name.isBlank() -> getString(R.string.mcp_name_required)
        server.url.isBlank() -> getString(R.string.mcp_url_required)
        !server.url.startsWith("http://") && !server.url.startsWith("https://") ->
            getString(R.string.mcp_url_scheme_invalid)
        !McpHeaders.isSendableToken(typedToken.trim()) -> getString(R.string.mcp_token_illegal)
        // Saving a typed row would replace the whole stored set, so it is refused while the dialog
        // cannot show what that set is — each state with the advice that actually helps it.
        headers.isNotEmpty() && headersState == Headers.UNKNOWN ->
            getString(R.string.mcp_credentials_loading)
        headers.isNotEmpty() && headersState == Headers.UNAVAILABLE ->
            getString(R.string.mcp_headers_unavailable_no_replace)
        headers.isNotEmpty() && headersState == Headers.UNREADABLE ->
            getString(R.string.mcp_headers_unreadable_no_replace)
        server.url.startsWith("http://") && credential == Credential.UNKNOWN ->
            getString(R.string.mcp_credentials_loading)
        server.url.startsWith("http://") &&
            (typedToken.isNotBlank() || credential == Credential.PRESENT || headers.isNotEmpty()) ->
            getString(R.string.mcp_url_insecure_credentials)
        else -> null
    }

    /**
     * What the dialog knows about the credential stored for the server it is showing.
     *
     * Three states rather than a flag: reading it is a Keystore decrypt on a background thread, and
     * the gap before the answer arrives is real enough for a user to edit the URL and hit Save in.
     */
    private enum class Credential {

        /** The decrypt has not answered yet. */
        UNKNOWN,

        /** A token, a header, or both are stored. */
        PRESENT,

        /** Nothing is stored — a new server, or one just cleared. */
        ABSENT,
    }

    /**
     * What the header rows on screen are, which decides what saving them may do.
     *
     * The host's own four-way secret read, as this dialog sees it: only [KNOWN] rows may replace
     * the stored set, and the three others each need their own sentence rather than one "could not
     * be read" that sends a user with intact headers off to enter them again.
     */
    private enum class Headers {

        /** The decrypt has not answered yet. */
        UNKNOWN,

        /** Stored headers this device's Keystore can no longer open. Permanent: clear and re-enter. */
        UNREADABLE,

        /** Stored headers the Keystore would not answer for this time. Transient: retry. */
        UNAVAILABLE,

        /** The rows on screen are the stored set — a new server, a successful read, or just cleared. */
        KNOWN,
    }

    /**
     * Runs [action] only while the dialog it draws on is still on screen.
     *
     * The ViewModel's work is tied to the ViewModel, not to this view: a refresh can take up to the
     * read timeout, and a rotation or a back press in the meantime destroys the view the queued
     * callback was about to write to — `getString` and `layoutInflater` on a detached Fragment
     * throw. Guarded here rather than by cancelling the work, because storing a token and a tool
     * catalogue has to finish whether or not anyone is still looking.
     *
     * @param action the UI update to run, if there is still a UI to run it on.
     */
    private fun whileDialogShown(action: () -> Unit) {
        if (isAdded && view != null && serverDialog?.isShowing == true) action()
    }

    /** Shows this plugin's tooltip for [tag] when [view] is long-pressed (Tier 1/2 + guide). */
    private fun wireTooltip(view: View, tag: String) {
        view.setOnLongClickListener { anchor ->
            val service = tooltipService ?: return@setOnLongClickListener false
            service.showTooltip(anchor, McpPlugin.TOOLTIP_CATEGORY, tag)
            true
        }
    }

    /**
     * Long-press on [box]'s end icon shows [tag]'s tooltip.
     *
     * Separate from [wireTooltip] because the end icon is a clickable child that consumes the
     * long-press before the box sees it — without this the reveal control would be the one
     * contributed element with no tooltip of its own.
     */
    private fun wireEndIconTooltip(box: TextInputLayout, tag: String) {
        box.setEndIconOnLongClickListener { icon ->
            val service = tooltipService ?: return@setEndIconOnLongClickListener false
            service.showTooltip(icon, McpPlugin.TOOLTIP_CATEGORY, tag)
            true
        }
    }
}
