package org.appdevforall.customidebuilder

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import org.appdevforall.customidebuilder.core.Branding
import org.appdevforall.customidebuilder.core.BrandingGate
import org.appdevforall.customidebuilder.core.BuildStage
import org.appdevforall.customidebuilder.core.Catalog
import org.appdevforall.customidebuilder.core.CatalogAddon
import org.appdevforall.customidebuilder.core.CatalogException
import org.appdevforall.customidebuilder.core.HttpFetcher
import org.appdevforall.customidebuilder.core.IconCheck
import org.appdevforall.customidebuilder.core.IconVerdict
import org.appdevforall.customidebuilder.core.KeyLoad
import org.appdevforall.customidebuilder.core.KeystoreOpen
import org.appdevforall.customidebuilder.core.Keystores
import org.appdevforall.customidebuilder.core.MinSdk
import org.appdevforall.customidebuilder.core.Naming
import org.appdevforall.customidebuilder.core.PickedAddon
import org.appdevforall.customidebuilder.core.PluginManifest
import org.appdevforall.customidebuilder.core.PluginMinSdk
import org.appdevforall.customidebuilder.core.Pipeline
import org.appdevforall.customidebuilder.core.SelectionResult
import org.appdevforall.customidebuilder.core.Staging
import org.appdevforall.customidebuilder.core.TemplateCheck
import java.io.File
import java.security.cert.X509Certificate

