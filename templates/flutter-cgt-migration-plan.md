# Plan: move Flutter Templates from a plugin to a `.cgt` bundle

ADFA-6252. Read `cgt-templates.md` first for the format.

## Goal

`plugins/Flutter-Templates/` becomes `templates/Flutter-Templates/`. It stops being an
Android application module. It becomes a source tree that a GitHub Action zips into one
`flutter-templates.cgt` holding five template directories, and publishes to
`addons.appdevforall.org` through Cloudflare R2.

## Rules this plan follows

| Rule | Effect |
|---|---|
| The `.cgt` is never committed | The Action generates it. `.gitignore` blocks it |
| Template content is plain text | Dart, YAML, Gradle and JSON only. No archives, no jars |
| Images stay as PNG | Seven files: two gallery icons and five thumbnails. These are packaging, not template content |
| A template is not a plugin | No `build.gradle.kts`, no `AndroidManifest.xml`, no Kotlin, no `.cgp` |
| Publication goes to R2 | The same path every other addon in this repository uses |

---

## 1. Target layout

The template directories sit at the addon root, the same shape `dev-assets/templates/` uses.

```
templates/Flutter-Templates/
├── addon.json               <- gallery metadata + template id, version, minAppVersion
├── flutter-templates.html   <- gallery page, name fixed by the slug
├── icon_day.png             <- gallery icon
├── icon_night.png
├── README.md
├── templates.json           <- archive root manifest
├── FlutterBasic/
│   ├── template/template.json
│   ├── template/thumb.png
│   ├── analysis_options.yaml
│   ├── pubspec.yaml.peb
│   ├── README.md.peb
│   └── lib/main.dart.peb
├── FlutterBloc/
├── FlutterGetx/
├── FlutterProvider/
└── FlutterRiverpod/
```

`addon.json`, the HTML page, the two icons and `README.md` are repository metadata. They must
not reach the archive.

### Select the files with an allow list, not an exclude list

An `-x` exclude list is the obvious way to keep those four out, and it is the wrong one. It
fails open: a metadata file added next year ships inside the `.cgt` until somebody remembers
to extend the list, and nothing reports it.

`templates.json` already names every directory that belongs in the archive. Build the file
list from it:

```bash
paths="$(jq -r '.templates[].path' templates.json)"
find templates.json $paths -type f | LC_ALL=C sort
```

This fails closed. A new metadata file is never listed, so it can never ship. It also fails
**loudly**: `find` exits non-zero when a path in `templates.json` does not exist on disk,
which catches a typo that the IDE would otherwise swallow in silence.

### Files to delete

`plugins/Flutter-Templates/` goes away whole. The `.peb` skeletons, the thumbnails and the
icons move to the new location first; everything else is discarded:
`build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`,
`src/main/AndroidManifest.xml`, `src/main/kotlin/com/ali/fluttertemplate/FlutterTemplate.kt`,
and all of `src/main/res/`. None has a meaning in a `.cgt`.

Do this as a `git mv` of the files that survive, so the history of each `.peb` file follows
it. See D07 in section 9 for what the deletion means for a user who installed the plugin.

### Files to move without change

Every `.peb` file and `analysis_options.yaml` under
`src/main/assets/templates/<Template>/`. They already use `${{APP_NAME}}`,
`${{APP_NAME | lower}}` and `${{PACKAGE_NAME}}`, which is exactly what the format wants.
Each `template/thumb.png` moves as it is.

---

## 2. New files to write

### `templates.json`

```json
{
  "templates": [
    { "path": "FlutterBasic" },
    { "path": "FlutterBloc" },
    { "path": "FlutterProvider" },
    { "path": "FlutterGetx" },
    { "path": "FlutterRiverpod" }
  ]
}
```

### `<Template>/template/template.json`

One for each template. The name and the description come from the `VARIANTS` list in
`FlutterTemplate.kt`, so nothing a user reads changes.

```json
{
  "name": "Flutter Basic",
  "description": "A minimal Flutter starter app with a counter screen",
  "version": "1.0.0",
  "tooltipTag": "template.flutter.basic",
  "parameters": {
    "required": {
      "appName": { "identifier": "APP_NAME" },
      "packageName": { "identifier": "PACKAGE_NAME" },
      "saveLocation": { "identifier": "SAVE_LOCATION" }
    }
  }
}
```

