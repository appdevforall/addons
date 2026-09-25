# How `.cgt` template bundles are made and managed

Reference for ADFA-6252. It records how `core.cgt` works today, so a new bundle in this
repository can follow the same model.

Sources examined:

| Source | Path |
|---|---|
| Template source tree | `/Users/eisen/src/dev-assets/templates/` |
| Local builder | `/Users/eisen/src/dev-assets/build-core-cgt.sh` |
| Validation workflow | `/Users/eisen/src/dev-assets/.github/workflows/lint-templates.yml` |
| Publication workflow | `/Users/eisen/src/dev-assets/.github/workflows/deploy.yml` |
| Programmatic builder | `com.itsaky.androidide.plugins.templates.CgtTemplateBuilder` in `libs/plugin-api.jar` |
| Format definition | `CodeOnTheGo/templates-impl/src/main/java/com/itsaky/androidide/templates/impl/zip/ZipTemplateConstants.kt` |
| Project generator | `.../impl/zip/ZipTemplateReader.kt` and `.../impl/zip/ZipRecipeExecutor.kt` |
| Manager UI reader | `CodeOnTheGo/app/src/main/java/com/itsaky/androidide/templates/manager/parsing/CgtTemplateReader.kt` |

The format has no written specification. `ZipTemplateConstants.kt` is the only definition.

---

## 1. What a `.cgt` file is

A `.cgt` file is a ZIP archive. It has no custom container and no header. It holds one or
more project templates. Code on the Go reads it and shows each template on the New Project
screen.

Two things produce a `.cgt`:

| Producer | Used by | Method |
|---|---|---|
| A ZIP of a source tree | `core.cgt` in `dev-assets` | `zip -0` over `templates/` |
| `CgtTemplateBuilder` | Template-installer plugins, such as `plugins/Flutter-Templates` | Java `ZipOutputStream` at run time on the device |

Both write the same internal layout. This is why ADFA-6252 is possible: the Flutter plugin
already builds a `.cgt` on the device, so the same files can ship as a `.cgt` directly.

---

## 2. Bundle layout

The archive root holds a manifest and one directory per template.

