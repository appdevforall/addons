package com.itsaky.androidide.plugins.aicore.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
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
 * @param isUserMessageExpanded whether a user bubble is unfolded; the state lives in ChatViewModel,
 *   so a fold outlives this adapter. Defaults to always folded for tests.
 * @param toggleUserMessageExpanded unfolds or folds a user bubble, returning its new state.
 * @param onMessageAction runs one of the `ACTION_*` constants for a message, in ChatFragment.
 */
class ChatAdapter(
    private val markwon: Markwon,
    private val wireTooltip: (View, String) -> Unit = { _, _ -> },
    private val isUserMessageExpanded: (messageId: String) -> Boolean = { false },
    private val toggleUserMessageExpanded: (messageId: String) -> Boolean = { false },
    private val onMessageAction: (action: String, message: ChatMessage) -> Unit
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DiffCallback) {

    private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val decimalSecondsFormatter = DecimalFormat("0.0")
    private val expandedMessageIds = mutableSetOf<String>()
    private val animatingHolders = mutableSetOf<DefaultMessageViewHolder>()

    companion object {
        private const val VIEW_TYPE_DEFAULT = 0
        private const val VIEW_TYPE_SYSTEM = 1
        private const val VIEW_TYPE_USER = 2

        const val ACTION_RETRY = "retry"
        const val ACTION_COPY = "copy"
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
        /** Fold toggle; only the user bubble layout has one. */
        val btnToggleExpand: ImageButton? = view.findViewById(R.id.btn_toggle_expand)
        val messageActions: LinearLayout = view.findViewById(R.id.message_actions)
        val btnCopyMessage: ImageButton = view.findViewById(R.id.btn_copy_message)

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
        } else if (message.sender == Sender.USER) {
            VIEW_TYPE_USER
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
            // Own view type, so a recycled row never carries the bubble over to an agent message.
            VIEW_TYPE_USER -> {
                val view = inflater.inflate(R.layout.list_item_chat_user_message, parent, false)
                DefaultMessageViewHolder(view).also(::wireMessageActions).apply {
                    // The bubble is the sender cue, so the label would only repeat it.
                    messageSender.visibility = View.GONE
                    // A match_parent child can't widen a wrap_content bubble; it stays one word wide.
                    messageContent.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    // Line count is only known once the text is laid out at its final width.
                    messageContent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        updateExpandToggleVisibility(this)
                    }
                }
            }
            else -> {
                val view = inflater.inflate(R.layout.list_item_chat_message, parent, false)
                DefaultMessageViewHolder(view).also(::wireMessageActions)
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
                updateMessageActions(holder, message)
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

        updateMessageActions(holder, message)
        holder.btnToggleExpand?.let { bindExpandToggle(holder, it, message) }

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

    /** Wired once per holder: a streamed reply grows via payloads, so the tap reads the current item. */
    private fun wireMessageActions(holder: DefaultMessageViewHolder) {
        holder.btnCopyMessage.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onMessageAction(ACTION_COPY, getItem(pos))
        }
        wireTooltip(holder.btnCopyMessage, AiCorePlugin.TOOLTIP_TAG_MESSAGE_COPY)
    }

    /**
     * Shows the actions under user and agent messages once their text is final: not while loading,
     * nor while a reply is still streaming. System error rows share this layout but get none.
     */
    private fun updateMessageActions(holder: DefaultMessageViewHolder, message: ChatMessage) {
        val streaming = message.sender == Sender.AGENT &&
            message.status == MessageStatus.SENT && message.durationMs == null
        val show = message.sender != Sender.SYSTEM && message.status != MessageStatus.LOADING && !streaming
        holder.messageActions.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun bindExpandToggle(holder: DefaultMessageViewHolder, toggle: ImageButton, message: ChatMessage) {
        applyUserMessageExpansion(holder, toggle, isUserMessageExpanded(message.id))
        toggle.setOnClickListener {
            val expanded = toggleUserMessageExpanded(message.id)
            val rowTop = holder.itemView.top
            applyUserMessageExpansion(holder, toggle, expanded)
            keepRowTopInPlace(holder.itemView, rowTop)
        }
        wireTooltip(toggle, AiCorePlugin.TOOLTIP_TAG_USER_MESSAGE_EXPAND)
    }

    /**
     * Folds the bubble to `R.integer.user_message_collapsed_lines` or unfolds it, and turns the arrow and its
     * spoken label to match: down/"show the whole message" while folded, up/"show less" once open.
     */
    private fun applyUserMessageExpansion(holder: DefaultMessageViewHolder, toggle: ImageButton, expanded: Boolean) {
        holder.messageContent.maxLines = if (expanded) Int.MAX_VALUE else collapsedLines(holder)
        toggle.setImageResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
        toggle.contentDescription = toggle.context.getString(
            if (expanded) R.string.desc_collapse_user_message else R.string.desc_expand_user_message
        )
    }

    private fun collapsedLines(holder: DefaultMessageViewHolder): Int =
        holder.messageContent.resources.getInteger(R.integer.user_message_collapsed_lines)

    /**
     * Scrolls [row] back to [rowTop] after its next layout, before that frame draws. The list stacks
     * from the end, so a row that changes height moves its top: unfolding would push the start of
     * the message off screen.
     */
    private fun keepRowTopInPlace(row: View, rowTop: Int) {
        val list = row.parent as? RecyclerView ?: return
        list.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                list.viewTreeObserver.removeOnPreDrawListener(this)
                if (row.parent !== list) return true
                val drift = row.top - rowTop
                if (drift == 0) return true
                list.scrollBy(0, drift)
                // Skip this frame: it was laid out before the correction.
                return false
            }
        })
    }

    /**
     * Shows the toggle only when the text runs past the fold. lineCount counts every line even while
     * maxLines hides some (no ellipsize is set), so this holds folded or not. Posted, since it runs
     * mid-layout and a visibility change there would be deferred with a warning anyway.
     */
    private fun updateExpandToggleVisibility(holder: DefaultMessageViewHolder) {
        val toggle = holder.btnToggleExpand ?: return
        toggle.post {
            val overflows = holder.messageContent.lineCount > collapsedLines(holder)
            val visibility = if (overflows) View.VISIBLE else View.GONE
            if (toggle.visibility != visibility) toggle.visibility = visibility
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
        if (holder is DefaultMessageViewHolder) {
            hideGeneratingDots(holder)
            // Here, not in bind: a rebind of the same row would flash its toggle off for a frame.
            holder.btnToggleExpand?.visibility = View.GONE
        }
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
