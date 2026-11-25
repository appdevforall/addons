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
import org.appdevforall.customidebuilder.core.PluginMinSdk
import org.appdevforall.customidebuilder.core.Pipeline
import org.appdevforall.customidebuilder.core.SelectionResult
import org.appdevforall.customidebuilder.core.Staging
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate

/** One add-on on the list. [source] is for the screen: where it came from. */
class Chosen(val picked: PickedAddon, val source: String, val minSdk: MinSdk? = null)

/**
 * What the screen shows, kept outside the Fragment because a build takes minutes and the Fragment
 * is rebuilt on rotation. Nothing secret is held here: passwords stay in the screen's text fields
 * and in the arrays of one running build, which are zeroed when it ends.
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

    fun listen(l: () -> Unit) { listeners.add(l) }

    fun unlisten(l: () -> Unit) { listeners.remove(l) }

    private fun changed() = listeners.toList().forEach { it() }

    private fun workDir(): File? = CustomIdeBuilderPlugin.context?.getPluginFilesDir()?.let { File(it, "work") }

    private fun stagingDir(): File? = workDir()?.let { File(it, "staged") }

    private fun report(text: String) {
        message = text
        changed()
    }

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
        launch("Reading the picture") {
            val name = AndroidBits.displayName(context, uri) ?: "picture"
            try {
                val prepared = withContext(Dispatchers.IO) { IconImages.prepare(context, uri) }
                val verdict =
                    withContext(Dispatchers.IO) {
                        val references = IconImages.hostIcons(context, hostPackage, hostApk)
                        if (references.isEmpty()) IconVerdict.Unchecked("Code on the Go's icon could not be drawn.") else IconCheck.compare(prepared.hashPixels, references)
                    }
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
                )
            } catch (e: java.io.IOException) {
                report("$name: ${e.message}")
            } catch (e: OutOfMemoryError) {
                report("$name is too large to read on this device. Choose a smaller picture.")
            }
        }
    }

    /** Called when the plugin is disposed: stops the job and the scope it ran in, and drops queued screen updates. */
    fun end() {
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        main.removeCallbacksAndMessages(null)
        busy = false
    }

    /** Copies the chosen files into the plugin's folder and adds each one that passes [AddonSelection.validate]. */
    fun addFromDevice(context: Context, uris: List<Uri>) {
        val staging = stagingDir() ?: return report("The plugin's storage is not available.")
        launch("Adding add-ons") {
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
                val added =
                    try {
                        withContext(Dispatchers.IO) {
                            val check = cancellationCheck()
                            AndroidBits.copyToFile(context, uri, file, checkCancelled = check)
                        }
                        accept(context, label, name, file, "this device")
                    } catch (e: java.io.IOException) {
                        discard(file)
                        "$label: ${e.message}"
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        discard(file)
                        throw e
                    }
                if (added != null) problems.add(added)
            }
            report(if (problems.isEmpty()) "" else problems.joinToString("\n"))
        }
    }

    /** Fetches the catalog from the website and leaves the entries that fit [hostVersionName] in [offer]. */
    fun fetchOffer(hostVersionName: String?) {
        launch("Reading the add-on list") {
            try {
                val text = withContext(Dispatchers.IO) { AddonDownloader(HttpFetcher()).fetchCatalog(checkCancelled = cancellationCheck()) }
                val parsed = Catalog.parse(text)
                offer = Catalog.offer(parsed.addons, hostVersionName)
                report(if (parsed.skipped > 0) "${parsed.skipped} catalog entries were left out because their download could not be verified." else "")
            } catch (e: java.io.IOException) {
                report("The add-on list could not be downloaded: ${e.message}")
            } catch (e: CatalogException) {
                report("The add-on list could not be read: ${e.message}")
            }
        }
    }

    /** Downloads [wanted] from the website, verifying each against the catalog, and adds them. */
    fun addFromWebsite(context: Context, wanted: List<CatalogAddon>) {
        val staging = stagingDir() ?: return report("The plugin's storage is not available.")
        launch("Downloading") {
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
                                main.post { progress = (got * 1000 / addon.size.coerceAtLeast(1)).toInt(); changed() }
                            }
                        }
                    } catch (e: java.io.IOException) {
                        problems.add("${addon.name}: ${e.message}")
                        continue
                    }
                accept(context, addon.name, addon.fileName, file, "the website")?.let { problems.add(it) }
            }
            report(if (problems.isEmpty()) "" else problems.joinToString("\n"))
        }
    }

    /** Adds the file if the list stays valid with it; otherwise deletes it and returns why. */
    private suspend fun accept(context: Context, label: String, fileName: String, file: File, source: String): String? {
        val id = if (AddonSelection.kindOf(fileName) == AddonKind.PLUGIN) withContext(Dispatchers.IO) { AndroidBits.readPluginId(context, file) } else null
        if (AddonSelection.kindOf(fileName) == AddonKind.TEMPLATE && !looksLikeZip(file)) {
            discard(file)
            return "$label: not a template bundle (it is not a zip file)"
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

    private fun looksLikeZip(file: File): Boolean =
        file.inputStream().use { s -> ByteArray(4).also { s.read(it) }.contentEquals(byteArrayOf(0x50, 0x4B, 3, 4)) }

    /** Reads the keystore and lists the keys in it; the password is used here and not kept. */
    fun loadKeystore(context: Context, storePassword: CharArray) {
        val uri = keystoreUri ?: return report("Choose a keystore file first.")
        launch("Reading the keystore") {
            try {
                val bytes = withContext(Dispatchers.IO) { AndroidBits.readBytes(context, uri, Keystores.MAX_BYTES) }
                val result = withContext(Dispatchers.IO) { Keystores.open(bytes, storePassword) }
                when (result) {
                    is KeystoreOpen.Opened -> {
                        val names = result.keyAliases
                        aliases = names
                        fingerprints = names.associateWith { fingerprintOf(result, it) }
                        selectedAlias = names.firstOrNull()
                        report(if (names.isEmpty()) "This keystore holds no signing keys." else "")
                    }
                    is KeystoreOpen.Rejected -> clearAliases(result.message)
                    KeystoreOpen.WrongPassword -> clearAliases("The store password is wrong.")
                }
            } catch (e: java.io.IOException) {
                clearAliases("The keystore could not be read: ${e.message}")
            } finally {
                storePassword.fill('\u0000')
            }
        }
    }

    private fun clearAliases(why: String) {
        aliases = emptyList()
        fingerprints = emptyMap()
        selectedAlias = null
        report(why)
    }

    private fun fingerprintOf(opened: KeystoreOpen.Opened, alias: String): String {
        val cert = opened.store.getCertificate(alias) as? X509Certificate ?: return ""
        return MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
    }

    /**
     * Builds, signs and saves the APK. [storePassword] and [keyPassword] are zeroed when the build
     * ends, however it ends.
     */
    fun build(context: Context, host: HostInfo, tree: Uri, storePassword: CharArray, keyPassword: CharArray) {
        val dir = workDir()
        val keystore = keystoreUri
        val alias = selectedAlias
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
        launch("Building") {
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
                        out.deleteRecursively()
                        val built =
                            Pipeline.run(BuildInput(host.apk, host.splitDirs, staged, key, host.minSdk, out, name, branding), check) { s, f ->
                                main.post { setStage(s, f) }
                            }
                        main.post { stage = "Saving to your folder"; progress = 0; changed() }
                        val target = AndroidBits.createInTree(context, tree, name)
                        saved = target
                        AndroidBits.copyFileTo(context, built.apk, target, check) { got ->
                            main.post { progress = (got * 1000 / built.apk.length().coerceAtLeast(1)).toInt(); changed() }
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
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                report("Cancelled.")
                throw e
            } catch (e: Exception) {
                report("The build failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                storePassword.fill('\u0000')
                keyPassword.fill('\u0000')
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    out.deleteRecursively()
                    // A save that failed or was cancelled leaves no APK, not even a partial one, in the folder.
                    if (!finished) saved?.let { AndroidBits.deleteDocument(context, it) }
                }
                buildsEnded++
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

    /** Runs [block] as the one running job, with the busy state around it. */
    private fun launch(title: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        stage = title
        progress = 0
        message = ""
        changed()
        job = (scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }).launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing else catches here, and an uncaught exception would take the whole IDE down with it.
                report("$title failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
                stage = ""
                progress = 0
                changed()
            }
        }
    }
}
