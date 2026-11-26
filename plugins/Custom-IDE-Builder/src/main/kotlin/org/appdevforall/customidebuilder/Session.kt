package org.appdevforall.customidebuilder

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.appdevforall.customidebuilder.core.AddonDownloader
import org.appdevforall.customidebuilder.core.AddonKind
import org.appdevforall.customidebuilder.core.AddonLimits
import org.appdevforall.customidebuilder.core.AddonSelection
import org.appdevforall.customidebuilder.core.BuildException
import org.appdevforall.customidebuilder.core.BuildInput
import org.appdevforall.customidebuilder.core.Catalog
import org.appdevforall.customidebuilder.core.CatalogAddon
import org.appdevforall.customidebuilder.core.CatalogException
import org.appdevforall.customidebuilder.core.HttpFetcher
import org.appdevforall.customidebuilder.core.KeyLoad
import org.appdevforall.customidebuilder.core.KeystoreOpen
import org.appdevforall.customidebuilder.core.Keystores
import org.appdevforall.customidebuilder.core.Naming
import org.appdevforall.customidebuilder.core.PickedAddon
import org.appdevforall.customidebuilder.core.Pipeline
import org.appdevforall.customidebuilder.core.ProgressGate
import org.appdevforall.customidebuilder.core.SelectionResult
import org.appdevforall.customidebuilder.core.Staging
import org.appdevforall.customidebuilder.core.WorkDirs
import java.io.File
import java.security.cert.X509Certificate

/** One add-on on the list. [source] is for the screen: where it came from. */
class Chosen(val picked: PickedAddon, val source: String)

/**
 * A question the running build is waiting on: the output name is taken in the folder. [message] says
 * why, [suggestion] is a free name to prefill. The build resumes when [Session.answerName] gives a
 * name or [Session.declineName] cancels the save.
 */
class NameQuestion internal constructor(val message: String, val suggestion: String) {
    internal val answer = CompletableDeferred<String?>()
}

/**
 * What the screen shows, kept outside the Fragment because a build takes minutes and the Fragment
 * is rebuilt on rotation. Nothing secret is held here: passwords stay in the screen's text fields
 * and in the arrays of one running task, which are zeroed when it ends, and also when the task
 * never starts (another is running, it is cancelled first, or the plugin is disposed).
 *
 * All fields are read and written on the main thread.
 */
object Session {
    val items = ArrayList<Chosen>()
    var keystoreUri: Uri? = null
    var keystoreName: String? = null
    var aliases: List<String> = emptyList()
    var fingerprints: Map<String, String> = emptyMap()
    var selectedAlias: String? = null

    /** The catalog entries just fetched, for the screen to show once and clear. */
    var offer: Catalog.Offer? = null

    /** Shown under the buttons: the latest outcome or problem. */
    var message: String = ""
    var busy: Boolean = false
    var stage: String = ""
    var progress: Int = 0

    /**
     * Set while a build waits for the user to pick another output name. It lives here, not in the
     * Fragment, so a rotation or leaving the screen does not lose it: the Fragment shows the dialog
     * again from [changed]. [end] and a cancelled build clear it.
     */
    var nameQuestion: NameQuestion? = null

    /**
     * Set when a build ends, however it ends, and by [end]; the screen clears its password fields and
     * calls [passwordsCleared]. It stays set while no screen has a view, so the next one clears its
     * fields before the user sees them.
     */
    var passwordsToClear: Boolean = false
        private set

    /** Moves on whenever [items] changes, so the screen rebuilds the rows only then. */
    var itemsVersion: Int = 0
        private set

    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var counter = 0
    private val listeners = ArrayList<() -> Unit>()
    private val progressListeners = ArrayList<() -> Unit>()
    private val progressGate = ProgressGate()

    /**
     * Bumped by [end]. A task remembers the value it started under, and what it reports or posts
     * to the main thread afterwards is dropped if the value has moved on, so work that outlives
     * the plugin cannot write into state [end] cleared or tell a Fragment that is gone.
     */
    private var generation = 0

    /** [l] runs on a state change: the list, busy, the keystore, a result, the name question. */
    fun listen(l: () -> Unit) { listeners.add(l) }