There is no `parameters.optional`. Flutter offers no Java or Kotlin choice, so the language
picker must stay hidden, and Flutter needs no `minSdk`. There is no `system` block, because
no `.peb` file reads an AGP, Gradle or SDK value.

The old plugin called `showPackageNameOption()`. `parameters.required.packageName` replaces
it.

### `addon.json`

The gallery metadata stays. A `template` object carries what the `AndroidManifest.xml` used
to hold.

```json
{
  "summary": "Five Flutter starter projects for the New Project screen.",
  "description": "...",
  "tags": ["flutter", "templates", "dart"],
  "origin": "community",
  "license": "AGPL-3.0-or-later",
  "author": { "name": "RJ Ali", "url": "" },
  "template": {
    "id": "org.appdevforall.fluttertemplates",
    "version": "1.0.0",
    "minAppVersion": "26.38"
  }
}
```

`template.id` replaces `plugin.id`. `template.version` replaces `plugin.version`.
`minAppVersion` replaces `plugin.min_ide_version`.

The id is `org.appdevforall.fluttertemplates`, not the old `com.ali.fluttertemplate`. The
bundle is maintained here, so it takes the maintainer's namespace. Credit to the original
contributor stays where a user reads it: `addon.json` `author`, the gallery description and
the addon README. Set the id once and never change it.

`minAppVersion` is **26.38**.

### `flutter-templates.html`

`check.py` requires a page named `<slug>.html`, with `<title>` equal to the display name, an
`<h1>` that contains it, no `<script>`, no inline event handler, and the product name written
in full. Copy the shape from `plugins/Random-XKCD/random-xkcd.html`.

### `.gitignore`

```
*.cgt
```

---

## 3. What to borrow from dev-assets

Borrow the commands, not the files. Neither workflow in dev-assets calls a script; both hold
the steps inline.

| Borrow | From | Why |
|---|---|---|
| The zip command | `lint-templates.yml` and `deploy.yml` | It is the build that produced the shipped `core.cgt` |
| The lint checks | `lint-templates.yml` | Junk files, XML, JSON, JSON5, Pebble braces |

The zip command, with the file list narrowed to what `templates.json` declares and the
provenance record added:

```bash
export TZ=UTC
( cd templates/Flutter-Templates \
    && paths="$(jq -r '.templates[].path' templates.json)" \
    && write-provenance cgt-build.properties \
    && find cgt-build.properties templates.json $paths -type f \
         -exec touch -t 198001010000 {} + \
    && find cgt-build.properties templates.json $paths -type f \
         | LC_ALL=C sort \
         | zip -0 -D -X -q "$OUT/flutter-templates.cgt" -@ )
```

`-0 -D -X` and the fixed mtime are unchanged from dev-assets. They are what makes one commit
give one archive.

### Do not borrow `build-core-cgt.sh`

It writes directory entries. The CI command does not. The two archives differ, and the
shipped `core.cgt` matches CI. Borrowing the script would make a local build disagree with
the Action for no gain.

### Do not produce a `.br` file

`core.cgt.br` exists because the app fetches assets from a plain web host that does no
negotiation. The gallery sits behind Cloudflare, which compresses on the fly. One `.cgt` is
enough.

---

## 4. Changes to `tools/addons`

Five small changes. Each matches a gap in `cgt-templates.md`.

### G01 — discovery

`discover.find_addons` requires `build.gradle.kts` with the plugin-builder predicate. A
template has no Gradle build.

Add a second rule: a directory under `templates/` that holds `templates.json` is a
template addon. Return the two kinds together, and let callers ask which kind a path is. A
helper such as `is_template(path)` keeps the branch in one place.

### G02 — packaging

`publish-addons.yml` runs `assemblePlugin` and copies `build/plugin/*.cgp`. A template takes
the zip command above instead. The step branches on the addon kind and produces
`dist/<slug>.cgt`.

### G03 — the artifact suffix

Three edits:

| File | Edit |
|---|---|
| `publish.py` | Add `".cgt": "application/octet-stream"` to `CONTENT_TYPES`, and `.cgt` to `ATTACHMENTS` |
| `catalog.py` | `download` becomes `dl/<slug>.cgt` for a template |
| `cli.py` | The publish object list uses `dl/<slug>.cgt` for a template |

