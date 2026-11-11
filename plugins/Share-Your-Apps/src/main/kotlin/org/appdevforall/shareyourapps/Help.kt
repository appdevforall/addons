package org.appdevforall.shareyourapps

import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry

/**
 * Tooltips for Manage Catalog. CodeOnTheGo stores these under the category
 * `plugin_org.appdevforall.shareyourapps`, derived from the plugin ID, and resolves the category
 * itself when one is shown, so tags only need to be unique within this plugin.
 * Summary is the one-line tooltip; detail is what "See more" shows.
 */
object Help {
    const val OVERVIEW = "shareyourapps.overview"
    const val STORE_NAME = "shareyourapps.store_name"
    const val STORE_DESCRIPTION = "shareyourapps.store_description"
    const val HEADER_IMAGE = "shareyourapps.header_image"
    const val PIN = "shareyourapps.pin"
    const val PRODUCTS = "shareyourapps.products"
    const val PRODUCT_PROJECT = "shareyourapps.product_project"
    const val PRODUCT_APK = "shareyourapps.product_apk"
    const val PUBLISH_APK = "shareyourapps.publish_apk"
    const val PUBLISH_SOURCE = "shareyourapps.publish_source"
    const val PRODUCT_MEDIA = "shareyourapps.product_media"
    const val MEDIA_DESCRIBE = "shareyourapps.media_describe"
    const val MEDIA_REMOVE = "shareyourapps.media_remove"
    const val PRODUCT_NAME = "shareyourapps.product_name"
    const val CAPTIONS = "shareyourapps.captions"
    const val PRODUCT_DESCRIPTION = "shareyourapps.product_description"
    const val PRODUCT_ORDER = "shareyourapps.product_order"
    const val TEMPLATE = "shareyourapps.template"
    const val EXTRA = "shareyourapps.extra"
    const val PRODUCT_EXTRA = "shareyourapps.product_extra"
    const val PUBLISH = "shareyourapps.publish"

