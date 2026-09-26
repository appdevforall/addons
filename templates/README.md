# Project templates

A template adds an entry to the **New Project** screen in Code on the Go. It ships as a
`.cgt` file: a ZIP holding one or more project skeletons and their metadata. The IDE reads it
directly.

A template is not a plugin. It runs no code. It has no `build.gradle.kts`, no
`AndroidManifest.xml` and no Kotlin, and it declares no permissions. If your idea needs to run
code, write a plugin instead — see [`../plugins/README.md`](../plugins/README.md).

Part 1 below is how to write one. Part 2 is the format reference.

## Start from `Flutter-Templates`

`templates/Flutter-Templates/` is the reference. Copy it and change what is yours. It is small
but complete: five templates in one bundle, an `addon.json` with the `template` block, a
gallery page, both icons, and thumbnails. Every rule in Part 1 is visible in it.

It is to a template what `plugins/Random-XKCD/` is to a plugin — the thing to copy, and the
thing a reviewer compares your submission against.

For a larger example, `core.cgt` in the `dev-assets` repository holds the nine built-in
Android templates. It exercises parts of the format the Flutter bundle does not: the language
chooser, user-supplied parameters, and placeholders in directory names.

---

# Part 1 — Make a template

## 1. Make the directory

Name it in MixedCase with single hyphens, ASCII letters and digits only, as
`docs/addon-naming-standards.md` describes. `Flutter-Templates`, not `flutter_templates`.

```
templates/My-Templates/
├── addon.json          <- gallery metadata
├── my-templates.html   <- gallery page
├── icon_day.png        <- gallery icon
├── icon_night.png
├── README.md
├── templates.json      <- from here down, this is the archive
└── MyFirstTemplate/
    ├── template/template.json
    ├── template/thumb.png
    └── ... your project skeleton ...
```

The first five files describe your addon to the gallery. They stay out of the `.cgt`. The
archive holds `templates.json` and the directories it names, and nothing else — the build
takes its file list from `templates.json`, so a file you do not list is never shipped.

## 2. Put your project skeleton under `<TemplateName>/`

Start from a project that already builds. Copy it in whole, then replace the parts that
change per project with placeholders.

## 3. Mark the files that need substitution

Add `.peb` to any file that contains a placeholder. Code on the Go renders it and removes the
suffix. Every other file is copied byte for byte, so binary files are safe.

`app/build.gradle.kts` becomes `app/build.gradle.kts.peb`.

## 4. Write the placeholders

The delimiters carry a `$` prefix. This is not stock Pebble syntax.

| Purpose | Syntax |
|---|---|
| Print a value | `${{APP_NAME}}` |
| Run a statement | `${% if LANGUAGE == 'kotlin' %} ... ${% endif %}` |
| Comment | `${# not rendered #}` |

Filters work, and they chain:

```
name: "${{APP_NAME | lower | replace({" ": "_", "-": "_"})}}"
${{PACKAGE_NAME | replace({"." : "_"})}}
```

`APP_NAME` is whatever the user typed, spaces and all — the default is `My Application2`. Any
file that needs an identifier rather than a label has to fold it, as the first line does. A
bare `| lower` is not enough: it yields `my application2`, which is valid YAML and an invalid
Dart package name.

Values you can use:

| Identifier | Value |
|---|---|
| `APP_NAME` | What the user typed |
| `PACKAGE_NAME` | The package, for example `com.example.myapp` |
| `CLASS_NAME` | The app name with every non-alphanumeric character removed |
| `MIN_SDK`, `COMPILE_SDK`, `TARGET_SDK` | SDK levels |
| `AGP_VERSION`, `KOTLIN_VERSION`, `GRADLE_VERSION` | Tool versions the IDE supplies |
| `JAVA_SOURCE_COMPAT`, `JAVA_TARGET_COMPAT`, `JAVA_TARGET` | Java compatibility |
| `LANGUAGE` | `kotlin` or `java`, only if you offer the choice |

You can add your own — see *Ask the user for more* below.