### G04 — metadata

`model.py` reads `plugin.id`, `plugin.version` and `plugin.min_ide_version` from
`src/main/AndroidManifest.xml`. A template has no manifest, so these read from `addon.json`
instead:

| Accessor | Plugin source | Template source |
|---|---|---|
| `plugin_id` | manifest `plugin.id` | `addon.json` `template.id` |
| `version` | manifest, then `build.gradle.kts` | `addon.json` `template.version` |
| `min_app_version` | manifest `plugin.min_ide_version` | `addon.json` `template.minAppVersion` |

`addon.schema.json` sets `additionalProperties: false`, so it must gain the `template` object,
required for a template and absent for a plugin.

Icons move to the addon root for a template, because `src/main/assets/` is an Android path
with no meaning here. `check.py` and `cli.py` both need the branch.

### The catalog renames `pluginId` to `addonId`

`pluginId` is wrong for three of the four addon types. The catalog carries plugins, templates,
snippets and code-actions, so the field becomes `addonId` and `schemaVersion` goes from 1
to 2.

`catalog.schema.json` says field removal needs a new major, so the bump is required rather
than optional. The cost is low and this is the moment to pay it: `site/app.js` never reads
the field, and no code in Code on the Go references `catalog.json` at all, so no consumer
observes the change today. Renaming later, once a gallery client exists, costs far more.

| File | Edit |
|---|---|
| `site/catalog.schema.json` | `pluginId` becomes `addonId`, in `properties` and in `required`. `schemaVersion` const becomes 2 |
| `catalog.py` | The entry key becomes `addonId`. `document["schemaVersion"]` becomes 2 |
| `tests/test_catalog.py` | Update the expected key and version |

### `check.py`

A template must skip the plugin checks: `pluginName` in `build.gradle.kts`, `plugin.name`,
`plugin.id` and `plugin.min_ide_version`. It gains its own:

- `templates.json` exists and is strict JSON.
- Every `path` in it names a directory that exists.
- Every one of those holds `template/template.json`, and that file parses as JSON5.
- `icon_day.png` and `icon_night.png` exist at the addon root.
- `<slug>.html` passes the checks that already exist.

The directory name, slug and page rules stay as they are. They are not plugin-specific.

### `tarball.py` — templates ship no source tarball

**Decision: `tarball.build()` returns early for a template. No archive is produced, and the
gallery card omits the Source link.**

A plugin tarball exists so somebody can unpack the folder and build it. Its archive root is
the project root, because Code on the Go reads a folder as a plugin project only when
`build.gradle.kts` and `libs/plugin-api.jar` sit in it. That is why `tarball.py` copies the
shared jars in, copies the Gradle wrapper in, rewrites `../libs/` references, and writes a
`BUILDING.md` that says `./gradlew assemblePlugin`.

None of it applies. A template has no project to open and no compiler to run.

Nothing is lost by omitting it. **The `.cgt` is the source.** Every file in it is plain text
by rule, so the published download already is what a second tarball would carry. `sourceUrl`
also stays in the catalog entry, pointing at the directory in this repository, so the tree
remains one click away. The AGPL obligation is met by the artifact itself.

`build()` therefore returns before `jars_for()`, which would otherwise raise
`"<name>: it references no shared jar"` on its first line.

#### Five places assume a tarball exists

Skipping the build is the small part. These are the rest:

| File | Change |
|---|---|
| `catalog.py` | `build()` raises when `<slug>-src.tar.gz` is missing from `dist`. Require it for plugins only. `entry()` omits the `sourceTarball` key for a template |
| `site/catalog.schema.json` | `sourceTarball` moves out of `required`. This is a removal, and `schemaVersion` is already going to 2 for `addonId`, so it costs nothing extra |
| `cli.py` | The publish object list drops `src/<slug>-src.tar.gz` for a template |
| `publish-addons.yml` | The "Build the source tarballs" step passes only the plugin directories |
| `site/app.js` | Guard the Source link — see below |

#### `site/app.js` would crash, not degrade

Line 137 reads the field with no guard:

