# Share Your Apps (Code On The Go plugin)

A plugin for [Code On The Go](https://github.com/appdevforall/CodeOnTheGo) that publishes
the owner's apps (APK, source, or both) to a small web store that people on the same Wi-Fi
network or hotspot can open in their phone's browser. The store is served by the companion
**Share Your Apps Server** app, which this plugin installs and starts.

## What it does

- **Manage Catalog** (the Share Your Apps tab): the store's name, description, header image and
  optional PIN; products with their project, APK, pictures and description; an optional
  Pebble template override and extra JSON. Everything is saved as it's edited.
- **Publish** builds a complete new Store directory beside the current one: it copies the
  APKs and media (inlining media of 10,240 bytes or less), zips the source without build
  output, caches, IDE settings or signing files, and renders the page. It then installs the
  server if needed, starts it on the new directory, checks every file is served correctly,
  and deletes the old directory. If anything fails, the store keeps serving the previous
  version.
- Tooltips on every heading and button (long-press).

The full design is in the Share Your Apps design document (ADFA-6207).

## Building

Open the project in Code On The Go and build the plugin. `plugin.id` is
`org.appdevforall.shareyourapps`.

The catalog page is rendered with Pebble, declared `compileOnly`: at runtime the plugin uses
Code On The Go's own copy (4.1.1), so building needs internet access once to fetch it for
compiling.

## Bundling the server

The plugin carries the server as `src/main/assets/share-your-apps-server.apk`. That file is a
build output, so it isn't committed; copy it in before building a release (see the
Share Your Apps Server's README). Without it, the plugin uses whatever server is installed.

## Running the tests

The tests are in `src/test/kotlin`: an end-to-end test that publishes real catalogs, serves them
with the server's own code and verifies every file, and a test of the HTML checker. Each runs a
list of named checks and, if any fail, names them. They run on a computer's Java, not on Android:

    ./gradlew testDebugUnitTest

**On the phone,** two things differ from a computer:

- **Start Gradle with `sh`.** Shared storage doesn't keep the executable bit, so `./gradlew` won't
  run there. Use `sh ./gradlew testDebugUnitTest` instead.
- **Use CodeOnTheGo's `aapt2`.** Run from a terminal, Gradle downloads the standard `aapt2`, which
  is built for Linux computers and can't run on a phone. The build then fails at
  `processDebugResources` with `Syntax error: word unexpected (expecting ")")`. CodeOnTheGo
  supplies its own `aapt2` to builds it starts, but not to a terminal, so pass it to Gradle:

      sh ./gradlew testDebugUnitTest -Pandroid.aapt2FromMavenOverride=/data/data/com.itsaky.androidide/files/home/android-sdk/build-tools/36.0.0/aapt2

  That's where CodeOnTheGo keeps it, with build tools 36.0.0. If a different version of
  CodeOnTheGo keeps it elsewhere, find it with

      find /data/data/com.itsaky.androidide/files -name aapt2 -type f 2>/dev/null | grep -v transforms

  and check a copy with `<path> version`, which should print `Android Asset Packaging Tool (aapt) 2.…`.

  Give it on the command line like this, not in `gradle.properties`: that file goes with the
  project, and the setting would break the build on every computer.

The end-to-end test needs the server's core, so `src/test/kotlin/org/appdevforall/shareyourapps/server/`
holds a **copy** of five of Share Your Apps Server's files. They're used only by the tests and never
ship in the plugin. When the server's copies of those files change, copy them here again.

**Writing tests:** unit tests compile against Android's version of Java's library (from
`android.jar`), but run on the computer's or phone's full Java. So they may only use Java
features Android also has. Reach anything newer through reflection, as `PublishTest.kt` does for
Unix-domain sockets, or the tests won't compile under Gradle.

## Documentation

- [`docs/share-your-apps-design.md`](docs/share-your-apps-design.md) — the design of Share Your Apps, both the plugin and Share Your Apps Server: how everything works, its limitations and open items, developer notes for building, releasing and testing, and the store page templates.
- [`docs/qa-steps.md`](docs/qa-steps.md) — step-by-step checks for testers.
- [`docs/tester-guide/`](docs/tester-guide/) — builds the one-page tester guide PDF.
- [`docs/review-prompt.md`](docs/review-prompt.md) — a prompt for an independent review of the design.
- [`templates/`](templates/) — alternative store page templates, and an annotated copy of the default.
- [`demo/`](demo/) — a demo store of twelve apps with placeholder downloads, for testing and demonstrations.

## License

GNU Affero General Public License v3.0 or later. See `LICENSE`.
