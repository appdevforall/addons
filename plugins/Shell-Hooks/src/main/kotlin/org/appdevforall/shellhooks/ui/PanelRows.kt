package org.appdevforall.shellhooks.ui

import androidx.recyclerview.widget.DiffUtil
import org.appdevforall.shellhooks.engine.HookRun
import org.appdevforall.shellhooks.engine.PendingTrust

/**
 * One row of the Hooks tab. The tab is a single list, so all of it scrolls together, as it did
 * when it was a ScrollView, and a change binds only the rows it touched.
 */
internal sealed interface PanelRow {
    /** The same thing across two lists: DiffUtil keeps, moves or rebinds a row by it. */
    val key: Any

    data object Off : PanelRow {
        override val key: Any get() = this
    }

    data object PendingTitle : PanelRow {
        override val key: Any get() = this
    }

    data class Pending(
        val entry: PendingTrust,
    ) : PanelRow {
        // HookLog lists a script once per project and path; new content replaces the entry.
        override val key: Any get() = entry.projectDir to entry.relativePath
    }

    data class PendingMore(
        val count: Int,
    ) : PanelRow {
        override val key: Any get() = PendingMore::class
    }

    data object RunsHeader : PanelRow {
        override val key: Any get() = this
    }

    data object Empty : PanelRow {
        override val key: Any get() = this
    }

    data class Run(
        val run: HookRun,
    ) : PanelRow {
        override val key: Any get() = run.id
    }

    companion object {
        /**
         * Scripts awaiting review listed by name; the rest are counted. They sit above the runs,
         * so a project with thousands of scripts would otherwise push the runs out of reach.
         */
        const val MAX_PENDING_ROWS = 50

        /** The tab from top to bottom: [runs] newest first, as [org.appdevforall.shellhooks.engine.HookLog] keeps them. */
        fun of(
            isOn: Boolean,
            runs: List<HookRun>,
            pending: List<PendingTrust>,
        ): List<PanelRow> =
            buildList {
                if (!isOn) add(Off)
                if (pending.isNotEmpty()) {
                    add(PendingTitle)
                    pending.take(MAX_PENDING_ROWS).forEach { add(Pending(it)) }
                    val more = pending.size - MAX_PENDING_ROWS
                    if (more > 0) add(PendingMore(more))
                }
                add(RunsHeader)
                if (runs.isEmpty()) add(Empty) else runs.forEach { add(Run(it)) }
            }
    }
}

/**
 * Rows are the same by [PanelRow.key] and unchanged when equal. A run is never changed once
 * recorded, so a finished hook inserts one row and, with a full log, removes the oldest; no other
 * run row is rebound. Runs keep the log's instances, so comparing them stops at identity before
 * reaching up to 64 KB of output.
 */
internal object PanelRowDiff : DiffUtil.ItemCallback<PanelRow>() {
    override fun areItemsTheSame(
        oldItem: PanelRow,
        newItem: PanelRow,
    ): Boolean = oldItem.key == newItem.key

    override fun areContentsTheSame(
        oldItem: PanelRow,
        newItem: PanelRow,
    ): Boolean = oldItem == newItem
}