    val entries = listOf(
        e(OVERVIEW, "<b>Share Your Apps</b><br>Share your apps and their source with people nearby.",
            "<p>Share Your Apps turns your projects into a small web store that people on the same Wi-Fi network " +
                "or hotspot can open in their phone's browser, to download your APKs and source code. " +
                "No internet or accounts are needed.</p>" +
                "<p>Fill in the store's details and products here, then tap <b>Publish</b>. The Share Your Apps " +
                "Server app serves what you publish; its Serve screen shows the codes people scan to reach it.</p>" +
                "<p>Changes are saved as you make them, but buyers only see them after you publish.</p>"),
        e(STORE_NAME, "The name at the top of your store, and on its PIN page.",
            "<p>Shown as the heading of the store page, and on the PIN page if you set a PIN.</p>"),
        e(STORE_DESCRIPTION, "Optional HTML shown under the store name.",
            "<p>Write it as HTML, for example <code>&lt;p&gt;Everything here is free.&lt;/p&gt;</code>. " +
                "It's placed on the page exactly as written, so it can include links and formatting.</p>" +
                "<p>Tag mistakes — a tag opened but never closed, closed but never opened, or closed in the wrong order — " +
                "are shown under the field as you type. They don't stop a publish.</p>"),
        e(HEADER_IMAGE, "The picture at the top of your store.",
            "<p>Choose an image or video from your phone, or tap <b>Default</b> to use the built-in icon. " +
                "Publishing copies it into the store, so you can change or delete the original afterward.</p>"),
        e(PIN, "Optional: a PIN buyers must enter before they can see the store.",
            "<p>Up to 6 digits. Leave it empty for an open store.</p>" +
                "<p>After entering the PIN, a buyer's browser stays signed in for 24 hours. Changing the PIN " +
                "doesn't sign anyone out.</p>" +
                "<p>The store doesn't use encryption, so treat the PIN as a way to keep casual visitors out, not " +
                "as real security.</p>"),
        e(PRODUCTS, "The apps in your store, in the order buyers see them.",
            "<p>Tap <b>+ Add product</b> to add one, and tap a product's name to open or close its details. " +
                "A product marked <i>draft</i> has nothing to download yet — no APK chosen with Publish the APK ticked, and " +
                "Publish the source unticked — so it's left out of the store. The message after publishing names any product left out, and why.</p>"),
        e(PRODUCT_PROJECT, "The CodeOnTheGo project this product comes from.",
            "<p>Needed for publishing the source, and for finding the project's APKs.</p>"),
        e(PRODUCT_APK, "The APK buyers download and install.",
            "<p>The list shows the APKs this project has built, newest first. Build the project in " +
                "CodeOnTheGo if it's empty.</p>"),
        e(PUBLISH_APK, "Include the APK in the store.",
            "<p>When this is off, the product has no Download APK button.</p>"),
        e(PUBLISH_SOURCE, "Include the project's source code in the store, as a zip.",
            "<p>The zip leaves out build output, caches, IDE settings, and signing files such as keystores and " +
                "<code>release.properties</code>, along with anything your <code>.gitignore</code> excludes.</p>" +
                "<p>Check that the project doesn't contain passwords or keys in other files before you publish it.</p>"),
        e(PRODUCT_MEDIA, "Pictures and videos shown for this product.",
            "<p>They appear in a row that buyers can swipe through. Publishing copies them into the store.</p>"),
        e(PRODUCT_NAME, "The product's name, as buyers see it.",
            "<p>It heads the product's card on the store page, and names the product's download files, " +
                "such as <code>todo-app.apk</code>. Two products can share a name: the second's files get a number, such as <code>todo-app-2</code>.</p>"),
        e(MEDIA_REMOVE, "Take this picture or video out of the product.",
            "<p>It leaves the store at your next publish, and its description and captions go with it. The file itself stays on your phone.</p>"),
        e(CAPTIONS, "Add captions to this video, for buyers who can't hear it, or remove them.",
            "<p><b>Choose captions file</b> picks a WebVTT file, ending <code>.vtt</code>: text with the time each line appears. " +
                "Publishing copies it beside the video, and buyers can turn the captions on in the video's menu or its <b>CC</b> button. " +
                "<b>No captions</b> removes them. Either takes effect when you tap <b>Save</b>.</p>"),
        e(MEDIA_DESCRIBE, "Say what a picture or video shows, for buyers who can't see it.",
            "<p>Screen readers read the description aloud, in place of the picture. Say what matters about it for choosing the app — " +
                "<i>The chat screen, with two messages</i> — not what kind of item it is. Without one, the page says only which picture it is: " +
                "<i>Termux, picture 1 of 3</i>.</p><p>For a video, you can also add captions: a WebVTT file, ending <code>.vtt</code>, " +
                "which the store shows under the video for buyers who can't hear it.</p>"),
        e(PRODUCT_DESCRIPTION, "Optional HTML describing this product.",
            "<p>Written as HTML and placed on the page exactly as written. Tag mistakes, such as a tag opened but never " +
                "closed, are shown under the field as you type, and on the product's card when it's closed. They don't stop a publish.</p>"),
        e(PRODUCT_ORDER, "Move this product up or down the list, or remove it.",
            "<p>Removing a product takes it out of the store at your next publish.</p>"),
        e(TEMPLATE, "Optional: your own Pebble template for the store page.",
            "<p>Leave it empty to use the built-in design. Tap <b>Load the default template</b> to start from " +
                "the built-in one and change it, or <b>Choose a template file</b> to load a Pebble template " +
                "(a <code>.peb</code> file) from your phone, starting in Download. Either replaces what's in the field.</p>" +
                "<p>A template that can't be rendered stops the publish and shows the error, so a mistake here " +
                "never reaches buyers.</p>"),
        e(EXTRA, "Optional JSON your template can use, as extra.",
            "<p>It must be a JSON object, in <code>{ }</code>. A template can then use, for example, " +
                "<code>{{ extra.motto }}</code>. You can't publish while it isn't valid.</p>" +
                "<p>Every template understands these keys:</p><ul>" +
                "<li><code>\"lang\"</code>: the store's language, such as <code>\"sw\"</code>. Without it, the phone's is used.</li>" +
                "<li><code>\"contact\"</code>: <code>{\"name\": …, \"email\": …, \"phone\": …, \"url\": …}</code>, shown at the foot of the page, " +
                "so buyers can reach you. Any part may be left out.</li>" +
                "<li><code>\"announcement\"</code>: a short line of news, shown above the products.</li>" +
                "<li><code>\"accent\"</code>: a colour such as <code>\"#1E88E5\"</code>, for the download buttons. " +
                "Text on it is black or white, whichever reads better. The accessible version keeps its own colours.</li></ul>"),
        e(PRODUCT_EXTRA, "Optional JSON about this product, for your template.",
            "<p>It must be a JSON object, in <code>{ }</code>. Every template shows these keys:</p><ul>" +
                "<li><code>\"whatsNew\"</code>: what's changed in this version.</li>" +
                "<li><code>\"license\"</code>: the licence, such as <code>\"GPL-3.0-or-later\"</code> — worth giving " +
                "when you publish the source.</li>" +
                "<li><code>\"author\"</code>: who made it.</li>" +
                "<li><code>\"website\"</code>: an https:// address.</li></ul>" +
                "<p>Other keys reach your own template as <code>{{ p.extra.yourKey }}</code>.</p>"),
        e(PUBLISH, "Send your catalog to the store.",
            "<p>Builds a new copy of the store, starts the Share Your Apps Server on it, checks that everything is " +
                "being served correctly, and then deletes the previous copy.</p>" +
                "<p>If anything goes wrong, nothing changes: the store keeps showing what you published last.</p>"),
    )

    /** Each entry's tier-3 button, to its section of the plugin's offline pages (HelpPages). */
    private fun e(tag: String, summary: String, detail: String) = PluginTooltipEntry(tag, summary, detail,
        listOfNotNull(HelpPages.buttons[tag]?.let { (label, uri) -> com.itsaky.androidide.plugins.extensions.PluginTooltipButton(label, uri, 0, false) }))
}