    fun unlisten(l: () -> Unit) { listeners.remove(l) }

    /** [l] runs when only [stage] or [progress] moved, which happens many times a second during a task; it must be cheap. */
    fun listenProgress(l: () -> Unit) { progressListeners.add(l) }

    fun unlistenProgress(l: () -> Unit) { progressListeners.remove(l) }

    fun passwordsCleared() { passwordsToClear = false }

    private fun changed() = listeners.toList().forEach { it() }

    private fun progressChanged() = progressListeners.toList().forEach { it() }

    /** Sets the progress from the main thread and tells the progress listeners. */
    private fun showProgress(text: String, permille: Int) {
        stage = text
        progress = permille
        progressGate.reset(text, permille)
        progressChanged()
    }

    /**
     * Progress from a worker, from any thread. At most one update is waiting for the main thread at a
     * time, and it shows the latest values when it runs (see [ProgressGate]). An update from a task
     * started under an older [gen] is dropped, as in [report].
     */
    private fun progressFrom(gen: Int, text: String, permille: Int) {
        if (!progressGate.offer(text, permille)) return
        main.post {
            val latest = progressGate.take()
            if (gen == generation) {
                stage = latest.stage
                progress = latest.permille
                progressChanged()
            }
        }
    }

    private fun workDir(): File? = CustomIdeBuilderPlugin.context?.getPluginFilesDir()?.let { File(it, "work") }

    private fun stagingDir(): File? = workDir()?.let { File(it, "staged") }

    /** [gen] is the generation a task started under; callers outside a task leave it at the current one. */
    private fun report(text: String, gen: Int = generation) {
        if (gen != generation) return
        message = text
        changed()
    }

    private const val SAVING = "Saving to your folder"

    private fun zero(secrets: List<CharArray>) = secrets.forEach { it.fill('\u0000') }

    /** Deletes [file] only if it is under the staging folder, so a bad path can never remove anything else. */
    private fun discard(file: File) {
        val staging = stagingDir() ?: return
        discard(staging, file)
    }

    /**
     * As [discard], against the [staging] folder a task captured when it started: the plugin's context is
     * gone once [end] has run, and a task that is interrupted then still has to delete its own file.
     */
    private fun discard(staging: File, file: File) {
        Staging.discard(staging, file)
    }

    fun remove(chosen: Chosen) {
        if (items.remove(chosen)) itemsVersion++
        discard(chosen.picked.file)
        changed()
    }

    fun clearItems() {
        items.toList().forEach { discard(it.picked.file) }
        items.clear()
        itemsVersion++
    }

    /** Stops the running job (the Cancel button). The Session can start another afterwards. */
    fun cancel() {
        job?.cancel()
    }

    /**
     * Called when the plugin is disposed: stops the job and the scope it ran in, drops queued
     * screen updates, deletes the staged files and empties everything the screen showed.
     *
     * A stage that cannot be interrupted (signing, the copy loops between checks) may still finish
     * on the IO pool after this returns. It holds no Session state, and whatever it reports or
     * posts is dropped by the [generation] check.
     */
    fun end() {
        generation++
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        main.removeCallbacksAndMessages(null)
        clearItems()
        listeners.clear()
        progressListeners.clear()
        progressGate.reset("", 0)
        // A screen still showing passwords clears them when it next refreshes.
        passwordsToClear = true
        keystoreUri = null
        keystoreName = null
        aliases = emptyList()
        fingerprints = emptyMap()
        selectedAlias = null
        offer = null
        nameQuestion?.answer?.cancel()
        nameQuestion = null
        message = ""
        stage = ""
        progress = 0
        busy = false
    }