## 5. Use placeholders in paths too

A directory or a file name is substituted as well.

```
app/src/main/java/PACKAGE_NAME/MainActivity.kt.peb
```

With the package `com.example.myapp` this becomes
`app/src/main/java/com/example/myapp/MainActivity.kt`. `CLASS_NAME` works the same way.

## 6. Write `<TemplateName>/template/template.json`

```json
{
  "name": "My First Template",
  "description": "One line the user reads under the name",
  "version": "1.0.0",
  "tooltipTag": "template.my.first",
  "parameters": {
    "required": {
      "appName": { "identifier": "APP_NAME" },
      "packageName": { "identifier": "PACKAGE_NAME" },
      "saveLocation": { "identifier": "SAVE_LOCATION" }
    },
    "optional": {
      "language": { "identifier": "LANGUAGE" },
      "minsdk": { "identifier": "MIN_SDK" }
    }
  },
  "system": {
    "agpVersion": { "identifier": "AGP_VERSION" },
    "kotlinVersion": { "identifier": "KOTLIN_VERSION" },
    "gradleVersion": { "identifier": "GRADLE_VERSION" },
    "compileSdk": { "identifier": "COMPILE_SDK" },
    "targetSdk": { "identifier": "TARGET_SDK" },
    "javaSourceCompat": { "identifier": "JAVA_SOURCE_COMPAT" },
    "javaTargetCompat": { "identifier": "JAVA_TARGET_COMPAT" },
    "javaTarget": { "identifier": "JAVA_TARGET" }
  }
}
```

**Declare only what you use.** Presence controls the wizard:

- Leave out `optional.language` and the language chooser hides. Do this when your template
  offers one language only.
- Leave out `optional.minsdk` and the minimum SDK field hides.
- Leave out the whole `system` block when no `.peb` file reads a tool version.

Add `template/thumb.png` for the image on the New Project screen. It is optional, but an
entry without one looks unfinished.

The full field list is in Part 2.

## 7. Write `templates.json`

List each directory, in the order you want them shown. This file is also the build's allow
list: a directory you leave out is not packaged.

```json
{
  "templates": [
    { "path": "MyFirstTemplate" },
    { "path": "MySecondTemplate" }
  ]
}
```

Add `"experimental": true` to an entry to hide it unless the user turns experiments on.

## 8. Write `addon.json`

This is what the gallery shows.

```json
{
  "summary": "One line, 120 characters at most.",
  "description": "A full paragraph. This is the gallery card.",
  "tags": ["flutter", "starter"],
  "origin": "community",
  "license": "AGPL-3.0-or-later",
  "author": { "name": "Your Name", "url": "" },
  "template": {
    "id": "com.example.mytemplates",
    "version": "1.0.0",
    "minAppVersion": "26.38"
  }
}
```

`template.id` must be unique and must never change across releases. `template.version` is a
dotted number. `minAppVersion` is the oldest Code on the Go release your bundle works on,
written as `YY.ww`.

Your gallery card shows **Download** and **Details**. It has no **Source** link, because a
template ships no source tarball — the `.cgt` is plain text throughout, so the download
already is the source. The card's Details page links back to your directory in this
repository.

## 9. Add the page and the icons

`my-templates.html` is your gallery page. Copy the shape from
`../plugins/Random-XKCD/random-xkcd.html`. The rules the checker enforces:

- The file name is the slug: the directory name in lower case.
- `<title>` is the display name exactly: the directory name with hyphens replaced by spaces.
- The `<h1>` contains that name.
- No `<script>` and no inline event handler. These pages are served from the site origin.
- Write **Code on the Go** in full. Not CoGo, not CotG, not CodeOnTheGo.

`icon_day.png` and `icon_night.png` are both required.

## 10. Check it

```sh
uv run --directory tools/addons addons --root "$PWD" check
```

Run it from the repository root. `--root` must be absolute. This is the same gate that runs
on every pull request. Treat its output as the authority.

## 11. Build it and look inside

