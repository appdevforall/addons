package com.itsaky.androidide.plugins.vectorsearch

/**
 * How many chunks an index build hands to the embedder at a time.
 *
 * The backend splits again to respect its own API's per-call limit; this split is about memory and
 * progress, so that indexing a large project never holds every chunk's text and vector at once and
 * a cancelled build stops at the next batch rather than at the end.
 */
object EmbeddingBatches {

    /** Chunks per call into the embedder. */
    const val MAX_CHUNKS_PER_CALL = 64

    /**
     * Characters per call, so a batch of large chunks is split before a batch of small ones.
     */
    const val MAX_CHARS_PER_CALL = 60_000

    /**
     * Splits [items] into batches, preserving order.
     *
     * An item over the character budget forms a batch of its own rather than being dropped: the
     * embedder is the only thing that can judge it, and dropping it here would leave a hole in the
     * index that nothing reports.
     *
     * @param items the chunks to embed, in index order
     * @param charCount the text length of one item
     * @return the batches to embed, in order; empty when [items] is empty
     */
    fun <T> split(items: List<T>, charCount: (T) -> Int): List<List<T>> {
        val batches = mutableListOf<List<T>>()
        var current = mutableListOf<T>()
        var currentChars = 0

        for (item in items) {
            val size = charCount(item)
            val wouldOverflow = current.isNotEmpty() &&
                (current.size >= MAX_CHUNKS_PER_CALL || currentChars + size > MAX_CHARS_PER_CALL)
            if (wouldOverflow) {
                batches.add(current)
                current = mutableListOf()
                currentChars = 0
            }
            current.add(item)
            currentChars += size
        }

        if (current.isNotEmpty()) batches.add(current)
        return batches
    }
}
