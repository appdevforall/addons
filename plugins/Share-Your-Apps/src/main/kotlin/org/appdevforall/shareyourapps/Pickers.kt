package org.appdevforall.shareyourapps

import android.app.AlertDialog
import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pickers that return paths relative to shared storage, which is what catalog.json stores.
 * Android's own file picker returns content URIs instead, which can't reliably be turned back
 * into paths.
 */
object Pickers {

    val sdcard: File get() = Environment.getExternalStorageDirectory()
    private val projects: File get() = File(sdcard, "CodeOnTheGoProjects")
    private val STORE = Regex("""Store_[0-9]{8}""")
    private val MEDIA = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "mp4", "webm", "mp3", "ogg", "wav", "m4a")

    fun rel(f: File): String = f.absoluteFile.relativeTo(sdcard.absoluteFile).path

    /** Folders in CodeOnTheGoProjects, except Store directories and hidden ones. */
    fun pickProject(ctx: Context, pick: (String) -> Unit) {
        val dirs = projects.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && !STORE.matches(it.name) }
            ?.sortedBy { it.name.lowercase() }.orEmpty()
        if (dirs.isEmpty()) { message(ctx, "Choose a project", "There are no projects in CodeOnTheGoProjects."); return }
        AlertDialog.Builder(ctx).setTitle("Choose a project")
            .setItems(dirs.map { it.name }.toTypedArray()) { _, i -> pick(rel(dirs[i])) }
            .setNegativeButton("Cancel", null).show()
    }

    /**
     * The APKs the project has built, newest first, from any module's outputs/apk. CodeOnTheGo 26.45
     * and later build into its own private storage instead of the project's build folders (see
     * [relocatedBuildTree]); older versions, and projects not yet synced since, still use the project's.
     */
    fun pickApk(ctx: Context, project: String?, pick: (String?) -> Unit) {
        if (project == null) { message(ctx, "Choose an APK", "Choose the product's project first."); return }
        val root = File(sdcard, project)
        val inProject = root.walkTopDown().onEnter { d -> d == root || !d.name.startsWith(".") }
            .filter { it.isFile && it.name.endsWith(".apk") && it.path.contains("/build/outputs/apk/") }
        val relocated = File(relocatedBuildTree(ctx, root), "main").listFiles().orEmpty()
            .flatMap { module -> File(module, "outputs/apk").walkTopDown().filter { it.isFile && it.name.endsWith(".apk") } }
        val apks = (inProject + relocated).sortedByDescending { it.lastModified() }.toList()
        if (apks.isEmpty()) {
            message(ctx, "Choose an APK", "This project hasn't built an APK yet. Build it in CodeOnTheGo, then try again.")
            return
        }
        val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)
        val labels = apks.map { f ->
            val variant = f.parentFile?.relativeTo(File(f.path.substringBefore("/outputs/apk/") + "/outputs/apk"))?.path.orEmpty()
            "${if (variant.isEmpty()) "" else "$variant · "}${f.name}\n${sizeLabel(f.length())} · built ${fmt.format(Date(f.lastModified()))}" +
                if (apkSigned(f)) "" else "\n⚠ Unsigned: buyers can't install it"
        } + "No APK"
        AlertDialog.Builder(ctx).setTitle("Choose an APK")
            .setItems(labels.toTypedArray()) { _, i -> pick(if (i < apks.size) rel(apks[i]) else null) }
            .setNegativeButton("Cancel", null).show()
    }

    /**
     * Where CodeOnTheGo 26.45+ builds [projectRoot]: `no_backup/gradle-build/<key>`, the root build's modules under
     * `main/`. The key is CodeOnTheGo's ProjectStorageKey.of() of the project's canonical path, copied exactly:
     * CodeOnTheGo offers plugins no API for build outputs, so if it changes the key, this must change with it.
     * The APKs it finds are stored as paths relative to shared storage, through `..`, like every other path.
     */
    fun relocatedBuildTree(ctx: Context, projectRoot: File): File =
        File(ctx.noBackupFilesDir, "gradle-build/" + storageKey(runCatching { projectRoot.canonicalFile }.getOrElse { projectRoot.absoluteFile }))

    /** CodeOnTheGo's ProjectStorageKey.of(): `<sanitized name>-<first 16 hex digits of the path's SHA-256>`. */
    fun storageKey(projectRoot: File): String {
        val normalized = projectRoot.absoluteFile.normalize().path
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
        val base = projectRoot.name.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }
            .joinToString("").take(40).ifEmpty { "project" }
        return "$base-$hash"
    }

    /** A WebVTT captions file, for a video (design, Known open items: picture descriptions and video captions). */
    fun pickCaptions(ctx: Context, pick: (String) -> Unit) {
        val start = File(sdcard, "Movies").takeIf { it.isDirectory } ?: File(sdcard, "Download").takeIf { it.isDirectory } ?: sdcard
        browse(ctx, "Choose a captions file", start, { it.lowercase().endsWith(".vtt") }, pick)
    }

    /** A small folder browser over shared storage, showing folders and media files. */
    fun pickMedia(ctx: Context, title: String, pick: (String) -> Unit) {
        val start = File(sdcard, "Pictures").takeIf { it.isDirectory } ?: sdcard
        browse(ctx, title, start, { it.substringAfterLast('.', "").lowercase() in MEDIA }, pick)
    }

    /** The same browser, showing folders and Pebble templates (.peb), starting in Download. */
    fun pickTemplate(ctx: Context, pick: (String) -> Unit) {
        val start = File(sdcard, Environment.DIRECTORY_DOWNLOADS).takeIf { it.isDirectory } ?: sdcard
        browse(ctx, "Choose a template", start, { it.endsWith(".peb", ignoreCase = true) }, pick)
    }

    /** Folders, and the files whose names [accept] allows. */
    private fun browse(ctx: Context, title: String, dir: File, accept: (String) -> Boolean, pick: (String) -> Unit) {
        val children = dir.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
        val folders = children.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val media = children.filter { it.isFile && accept(it.name) }
            .sortedBy { it.name.lowercase() }
        val atRoot = dir.absoluteFile == sdcard.absoluteFile
        val entries = (if (atRoot) emptyList() else listOf<File?>(null)) + folders + media
        val labels = entries.map { f ->
            when {
                f == null -> "⬆  Up"
                f.isDirectory -> "📁  ${f.name}"
                else -> "${f.name}  (${sizeLabel(f.length())})"
            }
        }
        val where = if (atRoot) "/sdcard" else "/sdcard/" + rel(dir)
        val b = AlertDialog.Builder(ctx).setTitle("$title — $where").setNegativeButton("Cancel", null)
        if (entries.isEmpty()) b.setMessage("Nothing here.") else b.setItems(labels.toTypedArray()) { _, i ->
            val f = entries[i]
            when {
                f == null -> browse(ctx, title, dir.parentFile ?: sdcard, accept, pick)
                f.isDirectory -> browse(ctx, title, f, accept, pick)
                else -> pick(rel(f))
            }
        }
        b.show()
    }

    fun message(ctx: Context, title: String, text: String) {
        AlertDialog.Builder(ctx).setTitle(title).setMessage(text).setPositiveButton("OK", null).show()
    }
}