```sh
./scripts/build-cgt.sh templates/My-Templates out
unzip -l out/my-templates.cgt
```

This is the same script the publish workflow runs, so a local build and a published one agree.
It takes the file list from `templates.json`, pins every mtime, and writes a stored, sorted
archive.

Confirm `templates.json` appears bare at the root, with no directory in front of it. The IDE
looks it up by that exact name and finds nothing otherwise. Confirm too that `addon.json`,
your HTML page and your icons are **not** in the listing.

If `find` reports a missing path, `templates.json` names a directory that does not exist.
Fix the spelling. The IDE would skip it in silence.

The published `.cgt` also carries `cgt-build.properties` at the root, which the Action writes.
It records the commit the bundle was built from. Your local build does not produce it.

**Never commit the `.cgt`.** The Action builds it on publication. Add `*.cgt` to your
`.gitignore`.

## 12. Test it on a device

A passing check and a clean zip prove nothing about whether the template works. The renderer
runs with strict variables: a placeholder that no parameter declares fails **at project
generation**, on the device, and nowhere earlier.

### Install the bundle

1. Copy the `.cgt` onto the device — `adb push out/my-templates.cgt /sdcard/Download/` is
   enough.
2. In Code on the Go, open **Preferences → Extensions Manager**. The screen is titled
   **Plugins & Templates**.
3. Switch to the **Templates** tab.
4. Tap the **+** button, bottom right. The system file picker opens.
5. Browse to your `.cgt` and select it. The picker starts in `CodeOnTheGoProjects`, so use its
   drawer to reach Downloads.
6. A dialog headed **Install Template Collection** lists every template in the bundle. Tap
   **Install**.

The card then reads **Installed**, and the bundle is copied into the IDE's own templates
directory.

> **Read that dialog.** It names each template, in `templates.json` order, taken from the
> `name` field of each `template/template.json`. It is the earliest place a missing entry, a
> wrong order or a typo in a name shows up — before you have generated anything.

Copying the file into Downloads is **not** installing it. The Templates tab lists what it
finds there as **Not installed — Imported**; step 6 is what installs it. A card marked
**Bundled** came with the app.

### Exercise it

Open **New Project**. Your templates appear beside the built-in ones. Generate a project from
every template you shipped, and check the result:

- The `.peb` suffixes are gone, and no `template/` directory came with them.
- Every placeholder is substituted — look inside the generated files, not just at their names.
- Any file your template generates that has a strict syntax (`pubspec.yaml`, XML, JSON) still
  parses. Run it through a real parser rather than reading it.

### Re-installing after a change

Rebuild, push the new file, then install it again the same way. Because a collection of that
name is already there, the dialog changes to **Template Collection Already Installed** and
offers **Overwrite**, **Rename & Install** and **Cancel**. Choose **Overwrite** — the other
option installs a second copy under a new name, and you end up testing against both.

---

## Ask the user for more

`parameters.user` adds fields to the New Project dialog.

```json
"user": {
  "text": [
    { "label": "Author", "identifier": "AUTHOR_NAME", "default": "" }
  ],
  "checkbox": [
    { "label": "Include sample code", "identifier": "WITH_SAMPLE", "default": true }
  ]
}
```

A text field gives you `${{AUTHOR_NAME}}` in any `.peb` file.

A checkbox gives you `${% if WITH_SAMPLE %}`, and it also works **in a path**. A directory
named after a checkbox identifier disappears from the path when the box is checked, and the
whole entry is dropped when it is not:

```
src/main/kotlin/PACKAGE_NAME/WITH_SAMPLE/demo/Sample.kt.peb
```

Checked, with the package `com.example`, this becomes
`src/main/kotlin/com/example/demo/Sample.kt`. Clear, and it is not written at all.

## Offer Java and Kotlin

Ship both files side by side, and declare `optional.language`:

```
MainActivity.kt.peb
MainActivity.java.peb
```

The IDE keeps the one that matches the choice and drops the other. This works **only** when
`parameters.optional.language` is declared. Without it both files are written.

