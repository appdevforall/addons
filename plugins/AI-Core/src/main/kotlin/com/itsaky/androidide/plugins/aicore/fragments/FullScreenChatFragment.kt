package com.itsaky.androidide.plugins.aicore.fragments

/**
 * The Agent chat over the whole screen, opened from the bottom sheet's chat so the transcript gets
 * the height the editor, its toolbar and the tabs take there. A class of its own because the host
 * opens a plugin screen by class name alone, with no arguments to say which kind it is.
 */
class FullScreenChatFragment : ChatFragment() {
    override val isFullScreen: Boolean = true
}
