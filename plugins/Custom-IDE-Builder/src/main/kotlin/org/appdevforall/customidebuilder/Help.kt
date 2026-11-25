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
    const val NAME = "customidebuilder.name"
    const val ICON = "customidebuilder.icon"
    const val CONFIRM = "customidebuilder.confirm"
    const val APP_NAME = "customidebuilder.appname"
    const val MINSDK = "customidebuilder.minsdk"
    const val FOLDER = "customidebuilder.folder"
    const val BUILD = "customidebuilder.build"
    const val REMOVE = "customidebuilder.remove"
    const val CANCEL = "customidebuilder.cancel"
    const val STORE_PASSWORD = "customidebuilder.storepassword"
    const val KEY_PASSWORD = "customidebuilder.keypassword"
    const val KEY_ALIAS = "customidebuilder.keyalias"
    const val STATUS = "customidebuilder.status"
    const val RESULT = "customidebuilder.result"
    const val SAME_KEY = "customidebuilder.samekey"
    const val NAME_TAKEN = "customidebuilder.nametaken"
    const val NAME_FIELD = "customidebuilder.namefield"

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
            NAME,
            "Give the copy a name and an icon of your own. Both are required.",
            "<p>The name replaces <i>Code on the Go</i> on the launcher, in the app's own screens and in notifications. " +
                "The icon replaces the launcher icon, the splash screen picture, the logo on the app's own screens and the " +
                "status-bar icon; the status-bar " +
                "icon is a white outline made from your picture, so a clear shape on a plain or transparent background " +
                "works best. Text that must keep naming Code on the Go, such as the credit line added to Git commits, " +
                "is left as it is.</p>" +
                "<p>A name close to <i>Code on the Go</i> or its short form, an empty name, or one over 50 characters is " +
                "refused, and so is a picture that looks like Code on the Go's own icon. A build needs a name, an " +
                "icon and the confirmation box ticked.</p>",
            more,
        ),
        PluginTooltipEntry(
            ICON,
            "Choose a picture for the icon (required). It is cropped to a square.",
            "<p>Any picture Android can open, up to 25 MB. The builder makes the launcher, splash and status-bar " +
                "icons and the in-app logo from it at every screen density. Nothing is uploaded.</p>",
            more,
        ),
        PluginTooltipEntry(
            CONFIRM,
            "Required for every build.",
            "<p>Tick it to say the name and picture are yours to use and are not Code on the Go's. The box clears " +
                "whenever you change the name or the picture.</p>",
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
                "<code>-custom.apk</code>. If the folder already has a file with that name, you are asked for " +
                "another one.</p>",
            more,
        ),
        PluginTooltipEntry(
            BUILD,
            "Copy the app, add the add-ons, sign it and save it.",
            "<p>Runs in the background. The app is copied without recompressing, so it takes about as long as " +
                "copying the file, plus the time to sign it. The keystore is read again for the build, and the " +
                "build stops if the key in it is no longer the one whose fingerprint was shown.</p>",
            more,
        ),
        PluginTooltipEntry(
            REMOVE,
            "Take this add-on off the list.",
            "<p>Removes it from the copy you are about to build and deletes the copy this plugin made of the " +
                "file. The original file is not touched.</p>",
            more,
        ),
        PluginTooltipEntry(
            CANCEL,
            "Stop the build or download in progress.",
            "<p>A cancelled or failed build saves nothing to your output folder: an unfinished APK that was already " +
                "being written there is deleted, and the temporary files are deleted too. Your add-on list and keystore choice stay as they are.</p>",
            more,
        ),
        PluginTooltipEntry(
            STORE_PASSWORD,
            "The password that opens the keystore file.",
            "<p>Used to read the keys and again for the build. It is never saved: the copy used for a task is " +
                "erased when the task ends, however it ends, and the field is cleared when a build ends and " +
                "when you leave this screen.</p>",
            more,
        ),
        PluginTooltipEntry(
            KEY_PASSWORD,
            "The password of the chosen key. Leave it blank if it is the store password.",
            "<p>PKCS12 keystores usually use one password for both. It is never saved: the copy used for a task " +
                "is erased when the task ends, however it ends, and the field is cleared when a build ends and " +
                "when you leave this screen.</p>",
            more,
        ),
        PluginTooltipEntry(
            KEY_ALIAS,
            "Which key in the keystore signs the copy.",
            "<p>Shown after you tap Read keys. The line below the list is the certificate fingerprint " +
                "(SHA-256) of the chosen key, so you can check it is the key you used before. Use the same key " +
                "for every build.</p>",
            more,
        ),
        PluginTooltipEntry(
            APP_NAME,
            "The name the copy shows on the launcher. Required.",
            "<p>Needed for every build. At most 50 characters, and not close to <i>Code on the Go</i> or its short form. The line under " +
                "the field says what is wrong with a name that is refused. Changing the name clears the " +
                "confirmation box.</p>",
            more,
        ),
        PluginTooltipEntry(
            MINSDK,
            "This plugin may not work on every device the copy installs on.",
            "<p>The plugin asks for a newer Android than this copy of Code on the Go needs, or asks in a way this " +
                "builder could not read. Keep it if everyone who will install the copy has a new enough device, " +
                "or tap Remove. The warning names this device's Android when it is older too.</p>",
            more,
        ),
        PluginTooltipEntry(
            STATUS,
            "What the plugin is doing now, and how far along it is.",
            "<p>Shows the current step of a download or a build, with a progress bar. Tap Cancel to stop it. " +
                "Nothing is shown here when nothing is running.</p>",
            more,
        ),
        PluginTooltipEntry(
            RESULT,
            "The latest result or problem. Press and hold the text to copy it.",
            "<p>Shows how the last task ended: where the finished APK was saved and the certificate it was " +
                "signed with, or what went wrong and what to try. It is cleared when the next task starts.</p>",
            more,
        ),
        PluginTooltipEntry(
            SAME_KEY,
            "Why every build should be signed with the same key.",
            "<p>Android installs an update only if it is signed with the same key as the version already " +
                "installed. A device that has a build signed with another key must uninstall it first, and " +
                "uninstalling Code on the Go erases the projects stored in its data.</p>",
            more,
        ),
        PluginTooltipEntry(
            NAME_TAKEN,
            "The folder already has a file with this name. Choose another, or cancel the save.",
            "<p>The plugin never lets the folder rename your APK on its own. It looks for the name when you tap " +
                "Build, and again just before saving, because a file can appear while the app is being built. " +
                "If the name is taken you are asked for another one; a free name is filled in for you.</p>" +
                "<p>Save keeps the name you typed (spaces and other odd characters become dashes, and " +
                "<code>.apk</code> is added). Cancel stops: nothing is built, or the APK that was built is deleted. " +
                "The build waits for your answer, also if you leave this screen and come back.</p>",
            more,
        ),
        PluginTooltipEntry(
            NAME_FIELD,
            "The file name for the APK, ending in .apk.",
            "<p>Letters, digits, dot, underscore and dash. Anything else becomes a dash. The name is checked " +
                "again against the folder when you tap Save.</p>",
            more,
        ),
    )
}
