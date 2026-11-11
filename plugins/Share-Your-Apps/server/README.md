# Share Your Apps Server

An Android app that serves the store published by the **Share Your Apps** plugin for
[Code On The Go](https://github.com/appdevforall/CodeOnTheGo). People on the same Wi-Fi
network or hotspot open the store in their phone's browser to download the owner's apps
and their source. No internet or accounts are needed.

The plugin builds the store and starts this server on it; the owner rarely opens the app
except to check it's running, change a setting, or read the log.

## What it does

- Serves a Store directory (`CodeOnTheGoProjects/Store_<8 digits>/`) that the plugin writes,
  and nothing outside it.
- An optional PIN, with a 24-hour browser cookie.
- Serves a gzip copy of `index.html` and each source zip to browsers that accept it,
  compressing them in the background after each start.
- Runs as a foreground service in its main process; the HTTP server runs in a separate
  process that's replaced on every restart.
- Access log (Apache format), at most five files, with a configurable size limit.
- Three-tier help from a bundled database.
- A hotspot when the phone isn't on Wi-Fi (Wi-Fi Direct first, then Android's local-only hotspot),
  and a Serve screen with QR codes for joining the network and opening the store (ZXing core).

The full design is in the Share Your Apps design document (ADFA-6207).

## Building

Open the project in Code On The Go and build it. `minSdk` is 28, the same as Code On The Go.

For now the app is signed with Code On The Go's debug key. The copy the plugin bundles and
the copy installed on a phone must always be signed with the same key.

## Releasing a new build through the plugin

The plugin installs this app when it's missing or older than the copy it carries, so:

1. Raise `versionCode` in `app/build.gradle.kts`.
2. Build, then copy `app/build/outputs/apk/debug/app-debug.apk` into the plugin as
   `src/main/assets/share-your-apps-server.apk`.
3. Rebuild the plugin.

## Help

The help text lives in `help/build_help_db.py`, which generates
`app/src/main/assets/web_server_documentation.db`. It uses the same tables, columns and
queries as Code On The Go's `documentation.db`, with every page stored as plain HTML.

The app copies the database only when its `versionCode` changes. If you edit the help
text, rebuild the database, then raise `versionCode` before building, or the old help stays
in place:

    python3 help/build_help_db.py app/src/main/assets/web_server_documentation.db

## Running the tests

The tests are in `app/src/test/java`: eight JUnit tests covering the server's core — the HTTP
server, PIN and cookies, compression, the help database, network selection, QR codes, the log
filters, the hotspot settings, and the page look. Each runs a list of named checks and, if any
fail, names them. They run on a computer's Java, not on Android:

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

They need two test libraries in `app/build.gradle.kts`:

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")

**Writing tests:** unit tests compile against Android's version of Java's library (from
`android.jar`), but run on the computer's or phone's full Java. So they may only use Java
features Android also has. Reach anything newer through reflection, as `PublishTest.kt` does for
Unix-domain sockets, or the tests won't compile under Gradle.

## Documentation

The design document, covering both this app and the Share Your Apps plugin, is in the plugin's repository at `docs/share-your-apps-design.md`. Its *Developer notes* cover building, signing, updating the help and running the tests.

## License

GNU Affero General Public License v3.0 or later. See `LICENSE`.
