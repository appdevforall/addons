# Layout Editor Plugin for Code on the Go

A visual, drag-and-drop editor for Android XML layouts, running on-device. Open a
layout XML file, tap the Layout Editor action in the editor toolbar, and build the
view hierarchy on a canvas, set attributes through typed dialogs, and preview the
result at different device sizes.

The editor was extracted from the host IDE into this standalone plugin so the host
APK stays smaller and its build stays shorter. The plugin carries its own resources
and libraries and links only against the stable `plugin-api` contract.

## Features

- **Design and Blueprint views** - render the layout as it looks at runtime, or as a wireframe of each view's bounds.
- **Palette** - drag widgets onto the canvas, grouped as Common, Text, Buttons, Widgets, Layouts, and Containers.
- **Component tree** - the view hierarchy as a tree; tap to select, long-press for information.
- **Attribute editing** - add and remove attributes on the selected view and edit values through typed dialogs (color, dimension, size, number, string, boolean, enum, flag, id, reference).
- **Resource Manager** - browse and edit the project's colors, strings, drawables, and fonts.
- **Device size preview** - check the layout at different device dimensions and orientations.
- **XML view** - read the generated XML for the current layout.
- **Long-press documentation** - a tooltip on every control, palette item, and attribute.

## Plugin interfaces implemented

- `IPlugin` - core plugin lifecycle.
- `UIExtension` - the toolbar action and its enable rule.
- `DocumentationExtension` - tooltips and documentation.
- `BuildStatusListener` - disables the action while a project has a failed sync.

## Architecture

```
plugins/Layout-Editor/
├── build.gradle.kts, settings.gradle.kts, proguard-rules.pro
├── layout-editor.html                 # full reference for this plugin
├── src/main/
│   ├── AndroidManifest.xml            # plugin id, main class, icons, permissions
│   ├── assets/                        # icons, widgetclasses.json, palette/, attributes/, editor/
│   ├── kotlin|java/.../layouteditor/
│   │   ├── LayoutEditorPlugin.kt      # entry point: toolbar action, enable rule, tooltips, build-status
│   │   ├── LayoutEditorFragment.kt    # full-screen editor: canvas, palette, component tree, drawer
│   │   ├── ResourceManagerFragment.kt # colors, strings, drawables, fonts
│   │   ├── editor/DesignEditor.kt     # drag-and-drop canvas over the live view hierarchy
│   │   ├── editor/dialogs/            # one typed dialog per attribute format
│   │   ├── editor/callers|initializer # apply values to real View instances; track attributes
│   │   ├── LayoutEditorDocs.kt        # long-press tooltip helper
│   │   └── PluginDialogContext.kt     # dialog context with a valid window token
│   └── res/                           # layout/, drawable/, values/, menu/
└── README.md
```

The editor opens full-screen through `IdeUIService.openPluginScreen()`, which carries
no `Bundle`, so screens hand data through process-level state holders
(`LayoutEditorState`, `EditorSubScreenState`). Because a plugin has its own resource
namespace, plugin XML is inflated with the plugin's own inflater
(`PluginFragmentHelper.getPluginInflater`) so `?attr/` and `app:` attributes resolve
against the plugin theme, and dialogs are shown through `PluginDialogContext`, which
pairs the host activity (for the window token) with the plugin context (for resources
and theme).

## Host services used

- `IdeEditorService` - read the current file for the enable rule and the target layout.
- `IdeProjectService` - resolve the project and its resource directories.
- `IdeBuildService` - subscribe to build status so a failed sync disables the action.
- `IdeUIService` - `openPluginScreen()` to present the editor and its sub-screens.
- `IdeTooltipService` - show the long-press documentation tooltips.

## Building

```bash
cd layout-editor
./gradlew clean assemblePluginDebug   # or assemblePlugin for release
```

Output: `build/plugin/layout-editor-debug.cgp`. Run `clean` first, since the plugin
builder copies the built APK into the `.cgp` and then deletes the source APK.

## Installation

1. Open Preferences, then Plugin Manager, then the add button.
2. Select the `layout-editor-debug.cgp` file.
3. The IDE discovers `LayoutEditorPlugin` from the manifest metadata and activates it.

## Usage

1. Open a layout XML file (under a `layout` resource directory).
2. Tap the Layout Editor action in the editor toolbar.
3. Drag widgets from the palette, select views on the canvas or in the component tree, and edit their attributes.
4. Open the Resource Manager to edit colors, strings, drawables, and fonts.
5. Save to write the layout back. Long-press any control for its tooltip.

## Requirements

- Android API 26 or newer.
- Minimum IDE version 1.0.0.
- Permissions: `filesystem.read`, `filesystem.write`, `project.structure`.
- No network access.

## Documentation

`layout-editor.html` is the full reference for this plugin. In the IDE,
long-press any control, palette item, or attribute for its tooltip.

## License

Layout Editor is an open-source example plugin for Code on the Go, licensed per the
surrounding `plugin-examples` repository. See `LICENSE` at the repo root.
