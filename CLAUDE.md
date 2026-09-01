# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository purpose

Reference plugins for [CodeOnTheGo](https://github.com/appdevforall/CodeOnTheGo) (CoGo / CotG). Addons live under `plugins/`, with `templates/`, `snippets/`, and `code-actions/` reserved beside it. Two unmigrated addons (`cotg-ndk`, `pebble-custom-function-template-installer`) are still at the repository root; that is the only reason `addons discover` still globs `*/build.gradle.kts`. Each addon directory is an independent Gradle project that builds a `.cgp` installable via the Plugin Manager. They are held together only by the shared `libs/` jars at the repo root, which an addon references as `../../libs/`.

## Common commands

Build one plugin:

```sh
cd plugins/Voice-Alerts   # or any addon folder
./gradlew assemblePlugin           # release .cgp -> build/plugin/<pluginName>.cgp
./gradlew assemblePluginDebug      # debug variant
```

Build every plugin from scratch (after rebuilding libs):

```sh
./scripts/update-libs.sh                          # default: github.com/appdevforall/CodeOnTheGo@stage
./scripts/update-libs.sh --ref <branch-or-tag>
./scripts/update-libs.sh --local ../CodeOnTheGo   # use an existing checkout instead of cloning
```

The script clones CoGo into `.cache/CodeOnTheGo/` on first run, rebuilds both jars, copies them into `libs/`, then runs `assemblePlugin` for every example. It auto-detects examples by scanning for `build.gradle.kts` files that apply `com.itsaky.androidide.plugins.build`.

`local.properties` must contain `sdk.dir=...`. The committed `local.properties` at the repo root is harmless leftover; each plugin needs its own.

## Git workflow

- **Always work on a branch.** Never commit directly to `main` — branch first (`git switch -c ...`) even for a one-line fix. Working on `main` is almost never right for this repo.
- **Fetch before you diff against `main`.** Any time you compute or reason about a diff against `main` (code review, PR base, "what changed"), run `git fetch origin` first and compare against `origin/main`. A stale local `main` produces phantom findings — a `/code-review` here once flagged 3 issues that were outside the actual PR diff because local `main` was ~50 files behind `origin/main`. When a diff-against-main is requested, suggest fetching first.

## Architecture

### `libs/` is the load-bearing piece

`libs/` holds **five** jars. Every plugin depends on at least these two:

- **`plugin-api.jar`** — the IDE-side API surface (`IPlugin`, `PluginContext`, `BuildStatusListener`, `IdeBuildService`, etc.). Each plugin uses it as `compileOnly` (provided by the IDE at runtime) AND as `buildscript classpath` so the Gradle plugin can resolve symbols at configuration time.
- **`gradle-plugin.jar`** — the Gradle plugin with id `com.itsaky.androidide.plugins.build`, applied by every plugin. It's the output of CoGo's `plugin-api/plugin-builder/` module (separate from CoGo's `gradle-plugin/` module, which is unrelated despite the name). It packages the compiled Android library into a `.cgp`.

There is also **one shared Gradle wrapper at the repo root** (`gradlew` + `gradle/wrapper/`). New plugins should use it — build them with `cd plugins/<Addon> && ../../gradlew assemblePlugin` rather than bundling a per-plugin `gradlew`/`gradle/wrapper/` copy. (`Flutter-Templates` and the five `AI-*` addons follow this; most older plugins still carry their own local wrapper and can be migrated opportunistically.)

An addon under `plugins/` references the shared jars as `../../libs/*.jar`. **Always use the repo-root `libs/` jars and the repo-root Gradle wrapper — never bundle per-plugin copies.** A plugin that ships its own `libs/plugin-api.jar` / `libs/gradle-plugin.jar` (e.g. copied from another plugin) can drift out of sync with the rest of the repo; point `build.gradle.kts` (`compileOnly`) and `settings.gradle.kts` (buildscript `classpath`) at `../../libs/*.jar` and delete any local `libs/`. The root `plugin-api.jar` already carries the full API surface (including `IdeTemplateService`/`CgtTemplateBuilder`), so newer sub-APIs do not justify a local copy. **A plugin folder is not standalone in isolation** — copy the root `libs/` along if you move one elsewhere. When CoGo's API changes, refresh via the script above or the **Update libs from CodeOnTheGo** GitHub Action (which commits the refreshed jars and cuts a release). Publishing addons is a separate workflow, **Publish addons**, which uploads to Cloudflare R2.

### Credentials: use the host's `KeystoreSecretStore`, never your own crypto

A plugin that stores a credential encrypts it with `com.itsaky.androidide.plugins.security.KeystoreSecretStore`
from `plugin-api.jar` (**26.36+** — set `plugin.min_ide_version` accordingly). It is `compileOnly`
like the rest of the API, so there is one implementation in the IDE's process rather than a copy
compiled into each `.cgp`. Do not re-implement AES/GCM in a plugin; three AI plugins each grew a
copy that started to diverge, which is what ADFA-5255 removed.

Construct it with **this plugin's own alias** (`KeystoreSecretStore(ALIAS)`) as a single
top-level `val` in a `SecureApiKeyStore.kt`/`SecureTokenStore.kt` that holds nothing but the alias;
callers use that instance directly. It takes no log tag — the store logs under its own name — and
that single-argument constructor is the only one a plugin can reach: the two-argument form takes an
`internal` `SecretKeySource`, which is not on the plugin's compile classpath at all.
`ai-agent-mcp`, `ai-agent-gemini` and `ai-agent-openai` are the reference shape. Do **not** wrap
it in an object of forwarding methods — that is just a second copy of the store's contract to keep
in step. The alias must be unique per plugin (all plugins share the host's UID and Keystore, so a
shared alias lets one plugin's invalidated-key recovery delete another's secret) and must never
change across releases.

`readAndMigrate` returns a four-way `Stored` rather than a nullable String on purpose — each state
needs different advice, and a plugin that collapses them tells a user their credential was refused
when it was never sent:

- `Absent` — nothing was ever saved. The ordinary first run; say nothing.
- `Value` — the plaintext. Trim it at the call site if your credential format wants it;
  `readAndMigrate` migrates verbatim.
- `Unreadable` — stored, but this device's Keystore can no longer open it (a restored backup, an
  OEM Keystore reset). Permanent: the user has to enter it again.
- `Unavailable` — the Keystore would not answer this time. **Transient: the credential is intact,
  so retry and never re-prompt.** In particular a pane that reads `Unavailable` must not dress
  itself as never-configured, and nothing on that screen may write over the credential it could
  not read — an empty field then means "not shown", not "removed".

Handle all four; collapse them only where the caller genuinely has one answer for every state, and
say so in a comment.

### Plugin shape

A plugin is an Android *application* module (despite installing as a library) with:

1. **`build.gradle.kts`** applies `com.android.application`, `org.jetbrains.kotlin.android`, and `com.itsaky.androidide.plugins.build`. Configures `pluginBuilder { pluginName = "..." }`. Uses `compileOnly(files("../../libs/plugin-api.jar"))` — never `implementation`.
2. **`settings.gradle.kts`** declares the jars it needs on the buildscript classpath plus AGP and Kotlin.
3. **`src/main/AndroidManifest.xml`** declares plugin identity as `<meta-data>` entries on `<application>`: `plugin.id`, `plugin.name`, `plugin.version` (resolved from `${pluginVersion}`), `plugin.description`, `plugin.author`, `plugin.main_class`, `plugin.min_ide_version`, and optional `plugin.permissions`. Optionally `plugin.vcs_revision` / `plugin.build_timestamp` — see **Build provenance** below.
4. **Main class** implements `com.itsaky.androidide.plugins.IPlugin`. Lifecycle: `initialize(PluginContext) → activate() → deactivate() → dispose()`. Services are obtained via `context.services.get(SomeService::class.java)` (e.g. `IdeBuildService` for build hooks). Android `Context` is `context.androidContext`.

Available permission strings (declared comma-separated in `plugin.permissions`): `filesystem.read`, `filesystem.write`, `network.access`, `system.commands`, `ide.settings`, `project.structure`.

### Build provenance (`plugin.vcs_revision`, `cgp-build.properties`)

Every `.cgp` records the commit it was built from, so a crash report or a support question traces back to source (ADFA-5256). The builder resolves it once per build and publishes it three ways: two `<meta-data>` entries, `assets/cgp-build.properties` inside the archive, and the IDE's plugin details dialog.

Manifests opt in by referencing the placeholders — the builder never injects `<meta-data>` on your behalf:

```xml
<meta-data android:name="plugin.vcs_revision"    android:value="${pluginVcsRevision}" />
<meta-data android:name="plugin.build_timestamp" android:value="${pluginBuildTimestamp}" />
```

**This is a hard build-time coupling to `libs/gradle-plugin.jar`.** A manifest that references a placeholder the builder does not define fails the manifest merger outright (*"requires a placeholder substitution but no value ... is provided"*), and all plugins resolve the builder from the single committed jar. So a manifest may only adopt these **after** the builder change is merged in CoGo and the **Update libs from CodeOnTheGo** Action has refreshed `libs/`. Never the other way round. The same coupling hits on-device builders, whose builder jar ships in `plugin-maven-repo.zip` and refreshes only with a CoGo **app release** — a plugin referencing these cannot be built inside an older CoGo at all.

Read the record out of a built artifact:

```sh
unzip -p <plugin>/build/plugin/<name>.cgp assets/cgp-build.properties
```

`revision_source` says how the revision was found, in the order the builder tries: `explicit` (you set `pluginBuilder { pluginVcsRevision = "..." }`) → `env:<VAR>` (`PLUGIN_VCS_REVISION`, `GITHUB_SHA`, `CI_COMMIT_SHA`, `GIT_COMMIT`) → `git` → `git-dir` (reads `.git` directly; this is the on-device path, since CoGo ships JGit in-process and no `git` binary) → `none`, which means `revision=unknown`. `+dirty` is appended when the plugin's **own** directory has uncommitted changes; the check is scoped to that directory so a `libs/` refresh elsewhere in the tree does not flag the build.

`timestamp` is the committer date of that revision in UTC, not the wall clock, and it lands in the version string as well (`1.0.0-release.<timestamp>`), so two builds of one commit produce a `.cgp` whose every entry matches by CRC and mtime (see CoGo's ADR-0012). The archives are not byte-identical: the v2 APK Signing Block differs between runs, so compare entry CRCs rather than a file hash. That determinism holds only where the builder could reach a `git` binary; it falls back to the clock and says so with `timestamp_source=wall-clock`, and the stamp it puts in the version string then changes on every build, so the artifact is **not** reproducible even entry-by-entry. On device it is always the fallback — CoGo ships no `git` — and under `--configuration-cache` the clock reading additionally freezes into the cached configuration.

`+dirty` has one systemic cause worth designing against: **a build-time download must land on a gitignored path.** A `downloadAssets` task that overwrites a git-tracked file (`ndk-installer` shipped a committed placeholder `ndk-cmake.tar.xz` until it was untracked) dirties the plugin directory on every build, so every artifact it ever produces records `+dirty` and no build of that plugin is traceable to a clean commit. Both download plugins now fetch onto ignored paths (`plugins/NDK-Installer/.gitignore`, `plugins/AI-Literacy-Course/.gitignore`); keep it that way when adding a new one.

`libs_revision` records which CoGo commit produced the jars the plugin was compiled against. The builder cannot see that checkout, so `scripts/update-libs.sh` exports `PLUGIN_LIBS_REVISION` before the build loop — both Actions workflows inherit it through the script. Note that under **Update libs from CodeOnTheGo** the plugin's own `revision` is the commit *before* the `chore: update libs` commit, because plugins are built before that commit is created; `libs_revision` is what pins the pairing.

`scripts/update-libs.sh` asserts the record after each `assemblePlugin`: a `.cgp` missing `assets/cgp-build.properties`, missing any required key, or disagreeing with the exported `PLUGIN_LIBS_REVISION` fails the run. `revision=unknown`, `+dirty` and `timestamp_source=wall-clock` warn instead — all three are legitimate off-CI (no `.git`, no `git` binary). Both Actions workflows build through the script, so this covers them without a per-workflow check.

### In-app help wiring (tooltips + Tier 3, `DocumentationExtension`)

Every plugin with UI implements `com.itsaky.androidide.plugins.extensions.DocumentationExtension`. This wiring is fixed and foundational — get **all** of it right or the tooltip renders the literal string **`n/a`** at runtime. The build stays green and the manifest looks fine, so **only device long-press testing catches a mistake** (this bit us once). All symbols are in `plugin-api.jar`.

1. **Category is `"plugin_<pluginId>"` — exactly.** `getTooltipCategory()` MUST return `"plugin_"` + the full `plugin.id` (e.g. `"plugin_org.appdevforall.projecttotemplate"`). The host registers your entries under this string **and** derives the same string when resolving a lookup. Any other value — a short slug, a dotless/underscore form — silently mismatches → `n/a`.
2. **Entries.** `getTooltipEntries()` returns `PluginTooltipEntry(tag, summary, detail, buttons)`: `summary` = Tier 1 (one line shown on long-press), `detail` = Tier 2 (HTML behind "See more"). Keep the `tag` in one shared `const val` used by steps 3–4.
3. **Look tooltips up with the 3-arg overload.** Call `IdeTooltipService.showTooltip(anchorView, category, tag)` and pass `category = "plugin_<pluginId>"` explicitly. **Never use the 2-arg `showTooltip(view, tag)`** — it resolves under a different default category and renders `n/a` even when the entry is registered correctly. Param order is `(anchorView, category, tag)`.
4. **Attach tags to UI.** Set `tooltipTag = <that same tag>` on every contributed `NavigationItem` / `TabItem` / menu item / FAB; `EditorTabItem` instead takes a literal `tooltip = "..."` string. A contributed element with no tooltip fails review clause 6.7.
5. **Tier 3 (offline page).** Override `getTier3DocsAssetPath()` to return an assets subdir name (convention: `"docs"`), ship real HTML at `src/main/assets/<dir>/index.html` (white background, black text, English), and link it from an entry via `PluginTooltipButton(description, uri = "index.html", order = 0)` — leave `directPath` false (`true` targets the host's shared docs tree, not your bundle).

Debug a mismatch against the on-device store (`adb root` first):
`sqlite3 /data/data/com.itsaky.androidide/databases/documentation.db "SELECT c.category, t.tag, substr(t.summary,1,40) FROM Tooltips t JOIN TooltipCategories c ON c.id=t.categoryId WHERE c.category LIKE 'plugin_%'"`. If the row is present but the tooltip still shows `n/a`, the bug is the **lookup** (step 1 or 3), not registration. (The unused `ide_tooltip_table` is a red herring — plugin entries live in `Tooltips` + `TooltipCategories`.)

### Convention: AAR metadata checks are disabled

Most plugins end with:

```kotlin
tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach { enabled = false }
```

This is intentional — the `application`-as-library packaging trips those checks. Keep it.

### Asset downloads (rare)

Some plugins (`ndk-installer`, `ai-literacy-course`) register a `downloadAssets` task that fetches large files at build time with pinned-MD5 verification. These assets are **not committed to git** — each plugin gitignores its own download paths (`ai-literacy-course` pulls a ~110 MB course ZIP plus `pdfjs.zip`; `ndk-installer` pulls `ndk-cmake.tar.xz`). Committing one, even as a placeholder, makes every build dirty — see **Build provenance** above. `scripts/update-libs.sh` runs `downloadAssets` automatically before `assemblePlugin` when the build file references it.

**Gotcha: a bare `./gradlew assemblePlugin` does NOT run `downloadAssets` and does not warn when the assets are missing** — it silently packages a broken `.cgp` (e.g. a course with no PDF viewer). When building such a plugin by hand, run `./gradlew downloadAssets` and then `./gradlew assemblePlugin` as two separate invocations (or use the script, which does exactly that). Combining them in one invocation fails: `downloadAssets` declares an output inside `src/main/assets`, which Gradle sees as an undeclared dependency of `mergeReleaseAssets`. Then confirm the expected files exist under `src/main/assets/` (or `unzip -l` the `.cgp`) before trusting it. `ndk-installer` is the exception: its asset merge fails outright when `ndk-cmake.tar.xz` is absent, rather than shipping an NDK-less plugin.

### One-time on-device install markers

Plugins that extract bundled assets on-device once (currently `ai-literacy-course`, via `CourseInstaller`) gate the work behind a marker file named from a version constant (`INSTALL_VERSION` → `.installed-vN`). If the marker for the current version exists, extraction **and** any post-extraction generation (e.g. `CourseShell.generate()`) are skipped entirely.

**Any change to extraction OR post-extraction generation logic must bump `INSTALL_VERSION`.** Otherwise the change compiles and packages cleanly but has zero effect on existing installs — they keep the stale extracted tree, and it looks like "my fix didn't work" (costing a device round-trip). Bumping the constant forces a clean re-extract. On a device with a prior install, confirm the marker version changed (or wipe plugin data) before concluding a fix works — see Verification below.

### Template-installer plugins (Pebble `.cgt`)

Some plugins are headless template installers (`flutter-template`, `pebble-custom-function-template-installer`): on `activate()` they register project templates with `IdeTemplateService` (building each `.cgt` from Pebble `.peb` skeletons under `src/main/assets/templates/<Variant>/`), and unregister on `deactivate()`. The templates then appear on the New Project screen beside the core ones. The source-of-truth skeletons follow the pattern in `~/src/dev-assets/templates`.

**Pebble gotcha:** a bare `${{TAG}}` at end-of-line loses its trailing newline to Pebble's newline-trimming and merges with the next line (this silently produced invalid YAML by collapsing `name:` and `description:` into one line). Ensure a non-newline character follows `}}` — the convention is to quote the value, e.g. `name: "${{APP_NAME | lower}}"`.

## Verification

**`./gradlew assemblePlugin` succeeding is not verification** — it only proves the plugin compiles and packages. Real verification for these plugins is device-level: push the built `.cgp` to a connected emulator/device, install through CoGo's Plugin Manager, exercise the feature end-to-end, and observe the expected behavior (UI element appears, file written, build hook fires, DB row replaced, etc.).

If device verification isn't possible in-session, say so explicitly rather than calling the change verified. Build success is necessary but never sufficient — this applies especially to plugins that mutate IDE state (`documentation.db`, settings, filesystem, project structure).

**Launching CoGo via adb.** Do **not** launch with `monkey` or a bare LAUNCHER intent (`adb shell monkey -p com.itsaky.androidide …`) — debug builds bundle **LeakCanary**, which registers its own launcher activity, so the intent can open LeakCanary's "Leaks" screen or a disambiguation chooser instead of the IDE. Start the explicit component: `adb shell am start -n com.itsaky.androidide/.activities.SplashActivity`. When re-verifying a plugin **icon** change under the same plugin id, note that the Plugin Manager caches icons via Glide (`cache/image_manager_disk_cache`) keyed by path without mtime invalidation — the old icon persists until that cache is cleared (`adb root`, delete the dir, restart) or you install on a clean device.

## Adding a new plugin

1. Copy `plugins/Random-XKCD/` — it's the canonical starting template (small but complete, includes the in-IDE help HTML pattern that submissions are expected to follow). Name the new directory in MixedCase with single hyphens between words (`APK-Analyzer`), ASCII letters and digits only. Every other name, filename, and URL derives from it — see `docs/plugin-naming-standards.md`.
2. Update **every** copied file that still names the template. Two values come from the directory name: the **slug** is it lowercased (`apk-analyzer`), the **display name** is it with hyphens replaced by spaces (`APK Analyzer`).

   | File | Change |
   |---|---|
   | `settings.gradle.kts` | `rootProject.name` — Gradle's own name for the build. Nothing derives from it; keep it in step with the slug anyway. |
   | `build.gradle.kts` | `pluginBuilder { pluginName }` → the slug; `android { namespace, applicationId }` |
   | `src/main/AndroidManifest.xml` | `plugin.id`, `plugin.name` → the display name, `plugin.main_class` |
   | `random-xkcd.html` | rename to `<slug>.html`; set `<title>` to the display name exactly, and make the `<h1>` contain it |
   | `addon.json` | `summary`, `description`, `tags`, `origin`, `license`, `author`. The schema checks the shape, not the words, so a copied one passes every check and puts xkcd's description on your gallery card. |
   | `src/main/assets/icon_day.png`, `icon_night.png` | replace both; both must be present |
   | `src/main/kotlin/...` | your implementation |

3. Run `uv run --directory tools/addons addons --root "$PWD" check` from the repository root before pushing. `--root` must be absolute: `--directory` moves uv into `tools/addons`, so `--root .` resolves there and finds no addons. It is the same gate `check-toolchain.yml` runs on every pull request, it costs a second, and it names the exact file and value it wants. Treat it as the authority — do not restate its rules here, or the two copies drift.
4. Add a row to the README's Examples table.
5. Nothing else to wire up. Publishing is automatic: `addons discover` finds any directory whose `build.gradle.kts` applies the plugin-builder, and the name, filenames, and URLs all derive from the directory name. The `MAP` array this file used to describe was deleted in #66 and no longer exists.

## Plugin review skill

`.claude/skills/plugin-review/` contains the `cogo-plugin-review` skill — invoke via `/plugin-review` or `/cotg-plugin-review` when the user asks to review, audit, or check a CoGo plugin for submission readiness. It builds, audits security, and scores against the submission rubric.

**Proactively offer `/plugin-review`** (don't wait for the user to ask) after any substantive change to a plugin: importing a new plugin folder, modifying dependencies, touching the `IPlugin`/`PluginContext` API surface, adding shipped assets, or updating `libs/`. It has caught real defects (resource leaks, missing manifest entries, missing in-IDE help HTML) that aren't visible from a clean `assemblePlugin` build.