---

## Things that go wrong

| Symptom | Cause |
|---|---|
| The template never appears | `templates.json` is not bare at the archive root, or the `path` does not match the directory name |
| One template is missing, with no error | Its `template/template.json` is absent. The reader skips it in silence |
| Generation fails on the device | A placeholder that no parameter declares. Strict variables is on |
| Two lines merged into one, and broken YAML | A bare `${{TAG}}` ended a line. Pebble trims the newline. Put a character after `}}` — quote the value |
| A `template/` directory appears in the generated project | It cannot. If you see one, it is not at `<TemplateName>/template/` |
| `${pluginVersion}` was not substituted | It is a Gradle placeholder with one brace, not Pebble. Leave it alone |

Metadata parsing is lenient in two different ways, which is a trap. `templates.json` is
**strict JSON**. Each `template/template.json` is **JSON5**, so unquoted keys are accepted
there and refused in the other file. Quote every key and both stay valid.

---

## Build provenance

Every published `.cgt` records the commit it was built from, in `cgt-build.properties` at the
archive root. You do not write this file. The publish workflow generates it, adds it to the
archive, and it is gitignored.

```sh
unzip -p flutter-templates.cgt cgt-build.properties
```

```properties
revision=a1b2c3d4e5f6
revision_source=env:GITHUB_SHA
timestamp=2026-09-25T14:02:11Z
timestamp_source=git
addon_id=org.appdevforall.fluttertemplates
version=1.0.0
```

### Why it exists

The gallery serves one URL per addon and overwrites it on each publish. Without this record,
"version 1.0.0" names every build that version number ever had, and a bug report cannot be
tied to the source that produced it. With it, any published bundle traces back to one commit.

`scripts/verify-provenance.sh` asserts the record after each build. A `.cgt` with no
`cgt-build.properties`, or missing a key, fails the run.

### What it asks of you

Two things, and both are about keeping the build reproducible. One commit must always produce
one archive, byte for byte.

| Rule | Why |
|---|---|
| Never commit a `.cgt` | The Action builds it. A committed one goes stale and nobody notices |
| Keep everything in the bundle plain text | A binary that changes on every build breaks determinism. The images are the deliberate exception |

Two values in the record are warnings rather than failures, and you will see both locally:

- `revision=unknown` — no commit was found. Normal outside CI.
- `timestamp_source=wall-clock` — no `git` was available, so the clock was used. A bundle
  built this way is **not** reproducible, because the stamp changes on every build.

A third, `+dirty` appended to the revision, means your directory had uncommitted changes when
the archive was built. On a published artifact that is a real problem: it says the bundle does
not match any commit. Commit your work before the publish workflow runs.

---

## Rules for the archive

The build pins every mtime to 1980 and sorts the entry list, so one commit always gives one
archive. Keep it that way.

Never commit these under your template directory. The checker refuses them:

```
.gradle/  build/  local.properties  *.swp  *.swo  *.bak  .DS_Store
```

Keep template content plain text. Images are the exception: `thumb.png` and the two gallery
icons are PNG.

---

# Part 2 — The `.cgt` format

The format has no written specification. This part records what the code does.

| Component | Where |
|---|---|
| Format definition | `CodeOnTheGo/templates-impl/.../impl/zip/ZipTemplateConstants.kt` |
| Project generator | `.../impl/zip/ZipTemplateReader.kt` and `.../impl/zip/ZipRecipeExecutor.kt` |
| Extensions Manager reader | `CodeOnTheGo/app/.../templates/manager/parsing/CgtTemplateReader.kt` |
| Programmatic builder | `CgtTemplateBuilder` in `libs/plugin-api.jar` |

## Bundle layout

A `.cgt` is a plain ZIP. No custom container, no header.

```
templates.json                        <- manifest, lists the templates
cgt-build.properties                  <- provenance, written by the publish workflow
extensions.jar                        <- optional, custom Pebble extensions
<TemplateName>/template/template.json <- metadata for this template
<TemplateName>/template/thumb.png     <- thumbnail for the New Project screen
<TemplateName>/<any path>.peb         <- a file that Pebble renders
<TemplateName>/<any path>             <- a file that is copied without change
```

