# Custom IDE Builder

Builds a copy of the installed Code on the Go with a chosen set of plugins (`.cgp`) and templates
(`.cgt`) bundled inside, signed with the user's own key. Release builds only: the host reads the bundle
from `assets/plugins/`, `assets/templates/` and `assets/bundled-addons.tsv` in its APK.

## Layout

Everything with logic is in `org.appdevforall.customidebuilder.core` and imports nothing from
`android.*`, so its tests run on a plain JVM.

```sh
../../gradlew testDebugUnitTest               # from this folder: the tests
scripts/build-plugins.sh Custom-IDE-Builder   # from the repository root: the .cgp, build/plugin/custom-ide-builder.cgp
```

Works with Code on the Go 26.48 and later (`plugin.min_ide_version` 26.48, `plugin.max_ide_version` 99).

| File | Does |
|---|---|
| `Zip.kt` | Reads a zip's central directory; writes zips with raw (still compressed) entries, 4-byte aligned when stored (`.so` files 16 KB) |
| `ApkRewriter.kt` | Copies the host APK entry by entry, drops the old bundle and v1 signature, adds the add-ons and a new index |
| `Bundle.kt` | The bundle index, byte for byte as Code on the Go's build writes it |
| `Selection.kt` | Validates the chosen add-ons: duplicate plugin IDs, duplicate template names, `core.cgt`, entry names |
| `Staging.kt` | Names the copy of a picked file (`<n>/addon.cgp`, never the provider's name) and checks a path is inside the staging folder before any write or delete |
| `Keystores.kt` | Detects PKCS12 or BKS, rejects JKS with the `keytool` conversion, loads a key |
| `Signing.kt` | apksig: signs v1, v2 and v3, then verifies |
| `Catalog.kt`, `Download.kt` | The v2 catalog, `minAppVersion` filtering, verified downloads from `addons.appdevforall.org` only |
| `Pipeline.kt` | Rewrite, sign, verify, check alignment |

The Android side (`BuilderFragment`, `Session`, `AndroidBits`) is the screen, the file pickers and
the Storage Access Framework output.

## Mirrors of Code on the Go

`AddonKind` repeats `BundledAddonKind`, and `BundleIndex.render` repeats `StageBundledAddons`
(`app/build.gradle.kts`). `BundledAddons.parseIndex` is copied into the index test with a comment naming its
source. If Code on the Go changes the bundle, change these together.

## Signing dependency

`com.android.tools.build:apksig` is bundled into the `.cgp`. Its version is kept equal to the Android Gradle
plugin's in `settings.gradle.kts`.
