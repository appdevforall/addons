# Custom IDE Builder

Builds a copy of the installed Code on the Go with a chosen set of plugins (`.cgp`) and templates
(`.cgt`) bundled inside, signed with the user's own key, optionally under a new app name and icon. Release builds only: the host reads the bundle
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
| `Branding.kt` | Edits the host's `resources.arsc` with ARSCLib: `app_name` and the other strings that name the app, and the files the three icon resources resolve to |
| `IconRaster.kt` | Densities and icon sizes, nearest-density choice, the white status-bar silhouette |
| `IconCheck.kt` | Perceptual hash of a picture and the "is this Code on the Go's own icon" decision |
| `AppNameCheck.kt` | Name rules: blank, length, look-alike normalising, "cogo" / "codeonthego" |
| `BrandingGate.kt` | What must be true (name ok, picture ok, ownership box ticked) before a branded build starts |
| `MinSdk.kt` | A plugin's `minSdkVersion` from its binary manifest, and the warning when it is above the host's |

The Android side (`BuilderFragment`, `Session`, `AndroidBits`, `IconImages`) is the screen, the file
pickers, the Storage Access Framework output, and the bitmap work: decoding the chosen picture, scaling it
to each density, and drawing the host's own launcher icon for the look-alike check.

## Name and icon

Both are optional and independent; choosing either requires ticking "This name and icon are my own, and
not Code on the Go's".

**Name.** `app_name` is replaced in every config (every locale). After ADFA-7237 every other display string
takes its name from `app_name`, so that is normally all there is. For older hosts, every other string value
that holds "Code on the Go" (any case, whitespace between the words) is rewritten too, except the keys in
`BrandingRules.KEEP_LITERAL`, a copy of `KEEP_LITERAL` in Code on the Go's `AppNameInStringsTest.kt`
(ADFA-7237): keep the two equal. A string with markup (spans) is skipped and counted, never rewritten, because
a rewrite could drop the spans. `CodeOnTheGo` without spaces (URLs) is never matched.

**Icon.** `ic_launcher` and `ic_launcher_round` (the whole icon, 48 dp), `ic_launcher_foreground` and
`ic_launcher_fg_vector` (the picture in the central 66 dp of a transparent 108 dp canvas, which is the
adaptive-icon safe zone and what the splash screen and the drawer header use), and `ic_cogo_notification`
(a white silhouette, 24 dp) are repointed, in `drawable` and `mipmap`, in every config they have. Resource IDs
do not change. New files are `res/custom-ide-builder/<role>-<density>.png`, stored and 4-byte aligned like
`resources.arsc`, which Android 11 and later require to be uncompressed.

- **Adaptive icon.** The `anydpi-v26` entries of `ic_launcher` and `ic_launcher_round` point straight at a
  launcher PNG instead of at the adaptive XML (which would keep the old foreground). A PNG is a valid
  launcher icon on API 26 and later; the launcher puts its own backing behind it.
- **Density.** A config's density picks the nearest generated density. A config with no density, `anydpi` or
  `nodpi` gets the sharpest (xxxhdpi).
- **Status-bar icon.** The silhouette uses the picture's alpha. A picture with no transparency uses whatever
  differs from the colour of its corners.
- **Look-alike check.** A 128-bit difference hash (9x8 horizontal and 8x9 vertical, brightness steps under 4
  levels ignored) of the chosen picture is compared, flipped or not, with the hash of the host's own
  `drawable/ic_launcher` as it draws. At most 14 differing bits is refused. `IconCheckTest` measures the margin
  on drawn pictures; the real icon has not been measured, so the threshold may need tuning against it.
- **Name check.** After case-folding, accent and width folding and look-alike mapping (0, 1, 3, 4, 5, 7, 9,
  `@`, `$`, `!`, Cyrillic and Greek homoglyphs), with everything but letters and digits removed, a name is
  refused if it contains `cogo` or `codeonthego` or has a substring within two edits of `codeonthego`. `cogo`
  is matched exactly only: two edits from four letters reach "logo", "congo" and "cargo". "Taco Go" is refused
  because spaces are ignored. Blank names, names over 50 characters, control characters and `%` are refused too.

The edited table is read back and compared with the original before it is used: every entry must still be there
and every non-string value unchanged.

## Resource editing dependency

`io.github.reandroid:ARSCLib` 1.4.0 (Apache-2.0, compatible with this repository's GPL) reads and writes
`resources.arsc` and reads a plugin's binary manifest without a platform parser. Its Android framework tables
(`frameworks/**`, 2.4 MB) are excluded from packaging; the code here never touches them, and the tests pass with
them removed from the classpath. The jar also contains `android.util.AttributeSet`,
`android.content.res.XmlResourceParser` and `org.xmlpull.v1.*`, which the platform already provides. They are not
excluded: dropping classes from a dependency needs a build transform that could not be tried here. If a device
build reports duplicate classes, that is the place to look.

## Mirrors of Code on the Go

`AddonKind` repeats `BundledAddonKind`, and `BundleIndex.render` repeats `StageBundledAddons`
(`app/build.gradle.kts`). `BundledAddons.parseIndex` is copied into the index test with a comment naming its
source. If Code on the Go changes the bundle, change these together.

## Signing dependency

`com.android.tools.build:apksig` is bundled into the `.cgp`. Its version is kept equal to the Android Gradle
plugin's in `settings.gradle.kts`.