    /** Copies the chosen files into the plugin's folder and adds each one that passes [AddonSelection.validate]. */
    fun addFromDevice(context: Context, uris: List<Uri>) {
        val staging = stagingDir() ?: return report("The plugin's storage is not available.")
        launch("Adding add-ons") { gen ->
            val problems = ArrayList<String>()
            for (uri in uris) {
                // The reported name is untrusted: it is the label and the source of the entry name, never a path.
                val name = AndroidBits.displayName(context, uri) ?: "add-on"
                val label = Staging.label(name)
                val file = Staging.stagedFile(staging, counter++, name)
                if (file == null) {
                    problems.add("$label: not a .cgp or .cgt file")
                    continue
                }
                val turnedAway =
                    withContext(Dispatchers.IO) {
                        val size = AndroidBits.displaySize(context, uri)
                        staging.mkdirs()
                        size?.let { AddonLimits.sizeProblem(label, it) } ?: AddonLimits.spaceProblem(label, size, staging.usableSpace)
                    }
                if (turnedAway != null) {
                    problems.add(turnedAway)
                    continue
                }
                val added =
                    try {
                        withContext(Dispatchers.IO) {
                            val check = cancellationCheck()
                            AndroidBits.copyToFile(context, uri, file, limit = AddonLimits.MAX_BYTES, checkCancelled = check)
                        }
                        accept(context, staging, label, name, file, "this device")
                    } catch (e: java.io.IOException) {
                        discard(staging, file)
                        "$label: ${e.message}"
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        discard(staging, file)
                        throw e
                    }
                if (added != null) problems.add(added)
            }
            report(if (problems.isEmpty()) "" else problems.joinToString("\n"), gen)
        }
    }

    /** Fetches the catalog from the website and leaves the entries that fit [hostVersionName] in [offer]. */
    fun fetchOffer(hostVersionName: String?) {
        launch("Reading the add-on list") { gen ->
            try {
                val text = withContext(Dispatchers.IO) { AddonDownloader(HttpFetcher()).fetchCatalog(checkCancelled = cancellationCheck()) }
                val parsed = Catalog.parse(text)
                offer = Catalog.offer(parsed.addons, hostVersionName)
                report(if (parsed.skipped > 0) "${parsed.skipped} catalog entries were left out because their download could not be verified." else "", gen)
            } catch (e: java.io.IOException) {
                report("The add-on list could not be downloaded: ${e.message}", gen)
            } catch (e: CatalogException) {
                report("The add-on list could not be read: ${e.message}", gen)
            }
        }
    }

    /** Downloads [wanted] from the website, verifying each against the catalog, and adds them. */
    fun addFromWebsite(context: Context, wanted: List<CatalogAddon>) {
        val staging = stagingDir() ?: return report("The plugin's storage is not available.")
        launch("Downloading") { gen ->
            val downloader = AddonDownloader(HttpFetcher())
            val problems = ArrayList<String>()
            for ((i, addon) in wanted.withIndex()) {
                val label = "Downloading ${addon.name} (${i + 1} of ${wanted.size})"
                showProgress(label, 0)
                val dir = File(staging, "${counter++}")
                if (!Staging.isInside(staging, File(dir, addon.fileName))) {
                    problems.add("${Staging.label(addon.name)}: its file name is not usable")
                    continue
                }
                // A cancellation can land after the download renamed the verified file but before withContext
                // returns it, so the file is named here, not taken from the result.
                var adopted = false
                try {
                    val file =
                        withContext(Dispatchers.IO) {
                            downloader.download(addon, dir, cancellationCheck()) { got ->
                                progressFrom(gen, label, (got * 1000 / addon.size.coerceAtLeast(1)).toInt())
                            }
                        }
                    val problem = accept(context, staging, addon.name, addon.fileName, file, "the website")
                    if (problem == null) adopted = true else problems.add(problem)
                } catch (e: java.io.IOException) {
                    problems.add("${addon.name}: ${e.message}")
                } finally {
                    if (!adopted) discard(staging, File(dir, addon.fileName))
                }
            }
            report(if (problems.isEmpty()) "" else problems.joinToString("\n"), gen)
        }
    }

