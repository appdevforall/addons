package org.appdevforall.shareyourapps

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.Html
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import java.io.File
import java.time.Instant

/** The largest file loaded as a template: real templates are a few KB, so this only stops something that isn't one. */
private const val TEMPLATE_LIMIT = 256 * 1024L

/**
 * Manage Catalog: the store's details, its products, and the Publish button, styled after the
 * wireframes (Style). Everything is saved as it's edited. The catalog and publish state live
 * outside this Fragment, so the screen can be rebuilt at any time.
 */
class ShareYourAppsFragment : Fragment() {

    private var content: LinearLayout? = null
    private var rootView: View? = null
    private var status: TextView? = null
    private var publish: Button? = null
    private var lastPhase = PublishFlow.phase
    private val listener: () -> Unit = { renderPublish() }
    private lateinit var st: Style

    // Registered before the Fragment starts, as the API requires. Launching through it makes the
    // server see CodeOnTheGo as the caller.
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            PublishFlow.onLaunchResult(r.data?.getIntExtra(ServerContract.RESULT_PORT, 0) ?: 0, null)
        } else {
            PublishFlow.onLaunchResult(0, r.data?.getStringExtra(ServerContract.RESULT_ERROR) ?: "the server app closed without answering",
                unchanged = r.data?.getBooleanExtra(ServerContract.RESULT_UNCHANGED, false) ?: false)
        }
    }

    // Opens Android's install confirmation. Its result is ignored: Android reports the install's outcome to
    // ServerInstaller separately. Launching through a registered launcher, as the server's start does, works in the
    // release build; requireActivity().startActivity() threw a NullPointerException there, after R8.
    private val confirmLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private fun ctx(): Context = requireContext()

    /** Long-press shows the tooltip; EditTexts keep their own long-press, so help goes on labels. */
    private fun <V : View> V.help(tag: String): V = apply {
        // With this plugin's category: the two-argument form looks in the IDE's own category, and finds nothing.
        setOnLongClickListener { v -> ShareYourAppsPlugin.tooltips()?.showTooltip(v, "plugin_org.appdevforall.shareyourapps", tag); true }
    }

    /** The bundled server's version, read once in the background. */
    @Volatile private var bundledServer: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        ShareYourAppsPlugin.dataDir()?.let { CatalogState.load(it) }
        st = Style(ctx())
        val root = LinearLayout(ctx()).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(st.bg) }
        rootView = root
        val c = LinearLayout(ctx()).apply { orientation = LinearLayout.VERTICAL; setPadding(st.dp(20), st.dp(18), st.dp(20), st.dp(24)) }
        content = c
        root.addView(ScrollView(ctx()).apply { addView(c) }, LinearLayout.LayoutParams(-1, 0, 1f))

        // The action row, always visible: the status line above the Publish button.
        val bar = LinearLayout(ctx()).apply {
            orientation = LinearLayout.VERTICAL
            // Tight but even padding: Code On The Go's own panel already leaves space below the plugin.
            setPadding(st.dp(20), st.dp(8), st.dp(20), st.dp(8))
            background = GradientDrawable().apply { setColor(st.surface) }
            elevation = st.dp(6).toFloat()
        }
        status = TextView(ctx()).apply { textSize = 13f; setTextColor(st.ink); setTextIsSelectable(true); setPadding(0, 0, 0, st.dp(8)) }
        publish = st.primary(Button(ctx())).apply { text = "Publish"; setOnClickListener { onPublish() } }.help(Help.PUBLISH)
        bar.addView(status)
        bar.addView(publish, LinearLayout.LayoutParams(-1, -2))
        root.addView(bar)

        renderForm()
        return root
    }

    override fun onResume() {
        super.onResume()
        PublishFlow.observe(listener)
        // A catalog.json changed by something else — say, installed by hand — is picked up here.
        if (CatalogState.reloadIfChanged() != Reload.NONE) renderForm()
    }
    override fun onPause() { super.onPause(); PublishFlow.remove(listener); CatalogState.saveNow() }
    override fun onDestroyView() { super.onDestroyView(); content = null; status = null; publish = null; rootView = null }

    // ---------------------------------------------------------------- the form

    /** Rebuilds the form from CatalogState. Called on structural changes, not on every keystroke. */
    private fun renderForm() {
        val c = content ?: return
        c.removeAllViews()
        val cat = CatalogState.catalog
        CatalogState.loadError?.let { c.addView(st.hint(it).apply { setTextColor(st.danger) }) }

        c.addView(st.eyebrow("Share Your Apps").help(Help.OVERVIEW))
        c.addView(st.title("Manage Catalog").help(Help.OVERVIEW))
        c.addView(st.hint("Long-press a heading or button for help."))

        c.addView(st.section("Store"))
        c.addView(st.label("Store name").help(Help.STORE_NAME))
        c.addView(field(cat.storeName) { v -> CatalogState.update { it.copy(storeName = v) } })

        c.addView(st.label("Store description (HTML, optional)").help(Help.STORE_DESCRIPTION))
        val storeHtml = htmlNote()
        c.addView(field(cat.storeDescriptionHtml.orEmpty(), lines = 3, mono = true) { v ->
            CatalogState.update { it.copy(storeDescriptionHtml = v.ifBlank { null }) }
            showHtmlProblems(storeHtml, v)
        })
        showHtmlProblems(storeHtml, cat.storeDescriptionHtml.orEmpty())
        c.addView(storeHtml)
        c.addView(st.hint("Shown as-is, unescaped, under the store name on the buyer's page."))

        c.addView(st.label("Header image").help(Help.HEADER_IMAGE))
        c.addView(row(
            st.body(cat.headerImage?.let { File(it).name } ?: "Built-in icon (default)").apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) },
            button("Change") { Pickers.pickMedia(ctx(), "Header image") { p -> CatalogState.update { it.copy(headerImage = p) }; renderForm() } }.help(Help.HEADER_IMAGE),
            button("Default") { CatalogState.update { it.copy(headerImage = null) }; renderForm() }.help(Help.HEADER_IMAGE),
        ))

        // The PIN is at most 6 digits, so its label and field share one line.
        c.addView(row(
            st.label("PIN (optional)").help(Help.PIN).apply { setPadding(0, 0, st.dp(12), 0) },
            // Just wide enough for six digits; the limit itself is in the note below.
            field(cat.pin.orEmpty(), digits = 6, hint = "none") { v -> CatalogState.update { it.copy(pin = v.ifEmpty { null }) } }
                .apply {
                    setEms(5)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                },
        ).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = st.dp(12) }
        })
        c.addView(st.hint("Up to 6 digits. Buyers enter this before reaching the store. It's sent without encryption."))

        c.addView(row(
            st.section("Products (${cat.products.size})").apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }.help(Help.PRODUCTS),
            button("+ Add product") {
                CatalogState.update { it.copy(products = it.products + Product("New product", null, null, true, false, emptyList(), null, Instant.now().toString(), it.products.size + 1)) }
                CatalogState.expanded = CatalogState.catalog.products.size - 1
                renderForm()
            }.help(Help.PRODUCTS),
        ))
        cat.products.forEachIndexed { i, p -> c.addView(productCard(i, p)) }
        if (cat.products.isEmpty()) c.addView(st.hint("No products yet. Tap + Add product to add one."))

        c.addView(st.section("Advanced"))
        c.addView(st.label("Template override (Pebble, optional)").help(Help.TEMPLATE))
        c.addView(field(cat.templateOverride.orEmpty(), lines = 4, mono = true, hint = "Leave empty to use the default template") { v ->
            CatalogState.update { it.copy(templateOverride = v.ifBlank { null }) }
        })
        // Both labels are long, so the two buttons share the row equally and wrap rather than run off a small screen.
        c.addView(row(button("Choose a template file") {
            Pickers.pickTemplate(ctx()) { rel -> loadTemplateFile(rel) }
        }.help(Help.TEMPLATE), button("Load the default template") {
            CatalogState.update { it.copy(templateOverride = Templates.DEFAULT_CATALOG) }; renderForm()
        }.help(Help.TEMPLATE)).apply {
            for (i in 0 until childCount) getChildAt(i).layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (i > 0) marginStart = st.dp(8) }
            // A little space between the template field and the buttons.
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = st.dp(8) }
        })
        c.addView(st.hint("When this isn't empty, it replaces the default template."))

        c.addView(st.label("Extra JSON (optional)").help(Help.EXTRA))
        val extraNote = st.hint("")
        fun showExtraNote() {
            val err = CatalogState.extraError
            extraNote.text = err ?: "Passed into the template as extra."
            extraNote.setTextColor(if (err != null) st.danger else st.muted)
        }
        c.addView(field(CatalogState.extraText, lines = 3, mono = true) { v -> CatalogState.setExtra(v); showExtraNote() })
        showExtraNote()
        c.addView(extraNote)
        c.addView(versionLine())
        renderPublish()
    }

    /** Which build this is, and which server it bundles: useful when phones must match. */
    private fun versionLine(): View {
        val built = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(BuildConfig.BUILD_TIME))
        val base = "Share Your Apps ${BuildConfig.VERSION_NAME} · built $built"
        val line = st.hint(bundledServer?.let { "$base · with Local Store Server $it" } ?: base).apply { setPadding(0, st.dp(16), 0, 0) }
        if (bundledServer == null) Thread {
            val v = ServerInstaller.bundledVersionName(ctx().applicationContext) ?: return@Thread
            bundledServer = v
            line.post { line.text = "$base · with Local Store Server $v" }
        }.start()
        return line
    }

    private fun productCard(i: Int, p: Product): View {
        val card = st.card(LinearLayout(ctx()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(st.dp(14), st.dp(12), st.dp(14), st.dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = st.dp(10) }
        }) as LinearLayout
        val open = CatalogState.expanded == i
        val name = TextView(ctx()).apply { text = p.name; textSize = 15f; setTextColor(st.ink); setTypeface(typeface, Typeface.BOLD) }
        val summary = TextView(ctx()).apply { textSize = 13f; setPadding(0, st.dp(2), 0, 0) }
        showSummary(summary, p)
        // Only the header toggles the card, so a tap between fields doesn't close it mid-edit.
        val header = LinearLayout(ctx()).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(summary)
            p.descriptionHtml?.let { d ->
                val plain = Html.fromHtml(d, Html.FROM_HTML_MODE_COMPACT).toString().trim()
                if (plain.isNotEmpty() && !open) addView(st.hint(plain).apply { maxLines = 2; textSize = 12f })
                // The description field is hidden while the card is closed, so flag a problem here.
                if (!open && HtmlCheck.problems(d).isNotEmpty()) addView(st.hint("⚠ The description's HTML has a problem. Open the product to see it.").apply {
                    setTextColor(st.danger); textSize = 12f
                })
            }
            setOnClickListener { CatalogState.expanded = if (open) -1 else i; renderForm() }
        }.help(Help.PRODUCTS)
        card.addView(header)
        if (!open) return card

        card.addView(st.label("Name").help(Help.PRODUCT_NAME))
        card.addView(field(p.name) { v -> CatalogState.updateProduct(i) { it.copy(name = v) }; name.text = v })

        card.addView(st.label("Project").help(Help.PRODUCT_PROJECT))
        card.addView(row(
            st.body(p.project?.substringAfterLast('/') ?: "None").apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) },
            button("Choose") { Pickers.pickProject(ctx()) { v -> CatalogState.updateProduct(i) { it.copy(project = v) }; renderForm() } }.help(Help.PRODUCT_PROJECT),
        ))

        card.addView(st.label("APK").help(Help.PRODUCT_APK))
        card.addView(row(
            st.body(p.apk?.let { apkLabel(it) } ?: "None").apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) },
            button("Choose") { Pickers.pickApk(ctx(), p.project) { v -> CatalogState.updateProduct(i) { it.copy(apk = v, apkPublished = v != null) }; renderForm() } }.help(Help.PRODUCT_APK),
        ))

        card.addView(check("Publish the APK", p.apkPublished) { v -> CatalogState.updateProduct(i) { it.copy(apkPublished = v) }; showSummary(summary, CatalogState.catalog.products[i]) }.help(Help.PUBLISH_APK))
        card.addView(check("Publish the source", p.sourceZipPublished) { v -> CatalogState.updateProduct(i) { it.copy(sourceZipPublished = v) }; showSummary(summary, CatalogState.catalog.products[i]) }.help(Help.PUBLISH_SOURCE))

        card.addView(st.label("Pictures and video (${p.carousel.size})").help(Help.PRODUCT_MEDIA))
        p.carousel.forEachIndexed { j, m ->
            val t = p.mediaText[m]
            val marks = listOfNotNull(if (!t?.description.isNullOrBlank()) "described" else null, if (t?.captions != null) "captions" else null)
            card.addView(row(
                st.body(File(m).name + if (marks.isEmpty()) "" else " · " + marks.joinToString(" · ")).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) },
                button("Describe") { describe(i, m) }.help(Help.MEDIA_DESCRIBE),
                button("Remove") { CatalogState.updateProduct(i) { it.copy(carousel = it.carousel.filterIndexed { k, _ -> k != j }, mediaText = it.mediaText - m) }; renderForm() }.help(Help.MEDIA_REMOVE),
            ))
        }
        card.addView(row(button("+ Add media") {
            Pickers.pickMedia(ctx(), "Add media") { v -> CatalogState.updateProduct(i) { it.copy(carousel = it.carousel + v) }; renderForm() }
        }.help(Help.PRODUCT_MEDIA)))

        card.addView(st.label("Description (HTML, optional)").help(Help.PRODUCT_DESCRIPTION))
        val productHtml = htmlNote()
        card.addView(field(p.descriptionHtml.orEmpty(), lines = 3, mono = true) { v ->
            CatalogState.updateProduct(i) { it.copy(descriptionHtml = v.ifBlank { null }) }
            showHtmlProblems(productHtml, v)
        })
        showHtmlProblems(productHtml, p.descriptionHtml.orEmpty())
        card.addView(productHtml)

        // The product's own Extra JSON: only valid JSON reaches the catalog, as for the store's.
        card.addView(st.label("Extra JSON (optional)").help(Help.PRODUCT_EXTRA))
        val productExtraNote = st.hint("")
        fun showProductExtra(error: String?) {
            productExtraNote.text = error ?: "For example, {\"whatsNew\": \"…\", \"license\": \"…\"}. Long-press the heading for the keys templates show."
            productExtraNote.setTextColor(if (error != null) st.danger else st.muted)
        }
        card.addView(field(if (p.extra.length() == 0) "" else p.extra.toString(2), lines = 2, mono = true) { v ->
            val parsed = runCatching { strictJsonObject(v.ifBlank { "{}" }) }
            parsed.getOrNull()?.let { obj -> CatalogState.updateProduct(i) { it.copy(extra = obj) } }
            showProductExtra(parsed.exceptionOrNull()?.let { "Not valid yet (${it.message}). The last valid value is kept." })
        })
        showProductExtra(null)
        card.addView(productExtraNote)

        val last = CatalogState.catalog.products.size - 1
        val up = button("Move up") { move(i, i - 1) }.help(Help.PRODUCT_ORDER)
        val down = button("Move down") { move(i, i + 1) }.help(Help.PRODUCT_ORDER)
        st.enable(up, i > 0)
        st.enable(down, i < last)
        val remove = button("Remove") {
            AlertDialog.Builder(ctx()).setTitle("Remove ${p.name}?")
                .setMessage("It leaves the store at the next publish.")
                .setPositiveButton("Remove") { _, _ ->
                    CatalogState.update { c -> c.copy(products = c.products.filterIndexed { k, _ -> k != i }) }
                    CatalogState.expanded = -1
                    renderForm()
                }
                .setNegativeButton("Cancel", null).show()
        }.help(Help.PRODUCT_ORDER).apply { setTextColor(st.danger) }
        card.addView(row(up, down, remove.help(Help.PRODUCT_ORDER)).apply { setPadding(0, st.dp(12), 0, 0) })
        return card
    }

    private fun htmlNote() = st.hint("").apply { setTextColor(st.danger); visibility = View.GONE }

    /** Warns under a description field when its HTML has unbalanced or misnested tags. It never blocks a publish. */
    private fun showHtmlProblems(note: TextView, html: String) {
        val problems = HtmlCheck.problems(html)
        note.visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
        note.text = when (problems.size) {
            0 -> ""
            1 -> "Check the HTML: ${problems[0]}."
            else -> "Check the HTML:\n" + problems.joinToString("\n") { "• $it" }
        } + if (problems.isEmpty()) "" else "\nThis won't stop a publish, but the page may not look the way you meant."
    }

    private fun move(from: Int, to: Int) {
        CatalogState.update { c ->
            val list = c.products.toMutableList()
            list.add(to, list.removeAt(from))
            c.copy(products = list)
        }
        CatalogState.expanded = to
        renderForm()
    }

    /** "Source ✓  APK ✓ 4.2 MB" — green for what's published, gray for what isn't, like the wireframe. */
    private fun showSummary(view: TextView, p: Product) {
        val src = if (p.sourceZipPublished) "Source ✓" else "Source –"
        val apk = when {
            !p.apkPublished || p.apk == null -> "APK –"
            else -> "APK ✓ " + (File(Pickers.sdcard, p.apk).takeIf { it.isFile }?.let { sizeLabel(it.length()) } ?: "(missing)")
        }
        val draft = if (!p.published) "   draft: not published" else ""
        view.text = "$src    $apk$draft"
        view.setTextColor(if (p.published) st.ok else st.muted)
    }

    private fun apkLabel(rel: String): String {
        val f = File(Pickers.sdcard, rel)
        return if (f.isFile) "${f.name} · ${sizeLabel(f.length())}" else "${f.name} (missing)"
    }

    // ---------------------------------------------------------------- publishing

    private fun onPublish() {
        // Refused before anything starts, so it gets its own dialog: the one below only
        // appears when a publish finishes.
        CatalogState.extraError?.let { Pickers.message(ctx(), "Couldn't publish", "Extra JSON: $it"); return }
        // Never publish a catalog the owner hasn't seen: if the file changed, show it first.
        when (CatalogState.reloadIfChanged()) {
            Reload.RELOAD -> {
                renderForm()
                Pickers.message(ctx(), "Catalog reloaded", "catalog.json was changed outside Share Your Apps, so it has been reloaded. Check it, then publish.")
                return
            }
            Reload.CONFLICT -> renderForm()
            Reload.NONE -> {}
        }
        confirmPublish()
    }

    /** Publishing replaces what buyers see, so it's confirmed first, saying what the store will hold. */
    private fun confirmPublish() {
        val cat = CatalogState.catalog
        val inStore = cat.products.filter { it.leftOutReason == null }
        val leftOut = cat.products.filter { it.leftOutReason != null }.map { it.name }
        val count = when (inStore.size) { 0 -> "no products"; 1 -> "1 product"; else -> "${inStore.size} products" }
        val left = when {
            leftOut.isEmpty() -> ""
            leftOut.size <= 3 -> "\n\nLeft out, with nothing to download: ${leftOut.joinToString(", ")}."
            else -> "\n\nLeft out, with nothing to download: ${leftOut.take(3).joinToString(", ")} and ${leftOut.size - 3} more."
        }
        val name = cat.storeName.trim().ifEmpty { "the store" }
        AlertDialog.Builder(ctx())
            .setTitle("Publish $name?")
            .setMessage("It will have $count.$left\n\nBuyers keep the current version until the new one is ready.")
            .setPositiveButton("Publish") { _, _ -> PublishFlow.publish(CatalogState.snapshot(), CatalogState.extraError, ctx().applicationContext) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderPublish() {
        // The screen stays on while a publish runs: a screen timeout would pause a server in
        // "Pause when the screen turns off" mode, and can slow the zipping down in any mode.
        rootView?.keepScreenOn = PublishFlow.busy
        publish?.let { st.enable(it, !PublishFlow.busy); it.text = if (PublishFlow.busy) "Publishing…" else "Publish" }
        status?.text = PublishFlow.message.ifEmpty { "Changes are saved as you make them. Publish sends them to the store." }
        status?.setTextColor(if (PublishFlow.phase == PublishFlow.Phase.IDLE && PublishFlow.failed) st.danger else st.ink)
        val justFinished = lastPhase != PublishFlow.Phase.IDLE && PublishFlow.phase == PublishFlow.Phase.IDLE
        lastPhase = PublishFlow.phase
        if (justFinished && PublishFlow.failed) Pickers.message(ctx(), "Couldn't publish", PublishFlow.message)

        PublishFlow.takeConfirmIntent()?.let { c ->
            try { confirmLauncher.launch(c) } catch (e: Throwable) {
                // The exception's type too, since some have no message; the full details go to Android's log.
                android.util.Log.e("ShareYourApps", "Couldn't show the install confirmation", e)
                PublishFlow.confirmFailed("${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}")
            }
        }
        val intent = PublishFlow.takeLaunchIntent() ?: return
        try {
            launcher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            PublishFlow.onLaunchResult(0, "Local Store Server isn't installed. Build and install it first.", unchanged = true)
        }
    }

    // ---------------------------------------------------------------- small builders

    /** Loads a chosen .peb file into the template field, or says why it can't. Nothing changes on a problem. */
    private fun loadTemplateFile(rel: String) {
        val f = java.io.File(Pickers.sdcard, rel)
        val problem = when {
            !f.name.endsWith(".peb", ignoreCase = true) -> "Only Pebble templates, ending in .peb, can be loaded."
            !f.isFile -> "The file couldn't be found."
            f.length() > TEMPLATE_LIMIT -> "That file is too large to be a template (over 256 KB)."
            else -> null
        }
        val text = if (problem == null) runCatching { f.readText() }.getOrNull() else null
        when {
            problem != null -> Pickers.message(ctx(), "Choose a template", problem)
            text == null -> Pickers.message(ctx(), "Choose a template", "The file couldn't be read.")
            text.isBlank() -> Pickers.message(ctx(), "Choose a template", "That file is empty.")
            else -> { CatalogState.update { it.copy(templateOverride = text) }; renderForm() }
        }
    }

    /**
     * What a picture or video shows, for buyers who can't see it, and a video's captions (design, Known open
     * items: picture descriptions and video captions). Saved only on Save; Cancel changes nothing.
     */
    private fun describe(i: Int, path: String) {
        val current = CatalogState.catalog.products[i].mediaText[path] ?: MediaText()
        val isVideo = Media.kind(File(path).name) == "video"
        var captions = current.captions
        val pad = st.dp(20)
        val box = LinearLayout(ctx()).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, st.dp(8), pad, 0) }
        box.addView(st.hint("What this item shows, for visually-impaired people. Say what matters for the user choosing the app, not what kind of item it is.").help(Help.MEDIA_DESCRIBE))
        val text = android.widget.EditText(ctx()).apply {
            setText(current.description.orEmpty()); minLines = 2; maxLines = 6
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = "For example: The chat screen, with two messages"
        }
        box.addView(text)
        if (isVideo) {
            val chosen = st.hint(captions?.let { "Captions: " + File(it).name } ?: "No captions")
            box.addView(chosen)
            box.addView(row(
                button("Choose captions file") { Pickers.pickCaptions(ctx()) { c -> captions = c; chosen.text = "Captions: " + File(c).name } }.help(Help.CAPTIONS),
                button("No captions") { captions = null; chosen.text = "No captions" }.help(Help.CAPTIONS),
            ))
        }
        // "Describe", then the item's name in italic.
        val name = File(path).name
        val title = android.text.SpannableString("Describe $name").apply {
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.ITALIC), length - name.length, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        AlertDialog.Builder(ctx()).setTitle(title).setView(box)
            .setPositiveButton("Save") { _, _ ->
                val t = MediaText(text.text.toString().trim().takeIf { it.isNotEmpty() }, captions)
                CatalogState.updateProduct(i) { p -> p.copy(mediaText = if (t.isEmpty) p.mediaText - path else p.mediaText + (path to t)) }
                renderForm()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun button(t: String, onClick: () -> Unit) = st.secondary(Button(ctx())).apply {
        text = t; setOnClickListener { onClick() }
        // 4 dp above and below, so buttons in rows stacked on each other are 8 dp apart.
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = st.dp(8); topMargin = st.dp(4); bottomMargin = st.dp(4) }
    }

    private fun row(vararg views: View) = LinearLayout(ctx()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it) }
    }

    private fun check(t: String, value: Boolean, onChange: (Boolean) -> Unit) = st.check(CheckBox(ctx())).apply {
        text = t; isChecked = value; setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun field(
        value: String, lines: Int = 1, mono: Boolean = false, digits: Int = 0, hint: String? = null,
        onChange: (String) -> Unit,
    ) = EditText(ctx()).apply {
        setText(value)
        if (hint != null) this.hint = hint
        if (mono) typeface = Typeface.MONOSPACE
        if (digits > 0) {
            // Digits only, but kept as text, so a leading zero survives.
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(digits))
        } else if (lines > 1) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = lines
            gravity = Gravity.TOP or Gravity.START
        } else {
            setSingleLine()
        }
        st.input(this)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString().orEmpty()) }
        })
    }
}