The names are fixed. `ZipTemplateConstants.kt` declares them:

```kotlin
const val ARCHIVE_JSON = "templates.json"
const val META_FOLDER = "template"
const val META_JSON = "template.json"
const val META_THUMBNAIL = "thumb.png"
const val META_EXTENSION_JAR = "extensions.jar"
const val TEMPLATE_EXTENSION = ".peb"
```

`extensions.jar` is optional and rarely used. It supplies custom Pebble `Extension` classes,
which the IDE dexes into `dex_opt/`. Neither `core.cgt` nor `flutter-templates.cgt` uses it.

An unrecognised entry at the archive root is ignored, which is why
`cgt-build.properties` can sit there safely.

### Two readers, both of which must be satisfied

| Reader | Purpose | Method |
|---|---|---|
| `ZipTemplateReader` | Builds the New Project list | Reads `templates.json` by that exact bare name at the archive root, then reads `<path>/template/template.json` for each entry |
| `CgtTemplateReader` | Fills the Extensions Manager Templates tab | Scans for any entry ending in `/template/template.json`, and ignores `templates.json` |

A missing `template.json` makes `ZipTemplateReader` skip that entry with no error.

## `templates.json`

The root manifest, in **strict** JSON. It names the directory of each template.

```json
{
  "templates": [
      { "path": "FlutterBasic" },
      { "path": "FlutterBloc" }
  ]
}
```

An entry also accepts `"experimental": true`. The IDE then hides that template unless
`FeatureFlags.isExperimentsEnabled` is set.

The manifest carries no version and no schema version. The IDE checks nothing about it; the
repository's `addons check` does, which is why a typo fails in CI rather than silently on a
device.

## `template/template.json`

One file per template, in **JSON5** — unquoted keys are accepted, and `core.cgt` uses them
(`{identifier: "APP_NAME"}`). Quote them anyway.

| Field | Purpose |
|---|---|
| `name` | The name on the New Project screen |
| `description` | One line under the name |
| `version` | The template version, such as `"1.0.0"` |
| `tooltipTag` | The in-app help tag, such as `"template.flutter.basic"` |
| `defaultAppName` | Optional. Pre-fills the app name field |
| `parameters.required` | `appName`, `packageName`, `saveLocation` |
| `parameters.optional` | `language`, `minsdk` — omit one to hide that field |
| `parameters.user.text` | Extra text fields: `label`, `identifier`, `default` |
| `parameters.user.checkbox` | Extra checkboxes: `label`, `identifier`, `default` |
| `system` | Values the IDE supplies: `agpVersion`, `kotlinVersion`, `gradleVersion`, `compileSdk`, `targetSdk`, `javaSourceCompat`, `javaTargetCompat`, `javaTarget` |

Each entry maps a name to an `identifier`, and the identifier is the token the `.peb` files
use.

## Rendering

The engine runs with `strictVariables(true)`. An identifier no parameter declares fails the
render — on the device, at project generation, and nowhere earlier.

Three identifiers are injected at render time and no `template.json` declares them:
`CLASS_NAME`, `COGO_CGT_PATH` and `COGO_CGT_DIRECTORY`.

### Path transformation

`ZipRecipeExecutor.renderProject` transforms each entry path in this order:

1. Entries outside `<TemplateName>/` are ignored, and `<TemplateName>/template/` is dropped.
   The metadata never reaches the new project.
2. If `parameters.optional.language` is declared and the user picked a language, the other
   language is filtered out. A Kotlin project skips every `*.java` and `*.java.peb`; a Java
   project skips every `*.kt` and `*.kt.peb`. This is why a template that offers the choice
   ships both.
3. A path segment matching a **checkbox identifier** acts as a flag. The segment is removed
   when the box is checked, and the whole entry is skipped when it is not.
