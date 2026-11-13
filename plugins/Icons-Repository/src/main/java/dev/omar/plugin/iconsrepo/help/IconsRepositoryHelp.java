package dev.omar.plugin.iconsrepo.help;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textfield.TextInputLayout;
import com.itsaky.androidide.plugins.ServiceRegistry;
import com.itsaky.androidide.plugins.base.PluginFragmentHelper;
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton;
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry;
import com.itsaky.androidide.plugins.services.IdeTooltipService;

import dev.omar.plugin.iconsrepo.Main;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Tooltip entries (Tier 1 and 2), the Tier 3 page, and long-press wiring for every UI element. */
public final class IconsRepositoryHelp {

    /** The host resolves plugin tooltips under exactly "plugin_" + plugin.id. */
    public static final String CATEGORY = "plugin_" + Main.PLUGIN_ID;

    /** Assets subdirectory holding the Tier 3 page. */
    public static final String DOCS_ASSET_PATH = "docs";

    public static final String TAG_MAIN = "icons_repository.main";
    public static final String TAG_SEARCH = "icons_repository.search";
    public static final String TAG_ICON = "icons_repository.icon";
    public static final String TAG_NAME = "icons_repository.name";
    public static final String TAG_MODULE = "icons_repository.module";
    public static final String TAG_COLOR = "icons_repository.color";
    public static final String TAG_IMPORT = "icons_repository.import";

    /** Literal tooltip for the editor tab, which takes text rather than a tag. */
    public static final String EDITOR_TAB_TOOLTIP =
            "Browse the Material icon catalog and add icons to your project as vector drawables.";

    private IconsRepositoryHelp() {}

    @NonNull
    public static List<PluginTooltipEntry> entries() {
        return Arrays.asList(
                entry(
                        TAG_MAIN,
                        "Browse Material icons and add them to your project as vector drawables.",
                        "<p>Opens the <b>Icons Repository</b> tab: a searchable grid of the"
                                + " bundled Material icons, which work offline.</p>"
                                + "<p>Tap an icon to import it. You choose the drawable name,"
                                + " the module and an optional tint, and the plugin writes a"
                                + " 24dp vector drawable into that module's"
                                + " <code>res/drawable</code> folder.</p>",
                        "Icons Repository guide",
                        "index.html"),
                entry(
                        TAG_SEARCH,
                        "Type to filter the icon grid by name.",
                        "<p>Matches icon names as you type, for example <code>arrow</code> or"
                                + " <code>home</code>. The clear button empties the field and"
                                + " shows every icon again.</p>",
                        "Searching icons",
                        "index.html"),
                entry(
                        TAG_ICON,
                        "Tap an icon to import it into your project.",
                        "<p>Tapping an icon opens the import dialog, where you set the"
                                + " drawable name, the target module and the tint before"
                                + " anything is written.</p>",
                        "Importing an icon",
                        "index.html"),
                entry(
                        TAG_NAME,
                        "The drawable's resource name, used as the file name.",
                        "<p>Use lowercase letters, digits and single underscores, starting"
                                + " with a letter, for example <code>ic_arrow_back</code>. The"
                                + " icon is saved as <code>res/drawable/&lt;name&gt;.xml</code>."
                                + " A file with the same name is replaced.</p>",
                        "Naming the drawable",
                        "index.html"),
                entry(
                        TAG_MODULE,
                        "Which module's res/drawable folder receives the icon.",
                        "<p><b>All modules</b> writes a copy into every Android module the"
                                + " plugin finds. Pick a single module to write it only"
                                + " there.</p>",
                        "Choosing a module",
                        "index.html"),
                entry(
                        TAG_COLOR,
                        "The tint applied to the icon: none, a theme color, or a custom hex value.",
                        "<p><b>None</b> leaves the icon untinted (black)."
                                + " A theme color such as <code>colorPrimary</code> is written as"
                                + " <code>?attr/colorPrimary</code>, so the icon follows the app"
                                + " theme. <b>Custom</b> takes a hex value in"
                                + " <code>#RRGGBB</code> or <code>#AARRGGBB</code> form.</p>",
                        "Choosing a tint",
                        "index.html"),
                entry(
                        TAG_IMPORT,
                        "Import writes the drawable; Cancel closes the dialog without changes.",
                        "<p>The preview shows the icon with the chosen tint. <b>Import</b>"
                                + " checks the name and color, then writes the file. If"
                                + " something is wrong, the field shows an error and nothing"
                                + " is written.</p>",
                        "Importing an icon",
                        "index.html"));
    }

    @NonNull
    private static PluginTooltipEntry entry(
            @NonNull String tag,
            @NonNull String summary,
            @NonNull String detail,
            @NonNull String buttonLabel,
            @NonNull String uri) {
        return new PluginTooltipEntry(
                tag,
                summary,
                detail,
                Collections.singletonList(new PluginTooltipButton(buttonLabel, uri, 0, false)));
    }

    /** Long-press on {@code view} shows the tooltip for {@code tag}. */
    public static void wire(@Nullable View view, @NonNull String tag) {
        if (view == null) return;
        view.setOnLongClickListener(anchor -> show(anchor, tag));
    }

    /**
     * Long-press on the field's start and end icons shows the tooltip for {@code tag}. The text
     * field itself keeps long-press for text selection.
     */
    public static void wireFieldIcons(@Nullable TextInputLayout layout, @NonNull String tag) {
        if (layout == null) return;
        layout.setStartIconOnLongClickListener(anchor -> show(anchor, tag));
        layout.setEndIconOnLongClickListener(anchor -> show(anchor, tag));
    }

    private static boolean show(@NonNull View anchor, @NonNull String tag) {
        IdeTooltipService service = tooltipService();
        if (service == null) return false;
        service.showTooltip(anchor, CATEGORY, tag);
        return true;
    }

    @Nullable
    private static IdeTooltipService tooltipService() {
        try {
            ServiceRegistry registry = PluginFragmentHelper.getServiceRegistry(Main.PLUGIN_ID);
            return registry != null ? registry.get(IdeTooltipService.class) : null;
        } catch (Exception ignored) {
            // Help is optional; without the service a long-press simply does nothing.
            return null;
        }
    }
}
