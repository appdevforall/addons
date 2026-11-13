package dev.omar.plugin.iconsrepo.utils;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class DynamicColorHelper {

    private DynamicColorHelper() {
        // Prevent instantiation
    }

    public static ColorReferenceModel getColorModel(int resAttr) {
        for (ColorReferenceModel model : getDynamicColors()) {
            if (resAttr == model.getAttrId()) {
                return model;
            }
        }
        return getDynamicColors().get(0);
    }

    @NonNull
    public static ColorReferenceModel getDefaultColorModel() {
        List<ColorReferenceModel> colors = getDynamicColors();
        for (ColorReferenceModel model : colors) {
            if ("colorOnSurface".equals(model.getDisplayName())) {
                return model;
            }
        }
        return colors.get(0);
    }

    @NonNull
    public static List<ColorReferenceModel> getDynamicColors() {
        return Arrays.asList(
                new ColorReferenceModel(
                        "?attr/colorControlNormal",
                        android.R.attr.colorControlNormal,
                        "colorControlNormal"),
                new ColorReferenceModel(
                        "?attr/colorPrimary", android.R.attr.colorPrimary, "colorPrimary"),
                new ColorReferenceModel(
                        "?attr/colorOnPrimary",
                        com.google.android.material.R.attr.colorOnPrimary,
                        "colorOnPrimary"),
                new ColorReferenceModel(
                        "?attr/colorPrimaryContainer",
                        com.google.android.material.R.attr.colorPrimaryContainer,
                        "colorPrimaryContainer"),
                new ColorReferenceModel(
                        "?attr/colorOnPrimaryContainer",
                        com.google.android.material.R.attr.colorOnPrimaryContainer,
                        "colorOnPrimaryContainer"),
                new ColorReferenceModel(
                        "?attr/colorSecondary",
                        com.google.android.material.R.attr.colorSecondary,
                        "colorSecondary"),
                new ColorReferenceModel(
                        "?attr/colorOnSecondary",
                        com.google.android.material.R.attr.colorOnSecondary,
                        "colorOnSecondary"),
                new ColorReferenceModel(
                        "?attr/colorSecondaryContainer",
                        com.google.android.material.R.attr.colorSecondaryContainer,
                        "colorSecondaryContainer"),
                new ColorReferenceModel(
                        "?attr/colorOnSecondaryContainer",
                        com.google.android.material.R.attr.colorOnSecondaryContainer,
                        "colorOnSecondaryContainer"),
                new ColorReferenceModel(
                        "?attr/colorTertiary",
                        com.google.android.material.R.attr.colorTertiary,
                        "colorTertiary"),
                new ColorReferenceModel(
                        "?attr/colorOnTertiary",
                        com.google.android.material.R.attr.colorOnTertiary,
                        "colorOnTertiary"),
                new ColorReferenceModel(
                        "?attr/colorTertiaryContainer",
                        com.google.android.material.R.attr.colorTertiaryContainer,
                        "colorTertiaryContainer"),
                new ColorReferenceModel(
                        "?attr/colorOnTertiaryContainer",
                        com.google.android.material.R.attr.colorOnTertiaryContainer,
                        "colorOnTertiaryContainer"),
                new ColorReferenceModel(
                        "?attr/colorError", android.R.attr.colorError, "colorError"),
                new ColorReferenceModel(
                        "?attr/colorOnError",
                        com.google.android.material.R.attr.colorOnError,
                        "colorOnError"),
                new ColorReferenceModel(
                        "?attr/colorErrorContainer",
                        com.google.android.material.R.attr.colorErrorContainer,
                        "colorErrorContainer"),
                new ColorReferenceModel(
                        "?attr/colorOnErrorContainer",
                        com.google.android.material.R.attr.colorOnErrorContainer,
                        "colorOnErrorContainer"),
                new ColorReferenceModel(
                        "?attr/colorSurface",
                        com.google.android.material.R.attr.colorSurface,
                        "colorSurface"),
                new ColorReferenceModel(
                        "?attr/colorOnSurface",
                        com.google.android.material.R.attr.colorOnSurface,
                        "colorOnSurface"),
                new ColorReferenceModel(
                        "?attr/colorSurfaceVariant",
                        com.google.android.material.R.attr.colorSurfaceVariant,
                        "colorSurfaceVariant"),
                new ColorReferenceModel(
                        "?attr/colorOnSurfaceVariant",
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                        "colorOnSurfaceVariant"),
                new ColorReferenceModel(
                        "?attr/colorOutline",
                        com.google.android.material.R.attr.colorOutline,
                        "colorOutline"),
                new ColorReferenceModel(
                        "?attr/colorOutlineVariant",
                        com.google.android.material.R.attr.colorOutlineVariant,
                        "colorOutlineVariant"));
    }

    /**
     * Resolves a dynamic color attribute by its string reference (e.g., "?attr/colorPrimary").
     *
     * @param model The model reference of {@link ColorReferenceModel}.
     * @param context The context used to access the theme.
     * @return The resolved color integer, or {@link Color#BLACK} if resolution fails.
     */
    public static int resolveDynamicColor(Context context, @NonNull ColorReferenceModel model) {
        if (context != null) {
            Integer attrId = model.getAttrId();
            if (attrId != null) {
                TypedValue typedValue = new TypedValue();
                if (context.getTheme().resolveAttribute(attrId, typedValue, true)) {
                    if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT
                            && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                        return typedValue.data;
                    }
                }
            }
        }
        return Color.BLACK;
    }

    /**
     * Returns a list of display names for all dynamic colors. Useful for populating a Spinner or
     * similar UI component.
     */
    @NonNull
    public static List<String> getDisplayNames() {
        List<ColorReferenceModel> colors = getDynamicColors();
        List<String> displayNames = new ArrayList<>(colors.size());
        for (ColorReferenceModel color : colors) {
            displayNames.add(color.getDisplayName());
        }
        return displayNames;
    }

    // Inner class representing a color reference
    public static class ColorReferenceModel {
        private final String ref;
        private final Integer attrId;
        private final String displayName;

        public ColorReferenceModel(String ref, Integer attrId, String displayName) {
            this.ref = ref;
            this.attrId = attrId;
            this.displayName = displayName;
        }

        public String getRef() {
            return ref;
        }

        public Integer getAttrId() {
            return attrId;
        }

        public String getDisplayName() {
            return displayName;
        }

        @NonNull
        @Override
        public String toString() {
            return "ColorReferenceModel{"
                    + "ref='"
                    + ref
                    + '\''
                    + ", attrId="
                    + attrId
                    + ", displayName='"
                    + displayName
                    + '\''
                    + '}';
        }
    }
}
