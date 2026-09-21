package com.itsaky.androidide.plugins.aicore.adapters

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.itsaky.androidide.plugins.aicore.R
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.models.ChatMessage
import com.itsaky.androidide.plugins.aicore.models.MessageStatus
import com.itsaky.androidide.plugins.aicore.models.Sender
import com.itsaky.androidide.plugins.aicore.plugin.AiCorePlugin
import io.noties.markwon.Markwon
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "$LOG_PREFIX.ChatAdapter"

/**
 * @param wireTooltip attaches this plugin's long-press help for a tag to a view. Supplied by
 *   ChatFragment, which owns the [com.itsaky.androidide.plugins.services.IdeTooltipService]
 *   lookup, so the adapter stays free of service plumbing. Defaults to a no-op for tests.
 */
class ChatAdapter(
    private val markwon: Markwon,
    private val wireTooltip: (View, String) -> Unit = { _, _ -> },
    private val onMessageAction: (action: String, message: ChatMessage) -> Unit
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DiffCallback) {

    private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val decimalSecondsFormatter = DecimalFormat("0.0")
    private val expandedMessageIds = mutableSetOf<String>()
    private val animatingHolders = mutableSetOf<DefaultMessageViewHolder>()

    companion object {
        private const val VIEW_TYPE_DEFAULT = 0
        private const val VIEW_TYPE_SYSTEM = 1

        const val ACTION_EDIT = "edit"
        const val ACTION_RETRY = "retry"
        const val ACTION_OPEN_SETTINGS = "open_settings"
    }

    sealed class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view)

    class DefaultMessageViewHolder(view: View) : MessageViewHolder(view) {
        val messageSender: TextView = view.findViewById(R.id.message_sender)
        val loadingIndicator: ProgressBar = view.findViewById(R.id.loading_indicator)
        val messageContent: TextView = view.findViewById(R.id.message_content)
        val messageMetadataContainer: LinearLayout = view.findViewById(R.id.message_metadata_container)
        val messageTimestamp: TextView = view.findViewById(R.id.message_timestamp)
        val generatingDots: TextView = view.findViewById(R.id.generating_dots)
        val messageDuration: TextView = view.findViewById(R.id.message_duration)
        val btnRetry: Button = view.findViewById(R.id.btn_retry)

        /**
         * Queued next step of the "..." animation, or null when it isn't running. Retained so
         * [ChatAdapter.hideGeneratingDots] can cancel it: a Runnable left on the main looper
         * would keep this holder, its views and their Context reachable after the row is gone.
         */
        var generatingDotsStep: Runnable? = null
    }

    class SystemMessageViewHolder(view: View) : MessageViewHolder(view) {
        val messageHeader: LinearLayout = view.findViewById(R.id.message_header)
        val messageHeaderTitle: TextView = view.findViewById(R.id.message_header_title)
        val expandIcon: ImageView = view.findViewById(R.id.expand_icon)
        val messageContent: TextView = view.findViewById(R.id.message_content)
    }

    override fun getItemCount(): Int {
        val count = super.getItemCount()
        android.util.Log.d(TAG, "getItemCount() = $count")
        return count
    }

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)
        return if (message.sender == Sender.SYSTEM && message.status == MessageStatus.ERROR) {
            VIEW_TYPE_DEFAULT
        } else if (message.sender == Sender.SYSTEM) {
            VIEW_TYPE_SYSTEM
        } else {
            VIEW_TYPE_DEFAULT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        android.util.Log.d(TAG, "onCreateViewHolder called, viewType=$viewType")
        // Inflate from the RecyclerView's Context so item views follow the IDE day/night theme.
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SYSTEM -> {
                val view = inflater.inflate(R.layout.list_item_chat_system_message, parent, false)
                SystemMessageViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.list_item_chat_message, parent, false)
                DefaultMessageViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is DefaultMessageViewHolder -> bindDefaultMessage(holder, message)
            is SystemMessageViewHolder -> bindSystemMessage(holder, message)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            // No payload, do full bind
            onBindViewHolder(holder, position)
        } else {
            // Handle payload update
            val payload = payloads[0]
            if (payload is TextUpdatePayload && holder is DefaultMessageViewHolder) {
                val message = getItem(position)
                // Only update the text content and status, don't rebind everything
                when (payload.status) {
                    MessageStatus.LOADING -> {
                        holder.loadingIndicator.visibility = View.VISIBLE
                        holder.messageContent.visibility = View.GONE
                        hideGeneratingDots(holder)
                    }
                    MessageStatus.SENT -> {
                        holder.loadingIndicator.visibility = View.GONE
                        holder.messageContent.visibility = View.VISIBLE
                        markwon.setMarkdown(holder.messageContent, payload.text)

                        // Show dots animation for AGENT messages being generated
                        if (message.sender == Sender.AGENT && message.durationMs == null) {
                            animateGeneratingDots(holder)
                        } else {
                            hideGeneratingDots(holder)
                        }
                    }
                    MessageStatus.COMPLETED -> {
                        holder.loadingIndicator.visibility = View.GONE
                        holder.messageContent.visibility = View.VISIBLE
                        hideGeneratingDots(holder)
                        markwon.setMarkdown(holder.messageContent, payload.text)
                    }
                    MessageStatus.ERROR -> {
                        holder.loadingIndicator.visibility = View.GONE
                        holder.messageContent.visibility = View.VISIBLE
                        hideGeneratingDots(holder)
                        holder.messageContent.text = payload.text
                    }
                }
            } else if (payload is TextUpdatePayload && holder is SystemMessageViewHolder) {
                markwon.setMarkdown(holder.messageContent, payload.text)
                updateSystemMessageExpansion(holder, getItem(position))
            } else {
                // Unknown payload, do full bind
                onBindViewHolder(holder, position)
            }
        }
    }

    private fun bindDefaultMessage(holder: DefaultMessageViewHolder, message: ChatMessage) {
        holder.messageSender.text = message.sender.name.lowercase(Locale.getDefault())
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }

        holder.itemView.setOnLongClickListener { view ->
            if (message.status == MessageStatus.SENT) {
                showContextMenu(view, message)
            }
            true
        }

        when (message.status) {
            MessageStatus.LOADING -> {
                holder.loadingIndicator.visibility = View.VISIBLE
                holder.messageContent.visibility = View.GONE
                holder.btnRetry.visibility = View.GONE
                holder.messageMetadataContainer.visibility = View.GONE
                // A row that goes back to LOADING after SENT still had a live dots loop.
                hideGeneratingDots(holder)
            }
            MessageStatus.SENT -> {
                holder.loadingIndicator.visibility = View.GONE
                holder.messageContent.visibility = View.VISIBLE
                holder.btnRetry.visibility = View.GONE
                markwon.setMarkdown(holder.messageContent, message.text)
                updateMessageMetadata(holder, message)

                // Show dots animation for AGENT messages being generated
                if (message.sender == Sender.AGENT && message.durationMs == null) {
                    animateGeneratingDots(holder)
                } else {
                    hideGeneratingDots(holder)
                }
            }
            MessageStatus.COMPLETED -> {
                holder.loadingIndicator.visibility = View.GONE
                holder.messageContent.visibility = View.VISIBLE
                holder.btnRetry.visibility = View.GONE
                hideGeneratingDots(holder)
                markwon.setMarkdown(holder.messageContent, message.text)
                updateMessageMetadata(holder, message)
            }
            MessageStatus.ERROR -> {
                holder.loadingIndicator.visibility = View.GONE
                holder.messageContent.visibility = View.VISIBLE
                holder.btnRetry.visibility = View.VISIBLE
                hideGeneratingDots(holder)
                holder.messageContent.text = message.text
                if (message.sender == Sender.SYSTEM) {
                    holder.btnRetry.text = holder.btnRetry.context.getString(R.string.action_open_settings)
                    holder.btnRetry.setOnClickListener {
                        onMessageAction(ACTION_OPEN_SETTINGS, message)
                    }
                    // Re-wired per bind: the same recycled button plays both roles, so the
                    // tag has to follow the role it currently has.
                    wireTooltip(holder.btnRetry, AiCorePlugin.TOOLTIP_TAG_MESSAGE_OPEN_SETTINGS)
                } else {
                    holder.btnRetry.text = holder.btnRetry.context.getString(R.string.action_retry)
                    holder.btnRetry.setOnClickListener {
                        onMessageAction(ACTION_RETRY, message)
                    }
                    wireTooltip(holder.btnRetry, AiCorePlugin.TOOLTIP_TAG_MESSAGE_RETRY)
                }
                updateMessageMetadata(holder, message)
            }
        }
    }

    private fun bindSystemMessage(holder: SystemMessageViewHolder, message: ChatMessage) {
        markwon.setMarkdown(holder.messageContent, message.text)
        updateSystemMessageExpansion(holder, message)

        holder.messageHeader.setOnClickListener {
            if (!expandedMessageIds.remove(message.id)) {
                expandedMessageIds.add(message.id)
            }
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                notifyItemChanged(pos)
            }
        }
        wireTooltip(holder.messageHeader, AiCorePlugin.TOOLTIP_TAG_SYSTEM_LOG)
    }

    private fun updateSystemMessageExpansion(holder: SystemMessageViewHolder, message: ChatMessage) {
        val isExpanded = expandedMessageIds.contains(message.id)
        if (isExpanded) {
            holder.messageHeaderTitle.text = holder.messageHeaderTitle.context.getString(R.string.system_log)
            holder.messageContent.visibility = View.VISIBLE
            holder.expandIcon.rotation = 180f
        } else {
            holder.messageHeaderTitle.text = createPreview(message.text)
            holder.messageContent.visibility = View.GONE
            holder.expandIcon.rotation = 0f
        }
    }

    /**
     * Starts the "..." animation, or leaves an already-running one alone: restarting on every
     * streaming rebind would reset the loop to "." and it would never visibly advance. The step is
     * posted on the dots view, not a bare main-looper Handler, so [hideGeneratingDots] can cancel it.
     *
     * @param holder the row whose dots should animate
     */
    private fun animateGeneratingDots(holder: DefaultMessageViewHolder) {
        if (holder.generatingDotsStep != null) return
        holder.generatingDots.visibility = View.VISIBLE
        val dotStates = arrayOf(".", "..", "...")
        var currentIndex = 0

        val step = object : Runnable {
            override fun run() {
                if (holder.generatingDots.visibility != View.VISIBLE) {
                    holder.generatingDotsStep = null
                    return
                }
                holder.generatingDots.text = dotStates[currentIndex]
                currentIndex = (currentIndex + 1) % dotStates.size
                holder.generatingDots.postDelayed(this, 500)
            }
        }
        holder.generatingDotsStep = step
        animatingHolders.add(holder)
        holder.generatingDots.post(step)
    }

    /**
     * Hides the dots and cancels the animation. Visibility alone is not enough: the running step
     * only notices it on its next tick, and never at all once the view is detached.
     *
     * @param holder the row whose dots should stop
     */
    private fun hideGeneratingDots(holder: DefaultMessageViewHolder) {
        holder.generatingDotsStep?.let { holder.generatingDots.removeCallbacks(it) }
        holder.generatingDotsStep = null
        animatingHolders.remove(holder)
        holder.generatingDots.visibility = View.GONE
    }

    /**
     * Stop every live "…" animation. Call from the host fragment's `onDestroyView`:
     * a message still streaming when the tab closes never reaches a terminal status
     * and its holder is never recycled, so nothing else cancels its Runnable.
     */
    fun stopAllAnimations() {
        animatingHolders.toList().forEach { hideGeneratingDots(it) }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is DefaultMessageViewHolder) hideGeneratingDots(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        stopAllAnimations()
    }

    private fun createPreview(rawText: String): String {
        val cleanedText = rawText
            .replace(Regex("`{1,3}|\\*{1,2}|_"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return "Log: $cleanedText"
    }

    private fun updateMessageMetadata(holder: DefaultMessageViewHolder, message: ChatMessage) {
        val timestampText = formatTimestamp(message.timestamp)
        val durationText = formatDuration(message.durationMs)

        val hasTimestamp = timestampText != null
        val hasDuration = durationText != null

        if (!hasTimestamp && !hasDuration) {
            holder.messageMetadataContainer.visibility = View.GONE
            return
        }

        holder.messageMetadataContainer.visibility = View.VISIBLE

        if (hasTimestamp) {
            holder.messageTimestamp.text = timestampText
            holder.messageTimestamp.visibility = View.VISIBLE
        } else {
            holder.messageTimestamp.visibility = View.GONE
        }

        if (hasDuration) {
            holder.messageDuration.text = durationText
            holder.messageDuration.visibility = View.VISIBLE
        } else {
            holder.messageDuration.visibility = View.GONE
        }
    }

    private fun formatTimestamp(timestamp: Long): String? {
        if (timestamp <= 0L) return null
        return synchronized(timeFormatter) {
            timeFormatter.format(Date(timestamp))
        }
    }

    private fun formatDuration(durationMs: Long?): String? {
        if (durationMs == null || durationMs <= 0) return null
        val seconds = durationMs / 1000.0
        return if (seconds < 60) {
            "took ${decimalSecondsFormatter.format(seconds)}s"
        } else {
            val minutes = seconds / 60.0
            "took ${decimalSecondsFormatter.format(minutes)}m"
        }
    }

    private fun showContextMenu(view: View, message: ChatMessage) {
        val context = view.context
        val popup = PopupMenu(context, view)

        popup.menu.add(0, 1, 0, "Copy Text")
        if (message.sender == Sender.USER) {
            popup.menu.add(0, 2, 0, "Edit Message")
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("chat_message", message.text)
                    clipboard.setPrimaryClip(clip)
                    Snackbar.make(view, view.context.getString(R.string.msg_copied), Snackbar.LENGTH_SHORT).show()
                    true
                }
                2 -> {
                    onMessageAction(ACTION_EDIT, message)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    override fun onCurrentListChanged(previousList: MutableList<ChatMessage>, currentList: MutableList<ChatMessage>) {
        super.onCurrentListChanged(previousList, currentList)
        expandedMessageIds.clear()
    }

    object DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }

        override fun getChangePayload(oldItem: ChatMessage, newItem: ChatMessage): Any? {
            // If only the text or status changed, return a payload to avoid full rebind
            if (oldItem.id == newItem.id &&
                (oldItem.text != newItem.text || oldItem.status != newItem.status)) {
                return TextUpdatePayload(newItem.text, newItem.status)
            }
            return null
        }
    }

    // Payload for partial updates
    data class TextUpdatePayload(val text: String, val status: MessageStatus)
}