4. The literal text `PACKAGE_NAME` in the path becomes the package as directories
   (`com.foo.bar` → `com/foo/bar`). The literal `CLASS_NAME` becomes the class name.
5. The `.peb` suffix is removed from the output name.
6. The output path is canonicalized. An entry that escapes the project root is refused.

`core.cgt` shows steps 3 and 4 together:

```
CodeOnTheGoPlugin/src/main/kotlin/PACKAGE_NAME/PLUGIN_SAMPLE/fragments/CLASS_NAMEFragment.kt.peb
```

With "Include Sample Code" checked and the package `com.foo`, this becomes
`src/main/kotlin/com/foo/fragments/MyPluginFragment.kt`. With the box clear, it disappears.

## How the archive is built

`scripts/build-cgt.sh` derives its file list from `templates.json`, writes the provenance
record, then:

```bash
export TZ=UTC
find <files> -exec touch -t 198001010000 {} +
find <files> | LC_ALL=C sort | zip -0 -D -X -q <slug>.cgt -@
```

| Flag | Effect |
|---|---|
| `-0` | Store every entry. No compression inside the ZIP |
| `-D` | Write no directory entries |
| `-X` | Strip uid, gid and extended attributes |
| `LC_ALL=C sort` | Fix the entry order |
| `touch -t 198001010000` | Fix every mtime to 1980-01-01 |

One commit therefore produces one archive, byte for byte. There is no compression step:
Cloudflare compresses on the fly for the gallery.

`core.cgt` is built by the same incantation in the `dev-assets` repository, which is where
the shape came from. It additionally produces a Brotli copy, because the app fetches it from
a plain web host that does no content negotiation.

A third producer exists: `CgtTemplateBuilder` in `plugin-api.jar` writes the same layout at
run time through `ZipOutputStream`. It is how a plugin can register a template of its own.
A bundle published to the gallery does not use it.

## How a bundle reaches the device

| Path | Mechanism | Shown as |
|---|---|---|
| Shipped with the app | `core.cgt`, as an app asset | `Bundled` |
| A user installs one | Preferences → Extensions Manager → Templates → **+** | `Imported` |
| A plugin registers one | `IdeTemplateService.registerTemplate(file)` | `Imported` |

The second is the path a gallery download takes. It needs no plugin, no manifest and no
Kotlin.

**Listing and installing are separate.** The Templates tab scans
`Environment.TEMPLATES_DIR` *and* the Downloads folder, so a `.cgt` sitting in Downloads
appears in the list — marked **Not installed**. Installing it is the explicit action behind
the **+** button, and that is what copies the file into `TEMPLATES_DIR`. Only a bundle in
`TEMPLATES_DIR` reaches the New Project screen.

Two readers back those two states, which is why a bundle must satisfy both:
`CgtTemplateReader` populates the list from any `*/template/template.json` it finds, and
`ZipTemplateReader` builds the New Project entries from `templates.json`.

## How publishing is wired

A directory under `templates/` holding `templates.json` is discovered as an addon — the rule
is deliberately separate from the plugin one, which requires a `build.gradle.kts` applying the
plugin-builder. From there:

| Stage | What happens |
|---|---|
| `addons check` | Structure, naming, metadata. Runs on every pull request |
| `check-toolchain.yml` | Also lints the bundle sources and builds every `.cgt` |
| `publish-addons.yml` | Zips the bundle and uploads `dl/<slug>.cgt` to Cloudflare R2 |
| `v2/catalog.json` | Gains an entry with `type: "template"` and no `sourceTarball` |

The catalog is published once per major version. `v2/catalog.json` is the current
one and the only one that can describe a template: `v1/catalog.json` requires a
`sourceTarball`, which a template does not have, and names its id field
`pluginId`. `v1` keeps being published, without the template entries, because the
main consumer is a fielded app that cannot be force-updated. Nothing you write
differs between them — the generator emits both.

The metadata a plugin keeps in its `AndroidManifest.xml` lives in `addon.json` under
`template` instead: `id`, `version` and `minAppVersion`.
