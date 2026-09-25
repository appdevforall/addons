package com.itsaky.androidide.plugins.aicore.fragments

import android.content.Context
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.plugins.aicore.R
import com.itsaky.androidide.plugins.aicore.adapters.ChatSessionAdapter
import com.itsaky.androidide.plugins.aicore.databinding.FragmentChatBinding
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.models.ChatSessionRows
import com.itsaky.androidide.plugins.aicore.models.SessionPaging
import com.itsaky.androidide.plugins.aicore.models.SessionRow
import com.itsaky.androidide.plugins.aicore.models.SessionSelection
import com.itsaky.androidide.plugins.aicore.plugin.AiCorePlugin
import com.itsaky.androidide.plugins.aicore.viewmodel.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * Owns the chat sidebar: the panel that slides over the transcript with a new chat at the top, the
 * project's conversations in the middle and this chat's own actions at the bottom.
 *
 * It replaces the toolbar overflow menu and the chat-history dialog that stood behind it, so there
 * is one place the user goes for anything that is not typing a message.
 *
 * Attached in onViewCreated and detached in onDestroyView, so nothing it animates or collects
 * outlives the views it drives.
 *
 * @param binding the chat screen's views; the panel is part of that layout, laid over the chat
 *   column rather than docked beside it.
 * @param viewModel the plugin-scoped chat state, which is where the conversations live. Read as a
 *   flow, so a reply still streaming retitles its row while the user is looking at it.
 * @param scope the view lifecycle's scope, which the session collector runs in.
 * @param wireTooltip attaches this plugin's long-press help for a tag to a view. Rows are the
 *   exception: long-pressing one picks it, so their help hangs off the section header and the row's
 *   own ⋮ instead.
 * @param showTooltip shows this plugin's help for a tag on a view right away. The sidebar menus use
 *   it to put an entry's help on the button that opened them, since their own rows cannot host it.
 * @param dialogContext an Activity-backed, plugin-themed Context for the rename and delete prompts,
 *   supplied by the fragment. A Dialog takes its window token from its Context, and the
 *   application-scoped plugin Context the panel itself is inflated with has a package name no
 *   PackageManager knows — showing a Dialog on it throws BadTokenException and takes the IDE down.
 *   Null once the fragment is detached, which is a prompt that must simply not open.
 * @param showMessage shows a message on the chat screen, for a sidebar menu entry that is
 *   unavailable and says why when tapped.
 * @param onOpenSettings opens the Agent settings screen, which the fragment owns because the same
 *   shortcut exists on error messages in the transcript.
 * @param onExportChat saves a row's conversation to a .txt file. The fragment owns it, since the
 *   system file picker reports back through an activity-result launcher only a Fragment can hold.
 * @param onImportChat reads a .txt file back as a new conversation, for the same reason. Offered
 *   from the header's + menu beside New chat rather than from a row: it starts a chat, it acts on
 *   none.
 * @param onOpenChanged the panel started opening or closing. Every route out of it ends here —
 *   the scrim, Back, picking a chat, any footer action — so the fragment has one place to keep the
 *   Back handler and the soft keyboard in step with it rather than one per exit.
 */
