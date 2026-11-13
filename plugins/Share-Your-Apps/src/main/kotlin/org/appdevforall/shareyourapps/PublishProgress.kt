package org.appdevforall.shareyourapps

/**
 * How far a publish has got, for the Share Your Apps tab's status line. The publishing thread updates it, and the
 * screen reads it every half second, so its fields are volatile, and the counts change together under a lock.
 * Totals are known before the first file is copied, since Publisher lists every file first; 0 means not yet.
 */
class PublishProgress {
    @Volatile var filesDone = 0; private set
    @Volatile var bytesDone = 0L; private set
    @Volatile var filesTotal = 0
    @Volatile var bytesTotal = 0L
    /** What's happening now, such as "zipping Todo App's source". */
    @Volatile var doing = ""

    /** A file copied, embedded in the page, or added to a source zip. */
    @Synchronized fun handled(bytes: Long) { filesDone++; bytesDone += bytes }

    @Synchronized fun reset() { filesDone = 0; bytesDone = 0; filesTotal = 0; bytesTotal = 0; doing = "" }

    /** "Publishing… zipping Todo App's source: 312 of 1,204 files, 12.3 MB of 48.0 MB" */
    fun line(): String {
        val files = if (filesTotal > 0) "%,d of %,d files".format(filesDone, filesTotal) else "%,d files".format(filesDone)
        val bytes = if (bytesTotal > 0) "${sizeLabel(bytesDone)} of ${sizeLabel(bytesTotal)}" else sizeLabel(bytesDone)
        return "Publishing…" + (if (doing.isNotEmpty()) " $doing" else "") + ": $files, $bytes"
    }
}
