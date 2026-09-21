package com.itsaky.androidide.plugins.aicore.fragments

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.itsaky.androidide.plugins.aicore.plugin.AiCorePlugin
import com.itsaky.androidide.plugins.aicore.R
import com.itsaky.androidide.plugins.aicore.databinding.FragmentChatBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the composer: whether it is on screen, the idle countdown that folds it away on a short
 * screen, and the send/stop button it carries. Attached in onViewCreated and detached in
 * onDestroyView, so nothing it schedules outlives the views it drives.
 */
internal class ComposerAutoHideController(
    binding: FragmentChatBinding,
    private val scope: CoroutineScope,
    private val wireTooltip: (View, String) -> Unit,
) {

    private companion object {
        const val KEY_COMPOSER_VISIBLE = "composer_visible"
        const val KEY_COMPOSER_AUTO_HIDE = "composer_auto_hide"
    }

    private var _binding: FragmentChatBinding? = binding

    private val autoHideMs =
        binding.root.resources.getInteger(R.integer.chat_composer_auto_hide_ms).toLong()
    private val compactHeightDp =
        binding.root.resources.getInteger(R.integer.chat_composer_compact_height_dp)

    /** True only while the screen is too short to keep the composer pinned. */
    private var autoHide = false
    private var agentRunning = false
    private var countdown: Job? = null

    /**
     * Wires the composer's controls and puts it in the state [savedState] left behind, or open on
     * a first run. [config] comes from the fragment so this never has to reach for an Activity.
     */
    fun attach(savedState: Bundle?, config: Configuration) {
        val binding = _binding ?: return
        binding.btnShowComposer.setOnClickListener { setVisible(true) }
        binding.btnHideComposer.setOnClickListener { setVisible(false) }
        wireTooltip(binding.btnShowComposer, AiCorePlugin.TOOLTIP_TAG_CHAT_INPUT)
        wireTooltip(binding.btnHideComposer, AiCorePlugin.TOOLTIP_TAG_CHAT_INPUT)

        // Focus means the user is mid-thought, so the countdown waits until they step away.
        binding.promptInputEdittext.setOnFocusChangeListener { _, _ -> onUserInteraction() }
        binding.promptInputEdittext.doAfterTextChanged { onUserInteraction() }

        autoHide = savedState?.getBoolean(KEY_COMPOSER_AUTO_HIDE) ?: false
        applyCompactRule(config, visible = savedState?.getBoolean(KEY_COMPOSER_VISIBLE) ?: true)
    }

    /** Stops the countdown and releases the views; call from onDestroyView. */
    fun detach() {
        countdown?.cancel()
        countdown = null
        _binding = null
    }

    /**
     * Re-evaluates the compact-screen rule after a rotation, always landing on an open composer:
     * rotating into portrait must never leave it folded away with the tab that would restore it
     * now gone.
     */
    fun onConfigurationChanged(config: Configuration) =
        applyCompactRule(config, visible = true)

    /**
     * Switches the trailing button between Send and Stop. Stop lives on that button, so starting a
     * run opens the composer and holds off the idle countdown until the run ends. Hide still folds
     * it away on request — that is the user's call, and the reopen tab brings Stop straight back.
     */
    fun onAgentRunningChanged(running: Boolean) {
        agentRunning = running
        val binding = _binding ?: return
        val context = binding.root.context
        if (running) {
            binding.sendButton.setImageResource(R.drawable.ic_stop)
            binding.sendButton.contentDescription = context.getString(R.string.desc_stop_agent)
            // setVisible re-schedules, and canAutoHide already refuses while the agent runs.
            setVisible(true)
        } else {
            binding.sendButton.setImageResource(R.drawable.ic_send)
            binding.sendButton.contentDescription = context.getString(R.string.desc_send_message)
            schedule()
        }
        updateSendAppearance()
    }

    /** The composer is in use — text edited, focus moved — so the countdown starts over. */
    fun onUserInteraction() {
        updateSendAppearance()
        schedule()
    }

    /** Holds the countdown while a modal is up: nothing behind it counts as the user going idle. */
    fun pauseUntilClosed(lifecycle: Lifecycle) {
        countdown?.cancel()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = schedule()
        })
    }

    /** Dismisses the soft keyboard raised by the input field. */
    fun hideKeyboard() {
        val binding = _binding ?: return
        val imm = binding.root.context
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.promptInputEdittext.windowToken, 0)
    }

    /**
     * Saves the composer across process death. The auto-hide flag is written too, but
     * [applyCompactRule] re-derives it on restore: the process can come back in an orientation
     * other than the one it died in, and the saved flag would describe a screen that is gone.
     */
    fun saveState(outState: Bundle) {
        outState.putBoolean(KEY_COMPOSER_AUTO_HIDE, autoHide)
        outState.putBoolean(KEY_COMPOSER_VISIBLE, _binding?.inputBarCard?.isVisible ?: true)
    }

    /**
     * Turns auto-hide on for short screens only, then settles the composer on [visible]. A folded
     * composer is asked for, not obeyed: only a compact screen carries the tab that reopens it, so
     * a taller one always lands open however it was left.
     */
    private fun applyCompactRule(config: Configuration, visible: Boolean) {
        autoHide = config.screenHeightDp < compactHeightDp
        setVisible(!autoHide || visible)
    }

    /**
     * Swaps which of the two tabs is on screen. They are separate views because each belongs on a
     * different side of the group's top border: the collapsed one stands above it, the expanded
     * one hangs below it, out of the chat text.
     */
    private fun setVisible(visible: Boolean) {
        val binding = _binding ?: return
        binding.inputBarCard.isVisible = visible
        binding.btnShowComposer.isVisible = autoHide && !visible
        binding.btnHideComposer.isVisible = autoHide && visible
        if (visible) {
            schedule()
        } else {
            countdown?.cancel()
            binding.promptInputEdittext.clearFocus()
            hideKeyboard()
        }
    }

    /** Restarts the idle countdown. Safe to call from any interaction; it no-ops when it must. */
    private fun schedule() {
        countdown?.cancel()
        val binding = _binding ?: return
        if (!autoHide || !binding.inputBarCard.isVisible || !canAutoHide()) return
        countdown = scope.launch {
            delay(autoHideMs)
            setVisible(false)
        }
    }

    /**
     * Guards against folding the composer away at a moment the user would lose something: a draft,
     * the caret, the Stop button, or a screen reader's only route to the controls.
     */
    private fun canAutoHide(): Boolean {
        val binding = _binding ?: return false
        if (binding.promptInputEdittext.hasFocus()) return false
        if (!binding.promptInputEdittext.text.isNullOrBlank()) return false
        if (isTouchExplorationEnabled()) return false
        return !agentRunning
    }

    /** Greys the send icon out on empty input; Stop stays lit because it is always actionable. */
    private fun updateSendAppearance() {
        val binding = _binding ?: return
        binding.sendButton.isActivated =
            agentRunning || !binding.promptInputEdittext.text.isNullOrBlank()
    }

    private fun isTouchExplorationEnabled(): Boolean {
        val context = _binding?.root?.context ?: return false
        val manager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        return manager?.isTouchExplorationEnabled == true
    }
}
