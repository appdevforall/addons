# Project templates

A template adds an entry to the **New Project** screen in Code on the Go. It ships as a
`.cgt` file: a ZIP that holds a project skeleton plus a small metadata file. One `.cgt` can
hold several templates.

A template is not a plugin. It runs no code. It has no `build.gradle.kts`, no
`AndroidManifest.xml` and no Kotlin. If your idea needs to run code, write a plugin instead —
see `../plugins/` and the repository `CLAUDE.md`.

For the format itself, read [`cgt-templates.md`](cgt-templates.md).

## Start from `Flutter-Templates`

`templates/Flutter-Templates/` is the reference. Copy it and change what is yours. It is small
but complete: five templates in one bundle, an `addon.json` with the `template` block, a
gallery page, both icons, and thumbnails. Every rule below is visible in it.

It is to a template what `plugins/Random-XKCD/` is to a plugin — the thing to copy, and the
thing a reviewer compares your submission against.

---

## Make a template

### 1. Make the directory

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

### 2. Put your project skeleton under `<TemplateName>/`

Start from a project that already builds. Copy it in whole, then replace the parts that
change per project with placeholders.

### 3. Mark the files that need substitution

Add `.peb` to any file that contains a placeholder. Code on the Go renders it and removes the
suffix. Every other file is copied byte for byte, so binary files are safe.

`app/build.gradle.kts` becomes `app/build.gradle.kts.peb`.

### 4. Write the placeholders

The delimiters carry a `$` prefix. This is not stock Pebble syntax.

| Purpose | Syntax |
|---|---|
| Print a value | `${{APP_NAME}}` |
| Run a statement | `${% if LANGUAGE == 'kotlin' %} ... ${% endif %}` |
| Comment | `${# not rendered #}` |

Filters work:

```
name: "${{APP_NAME | lower}}"
${{PACKAGE_NAME | replace({"." : "_"})}}
```

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

### 5. Use placeholders in paths too

A directory or a file name is substituted as well.

```
app/src/main/java/PACKAGE_NAME/MainActivity.kt.peb
```

With the package `com.example.myapp` this becomes
`app/src/main/java/com/example/myapp/MainActivity.kt`. `CLASS_NAME` works the same way.

### 6. Write `<TemplateName>/template/template.json`

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

### 7. Write `templates.json`

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

### 8. Write `addon.json`

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
    "minAppVersion": "26.36"
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

### 9. Add the page and the icons

`my-templates.html` is your gallery page. Copy the shape from
`../plugins/Random-XKCD/random-xkcd.html`. The rules the checker enforces:

- The file name is the slug: the directory name in lower case.
- `<title>` is the display name exactly: the directory name with hyphens replaced by spaces.
- The `<h1>` contains that name.
- No `<script>` and no inline event handler. These pages are served from the site origin.
- Write **Code on the Go** in full. Not CoGo, not CotG, not CodeOnTheGo.

`icon_day.png` and `icon_night.png` are both required.

### 10. Check it

```sh
uv run --directory tools/addons addons --root "$PWD" check
```

Run it from the repository root. `--root` must be absolute. This is the same gate that runs
on every pull request. Treat its output as the authority.

### 11. Build it and look inside

```sh
./scripts/build-cgt.sh templates/My-Templates out
unzip -l out/my-templates.cgt
```

This is the same script the publish workflow runs, so a local build and a published one agree.
It takes the file list from `templates.json`, pins every mtime, and writes a stored,
sorted archive.

Confirm `templates.json` appears bare at the root, with no directory in front of it. The IDE
looks it up by that exact name and finds nothing otherwise. Confirm too that `addon.json`,
your HTML page and your icons are **not** in the listing.

If `find` reports a missing path, `templates.json` names a directory that does not exist.
Fix the spelling. The IDE would skip it in silence.

The published `.cgt` also carries `cgt-build.properties` at the root, which the Action writes.
It records the commit the bundle was built from. Your local build does not produce it.

**Never commit the `.cgt`.** The Action builds it on publication. Add `*.cgt` to your
`.gitignore`.

### 12. Test it on a device

Copy the `.cgt` to the device Downloads folder, open the Templates manager, then open New
Project and generate a project from every template you shipped.

A passing check and a clean zip prove nothing about whether the template works. The renderer
runs with strict variables: a placeholder that no parameter declares fails **at project
generation**, on the device, and nowhere earlier.

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
