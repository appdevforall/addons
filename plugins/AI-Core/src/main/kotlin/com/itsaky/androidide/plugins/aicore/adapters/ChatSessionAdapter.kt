package com.itsaky.androidide.plugins.aicore.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.view.isVisible
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.itsaky.androidide.plugins.aicore.R
import com.itsaky.androidide.plugins.aicore.models.SessionRow
import com.itsaky.androidide.plugins.aicore.plugin.AiCorePlugin

/**
 * Draws the chat-history list.
 *
 * It is handed [SessionRow]s already shaped for display, so it neither reads a session nor decides
 * what one is called or where it sorts — see ChatSessionRows for that.
 *
 * @param onSelect the row was tapped: make that conversation the active one, or tick it while the
 *   list is picking several.
 * @param onLongPress the row was held: start picking several, with this row ticked.
 * @param onMenu the row's overflow was tapped, anchored on the view passed back so the caller can
 *   hang a PopupMenu on it. Rename and delete live there rather than on the row itself, which is
 *   already spoken for by "switch to this chat".
 * @param wireTooltip attaches this plugin's long-press help for a tag to a view, supplied by the
 *   fragment that owns the tooltip-service lookup. Defaults to a no-op for tests. It is wired to
 *   the row's ⋮ only — the row itself answers a long press with [onLongPress], and a view cannot
 *   do both.
 */
class ChatSessionAdapter(
    private val onSelect: (SessionRow) -> Unit,
    private val onLongPress: (SessionRow) -> Unit = {},
    private val onMenu: (SessionRow, View) -> Unit,
    private val wireTooltip: (View, String) -> Unit = { _, _ -> }
) : ListAdapter<SessionRow, ChatSessionAdapter.SessionViewHolder>(DiffCallback) {

    class SessionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val activeMarker: ImageView = view.findViewById(R.id.session_active_marker)
        val checkbox: MaterialCheckBox = view.findViewById(R.id.session_checkbox)
        val title: TextView = view.findViewById(R.id.session_title)
        val subtitle: TextView = view.findViewById(R.id.session_subtitle)
        val menu: ImageButton = view.findViewById(R.id.session_row_menu)

        /** What this holder currently shows; read by the listeners wired once in onCreateView. */
        var row: SessionRow? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_chat_session, parent, false)
        val holder = SessionViewHolder(view)
        // Wired once per holder rather than on every bind: the listeners read whatever the holder
        // shows when they fire, so a recycled holder never needs them replaced.
        view.setOnClickListener { holder.row?.let(onSelect) }
        view.setOnLongClickListener {
            val row = holder.row ?: return@setOnLongClickListener false
            onLongPress(row)
            true
        }
        holder.menu.setOnClickListener { anchor -> holder.row?.let { onMenu(it, anchor) } }
        wireTooltip(holder.menu, AiCorePlugin.TOOLTIP_TAG_CHAT_SESSIONS)
        return holder
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        val row = getItem(position)
        val resources = holder.itemView.resources
        holder.row = row

        holder.title.text = row.title
        holder.subtitle.text = resources.getString(
            R.string.session_subtitle,
            row.date,
            resources.getQuantityString(
                R.plurals.session_message_count,
                row.messageCount,
                row.messageCount,
            ),
        )
        holder.checkbox.isVisible = row.isSelecting
        holder.checkbox.isChecked = row.isSelected
        // While picking, the row's one meaning is "tick me"; rename and delete would conflict.
        holder.menu.isVisible = !row.isSelecting
        // INVISIBLE, not GONE: see the note in list_item_chat_session.xml. The checkbox has the
        // slot while picking, so the tick gives it up rather than drawing over it.
        holder.activeMarker.visibility =
            if (row.isActive && !row.isSelecting) View.VISIBLE else View.INVISIBLE
    }

    private companion object {
        val DiffCallback = object : DiffUtil.ItemCallback<SessionRow>() {
            override fun areItemsTheSame(oldItem: SessionRow, newItem: SessionRow): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: SessionRow, newItem: SessionRow): Boolean =
                oldItem == newItem
        }
    }
}