```js
const src = node.querySelector('[data-slot="source"]');
src.href = safeUrl(addon.sourceTarball.url);
src.title = `Source tarball, ${size(addon.sourceTarball.size)}`;
```

On an entry with no `sourceTarball` this throws a `TypeError`. The call sits inside the loop
that builds every card, so the exception leaves the gallery stuck on "Loading…" with a partial
list — **one template would break the page for every addon**, not just its own card.

Hide the anchor instead:

```js
const src = node.querySelector('[data-slot="source"]');
if (addon.sourceTarball) {
  src.href = safeUrl(addon.sourceTarball.url);
  src.title = `Source tarball, ${size(addon.sourceTarball.size)}`;
} else {
  src.remove();
}
```

`site/index.html` needs no change. The anchor stays in the card template and is removed per
card.

---

## 5. Workflow changes

### Rename `build-plugins.yml` to `build-addons.yml`

The rename is overdue, and this change forces it. The file already calls its subjects addons
in its own words — the header comment says "Builds every addon (or one)", the input is
described as "One addon directory name", and the last step prints "Every discovered addon
produced a release `.cgp`." Only four things still say plugin: the file name, the `name:`
field, the `plugin` input key and the `ONLY_PLUGIN` environment variable. Once the workflow
also produces `.cgt` files, the name is simply wrong.

It matches the `pluginId` to `addonId` decision in section 4, and the umbrella term the
repository already uses everywhere else: `tools/addons`, `addon.json`, `addons discover`,
`publish-addons.yml`, `addons.appdevforall.org`.

| Rename step | Note |
|---|---|
| `build-plugins.yml` → `build-addons.yml` | The `name:` field becomes `Build addon artifacts` |
| The `plugin` input → `addon` | With `ONLY_PLUGIN` becoming `ONLY_ADDON` |
| `check-toolchain.yml` line 8 | Its comment names "Build plugin artifacts". Update the text |

The rename is safe. The workflow is `workflow_dispatch` only, so it cannot be a required pull
request check and no branch protection rule depends on the name.

Leave the three references under `docs/superpowers/` alone. Those are the plan and the specs
for the addon-distribution work as it was carried out. They are a record, not live
documentation, and rewriting history to match a later rename makes them harder to trust.

### The changes themselves

| Workflow | Change |
|---|---|
| `check-toolchain.yml` | `addons check` now covers templates. Add the dev-assets lint steps: junk files, `xmllint`, `jq`, JSON5, Pebble brace balance. This is the **only** workflow here that runs on a pull request, so it is the only place a broken bundle can be caught before merge |
| `publish-addons.yml` | Branch the build step. A plugin runs Gradle; a template runs `scripts/build-cgt.sh` |
| `build-addons.yml` | Same branch. It delegates to `scripts/update-libs.sh`, which rebuilds the two jars and runs `assemblePlugin` for each addon — a template has neither, so it must be skipped there and built by the script instead |

### Put the zip in one script, not in each workflow

Section 3 says to borrow the commands rather than the files. That advice holds for
`build-core-cgt.sh`, and it stops there. `dev-assets` inlines the same steps in two workflows
and keeps a third copy in a script, and the script has already drifted: it writes directory
entries and the workflows do not, so it cannot reproduce what CI ships.

Two workflows and one local instruction need this build. Write `scripts/build-cgt.sh` once
and call it from all three. One copy cannot drift from itself.

---

## 6. Documentation to update

Three documents describe a repository that holds plugins only. Each states something that
becomes false.

### `docs/plugin-naming-standards.md`

It derives six values from one directory name, and one of the six is "the `.cgp` filename".
A template produces a `.cgt`. The rest of the standard needs no change at all — the
MixedCase rule, the slug, the display name, the HTML file name and the page title all apply
unchanged, which is why the file needs an edit rather than a sibling.

| Edit | Detail |
|---|---|
| The artifact row | "the `.cgp` filename" becomes the addon file name: `<slug>.cgp` for a plugin, `<slug>.cgt` for a template |
| The wording | "Every plugin has up to six places" becomes "Every addon". The same for the body text |
| The manager reference | "the name in the Code on the Go plugin manager" is the Templates manager for a template |

Renaming the file to `addon-naming-standards.md` is consistent with `addonId` and
`build-addons.yml`. It is referenced from `CLAUDE.md` and from `templates/README.md`, so a
rename means updating both.