internal class ChatSidebarController(
    binding: FragmentChatBinding,
    private val viewModel: ChatViewModel,
    private val scope: CoroutineScope,
    private val wireTooltip: (View, String) -> Unit,
    showTooltip: (View, String) -> Boolean,
    showMessage: (String) -> Unit,
    private val dialogContext: () -> Context?,
    private val onOpenSettings: () -> Unit,
    private val onExportChat: (SessionRow) -> Unit,
    private val onImportChat: () -> Unit,
    private val onOpenChanged: (Boolean) -> Unit,
) {

    private var _binding: FragmentChatBinding? = binding

    private val resources = binding.root.resources
    private val animationMs = resources.getInteger(R.integer.chat_sidebar_animation_ms).toLong()
    private val pageSize = resources.getInteger(R.integer.chat_sidebar_page_size)
    private val pageLookahead = resources.getInteger(R.integer.chat_sidebar_page_lookahead)

    private val sessionAdapter = ChatSessionAdapter(
        onSelect = ::onRowTapped,
        onLongPress = ::onRowLongPressed,
        onMenu = ::showRowMenu,
        wireTooltip = wireTooltip,
    )

    /**
     * Whether the list is browsing or picking, and what is ticked. A flow, not a plain field, so
     * the list is rebuilt from it by the same collector that rebuilds it from the sessions — one
     * path to the adapter rather than two that can disagree.
     */
    private val selection = MutableStateFlow(SessionSelection.BROWSING)

    /**
     * How many conversations are built into rows right now. Grows as the list is scrolled and is
     * reset each time the panel opens, so a long browse does not leave hundreds of rows behind for
     * every later open. In the same [combine] as the sessions for the reason above.
     */
    private val visibleCount = MutableStateFlow(pageSize)

    /**
     * The rename prompt or the delete confirmation, whichever is up. Tracked so neither can outlive
     * this screen: they are plain dialogs, and one still showing when the host tears the Agent tab
     * down leaks its window.
     */
    private var childDialog: AlertDialog? = null

    /**
     * The + menu and the rows' options menus, one at a time. Dismissed with the panel's views for
     * the same reason as [childDialog].
     */
    private val popupMenu = SidebarPopupMenu(showTooltip, showMessage)

    /**
     * True from the moment the panel starts opening until it has finished closing. A flow rather
     * than a plain field so [observeSessions] can stand down while the panel is hidden.
     */
    private val openState = MutableStateFlow(false)
    val isOpen: Boolean get() = openState.value

    /** Wires the panel's controls and starts the collector that fills its list. */
    fun attach() {
        val binding = _binding ?: return

        binding.btnChatSidebar.setOnClickListener { open() }
        binding.sidebarScrim.setOnClickListener { close() }
        binding.chatSidebar.btnSidebarClose.setOnClickListener { close() }
        binding.chatSidebar.btnSidebarAdd.setOnClickListener(::showAddMenu)
        binding.chatSidebar.sidebarClearChat.setOnClickListener { confirmClearChat() }
        binding.chatSidebar.sidebarSettings.setOnClickListener {
            onOpenSettings()
            close()
        }
        binding.chatSidebar.btnSidebarSelectionCancel.setOnClickListener {
            selection.value = SessionSelection.BROWSING
        }
        binding.chatSidebar.btnSidebarDeleteSelected.setOnClickListener { confirmDeleteSelected() }

        wireTooltip(binding.btnChatSidebar, AiCorePlugin.TOOLTIP_TAG_CHAT_MENU)
        // The same help as the toolbar button it undoes — both describe the panel as a whole.
        wireTooltip(binding.chatSidebar.btnSidebarClose, AiCorePlugin.TOOLTIP_TAG_CHAT_MENU)
        wireTooltip(binding.chatSidebar.btnSidebarAdd, AiCorePlugin.TOOLTIP_TAG_SIDEBAR_ADD)
        wireTooltip(binding.chatSidebar.sidebarClearChat, AiCorePlugin.TOOLTIP_TAG_SIDEBAR_CLEAR_CHAT)
        wireTooltip(binding.chatSidebar.sidebarSettings, AiCorePlugin.TOOLTIP_TAG_SIDEBAR_SETTINGS)
        // The rows themselves cannot carry this — long-pressing one picks it — so the header the
        // list sits under does, along with each row's ⋮ (wired in the adapter).
        wireTooltip(binding.chatSidebar.sidebarHistoryLabel, AiCorePlugin.TOOLTIP_TAG_CHAT_SESSIONS)
        wireTooltip(binding.chatSidebar.btnSidebarDeleteSelected, AiCorePlugin.TOOLTIP_TAG_CHAT_SESSIONS)
        wireTooltip(binding.chatSidebar.btnSidebarSelectionCancel, AiCorePlugin.TOOLTIP_TAG_CHAT_SESSIONS)

        setupSessionList(binding.chatSidebar.sessionRecyclerView)
        observeSessions()
    }

    /** Stops the animations and releases the views; call from onDestroyView. */
    fun detach() {
        // The panel is gone with its views, so anything the fragment keeps in step with it — the
        // Back handler above all — must be told, or it stays armed against a controller that has
        // nothing left to close.
        if (isOpen) {
            openState.value = false
            onOpenChanged(false)
        }
        _binding?.chatSidebar?.root?.animate()?.cancel()
        _binding?.sidebarScrim?.animate()?.cancel()
        childDialog?.dismiss()
        childDialog = null
        popupMenu.dismiss()
        _binding = null
    }

    /**
     * Re-measures the panel after a rotation. EditorActivity handles orientation itself, so nothing
     * here is recreated and the width worked out for the old orientation would otherwise stand —
     * either overhanging the screen or leaving most of it bare.
     *
     * Call it once the rotated geometry has been published — [panelWidth] measures the container,
     * which still reports the pre-rotation width when the configuration change first arrives.
     */
    fun onConfigurationChanged() {
        val panel = _binding?.chatSidebar?.root ?: return
        if (!isOpen) return
        panel.updateLayoutParams { width = panelWidth() }
        panel.translationX = 0f
    }

    /** Slides the panel in. A no-op when it is already on screen, so a double tap cannot stack. */
    fun open() {
        val binding = _binding ?: return
        if (isOpen) return
        AgentTrace.detail("UI", "chat sidebar opened sessions=${viewModel.sessions.value.size}")

        // A fresh open starts at the newest conversations, so the list is re-paged from the top.
        // Before the flag below, so the collector it restarts never builds last time's rows first.
        visibleCount.value = pageSize
        selection.value = SessionSelection.BROWSING
        binding.chatSidebar.sessionRecyclerView.scrollToPosition(0)
        openState.value = true

        val width = panelWidth()
        binding.chatSidebar.root.updateLayoutParams { this.width = width }
        // Parked off-screen *before* it is shown: the panel is GONE until now, so it is laid out
        // and drawn in the same frame and there is no pass where it flashes in place.
        binding.chatSidebar.root.translationX = parkedX(width)
        binding.chatSidebar.root.isVisible = true
        binding.chatSidebar.root.animate().translationX(0f).setDuration(animationMs).start()

        binding.sidebarScrim.alpha = 0f
        binding.sidebarScrim.isVisible = true
        binding.sidebarScrim.animate().alpha(1f).setDuration(animationMs).start()

        onOpenChanged(true)
    }

    /**
     * Slides the panel out.
     *
     * @return true when it had something to close, which is what makes it an answer for the back
     *   button; false when it was already shut and the press belongs to the host.
     */
    fun close(): Boolean {
        val binding = _binding ?: return false
        if (!isOpen) return false
        openState.value = false

        binding.chatSidebar.root.animate()
            // Re-derived rather than read off the view: a close in the same frame as the open that
            // preceded it would find a width the layout pass has not caught up with yet, and slide
            // the panel only part of the way out.
            .translationX(parkedX(panelWidth()))
            .setDuration(animationMs)
            // Hidden only at the end, so it is not whipped away mid-slide. Read back through
            // _binding because onDestroyView can land while this is in flight.
            .withEndAction { _binding?.chatSidebar?.root?.isVisible = false }
            .start()

        binding.sidebarScrim.animate()
            .alpha(0f)
            .setDuration(animationMs)
            .withEndAction { _binding?.sidebarScrim?.isVisible = false }
            .start()

        onOpenChanged(false)
        return true
    }

    /**
     * How wide the panel may be: its preferred width, or whatever leaves a strip of scrim when the
     * container is narrower than that. The strip matters — it is the tap target that closes the
     * panel, and a panel covering the chat whole reads as a screen the user is stuck on.
     */
    private fun panelWidth(): Int {
        val preferred = resources.getDimensionPixelSize(R.dimen.chat_sidebar_width)
        val minScrim = resources.getDimensionPixelSize(R.dimen.chat_sidebar_min_scrim)
        val available = _binding?.root?.width ?: 0
        // Before the first layout there is nothing to measure against; the preferred width is
        // re-capped on the next open, and open() is the only caller that can run that early.
        if (available <= 0) return preferred
        return minOf(preferred, (available - minScrim).coerceAtLeast(1))
    }

    /**
     * Where the panel sits while closed: just past the edge it is anchored to.
     *
     * Signed by layout direction rather than always positive, because `layout_gravity="end"` puts
     * the panel on the left under RTL, where a positive offset would park it across the chat
     * instead of off the screen.
     *
     * @param width the panel's laid-out width.
     * @return the translationX that holds it off screen.
     */
    private fun parkedX(width: Int): Float {
        val rtl = _binding?.root?.layoutDirection == View.LAYOUT_DIRECTION_RTL
        return if (rtl) -width.toFloat() else width.toFloat()
    }

    /**
     * Sets the history list up as the one scrolling region on this panel, and keeps that scroll to
     * itself.
     *
     * The Agent tab lives in the IDE's bottom sheet, whose behavior drags the whole sheet on any
     * vertical gesture it is allowed to intercept — so without the two guards here, flicking
     * through the conversations pulls the sheet shut instead. [RecyclerView.setNestedScrollingEnabled]
     * keeps the list out of the sheet's nested-scroll chain, and the disallow-intercept on touch
     * down stops the sheet's drag helper claiming the gesture before the list sees it. The request
     * propagates up every parent, which is how it reaches the CoordinatorLayout the behavior is on.
     */
    private fun setupSessionList(list: RecyclerView) {
        val layoutManager = LinearLayoutManager(list.context)
        list.layoutManager = layoutManager
        list.adapter = sessionAdapter
        list.isNestedScrollingEnabled = false

        list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        rv.parent?.requestDisallowInterceptTouchEvent(true)
                    // Released again at the end of the gesture, or the sheet would stay
                    // undraggable for every gesture that follows anywhere on the screen.
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        rv.parent?.requestDisallowInterceptTouchEvent(false)
                }
                return false
            }
        })

        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // Only downwards: scrolling back up never needs more rows, and asking on every
                // frame of an upward fling is work for nothing.
                if (dy <= 0) return
                val total = viewModel.sessions.value.size
                val built = sessionAdapter.itemCount
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (SessionPaging.shouldGrow(lastVisible, built, total, pageLookahead)) {
                    visibleCount.value = SessionPaging.grow(built, total, pageSize)
                }
            }
        })
    }

    /**
     * Republishes the list, while the panel is open, on every change to the sessions, which one is
     * active, what is ticked or how much has been paged in — so a row retitles itself as a reply
     * streams in, the tick follows a switch made from anywhere, and a page appended by scrolling
     * goes through the same path as everything else.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeSessions() {
        val untitled = _binding?.root?.context?.getString(R.string.session_untitled) ?: return
        scope.launch {
            // Nothing is collected while the panel is hidden: the sessions are rewritten once per
            // streamed token, and re-sorting, re-paging and diffing a list nobody can see is the
            // per-token cost SessionPaging was added to avoid.
            openState.flatMapLatest { open ->
                if (!open) emptyFlow() else combine(
                    viewModel.sessions,
                    viewModel.currentSessionId,
                    selection,
                    visibleCount,
                ) { sessions, currentId, picked, limit ->
                    ChatSessionRows.from(sessions, currentId, untitled, picked, limit)
                }
            }.collect { rows ->
                sessionAdapter.submitList(rows)
                // Counted from the rows, not from the ticked ids: a conversation deleted while the
                // panel was open leaves its id behind, and only the rows still exist. A row paged
                // out of sight is not deselected by this — nothing acts on the count but the label
                // and the delete button's enabled state.
                updateChrome(rows.count { it.isSelected }, rows.isEmpty())
            }
        }
    }

    /**
     * Swaps the header between browsing and picking, and shows the empty note when the project has
     * no conversations yet.
     *
     * @param selectedCount how many rows are ticked right now.
     * @param isEmpty whether there is nothing to list at all.
     */
    private fun updateChrome(selectedCount: Int, isEmpty: Boolean) {
        val binding = _binding ?: return
        val picking = selection.value.isSelecting
        binding.chatSidebar.sidebarBrowseBar.isVisible = !picking
        binding.chatSidebar.sidebarSelectionBar.isVisible = picking
        binding.chatSidebar.sidebarHistoryEmpty.isVisible = isEmpty && !picking
        binding.chatSidebar.sidebarSelectionCount.text = binding.root.resources.getQuantityString(
            R.plurals.session_selected_count, selectedCount, selectedCount
        )
        // Nothing ticked is not an error to report; it is a button with nothing to do.
        binding.chatSidebar.btnSidebarDeleteSelected.isEnabled = selectedCount > 0
        binding.chatSidebar.btnSidebarDeleteSelected.alpha = if (selectedCount > 0) 1f else DISABLED_ALPHA
    }

    /**
     * Makes [row]'s conversation the one the chat is in and gets out of the way, or ticks it when
     * the list is picking rather than browsing.
     *
     * @param row the row the user tapped.
     */
    private fun onRowTapped(row: SessionRow) {
        if (selection.value.isSelecting) {
            selection.value = selection.value.toggle(row.id)
            return
        }
        AgentTrace.stage("UI", "chat session selected id=${row.id}")
        viewModel.switchToSession(row.id)
        close()
    }

    /**
     * Long-press is how the list gets into picking mode, with the pressed row already ticked — the
     * panel has no room for a Select button, and a mode nothing is selected in would be a step the
     * user takes for nothing.
     *
     * @param row the row the user held.
     */
    private fun onRowLongPressed(row: SessionRow) {
        selection.value = selection.value.toggle(row.id)
    }

    /**
     * Opens the + menu: New chat, then Import chat.
     *
     * @param anchor the + button, which the menu hangs under.
     */
    private fun showAddMenu(anchor: View) {
        if (_binding == null) return
        popupMenu.show(
            anchor,
            listOf(
                SidebarPopupMenu.Item(
                    icon = R.drawable.ic_add_comment,
                    label = R.string.menu_new_chat,
                    tooltipTag = AiCorePlugin.TOOLTIP_TAG_SIDEBAR_NEW_CHAT,
                ) {
                    viewModel.createNewSession()
                    close()
                },
                SidebarPopupMenu.Item(
                    icon = R.drawable.ic_upload_file,
                    label = R.string.sidebar_import_chat,
                    tooltipTag = AiCorePlugin.TOOLTIP_TAG_CHAT_IMPORT,
                    onClick = onImportChat,
                ),
            ),
        )
    }

    /**
     * Opens a row's options menu: Rename, Export and Delete.
     *
     * @param row the conversation the row shows.
     * @param anchor the row's overflow button, which the menu hangs under; its Context is the
     *   themed one the list was inflated with, so the menu follows the IDE's day/night setting.
     */
    private fun showRowMenu(row: SessionRow, anchor: View) {
        if (_binding == null) return
        popupMenu.show(
            anchor,
            listOf(
                SidebarPopupMenu.Item(
                    icon = R.drawable.ic_edit,
                    label = R.string.session_rename,
                    tooltipTag = AiCorePlugin.TOOLTIP_TAG_CHAT_SESSIONS,
                ) { showRenameDialog(row) },
                SidebarPopupMenu.Item(
                    icon = R.drawable.ic_download,
                    label = R.string.session_export,
                    tooltipTag = AiCorePlugin.TOOLTIP_TAG_CHAT_EXPORT,
                    // An empty chat would export as a header and nothing else.
                    unavailableReason = R.string.session_export_empty.takeIf { row.messageCount == 0 },
                ) { onExportChat(row) },
                SidebarPopupMenu.Item(
                    icon = R.drawable.ic_delete,
                    label = R.string.session_delete,
                    tooltipTag = AiCorePlugin.TOOLTIP_TAG_CHAT_SESSIONS,
                    tint = R.color.plugin_error,
                ) { confirmDelete(row) },
            ),
        )
    }

    /**
     * Asks for a new name, seeded with whatever the row shows now so a small correction does not
     * mean retyping the whole thing. Submitting it empty is how the user gets back to the
     * auto-title, which the helper text under the field says.
     *
     * @param row the conversation to rename.
     */
    private fun showRenameDialog(row: SessionRow) {
        val context = dialogContext() ?: return
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHint(R.string.session_rename_hint)
            // Empty for a chat with no title of its own, so the hint and helper text stand.
            setText(if (row.isUntitled) "" else row.title)
            setSelection(text.length)
        }

        showChildDialog(
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.session_rename_title)
                .setMessage(R.string.session_rename_help)
                .setView(inset(input))
                .setPositiveButton(R.string.session_rename_save) { _, _ ->
                    viewModel.renameSession(row.id, input.text?.toString())
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    /**
     * Confirms before the current conversation is emptied.
     *
     * The one footer action that asks first, and for the same reason deleting does: New chat beside
     * it keeps the thread in the list, whereas this keeps no copy of it anywhere. The panel stays up
     * behind the prompt, so declining puts the user back where they were.
     */
    private fun confirmClearChat() {
        val context = dialogContext() ?: return
        showChildDialog(
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.session_clear_title)
                .setMessage(R.string.session_clear_message)
                .setPositiveButton(R.string.session_clear_confirm) { _, _ ->
                    AgentTrace.stage("UI", "chat cleared from sidebar")
                    viewModel.clearMessages()
                    close()
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    /**
     * Confirms before a conversation goes, since nothing here can bring one back.
     *
     * @param row the conversation to delete.
     */
    private fun confirmDelete(row: SessionRow) {
        val context = dialogContext() ?: return
        showChildDialog(
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.session_delete_title)
                .setMessage(context.getString(R.string.session_delete_message, row.title))
                .setPositiveButton(R.string.session_delete) { _, _ ->
                    AgentTrace.stage("UI", "chat session deleted id=${row.id}")
                    viewModel.deleteSession(row.id)
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    /**
     * Confirms before the ticked conversations go, the way deleting a single row does — there are
     * simply more of them, and still no way back.
     */
    private fun confirmDeleteSelected() {
        val context = dialogContext() ?: return
        val ids = selection.value.ids
        if (ids.isEmpty()) return
        showChildDialog(
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.session_delete_title)
                .setMessage(
                    context.resources.getQuantityString(
                        R.plurals.session_delete_many_message, ids.size, ids.size
                    )
                )
                .setPositiveButton(R.string.session_delete) { _, _ ->
                    AgentTrace.stage("UI", "chat sessions deleted count=${ids.size}")
                    viewModel.deleteSessions(ids)
                    selection.value = SessionSelection.BROWSING
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    /**
     * Shows one of the panel's secondary dialogs, replacing any other it already had up so the two
     * can never stack, and holds it in [childDialog] for the teardown there.
     *
     * @param builder the dialog to show.
     */
    private fun showChildDialog(builder: MaterialAlertDialogBuilder) {
        // The tap that got here can land just after the host has taken the Agent tab down.
        if (_binding == null) return
        childDialog?.dismiss()
        childDialog = builder.show()
    }

    /**
     * Holds a bare input off the dialog's edges, which it would otherwise sit flush against.
     *
     * @param view the input to inset.
     * @return the view wrapped in its padding.
     */
    private fun inset(view: View): View {
        val horizontal = view.resources.getDimensionPixelSize(R.dimen.session_dialog_input_inset)
        return FrameLayout(view.context).apply {
            setPadding(horizontal, 0, horizontal, 0)
            addView(view)
        }
    }

    private companion object {
        /** Greys the delete button out while nothing is ticked; isEnabled alone does not tint. */
        const val DISABLED_ALPHA = 0.4f
    }
}