    /** Adds the file if the list stays valid with it; otherwise deletes it and returns why. */
    private suspend fun accept(context: Context, staging: File, label: String, fileName: String, file: File, source: String): String? {
        val id = if (AddonSelection.kindOf(fileName) == AddonKind.PLUGIN) withContext(Dispatchers.IO) { AndroidBits.readPluginId(context, file) } else null
        if (AddonSelection.kindOf(fileName) == AddonKind.TEMPLATE && !looksLikeZip(file)) {
            discard(staging, file)
            return "$label: not a template bundle (it is not a zip file)"
        }
        val picked = PickedAddon(label, fileName, file, id)
        return when (val r = AddonSelection.validate(items.map { it.picked } + picked)) {
            is SelectionResult.Valid -> {
                items.add(Chosen(picked, source))
                itemsVersion++
                null
            }
            is SelectionResult.Invalid -> {
                discard(staging, file)
                r.problems.firstOrNull { label in it } ?: r.problems.first()
            }
        }
    }

    private fun looksLikeZip(file: File): Boolean =
        file.inputStream().use { s -> ByteArray(4).also { s.read(it) }.contentEquals(byteArrayOf(0x50, 0x4B, 3, 4)) }

    /** Reads the keystore and lists the keys in it; the password is used here and not kept, and is zeroed on every path. */
    fun loadKeystore(context: Context, storePassword: CharArray) {
        val uri = keystoreUri
        if (uri == null) {
            zero(listOf(storePassword))
            return report("Choose a keystore file first.")
        }
        launch("Reading the keystore", listOf(storePassword)) { gen ->
            try {
                val bytes = withContext(Dispatchers.IO) { AndroidBits.readBytes(context, uri, Keystores.MAX_BYTES) }
                val result = withContext(Dispatchers.IO) { Keystores.open(bytes, storePassword) }
                when (result) {
                    is KeystoreOpen.Opened -> {
                        val names = result.keyAliases
                        aliases = names
                        fingerprints = names.associateWith { fingerprintOf(result, it) }
                        selectedAlias = names.firstOrNull()
                        report(if (names.isEmpty()) "This keystore holds no signing keys." else "", gen)
                    }
                    is KeystoreOpen.Rejected -> clearAliases(result.message, gen)
                    KeystoreOpen.WrongPassword -> clearAliases("The store password is wrong.", gen)
                }
            } catch (e: java.io.IOException) {
                clearAliases("The keystore could not be read: ${e.message}", gen)
            } finally {
                storePassword.fill('\u0000')
            }
        }
    }

    private fun clearAliases(why: String, gen: Int) {
        if (gen != generation) return
        aliases = emptyList()
        fingerprints = emptyMap()
        selectedAlias = null
        report(why, gen)
    }

    private fun fingerprintOf(opened: KeystoreOpen.Opened, alias: String): String {
        val cert = opened.store.getCertificate(alias) as? X509Certificate ?: return ""
        return Keystores.fingerprintOf(cert)
    }

