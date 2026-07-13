package org.appdevforall.maps.ui

import android.view.LayoutInflater
import androidx.fragment.app.Fragment
import org.appdevforall.maps.MapsPlugin
import com.itsaky.androidide.plugins.base.PluginFragmentHelper

/**
 * Returns the plugin-resource-aware inflater every Maps Fragment must use.
 *
 * [PluginFragmentHelper.getPluginInflater] hands back an inflater whose context
 * (`PluginResourceContext` host-side) can resolve plugin-side R.layout / R.id /
 * R.drawable — without it, plugin layouts are unknown to the host's resource
 * loader. The host also applies our theme for us: it looks up the style named
 * `PluginTheme` in the plugin package and merges it onto the context theme
 * (CoGo convention since ADFA-3260).
 *
 * ## Why this does NOT wrap the inflater in a ContextThemeWrapper
 *
 * An earlier revision wrapped the plugin inflater in a
 * `ContextThemeWrapper(plugin.context, R.style.PluginTheme)` +
 * `cloneInContext(...)`, expecting M3 color attrs (`?attr/colorPrimary`, …) to
 * resolve against the plugin palette. On-device (A56, 2026-07-06) that can't
 * work, and is actively harmful:
 *
 *  1. **Attr-namespace wall.** The plugin-builder compiles plugin resources at
 *     a custom package id (Maps: 0x5d), while `MaterialButton` & friends load
 *     from the HOST APK (parent-first plugin classloader) and resolve M3 attrs
 *     by the HOST's 0x7f attr ids. A plugin-side theme can never supply those —
 *     no wrapper changes that. The only routes through the wall are framework
 *     `android:*` attrs (shared 0x01 namespace) and programmatic
 *     ColorStateLists; this plugin deliberately uses NEITHER for Material
 *     widgets and lets the host color them, matching every other plugin in
 *     this repo (see the note in values/styles.xml).
 *  2. **The wrapper degrades the merged theme.** The host's
 *     `PluginResourceContext.getTheme()` starts from the host Activity theme
 *     (`setTo(actTheme)`) then applies `PluginTheme` on top — so host-namespace
 *     attrs keep sane host values. A `ContextThemeWrapper` built from a bare
 *     `newTheme()` LOSES those host values, dropping Material widgets to
 *     M3-baseline defaults (the lavender/purple buttons in the 2026-07-06
 *     screenshots).
 *  3. On older hosts the plugin inflater's `cloneInContext` is overridden to
 *     ignore the new context entirely, so the wrapper silently no-opped there.
 *
 * Mirrors the Forms plugin's inflater pattern; the Forms `ThemedInflater`'s
 * wrapper has the same latent issue (tracked as a Forms followup).
 */
internal fun Fragment.themedPluginInflater(base: LayoutInflater): LayoutInflater =
    PluginFragmentHelper.getPluginInflater(MapsPlugin.PLUGIN_ID, base)