```
templates.json                        <- manifest, lists the templates
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

`core.cgt` holds nine templates: `NoActivity`, `EmptyActivity`, `BasicActivity`,
`NavigationDrawer`, `BottomNavActivity`, `NoAndroidX`, `TabbedActivity`, `ComposeActivity`,
`CodeOnTheGoPlugin`. It has 378 entries, all stored, all dated 1980-01-01, and no directory
entries.

`extensions.jar` is optional. It supplies custom Pebble `Extension` classes, which the IDE
dexes into `dex_opt/`. `core.cgt` does not use it.

`CgtTemplateBuilder` writes the same names.

### A bundle holds one or more templates

Two readers exist. They find the templates in different ways, so a bundle must satisfy both.

| Reader | Purpose | Method |
|---|---|---|
| `ZipTemplateReader` | Builds the New Project list | Reads `templates.json` by that exact bare name at the archive root, then reads `<path>/template/template.json` for each entry |
| `CgtTemplateReader` | Shows the Templates manager list | Scans for any entry that ends in `/template/template.json`, and ignores `templates.json` |

A missing `template.json` makes `ZipTemplateReader` skip that entry without an error.

---

## 3. `templates.json`

The root manifest. It is strict JSON. It lists the directory of each template.

```json
{
  "templates": [
      { "path": "NoActivity" },
      { "path": "EmptyActivity" }
  ]
}
```

An entry also accepts `"experimental": true`. The IDE then hides that template unless
`FeatureFlags.isExperimentsEnabled` is set. `core.cgt` does not use it.

The manifest holds no version and no schema version. Nothing checks that each `path` exists
on disk.

---

## 4. `template/template.json`

One file for each template. It is **JSON5**, not strict JSON. Unquoted keys are permitted,
and `core.cgt` uses them: `{identifier: "APP_NAME"}`.

| Field | Purpose |
|---|---|
| `name` | The name on the New Project screen |
| `description` | One line under the name |
| `version` | The template version, such as `"0.1"` |
| `tooltipTag` | The in-app help tag, such as `"template.empty.activity"` |
| `defaultAppName` | Optional. Pre-fills the app name field |
| `parameters.required` | `appName`, `packageName`, `saveLocation` |
| `parameters.optional` | `language`, `minsdk` — omit one to hide that field |
| `parameters.user.text` | Extra text fields: `label`, `identifier`, `default` |
| `parameters.user.checkbox` | Extra checkboxes: `label`, `identifier`, `default` |
| `system` | Values the IDE supplies: `agpVersion`, `kotlinVersion`, `gradleVersion`, `compileSdk`, `targetSdk`, `javaSourceCompat`, `javaTargetCompat`, `javaTarget` |

Each entry maps a name to an `identifier`. The identifier is the token that the Pebble files
use.

Presence controls the wizard. Declare `parameters.optional.language` and the language picker
shows; omit it and the picker hides **and** the `.java`/`.kt` filter stops running.
`ComposeActivity` omits it, and it ships no `.java.peb` files.

`CodeOnTheGoPlugin/template/template.json` is the only one that uses `parameters.user`. It
adds a text field `PLUGIN_AUTHOR` and a checkbox `PLUGIN_SAMPLE`. A checkbox identifier is
also a path flag — see section 5.

---

## 5. Pebble files

A file with the `.peb` suffix is rendered. The IDE removes the suffix and writes the result
into the new project. Every other file is copied byte for byte.

### Delimiters

Code on the Go changes the Pebble delimiters. Every one carries a `$` prefix:

| Purpose | Open | Close |
|---|---|---|
| Print | `${{` | `}}` |
| Execute | `${%` | `%}` |
| Comment | `${#` | `#}` |

```kotlin
android {
    namespace = "${{PACKAGE_NAME}}"
    compileSdk = ${{COMPILE_SDK}}
}
${% if LANGUAGE == 'kotlin' %}
kotlin("android") version "${{KOTLIN_VERSION}}"
${% endif %}
```

Filters work: `${{CLASS_NAME | lower}}` and `${{PACKAGE_NAME | replace({"." : "_"})}}` both
appear in `core.cgt`.

The engine runs with `strictVariables(true)`. An identifier that no parameter declares fails
the render.

### Identifiers

`core.cgt` uses these: `APP_NAME`, `PACKAGE_NAME`, `CLASS_NAME`, `MIN_SDK`, `COMPILE_SDK`,
`TARGET_SDK`, `AGP_VERSION`, `KOTLIN_VERSION`, `GRADLE_VERSION`, `JAVA_SOURCE_COMPAT`,
`JAVA_TARGET_COMPAT`, `JAVA_TARGET`, `PLUGIN_AUTHOR`.

Three more are injected at render time, and no `template.json` declares them: `CLASS_NAME`,
`COGO_CGT_PATH` and `COGO_CGT_DIRECTORY`.

### Path transformation

`ZipRecipeExecutor.renderProject` transforms each entry path in this order:

1. Entries outside `<TemplateName>/` are ignored, and `<TemplateName>/template/` is dropped.
   The metadata never reaches the new project.
2. If `parameters.optional.language` is declared and the user picked a language, the other
   language is filtered out. A Kotlin project skips every `*.java` and `*.java.peb`; a Java
   project skips every `*.kt` and `*.kt.peb`. This is why each template ships both.
3. A path segment that matches a **checkbox identifier** acts as a flag. The segment is
   removed when the box is checked, and the whole entry is skipped when it is not.
4. The literal text `PACKAGE_NAME` in the path becomes the package as directories
   (`com.foo.bar` becomes `com/foo/bar`). The literal text `CLASS_NAME` becomes the class
   name.
5. The `.peb` suffix is removed from the output name.
6. The output path is canonicalized. An entry that escapes the project root is refused.

`CodeOnTheGoPlugin` shows steps 3 and 4 together:

```
CodeOnTheGoPlugin/src/main/kotlin/PACKAGE_NAME/PLUGIN_SAMPLE/fragments/CLASS_NAMEFragment.kt.peb
```

With "Include Sample Code" checked, and the package `com.foo`, this becomes
`src/main/kotlin/com/foo/fragments/MyPluginFragment.kt`. With the box clear, it disappears.

### Three gotchas

1. A bare `${{TAG}}` at the end of a line loses its newline. Pebble trims newlines, so the
   line merges with the next one. This produced invalid YAML once, because `name:` and
   `description:` collapsed into one line. Put a non-newline character after `}}`. The
   convention is to quote the value: `name: "${{APP_NAME | lower}}"`.
2. The lint step counts `{{` against `}}`. It does not render the template. A balanced but
   wrong expression passes, and `strictVariables(true)` then fails on the device.
3. `CodeOnTheGoPlugin/src/main/AndroidManifest.xml.peb` holds a literal `${pluginVersion}`.
   That is a **Gradle** manifest placeholder, not Pebble. It has one brace. Do not change it
   to `${{...}}`.

---

## 6. How the archive is built

### In CI

`lint-templates.yml` and `deploy.yml` hold the same inline steps. There is no shared script.

```bash
export TZ=UTC
( cd ./templates \
    && find . -type f -exec touch -t 198001010000 {} + \
    && find . -type f | LC_ALL=C sort | zip -0 -D -X -q "$OUT/core.cgt" -@ )
brotli -q 11 -f -o "$OUT/core.cgt.br" "$OUT/core.cgt"
```

| Flag | Effect |
|---|---|
| `-0` | Store every entry. No compression inside the ZIP |
| `-D` | Write no directory entries |
| `-X` | Strip uid, gid and extended attributes |
| `LC_ALL=C sort` | Fix the entry order |
| `touch -t 198001010000` | Fix every mtime to 1980-01-01 |

The result is deterministic. The same source gives the same MD5.

Compression happens one level up. `core.cgt.br` is Brotli quality 11 over the stored ZIP.
This is the same debug/release split that the other assets use: the plain archive is the
debug form, the Brotli archive is the release form.

### Locally

`build-core-cgt.sh <templates-dir> <output-dir>` writes both files. It checks that
`templates.json` is present and that the two plugin jars are present.

**Warning.** The local script uses Python `zipfile` and **writes directory entries**. CI uses
`zip -0 -D -X` and writes none. The two archives are therefore not byte-identical. The local
script shows that a change builds; it does not reproduce the CI MD5.

### On the device

`CgtTemplateBuilder` writes the same layout through `ZipOutputStream`. A plugin calls
`IdeTemplateService.createTemplateBuilder(name)`, adds files, then calls `build(dir)` and
`registerTemplate(file)`.

---

## 7. Validation

`lint-templates.yml` runs on every pull request that touches `templates/**`.

| Step | Check |
|---|---|
| Junk files | Rejects `.gradle/`, `build/`, `local.properties`, `*.swp`, `*.swo`, `*.bak`, `.DS_Store` |
| XML | `xmllint --noout` on every `*.xml` |
| Root manifest | `jq empty templates/templates.json` — strict JSON |
| Template metadata | Python `json5.load` on every `*/template/template.json` |
| Pebble braces | Counts `{{` against `}}` in every `*.peb` |
| Plugin jars | `sha256sum -c checksums.txt` on the fetched jars |
| Build | Rebuilds `core.cgt` and `core.cgt.br`, prints the sizes and MD5s |

There is **no JSON Schema** anywhere in `dev-assets`. Syntax is checked; structure is not.
Nothing confirms that a `path` in `templates.json` exists, that `template/template.json` and
`template/thumb.png` are present, or that the required fields are filled.

There is **no render test**. No template is ever rendered in CI.

---

## 8. Versioning and publication

### Versioning

`core.cgt` has no version. There is no tag, no version file, and no version field in
`templates.json`. The name is always `core.cgt`, and each deploy overwrites it. Only the
individual templates carry a `version` field, and nothing aggregates them.

Clients detect a change through the MD5 sidecar.

### Publication

This section describes **dev-assets only**. `core.cgt` ships with the app assets, so it takes
the app asset pipeline. It does not set a precedent for this repository: everything here
publishes to Cloudflare R2, and a template bundle will do the same. Read this section for how
the archive is built, not for where it goes.

`deploy.yml` runs on manual dispatch only. It publishes to the GreenGeeks web host over SSH.

| Artifact | Path on the site |
|---|---|
| `core.cgt` + `core.cgt.md5` | `https://appdevforall.org/dev-assets/debug/` |
| `core.cgt.br` + `core.cgt.br.md5` | `https://appdevforall.org/dev-assets/release/` |

The step packs the whole asset set as `asset<yymmdd>.zip`, copies it with `scp`, compares the
remote MD5, backs up the live tree, removes the live tree, then unpacks the new set. It is a
full-set replace: a file that is absent from the zip disappears from the site.

Integrity is MD5 only. Nothing signs `core.cgt`.

### How a bundle reaches the device

`core.cgt` ships with the app assets. Any other `.cgt` arrives as a file.

| Path | Mechanism |
|---|---|
| A plugin registers one | `IdeTemplateService.registerTemplate(file)` copies it into `Environment.TEMPLATES_DIR` under a prefixed name |
| A user installs one | The Templates manager scans `Environment.TEMPLATES_DIR` and the Downloads folder |

The second path is the one ADFA-6252 needs. A `.cgt` downloaded from the gallery needs no
plugin, no manifest and no Kotlin.

---

## 9. The `CodeOnTheGoPlugin` template needs two jars

`templates/CodeOnTheGoPlugin/libs/{plugin-api,gradle-plugin}.jar` are **not committed**. They
are gitignored. Both the lint workflow and the deploy workflow fetch them from the
`plugin-api-latest` GitHub Release on `appdevforall/CodeOnTheGo`, then verify them against
`checksums.txt`.

They are gitignored but they **do ship**. Both jars are entries in the built `core.cgt`,
because the build runs after the fetch. A template that needs a binary can therefore hold
one, as long as the build puts it in place first.

To fetch them by hand:

```bash
BASE=https://github.com/appdevforall/CodeOnTheGo/releases/download/plugin-api-latest
LIBS=templates/CodeOnTheGoPlugin/libs
mkdir -p "$LIBS"
curl -fL "$BASE/plugin-api.jar"    -o "$LIBS/plugin-api.jar"
curl -fL "$BASE/gradle-plugin.jar" -o "$LIBS/gradle-plugin.jar"
```

The `README.md` of `dev-assets` mentions `scripts/update-plugin-libs.sh`. That script does not
exist. A later section of the same file says so. The line is stale.

---

## 10. To add a template to `core.cgt`

1. Make `templates/<NewTemplate>/` with the project skeleton.
2. Add `template/template.json` and `template/thumb.png`.
3. Give the `.peb` suffix to every file that needs substitution.
4. Add `{ "path": "<NewTemplate>" }` to `templates/templates.json`.
5. Open a pull request. `lint-templates.yml` runs.
6. Dispatch `deploy.yml` after the merge.

---

## 11. What this repository must add for ADFA-6252

A template bundle under `templates/` publishes to `addons.appdevforall.org` through
Cloudflare R2, the same as every plugin. The tooling is written for plugins only, so five
places need a template branch. **A template is not a plugin**: it builds no APK, it has no
Gradle build, and it has no `AndroidManifest.xml`.

| ID | Gap | Resolution |
|---|---|---|
| G01 | Discovery | `discover.find_addons` requires a `build.gradle.kts` with the plugin-builder predicate. Template discovery is a separate rule: a directory under `templates/` that holds `templates.json`. |
| G02 | Packaging | `publish-addons.yml` runs `assemblePlugin` and copies a `.cgp`. A template is zipped into a `.cgt` instead. The step branches on the addon kind. |
| G03 | Artifact suffix | `catalog.entry()` hardcodes `dl/<slug>.cgp`, and `publish.py` maps a content type for `.cgp` and `.gz` only. Both need `.cgt`. |
| G04 | Metadata source | `plugin.id` has no meaning here. A template declares `template.id`, `template.version` and `template.minAppVersion` in `addon.json`, which `model.py` reads in place of the manifest. The catalog field becomes `addonId`, at `schemaVersion` 2. |
| G05 | Provenance | `scripts/verify-provenance.sh` asserts `assets/cgp-build.properties` inside a `.cgp`. A `.cgt` carries `cgt-build.properties` at its root instead, and the script gains that branch. |

Already in place:

| ID | Item |
|---|---|
| P01 | `templates` is one of the four areas in `discover.AREAS`. |
| P02 | `catalog.TYPES` maps `templates` to the type `template`. |
| P03 | `site/catalog.schema.json` accepts `"template"` in the `type` enum. |

The `.cgt` is **never committed**. A GitHub Action generates it at publication. The plan is in
[`flutter-cgt-migration-plan.md`](flutter-cgt-migration-plan.md); community guidance is in
[`README.md`](README.md).

---

## 12. What the Flutter conversion involves

`plugins/Flutter-Templates` already holds five Pebble skeletons under
`src/main/assets/templates/<Template>/`, and each one already uses the `${{TAG}}` syntax that
`core.cgt` uses. The conversion is mostly a move.

The five templates use only three expressions: `${{APP_NAME}}`, `${{APP_NAME | lower}}` and
`${{PACKAGE_NAME}}`. They declare no language option, they hold no `PACKAGE_NAME` or
`CLASS_NAME` directory, and they use no execute block. None of the harder rules in section 5
applies to them.

| Present today | Needed in the bundle |
|---|---|
| `src/main/assets/templates/FlutterBasic/**` | `FlutterBasic/**` at the bundle root |
| `template/thumb.png` for each template | Unchanged |
| The name and description in the `VARIANTS` list in `FlutterTemplate.kt` | `FlutterBasic/template/template.json` |
| `showPackageNameOption()` in the Kotlin code | `parameters.required.packageName` |
| The five entries in `VARIANTS` | Five entries in `templates.json` |
| `AndroidManifest.xml`, `build.gradle.kts`, `FlutterTemplate.kt`, the icons | Removed. A `.cgt` needs none of them |
| `addon.json` | Kept, and extended to carry the values that the manifest used to hold |

Settled. The detail is in [`flutter-cgt-migration-plan.md`](flutter-cgt-migration-plan.md).

| Question | Answer |
|---|---|
| One bundle or five? | One, `flutter-templates.cgt`, holding five template directories |
| Where do the version, id and minimum come from? | `addon.json`, in a `template` object. The id is `org.appdevforall.fluttertemplates`, the minimum is `26.38` |
| Is a Brotli form published? | No. Cloudflare compresses on the fly |
| Do the `.peb` files keep the suffix? | Yes |
| Does this repository lint `templates/`? | Yes, in `check-toolchain.yml`, using the checks borrowed from `dev-assets` |

### One behaviour change to expect

Today the plugin builds each `.cgt` on the device and calls `registerTemplate`. The templates
appear when the user enables the plugin. After the conversion, the user downloads a `.cgt`
and the Templates manager picks it up. The New Project screen is the same, but the install
step moves from the Plugin Manager to the Templates manager. Verify this on a device before
the work is called complete.