### Root `README.md`

The Examples table is one flat list of `plugins/…` rows. `Flutter-Templates` has a row there
today and must move.

Split the table by area, with a heading for each, so the four areas in `discover.AREAS` are
visible to a reader who is deciding what to build. A template contributor currently has no
sign that templates are even possible.

### `CLAUDE.md`

Four claims stop being true for every addon:

| Section | Problem |
|---|---|
| "Plugin shape" | States an addon is an Android application module with a manifest and a main class. True for a plugin only |
| "Adding a new plugin" | Step 1 says to copy `plugins/Random-XKCD/`. Wrong starting point for a template |
| "`libs/` is the load-bearing piece" | A template depends on neither jar |
| "Build provenance" | Describes `cgp-build.properties` as the record. A template has `cgt-build.properties` |

The cheapest honest fix is a short **Templates** section that states what a template is, what
it does not have, and points at `templates/README.md` for the detail — plus the word "plugin"
narrowed to "addon" where a claim covers both. Do not restate the template format in
`CLAUDE.md`. Two copies of a format specification drift, and `cgt-templates.md` is the one
that gets read.

---

## 7. Order of work

1. Move the directory and delete the plugin scaffolding.
2. Write `templates.json`, the five `template.json` files, `addon.json`, the HTML page and
   the `.gitignore`.
3. Change `tools/addons`: discovery, model, check, catalog, publish, cli and **tarball**. Add
   tests beside the ones that exist.
4. Write `scripts/build-cgt.sh`, and branch `scripts/verify-provenance.sh`.
5. Change the three workflows, and rename `build-plugins.yml`.
6. Update `docs/plugin-naming-standards.md`, the root `README.md` and `CLAUDE.md`. The root
   README is where the `Flutter-Templates` row moves out of the plugins table.
7. Build the `.cgt` locally with `scripts/build-cgt.sh`. Confirm with `unzip -l` that
   `templates.json` is bare at the root, that `cgt-build.properties` is beside it, and that
   the archive holds no `addon.json`, no HTML page, no icon, no `.gradle` and no `build/`.
8. Verify on a device.

---

## 8. Verification

A green build proves nothing here. The bundle must be installed and used.

1. Copy `flutter-templates.cgt` to the device Downloads folder.
2. Open the Templates manager. Confirm five entries appear, with the right names,
   descriptions and thumbnails.
3. Open New Project. Confirm the five Flutter templates appear beside the core ones.
4. Generate one project from each. Confirm the app name reaches `pubspec.yaml` in lower case
   and `main.dart` unchanged, and that the package name reaches `README.md`.
5. Confirm no `template/` directory reaches the generated project.

The engine runs with `strictVariables(true)`. An identifier that `template.json` does not
declare fails at generation, not at build. Only step 4 catches it.

---

## 9. Decisions

Nothing is open. Every question this plan raised has an answer.

| ID | Decision |
|---|---|
| D01 | Images stay PNG. "Plain text" governs template content, not packaging |
| D02 | The template directories sit at the addon root. The build's file list comes from `templates.json` |
| D03 | The catalog field is `addonId`, at `schemaVersion` 2 |
| D04 | Provenance is `cgt-build.properties` at the archive root |
| D05 | `minAppVersion` is `26.38` |
| D06 | `template.id` is `org.appdevforall.fluttertemplates` |
| D07 | The old plugin is deleted in this pull request |
| D08 | Templates ship no source tarball, and the gallery card omits the Source link |

### D07 — what deleting the plugin means for existing users

`plugins/Flutter-Templates/` is removed whole, in the same pull request.

A user who installed the old plugin keeps five registered templates on their device. The
plugin copied a built `.cgt` into `Environment.TEMPLATES_DIR` under a prefixed name on
`activate()`, and removes it on `deactivate()`. Deleting the directory here removes the addon
from the gallery; it does nothing to a device.

So a user who installs the new bundle **and** still has the old plugin enabled sees each of
the five templates twice, from two independent sources. The bundle cannot detect or clean up
the plugin's copies — nothing links them.

The remedy is the release note, not code: say that the Flutter Templates plugin is replaced
by the Flutter Templates bundle, and that the plugin should be disabled or uninstalled
first. Disabling it calls `deactivate()`, which unregisters its templates and deletes its
staged files, so the duplicates disappear by the plugin's own existing path.