    /**
     * Builds, signs and saves the APK. [storePassword] and [keyPassword] are zeroed when the build
     * ends, however it ends, and also if it never starts.
     */
    fun build(context: Context, host: HostInfo, tree: Uri, storePassword: CharArray, keyPassword: CharArray) {
        val dir = workDir()
        val keystore = keystoreUri
        val alias = selectedAlias
        val shownFingerprint = alias?.let { fingerprints[it] }
        val staged =
            when (val r = AddonSelection.validate(items.map { it.picked })) {
                is SelectionResult.Valid -> r.staged
                is SelectionResult.Invalid -> {
                    storePassword.fill('\u0000'); keyPassword.fill('\u0000')
                    return report(r.problems.joinToString("\n"))
                }
            }
        if (dir == null || keystore == null || alias == null) {
            storePassword.fill('\u0000'); keyPassword.fill('\u0000')
            return report("Choose a keystore and load its keys first.")
        }
        launch("Building", listOf(storePassword, keyPassword), endsBuild = true) { gen ->
            var name = Naming.outputName(host.label, host.versionName)
            // Its own folder: after end() a new build can start while this one is still cleaning up.
            val outName = WorkDirs.outDirName(counter++)
            val out = File(dir, outName)
            var saved: Uri? = null
            var finished = false
            try {
                // Before the minutes of work, so a taken name costs the user nothing.
                val free = freeNameIn(context, tree, name, gen, "The folder already has a file named %s. Choose another name.")
                    ?: return@launch report("Cancelled: nothing was built, because $name is already in the folder and no other name was chosen.", gen)
                name = free
                val result =
                    withContext(Dispatchers.IO) {
                        val job = kotlin.coroutines.coroutineContext[Job]
                        val check = { job?.ensureActive(); Unit }
                        val opened = Keystores.open(AndroidBits.readBytes(context, keystore, Keystores.MAX_BYTES), storePassword)
                        if (opened !is KeystoreOpen.Opened) throw BuildException(if (opened is KeystoreOpen.Rejected) opened.message else "The store password is wrong.")
                        val key =
                            when (val k = Keystores.loadKey(opened, alias, keyPassword, storePassword)) {
                                is KeyLoad.Loaded -> k.key
                                is KeyLoad.Failed -> throw BuildException(k.message)
                            }
                        // The file is read again here, so check it is still the key whose fingerprint the user saw.
                        Keystores.shownKeyProblem(shownFingerprint, key)?.let { throw BuildException(it) }
                        removeStaleOutDirs(dir, outName)
                        out.deleteRecursively()
                        val built =
                            Pipeline.run(BuildInput(host.apk, host.splitDirs, staged, key, host.minSdk, out, name), check) { s, f ->
                                progressFrom(gen, s.label, (f * 1000).toInt())
                            }
                        // The pipeline has verified the APK, so a signer is always there; checked before saving, so no file is written without one.
                        val signer = built.verify.signerSha256 ?: throw BuildException("The finished APK carries no signer certificate.")
                        // A file can appear during the build, and the provider would rename ours without saying so.
                        val chosen = freeNameIn(context, tree, name, gen, "While the app was being built, a file named %s appeared in the folder. Choose another name.")
                            ?: return@withContext null
                        name = chosen
                        progressFrom(gen, SAVING, 0)
                        val target = AndroidBits.createInTree(context, tree, name)
                        saved = target
                        val size = built.apk.length().coerceAtLeast(1)
                        AndroidBits.copyFileTo(context, built.apk, target, check) { got ->
                            progressFrom(gen, SAVING, (got * 1000 / size).toInt())
                        }
                        // What the folder really called it: the provider renames a file whose name is taken.
                        val actual = AndroidBits.displayName(context, target)
                        Triple(signer, AndroidBits.treeLabel(tree), actual)
                    }
                if (result == null) {
                    return@launch report("Cancelled: the APK was built but not saved, because a file named $name appeared in the folder and no other name was chosen. The built file was deleted.", gen)
                }
                finished = true
                val note = Naming.savedNote(name, result.third)
                report(
                    "Done. ${result.third ?: name} is in ${result.second}.\n" + (if (note.isEmpty()) "" else "$note\n") +
                        "Signed with certificate ${result.first}.\n\n" +
                        "It installs only after Code on the Go is uninstalled, because it is signed with a different key. " +
                        "Uninstalling erases the projects stored in the app's data, so copy them out first.",
                    gen,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                report("Cancelled.", gen)
                throw e
            } catch (e: Exception) {
                report("The build failed: ${e.message ?: e.javaClass.simpleName}", gen)
            } finally {
                storePassword.fill('\u0000')
                keyPassword.fill('\u0000')
                // Nothing may follow this in the finally: once the job is cancelled, withContext throws on
                // its way back instead of returning, so a line after it never runs. The end of the build
                // is signalled by launch (endsBuild).
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    out.deleteRecursively()
                    // A save that failed or was cancelled removes its partial APK from the folder; if the
                    // provider refuses, the result says which file to delete by hand.
                    if (!finished) {
                        saved?.let { target ->
                            if (!AndroidBits.deleteDocument(context, target)) {
                                val left = AndroidBits.displayName(context, target) ?: name
                                val note = Naming.partialLeftNote(left, AndroidBits.treeLabel(tree))
                                // Posted, not called: once the job is cancelled nothing can follow this block. It runs
                                // before the job's own completion, so [message] is still this build's outcome.
                                main.post { if (gen == generation) report("$message\n$note", gen) }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Deletes the output folders other builds left in [dir] (a killed process, or a cleanup that never ran), never [current]. */
    private fun removeStaleOutDirs(dir: File, current: String) {
        for (name in WorkDirs.staleOutDirs(dir.list()?.toList().orEmpty(), current)) {
            val stale = File(dir, name)
            if (Staging.isInside(dir, stale)) stale.deleteRecursively()
        }
    }

    /**
     * The answer to [nameQuestion]: [text] is cleaned like every output name and must end up
     * non-empty. Returns false (the question stays) when nothing usable is left, so the dialog can
     * stay open and say so.
     */
    fun answerName(question: NameQuestion, text: String): Boolean {
        val name = Naming.fromEntry(text) ?: return false
        if (nameQuestion === question) {
            nameQuestion = null
            question.answer.complete(name)
            changed()
        }
        return true
    }

    /** Cancels the save: the question ends with no name, and the build deletes what it built. */
    fun declineName(question: NameQuestion) {
        if (nameQuestion === question) {
            nameQuestion = null
            question.answer.complete(null)
            changed()
        }
    }

    /**
     * Asks the user for another name and waits. Returns null if they cancel the save. A cancelled
     * build or [end] ends the wait with a cancellation, and the question is cleared either way.
     */
    private suspend fun askName(gen: Int, message: String, suggestion: String): String? {
        val question = NameQuestion(message, suggestion)
        // The try opens before the publication: a cancel between publishing and resuming must still reach the finally.
        try {
            withContext(Dispatchers.Main) {
                if (gen != generation) throw kotlinx.coroutines.CancellationException("The plugin was disposed.")
                nameQuestion = question
                changed()
            }
            return question.answer.await()
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                if (nameQuestion === question) {
                    nameQuestion = null
                    changed()
                }
            }
        }
    }

    /**
     * Returns [wanted] once the folder does not hold it, asking the user for another name each time
     * it does ([firstReason] explains the first question). Returns null if they cancel. The folder
     * is listed afresh on every round, since a file can appear while the dialog is open.
     */
    private suspend fun freeNameIn(context: Context, tree: Uri, wanted: String, gen: Int, firstReason: String): String? {
        var name = wanted
        var reason = firstReason
        while (true) {
            val taken = withContext(Dispatchers.IO) { AndroidBits.childNames(context, tree) }
            if (!Naming.isTaken(name, taken)) return name
            name = askName(gen, reason.replace("%s", name), Naming.freeName(name, taken)) ?: return null
            reason = "The folder already has a file named %s. Choose another name."
        }
    }

    /** A check for the running coroutine's Job, for the blocking copy loops, which `withContext` does not interrupt. */
    private suspend fun cancellationCheck(): () -> Unit {
        val job = kotlin.coroutines.coroutineContext[Job]
        return { job?.ensureActive() }
    }

    /**
     * Runs [block] as the one running job, with the busy state around it; [block] gets the
     * generation it started under, for [report] and [progressFrom]. [secrets] are zeroed when the job
     * completes however it completes, including a cancellation before its first line runs (where no
     * `finally` inside [block] would ever execute), and at once if another job is running and
     * this one is refused. Returns false for that refusal.
     *
     * [endsBuild] sets [passwordsToClear] when the job completes. It is set here, in a finally with no
     * suspension before it, because a line after a suspending call in [block]'s own finally is
     * skipped once the job is cancelled.
     */
    private fun launch(title: String, secrets: List<CharArray> = emptyList(), endsBuild: Boolean = false, block: suspend (Int) -> Unit): Boolean {
        if (busy) {
            zero(secrets)
            return false
        }
        busy = true
        stage = title
        progress = 0
        progressGate.reset(title, 0)
        message = ""
        changed()
        val gen = generation
        val started = (scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }).launch {
            try {
                block(gen)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing else catches here, and an uncaught exception would take the whole IDE down with it.
                report("$title failed: ${e.message ?: e.javaClass.simpleName}", gen)
            } finally {
                if (gen == generation) {
                    if (endsBuild) passwordsToClear = true
                    busy = false
                    stage = ""
                    progress = 0
                    progressGate.reset("", 0)
                    changed()
                }
            }
        }
        started.invokeOnCompletion { zero(secrets) }
        job = started
        return true
    }
}
