# Flutter Templates

A [Code on the Go](https://github.com/appdevforall/CodeOnTheGo) template bundle that adds
**Flutter starter projects** to the New Project screen, alongside the built-in core templates.

It contributes five templates, one per state-management approach:

| Template | Directory | State management |
|---|---|---|
| Flutter Basic | `FlutterBasic/` | none (plain `setState`) |
| Flutter BLoC | `FlutterBloc/` | `flutter_bloc` |
| Flutter Provider | `FlutterProvider/` | `provider` |
| Flutter GetX | `FlutterGetx/` | `get` |
| Flutter Riverpod | `FlutterRiverpod/` | `flutter_riverpod` |

Each generates a small, idiomatic Flutter project (a counter app) with `pubspec.yaml`, `lib/`
and `analysis_options.yaml`, substituting the app name and package name entered in the New
Project dialog.

## How it works

There is no code. The bundle is a `.cgt` file: a zip of the five directories plus
`templates.json`, which Code on the Go reads directly. Install it under **Preferences →
Extensions Manager → Templates** with the **+** button, and the five templates appear in
**New Project**. Nothing to enable, no permissions.

Files ending in `.peb` are rendered by Pebble at project-creation time and lose the suffix;
everything else is copied byte for byte. These templates use three expressions only:
`${{APP_NAME}}`, `${{APP_NAME | lower}}` and `${{PACKAGE_NAME}}`.

## Building

The `.cgt` is **never committed**. The publish workflow generates it. To build one locally:

```sh
./scripts/build-cgt.sh templates/Flutter-Templates out
unzip -l out/flutter-templates.cgt
```

See [`../README.md`](../README.md) for authoring guidance and the format reference.

## This replaces the Flutter Templates plugin

Until ADFA-6252 these templates shipped as a plugin that built a `.cgt` on the device at
`activate()` and registered it. The plugin is gone; the skeletons are unchanged.

If a device still has the old plugin installed, **disable or uninstall it before adding this
bundle**. Both register the same five templates from unlinked sources, so each would appear
twice. Disabling the plugin calls its `deactivate()`, which removes its copies.

## Note on the Flutter SDK

This bundle **scaffolds project files only** — it does not install the Flutter or Dart SDK.
Code on the Go does not yet ship an on-device Flutter toolchain, so building or running a
generated project needs Flutter on a separate machine. Enter an app name that is a valid Dart
package identifier (lowercase, no spaces — underscores are fine); it is lower-cased for the
`name:` field in `pubspec.yaml`.

## Credit

Original idea by **Raju Kumar**
([ADFA-2599](https://appdevforall.atlassian.net/browse/ADFA-2599)).

Contributed by **RJ Ali** &lt;rjali3232@gmail.com&gt; via the Code on the Go community
submission process, rebuilt on the IDE's template system
([ADFA-3857](https://appdevforall.atlassian.net/browse/ADFA-3857)), and converted to a
template bundle in [ADFA-6252](https://appdevforall.atlassian.net/browse/ADFA-6252).