---

## 10. Provenance, and what it would mean for a `.cgt`

### What provenance is

Every `.cgp` this repository publishes records the commit it was built from. A user reports a
fault. A crash report arrives. Provenance answers one question: **which source produced the
exact file that user installed?**

Without it, the answer is a guess. The gallery serves one URL per addon and overwrites it on
each publish, so "version 1.0.0" can name many different builds.

### How it works for a plugin

The Gradle plugin-builder resolves the revision once per build and writes it three ways: two
`<meta-data>` entries in the manifest, `assets/cgp-build.properties` inside the archive, and
the plugin details dialog in the IDE.

```sh
unzip -p <plugin>/build/plugin/<name>.cgp assets/cgp-build.properties
```

| Key | Meaning |
|---|---|
| `revision` | The commit. `unknown` when none was found |
| `revision_source` | How it was found: `explicit`, `env:<VAR>`, `git`, `git-dir`, or `none` |
| `timestamp` | The committer date in UTC, not the clock |
| `timestamp_source` | `wall-clock` when it fell back to the clock |
| `libs_revision` | Which Code on the Go commit produced the jars it compiled against |

`+dirty` is appended to the revision when the addon's own directory has uncommitted changes.
`scripts/verify-provenance.sh` asserts the record after each build. A missing file or a
missing key fails the run.

### Why a `.cgt` has none of this

All of it is Gradle work, done by `libs/gradle-plugin.jar`. A template has no Gradle build,
so nothing writes the record, and `verify-provenance.sh` would fail on an archive that has no
`assets/cgp-build.properties`.

The gap is real, not cosmetic. A Flutter template bundle is generated on every publish. Two
publishes a month apart produce two different files at the same URL, with nothing inside
either one to tell them apart.

### Decided: write `cgt-build.properties` at the archive root

The build step generates the file, includes it in the archive, and gitignores it. Read it
back the same way as a plugin:

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

The key names match `cgp-build.properties` where they mean the same thing, so one reader
serves both. `libs_revision` is absent on purpose: a template compiles against nothing.

A root entry is inert to all three readers. `ZipTemplateReader` reads only `templates.json`
and the paths it lists. `ZipRecipeExecutor` skips every entry outside `<TemplateName>/`.
`CgtTemplateReader` matches only names ending in `/template/template.json`. I read all three
to confirm this.

### Two rules the build step must keep

1. **Write the committer date, not the clock.** The archive is deterministic today: fixed
   1980 mtimes and a sorted entry list mean one commit gives one archive. A wall-clock
   timestamp would end that, and every rebuild of one commit would differ.
2. **Pin the mtime of the generated file too.** It joins the sorted file list like any other
   entry, so it takes the same `touch -t 198001010000`.

### `verify-provenance.sh`

Extend it rather than exempting templates. Three things in it are plugin-shaped.

**The required keys.** Today:

```bash
REQUIRED_PROVENANCE_KEYS=(name version variant revision revision_source timestamp timestamp_source)
```

`variant` here is the **Android build variant**, debug or release. A `.cgt` has no such
thing, and neither `name` nor `version` is written the same way. The `.cgt` list is
`revision revision_source timestamp timestamp_source addon_id version`.

This is also why the plan says "template" and never "variant" for the five Flutter
directories. The word is already taken in this exact file, for a different thing.

**Where it looks.** It globs `build/plugin/*.cgp`, rejects a run that finds none, and rejects
a run that finds more than one — the second guard exists because a `pluginName` rename leaves
a stale artifact behind. Neither applies. A template's archive is written once, to the path
`scripts/build-cgt.sh` was given.

**What it reads.** `unzip -p "$cgp" assets/cgp-build.properties` becomes
`unzip -p "$cgt" cgt-build.properties`. The record is at the archive root, not under
`assets/`, because a `.cgt` has no `assets/` directory.

Keep unchanged: the warnings for `revision=unknown`, `+dirty` and
`timestamp_source=wall-clock`. All three are legitimate off CI, and they mean the same thing
for either artifact. Drop only the `libs_revision` comparison — a template compiles against
nothing, so there is no pairing to record.
