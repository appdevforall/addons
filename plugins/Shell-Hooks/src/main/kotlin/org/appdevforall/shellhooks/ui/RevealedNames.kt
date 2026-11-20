package org.appdevforall.shellhooks.ui

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import org.appdevforall.shellhooks.R
import org.appdevforall.shellhooks.engine.ScriptReview

/**
 * A name the project chose (a project folder, a file a run was about, a script that cannot be
 * reviewed) as the UI shows it: revealed by [ScriptReview.reveal], each `<U+XXXX>` highlighted so
 * it cannot be mistaken for the same characters typed in the name. Names are short, so a span per
 * escape is cheap; a name longer than [MAX_HIGHLIGHTED] is shown revealed but not highlighted.
 */
internal object RevealedNames {
    const val MAX_HIGHLIGHTED = 4096

    fun show(
        context: Context,
        name: String,
    ): CharSequence {
        val revealed = ScriptReview.reveal(name)
        if (revealed.escapes.isEmpty() || revealed.text.length > MAX_HIGHLIGHTED) return revealed.text
        val color = context.getColor(R.color.reveal_escape_background)
        return SpannableString(revealed.text).apply {
            for (range in revealed.escapes) {
                setSpan(BackgroundColorSpan(color), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
