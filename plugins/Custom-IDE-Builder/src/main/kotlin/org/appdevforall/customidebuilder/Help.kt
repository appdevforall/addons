package org.appdevforall.customidebuilder

import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry

/**
 * Tooltips for Custom IDE Builder. The category is `plugin_` plus the plugin ID, exactly, and
 * lookups pass it explicitly to the three-argument showTooltip (plugins/README.md).
 */
object Help {
    const val CATEGORY = "plugin_" + CustomIdeBuilderPlugin.PLUGIN_ID

    const val MENU = "customidebuilder.menu"
    const val ADDONS = "customidebuilder.addons"
    const val WEBSITE = "customidebuilder.website"
    const val KEYSTORE = "customidebuilder.keystore"
    const val FOLDER = "customidebuilder.folder"
    const val BUILD = "customidebuilder.build"

    private val more = listOf(PluginTooltipButton(description = "About Custom IDE Builder", uri = "index.html", order = 0))

    val entries = listOf(
        PluginTooltipEntry(
            MENU,
            "Make a copy of Code on the Go with the add-ons you choose, signed with your own key.",
            "<p>Builds a copy of the Code on the Go installed on this device, with the plugins and templates " +
                "you pick bundled inside, and signs it with your keystore.</p>" +
                "<p>The copy installs only after Code on the Go is uninstalled, and uninstalling erases the " +
                "projects stored in the app's data.</p>",
            more,
        ),
        PluginTooltipEntry(
            ADDONS,
            "Choose the plugins (.cgp) and templates (.cgt) to bundle.",
            "<p>Pick files from this device, or download them from the add-ons website. Each plugin must have " +
                "a different plugin ID, and each template a different file name. With nothing chosen the copy " +
                "carries no add-ons.</p>",
            more,
        ),
        PluginTooltipEntry(
            WEBSITE,
            "Download add-ons from addons.appdevforall.org.",
            "<p>This is the only part of the plugin that uses the internet. You are asked first. The list shows " +
                "the plugins and templates that work with this version of Code on the Go, and every download is " +
                "checked against the size and SHA-256 the catalog publishes.</p>",
            more,
        ),
        PluginTooltipEntry(
            KEYSTORE,
            "The key the copy is signed with: a PKCS12 or BKS keystore.",
            "<p>Passwords are used for this visit only and are never saved. A JKS file must be converted first " +
                "with <code>keytool -importkeystore</code>. Use the same key for every later build, or devices " +
                "that have an earlier build must uninstall it first.</p>",
            more,
        ),
        PluginTooltipEntry(
            FOLDER,
            "The folder the signed APK is saved in.",
            "<p>Chosen once and remembered. The file is named after the app and its version, ending in " +
                "<code>-custom.apk</code>.</p>",
            more,
        ),
        PluginTooltipEntry(
            BUILD,
            "Copy the app, add the add-ons, sign it and save it.",
            "<p>Runs in the background. The app is copied without recompressing, so it takes about as long as " +
                "copying the file, plus the time to sign it.</p>",
            more,
        ),
    )
}
