package com.itsaky.androidide.plugins.aiagentmcp.ui

import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.Choreographer
import android.widget.EditText
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.plugins.aiagentmcp.R

/**
 * The reveal control for this plugin's masked credential field.
 *
 * The control is the field's own [TextInputLayout] end icon rather than a loose `ImageButton`,
 * which is what gives it a real touch target wherever the field is shown. Icon, content
 * description and toggle behaviour are decided here and nowhere else, so this dialog cannot drift
 * from the other AI plugins' credential fields (ADFA-5491).
 *
 * Deliberately one copy per AI plugin: each addon is an independent Gradle build sharing only the
 * repo's `libs/` jars, so there is nowhere cheaper to put this until the host's plugin-api carries
 * it — a change to the masking logic is three edits, on purpose.
 *
 * @param box the field's own layout, whose end icon becomes the control
 * @param field the masked field
 * @param onLegibleChanged called with true while the secret stands in clear text, so the caller can
 *   flag its window secure — which window that is depends on the screen, not on this control
 */
internal class SecretRevealController(
    private val box: TextInputLayout,
    private val field: EditText,
    private val onLegibleChanged: (legible: Boolean) -> Unit,
) {

    /** Whether the secret currently stands in clear text. */
    var isRevealed: Boolean = false
        private set

    /**
     * Take over [box]'s end icon and mask the field.
     *
     * The drawable is set here rather than in the layout because Material 1.10 never reads
     * `app:endIconDrawable`, so a custom end icon declared in XML draws blank.
     */
    fun attach() {
        // The box exists only to carry the end icon: the dialog's other fields are plain EditTexts,
        // so a floating label and a filled box would make this one field look like another control.
        // The field keeps its own hint, which is what says whether a token is already stored.
        box.isHintEnabled = false
        box.boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_NONE
        box.endIconMode = TextInputLayout.END_ICON_CUSTOM
        // Not announced as a toggle: with END_ICON_CUSTOM nothing ever moves the icon's checked
        // state, so TalkBack would read "not checked" over a legible secret. The content
        // description below carries the state instead.
        box.isEndIconCheckable = false
        box.setEndIconOnClickListener { toggle() }
        apply()
    }

    /**
     * Re-mask the secret and report it illegible.
     *
     * Called when the dialog goes away or the screen leaves the foreground, so neither a screenshot
     * nor the recents thumbnail can catch a revealed credential.
     */
    fun mask() {
        if (!isRevealed) return
        isRevealed = false
        apply()
    }

    private fun toggle() {
        isRevealed = !isRevealed
        apply()
    }

    /** Dress the field and its icon for [isRevealed], then report what is now legible. */
    private fun apply() {
        field.transformationMethod = if (isRevealed) {
            HideReturnsTransformationMethod.getInstance()
        } else {
            PasswordTransformationMethod.getInstance()
        }
        box.setEndIconDrawable(
            if (isRevealed) R.drawable.ic_visibility_off else R.drawable.ic_visibility
        )
        box.setEndIconContentDescription(
            if (isRevealed) R.string.cd_hide_credential else R.string.cd_show_credential
        )
        // Swapping the transformation drops the cursor to the start, so typing would continue in
        // front of the token rather than after it.
        field.setSelection(field.text?.length ?: 0)
        // Masking only invalidates: the secret stays on screen until the next frame is drawn.
        if (isRevealed) {
            onLegibleChanged(true)
        } else {
            afterNextDraw { if (!isRevealed) onLegibleChanged(false) }
        }
    }

    /**
     * Run [action] once the next frame has been drawn, or right away if the field is already gone:
     * a frame callback runs before that frame's traversal, so a message posted from it lands after
     * the field has been redrawn.
     */
    private fun afterNextDraw(action: () -> Unit) {
        if (!field.isAttachedToWindow) {
            action()
            return
        }
        Choreographer.getInstance().postFrameCallback { field.post(action) }
    }
}