/** One add-on on the list. [source] is for the screen: where it came from. */
class Chosen(val picked: PickedAddon, val source: String, val minSdk: MinSdk? = null)

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

    /** The name typed for the copy; blank keeps Code on the Go's own. */
    var appName: String = ""

    /** The picture chosen for the icon, with its PNGs and the outcome of the look-alike check (null until that finishes). */
    var icon: PreparedIcon? = null
    var iconName: String? = null
    var iconVerdict: IconVerdict? = null

    /** The ownership checkbox. It is cleared whenever the name or picture changes, so it always covers what is on screen. */
    var confirmedOwn: Boolean = false

    /** The catalog entries just fetched, for the screen to show once and clear. */
    var offer: Catalog.Offer? = null

    /** Shown under the buttons: the latest outcome or problem. */
    var message: String = ""
    var busy: Boolean = false
    var stage: String = ""
    var progress: Int = 0

    /** How many builds have run to an end (done, failed or cancelled); the screen clears its password fields when it changes. */
    var buildsEnded: Int = 0

    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var counter = 0
    private val listeners = ArrayList<() -> Unit>()

    /**
     * Bumped by [end]. A task remembers the value it started under, and what it reports or posts
     * to the main thread afterwards is dropped if the value has moved on, so work that outlives
     * the plugin cannot write into state [end] cleared or tell a Fragment that is gone.
     */
    private var generation = 0

    fun listen(l: () -> Unit) { listeners.add(l) }

    fun unlisten(l: () -> Unit) { listeners.remove(l) }

    private fun changed() = listeners.toList().forEach { it() }

    private fun workDir(): File? = CustomIdeBuilderPlugin.context?.getPluginFilesDir()?.let { File(it, "work") }

    private fun stagingDir(): File? = workDir()?.let { File(it, "staged") }

    /** [gen] is the generation a task started under; callers outside a task leave it at the current one. */
    private fun report(text: String, gen: Int = generation) {
        if (gen != generation) return
        message = text
        changed()
    }

    private fun post(gen: Int, block: () -> Unit) {
        main.post { if (gen == generation) block() }
    }

    private fun zero(secrets: List<CharArray>) = secrets.forEach { it.fill('\u0000') }

    /** Deletes [file] only if it is under the staging folder, so a bad path can never remove anything else. */
    private fun discard(file: File) {
        val staging = stagingDir() ?: return
        if (Staging.isInside(staging, file)) file.delete()
    }

    fun remove(chosen: Chosen) {
        items.remove(chosen)
        discard(chosen.picked.file)
        changed()
    }

    fun clearItems() {
        items.toList().forEach { discard(it.picked.file) }
        items.clear()
    }

    /** Stops the running job (the Cancel button). The Session can start another afterwards. */
    fun cancel() {
        job?.cancel()
    }

    fun setName(text: String) {
        if (text == appName) return
        appName = text
        confirmedOwn = false
        changed()
    }

    fun clearIcon() {
        // The preview is not recycled: the ImageView may still be drawing it.
        icon = null
        iconName = null
        iconVerdict = null
        confirmedOwn = false
        changed()
    }

    /** Decodes the picture, makes the icon files and compares it with Code on the Go's own icon. */
    fun pickIcon(context: Context, uri: Uri, hostPackage: String, hostApk: File) {
        launch("Reading the picture") { gen ->
            val name = AndroidBits.displayName(context, uri) ?: "picture"
            try {
                val prepared = withContext(Dispatchers.IO) { IconImages.prepare(context, uri) }
                val verdict =
                    withContext(Dispatchers.IO) {
                        val references = IconImages.hostIcons(context, hostPackage, hostApk)
                        if (references.isEmpty()) IconVerdict.Unchecked("Code on the Go's icon could not be drawn.") else IconCheck.compare(prepared.hashPixels, references)
                    }
                if (gen != generation) return@launch
                clearIcon()
                icon = prepared
                iconName = name
                iconVerdict = verdict
                report(
                    when (verdict) {
                        is IconVerdict.TooSimilar -> "$name is too close to Code on the Go's own icon. Choose a picture of your own."
                        is IconVerdict.Unchecked -> "${verdict.reason} The picture was not compared with it."
                        is IconVerdict.Different ->
                            if (prepared.silhouetteCoverage < 0.02 || prepared.silhouetteCoverage > 0.9) {
                                "The status-bar icon made from $name will be nearly empty or a solid block. A picture with a clear shape on a plain or transparent background works best."
                            } else {
                                ""
                            }
                    },
                    gen,
                )
            } catch (e: java.io.IOException) {
                report("$name: ${e.message}", gen)
            } catch (e: OutOfMemoryError) {
                report("$name is too large to read on this device. Choose a smaller picture.", gen)
            }
        }
    }

    /**
     * Called when the plugin is disposed: stops the job and the scope it ran in, drops queued
     * screen updates, deletes the staged files and empties everything the screen showed.
     *
     * A stage that cannot be interrupted (signing, the copy loops between checks) may still finish
     * on the IO pool after this returns. It holds no Session state, and whatever it reports or
     * posts is dropped by the [generation] check.
     *
     * The icon preview is dropped, not recycled: the screen's ImageView may still hold it, and a
     * frame already handed to the render thread may still draw it. Nothing refers to it afterwards,
     * so it is freed with the screen.
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
        keystoreUri = null
        keystoreName = null
        aliases = emptyList()
        fingerprints = emptyMap()
        selectedAlias = null
        appName = ""
        icon = null
        iconName = null
        iconVerdict = null
        confirmedOwn = false
        IconImages.release()
        offer = null
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
                        accept(label, name, file, "this device")
                    } catch (e: java.io.IOException) {
                        discard(file)
                        "$label: ${e.message}"
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        discard(file)
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
                stage = "Downloading ${addon.name} (${i + 1} of ${wanted.size})"
                progress = 0
                changed()
                val dir = File(staging, "${counter++}")
                if (!Staging.isInside(staging, File(dir, addon.fileName))) {
                    problems.add("${Staging.label(addon.name)}: its file name is not usable")
                    continue
                }
                val file =
                    try {
                        withContext(Dispatchers.IO) {
                            downloader.download(addon, dir, cancellationCheck()) { got ->
                                post(gen) { progress = (got * 1000 / addon.size.coerceAtLeast(1)).toInt(); changed() }
                            }
                        }
                    } catch (e: java.io.IOException) {
                        problems.add("${addon.name}: ${e.message}")
                        continue
                    }
                accept(addon.name, addon.fileName, file, "the website")?.let { problems.add(it) }
            }
            report(if (problems.isEmpty()) "" else problems.joinToString("\n"), gen)
        }
    }

    /** Adds the file if the list stays valid with it; otherwise deletes it and returns why. */
    private suspend fun accept(label: String, fileName: String, file: File, source: String): String? {
        // Read from the binary manifest, so a plugin for a newer Android than this device's is still identified.
        val id = if (AddonSelection.kindOf(fileName) == AddonKind.PLUGIN) withContext(Dispatchers.IO) { PluginManifest.fromArchive(file)?.pluginId } else null
        if (AddonSelection.kindOf(fileName) == AddonKind.TEMPLATE) {
            val problem = withContext(Dispatchers.IO) { TemplateCheck.problem(file) }
            if (problem != null) {
                discard(file)
                return "$label: $problem"
            }
        }
        val picked = PickedAddon(label, fileName, file, id)
        val minSdk = if (AddonSelection.kindOf(fileName) == AddonKind.PLUGIN) withContext(Dispatchers.IO) { PluginMinSdk.fromArchive(file) } else null
        return when (val r = AddonSelection.validate(items.map { it.picked } + picked)) {
            is SelectionResult.Valid -> {
                items.add(Chosen(picked, source, minSdk))
                null
            }
            is SelectionResult.Invalid -> {
                discard(file)
                r.problems.firstOrNull { label in it } ?: r.problems.first()
            }
        }
    }

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
        val prepared = icon
        val gate = BrandingGate.problems(appName, prepared != null, iconVerdict, confirmedOwn)
        if (gate.isNotEmpty()) {
            storePassword.fill('\u0000'); keyPassword.fill('\u0000')
            return report(gate.joinToString("\n"))
        }
        val branding = if (BrandingGate.isCustom(appName, prepared != null)) Branding(BrandingGate.nameToWrite(appName), prepared?.icons) else null
        launch("Building", listOf(storePassword, keyPassword)) { gen ->
            val name = Naming.outputName(branding?.appName ?: host.label, host.versionName)
            val out = File(dir, "out")
            var saved: Uri? = null
            var finished = false
            try {
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
                        out.deleteRecursively()
                        val built =
                            Pipeline.run(BuildInput(host.apk, host.splitDirs, staged, key, host.minSdk, out, name, branding), check) { s, f ->
                                post(gen) { setStage(s, f) }
                            }
                        post(gen) { stage = "Saving to your folder"; progress = 0; changed() }
                        val target = AndroidBits.createInTree(context, tree, name)
                        saved = target
                        AndroidBits.copyFileTo(context, built.apk, target, check) { got ->
                            post(gen) { progress = (got * 1000 / built.apk.length().coerceAtLeast(1)).toInt(); changed() }
                        }
                        Triple(built.verify.signerSha256, AndroidBits.treeLabel(tree), built.rewrite.branding)
                    }
                finished = true
                val skipped = result.third?.styledSkipped ?: 0
                report(
                    "Done. $name is in ${result.second}.\nSigned with certificate ${result.first}.\n" +
                        (if (skipped > 0) "$skipped text${if (skipped == 1) "" else "s"} that name Code on the Go and carry formatting were left as they are.\n" else "") +
                        "\n" +
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
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    out.deleteRecursively()
                    // A save that failed or was cancelled leaves no APK, not even a partial one, in the folder.
                    if (!finished) saved?.let { AndroidBits.deleteDocument(context, it) }
                }
                if (gen == generation) buildsEnded++
            }
        }
    }

    private fun setStage(s: BuildStage, fraction: Double) {
        stage = s.label
        progress = (fraction * 1000).toInt()
        changed()
    }

    /** A check for the running coroutine's Job, for the blocking copy loops, which `withContext` does not interrupt. */
    private suspend fun cancellationCheck(): () -> Unit {
        val job = kotlin.coroutines.coroutineContext[Job]
        return { job?.ensureActive() }
    }

    /**
     * Runs [block] as the one running job, with the busy state around it; [block] gets the
     * generation it started under, for [report] and [post]. [secrets] are zeroed when the job
     * completes however it completes, including a cancellation before its first line runs (where no
     * `finally` inside [block] would ever execute), and at once if another job is running and
     * this one is refused. Returns false for that refusal.
     */
    private fun launch(title: String, secrets: List<CharArray> = emptyList(), block: suspend (Int) -> Unit): Boolean {
        if (busy) {
            zero(secrets)
            return false
        }
        busy = true
        stage = title
        progress = 0
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
                    busy = false
                    stage = ""
                    progress = 0
                    changed()
                }
            }
        }
        started.invokeOnCompletion { zero(secrets) }
        job = started
        return true
    }
}
