package org.appdevforall.shareyourapps

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The catalog being edited, process-wide, so it survives the tab being rebuilt. Every change
 * is saved to catalog.json shortly afterward. Main thread only, apart from the file writes.
 */
object CatalogState {

    private val main = Handler(Looper.getMainLooper())
    private var writer = Executors.newSingleThreadExecutor()
    private val saveSoon = Runnable { saveNow() }
    private var dataDir: File? = null

    var catalog = empty()
        private set

    /** The extra-JSON field as typed; it only reaches [catalog] while it's a valid object. */
    var extraText = "{}"
        private set
    var extraError: String? = null
        private set

    /** Which product's editor is open (-1 for none): UI state, kept here so it survives rebuilds. */
    var expanded = -1

    var loadError: String? = null
        private set

    fun empty() = Catalog(null, "Share Your Apps", null, null, null, null, JSONObject(), emptyList())

    /**
     * The catalog's text as last loaded or successfully written. A save with the same text is
     * skipped, so leaving the tab without changing anything never touches catalog.json — a file
     * replaced by hand isn't overwritten — and an edit that's undone doesn't count as a change.
     */
    @Volatile private var lastWritten: String? = null

    /** catalog.json as it was at the last load or save, to notice when something else changes it. */
    @Volatile private var loadedStamp: FileStamp? = null
    /** Set while a save is being written, when the file may briefly differ from [loadedStamp]. */
    @Volatile private var writing = false

    fun load(dir: File) {
        if (dataDir == dir) return
        dataDir = dir
        // An unreadable file is set aside as catalog.json.bad first, so the next save can't overwrite it.
        val loaded = loadCatalogFile(File(dir, "catalog.json"))
        loadError = loaded.problem
        catalog = loaded.catalog ?: empty()
        extraText = catalog.extra.toString(2)
        extraError = null
        lastWritten = snapshot().toJson().toString(2)
        loadedStamp = FileStamp.of(File(dir, "catalog.json"))
    }

    fun hasUnsavedEdits(): Boolean = snapshot().toJson().toString(2) != lastWritten

    /**
     * Called when the tab returns to the screen, and before publishing: if something else has
     * changed catalog.json since it was loaded or saved — say, a catalog installed by hand — it's
     * reloaded. With unsaved edits, those are kept instead, and the owner is told.
     */
    fun reloadIfChanged(): Reload {
        val dir = dataDir ?: return Reload.NONE
        if (writing) return Reload.NONE
        val f = File(dir, "catalog.json")
        val decision = reloadDecision(loadedStamp, FileStamp.of(f), hasUnsavedEdits())
        when (decision) {
            Reload.RELOAD -> {
                main.removeCallbacks(saveSoon)
                val loaded = loadCatalogFile(f)
                loadError = loaded.problem
                catalog = loaded.catalog ?: empty()
                extraText = catalog.extra.toString(2)
                extraError = null
                expanded = -1
                lastWritten = snapshot().toJson().toString(2)
                loadedStamp = FileStamp.of(f)
            }
            Reload.CONFLICT -> {
                loadError = "catalog.json was changed outside Share Your Apps while you had unsaved changes here. " +
                    "Your changes are kept, and saving them will replace the other version."
                loadedStamp = FileStamp.of(f)   // say it once
            }
            Reload.NONE -> {}
        }
        return decision
    }

    fun update(change: (Catalog) -> Catalog) {
        catalog = change(catalog)
        main.removeCallbacks(saveSoon)
        main.postDelayed(saveSoon, 400)
    }

    fun updateProduct(index: Int, change: (Product) -> Product) = update { c ->
        c.copy(products = c.products.mapIndexed { i, p -> if (i == index) change(p) else p })
    }

    fun setExtra(text: String) {
        extraText = text
        val result = runCatching { strictJsonObject(text.ifBlank { "{}" }) }
        val parsed = result.getOrNull()
        extraError = if (parsed == null) "Not valid yet (${result.exceptionOrNull()?.message}). The last valid value is kept." else null
        if (parsed != null) update { it.copy(extra = parsed) }
    }

    /** Called when a publish has switched the server to [dir]; that publish is no longer pending. */
    fun setStoreDir(dir: String) {
        catalog = catalog.copy(storeDir = dir, pendingDir = null)
        saveNow()
    }

    /** Records [dir] as handed to the server, before it's launched, or clears it; saved at once. */
    fun setPendingDir(dir: String?) {
        catalog = catalog.copy(pendingDir = dir)
        saveNow()
    }

    /** The catalog as it stands, with products numbered in list order. */
    fun snapshot(): Catalog = catalog.copy(
        products = catalog.products.mapIndexed { i, p -> p.copy(ordinal = i + 1) }
    )

    /** The plugin is being unloaded: saves any edit still waiting, lets that write finish, and stops the writer's thread. */
    fun dispose() {
        saveNow()
        writer.shutdown()
        dataDir = null
    }

    fun saveNow() {
        main.removeCallbacks(saveSoon)
        val dir = dataDir ?: return
        val json = snapshot().toJson().toString(2)
        if (json == lastWritten) return
        if (writer.isShutdown) writer = Executors.newSingleThreadExecutor()
        writer.execute {
            writing = true
            runCatching {
                dir.mkdirs()
                val tmp = File(dir, "catalog.json.tmp")
                tmp.writeText(json)
                // Recorded only once written, so a failed write is tried again at the next save.
                val target = File(dir, "catalog.json")
                // The stamp first: until lastWritten catches up, the edits still count as unsaved.
                if (tmp.renameTo(target)) { loadedStamp = FileStamp.of(target); lastWritten = json }
            }
            writing = false
        }
    }
}
