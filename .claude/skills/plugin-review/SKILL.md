---
name: cogo-plugin-review
description: Review a Code on the Go (CoGo or cotg) plugin project for submission readiness — verify the assemblePlugin build, audit actual security risks, and score the code against the submission rubric (compatibility, resource discipline, build reproducibility, native binaries, reflection ban, html documentation, tooltips and in-app help, manifest completeness, icons and imagery). Use when the user asks to review, audit, or check a Code on the Go plugin repo.
metadata:
  author: Hal Eisen
  keywords:
  - codeonthego
  - cotg
  - plugin
  - submission-review
  - cgp
---

## When to invoke

Triggered by requests like "review this CotG plugin", "does this plugin build", or any plugin project that shows these markers:

- `build.gradle.kts` applies `com.itsaky.androidide.plugins.build`
- `libs/plugin-api.jar` and/or `libs/gradle-plugin.jar` present
- `src/main/AndroidManifest.xml` uses `plugin.*` meta-data keys (`plugin.id`, `plugin.main_class`, `plugin.permissions`, etc.)
- Main class implements `com.itsaky.androidide.plugins.IPlugin`

If none match, stop and tell the user this doesn't look like a CoGo plugin.

## Workflow

Run the four phases in order. Each phase produces a section of the final report.

### Phase 1 — Verify `assemblePlugin` builds

The submission artifact is a `.cgp` file produced by the `assemblePlugin` Gradle task (added by the IDE's `com.itsaky.androidide.plugins.build` plugin; `assemblePluginDebug` exists for debug variant).

1. Ensure `local.properties` exists with `sdk.dir=<path>`. Resolve from `$ANDROID_HOME`, `$ANDROID_SDK_ROOT`, `~/Library/Android/sdk` (macOS), or `~/Android/Sdk` (Linux). Create if missing.
2. `chmod +x gradlew` if needed, then run `./gradlew assemblePlugin`. Allow up to 10 minutes for first-time SDK component downloads.
3. Confirm the artifact path printed by the task (typically `build/plugin/<pluginName>.cgp`).
4. Note any of the following for the report:
   - Compile warnings (deprecated API, unchecked, etc.)
   - SDK components auto-installed (build-tools / platforms)
   - Version skew (e.g. `kotlin-stdlib` newer than the Kotlin Gradle plugin)
   - `buildToolsVersion` / `compileSdk` that exceed what's commonly pre-installed
   - Disabled checks (`tasks.matching { ... }.enabled = false` blocks)

If the build fails: diagnose the root cause. Do not suggest `--no-verify`, disabling checks, or downgrading to make it pass.

### Phase 2 — Audit actual security risks

These are real-world risks, separate from rubric violations. Check each:

- **Committed signing material**: search the working tree (not just `.gitignore`) for `*.keystore`, `*.jks`, `*.p12`, `*.pem`, `release.properties`, `signing.properties`, `keystore.properties`. Confirm against `.gitignore` — the standard Android template comments out `*.jks` / `*.keystore` rules, so check whether they're active. Plaintext `storePassword` / `keyPassword` in any committed file is a leaked signing identity.
- **Other plaintext credentials**: `grep -rIE 'password|secret|token|api[_-]?key' --include='*.properties' --include='*.kts' --include='*.kt' --include='*.java' --include='*.xml'` and triage.
- **XXE**: `XmlPullParserFactory`, `DocumentBuilderFactory`, `SAXParserFactory`, `TransformerFactory`, `SchemaFactory` used on user-supplied XML without `setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)` or explicitly disabling external entities. Regex-based extraction does not introduce XXE.
- **Zip Slip**: `ZipInputStream` / `ZipFile` reads that write `entry.getName()` to disk without verifying the resolved path is `startsWith(destDir.getCanonicalPath() + File.separator)`. Reading entries to in-memory `byte[]` is safe.
- **Path traversal from UI**: user-typed filenames passed to `new File(parent, name)` must be sanitized (e.g. replace `[^a-zA-Z0-9._-]` → `_`) or rejected by a validator before write. A sanitizer that allows `.` is fine since `..` becomes `..` only after `/` collapse — verify the actual regex.
- **Insecure URLs**: `http://` schemes in code paths (Maven repo URLs aren't user-traffic and are out of scope here).
- **`mavenLocal()`** in `settings.gradle.kts` `dependencyResolutionManagement` — supply-chain risk: any locally cached artifact can shadow the published version.
- **Unsafe regex on user input**: catastrophic backtracking patterns.
- **Reflection bypassing API boundaries** — this is also rubric clause 6.6, but if reflection accesses `private`/`@hide` fields of IDE classes it's a security concern as well as a rubric fail.

Each finding gets: file:line, what's wrong in one line, concrete fix.

### Phase 3 — Score against the rubric

See [references/RUBRIC.md](references/RUBRIC.md) for the verbatim criteria. Score each clause **Pass / Partial / Fail** with file:line evidence.

#### 6.1 Compatibility — must support current stable + immediately preceding minor
- Search https://github.com/appdevforall/CodeOnTheGo/releases for the most recent YY.WW release where YY is the 2-digit year and WW is the week-of-the-year. The plugin must support the current release. Ignore releases related to "plugin-api".
- Check `AndroidManifest.xml` for both `plugin.min_ide_version` AND `plugin.max_ide_version` (or whatever range key the API exposes — verify in `plugin-api.jar`'s `PluginMetadata` class).
- Read `README.md`. If it advertises a single point release as the only supported version, that's the explicit reject pattern, even if the manifest range is wider.
- A min-only declaration with no upper bound is **Partial**, not Pass — the rule wants a *range*.

#### 6.2 Resource discipline — four sub-checks
Search the source tree:
- `Executors.new*`, `new Thread`, `GlobalScope.launch`, `CoroutineScope(...)`, `Handler` with a non-main looper → every one must have a matching `shutdown()` / `shutdownNow()` / `cancel()` in `dispose()` or the equivalent lifecycle teardown.
- `static` mutable fields. Any that hold `Context`, `View`, `PluginContext`, IDE service interfaces, or transitively reach them → must be nulled in `dispose()`. A `static volatile Main instance` set in `onInitialized` and never cleared is the canonical fail.
- `BasePlugin.dispose()` (or equivalent) implementation — if it's `{}` and the plugin registers anything stateful, that's a fail.
- `InputStream` / `OutputStream` / `ZipInputStream` / sockets → must be try-with-resources. Long-lived sockets need explicit close in lifecycle teardown.
- LSP plugins: `ProcessBuilder` / `Runtime.exec` children must be terminated when the workspace closes (look for `Process.destroy()` / `destroyForcibly()` wiring).

#### 6.3 Build reproducibility
- `README.md` must document the build command (`./gradlew assemblePlugin`). No mention = **Partial** at best.
- `settings.gradle.kts` must NOT include `mavenLocal()` in `dependencyResolutionManagement` (it's also iffy in `pluginManagement`).
- All dependency versions pinned — no `+`, `latest.release`, or unbounded ranges.
- Phase 1 already verified the build works from a clean checkout. Cite the result here.
- **Provenance record** (ADFA-5256): `unzip -p <plugin>/build/plugin/<name>.cgp assets/cgp-build.properties` on the artifact Phase 1 built. The file is written unconditionally by any builder from 26.36 on, so its **absence** means the submission was built with a pre-5256 builder — note it, do not fail on it. When present, read `revision_source`: `git`, `git-dir`, `explicit` or `env:*` all satisfy "rebuildable from the linked source". `none` (i.e. `revision=unknown`) means the artifact cannot be traced back to a commit → **Partial**, and tell the author to either build from a checkout or declare `pluginBuilder { pluginVcsRevision = "..." }` for a source-archive distribution.
- **`+dirty` in a submitted artifact** → **Partial**: the tree had uncommitted changes in the plugin's own directory, so the linked source does not reproduce it.
- **Manifest wiring** is optional but graded together with the above: if `src/main/AndroidManifest.xml` declares `plugin.vcs_revision` / `plugin.build_timestamp`, both must use `${pluginVcsRevision}` / `${pluginBuildTimestamp}` verbatim — a hardcoded value defeats the point and is a **Fail**. If neither is declared, the record still exists in the archive but the IDE cannot show it; note it as a recommendation, not a defect.
- Do **not** flag a revision that differs from the linked repo's `HEAD` — an artifact built one commit before a `libs/` refresh is normal in this repo's release pipeline (see CLAUDE.md, *Build provenance*). Cross-check `libs_revision` instead.

#### 6.4 Native binaries
- `find src -name '*.so'`, look for `jniLibs/`, `externalNativeBuild`, NDK config.
- If native code present → `plugin.permissions` MUST include `native.code`.
- If absent → `native.code` MUST NOT be requested.

#### 6.5 No reflection into IDE internals
- `grep -rE 'java\.lang\.reflect|Class\.forName|getDeclaredField|getDeclaredMethod|getDeclaredConstructor|setAccessible' src --include='*.java' --include='*.kt'`
- Zero hits = Pass.
- Reflection limited to the plugin's own classes is acceptable — fail only when the target is `com.itsaky.androidide.*` or other IDE-internal types.

#### 6.6 Must have a html documentation file
- Must have the following sections: executive overview, core functionality, technical architecture, usage, key benefits
- May have other sections. If other sections are present, ensure they are relevant and correct
- Must have a white background and body text must be black.
- Must be written in English
- Must be at the top-level of the plugin folder, named after the **plugin folder** (kebab-case) with a `.html` extension: `template-manager/` → `template-manager.html`. The short folder-name form is preferred; a `-documentation` suffix (`random-xkcd-documentation.html`) is also acceptable. Do **not** name it after the lowercase `pluginBuilder { pluginName }` when that differs from the folder (i.e. not `templatemanagerplugin.html`).

#### 6.7 Tooltips and in-app help
Code on the Go has a three-tier in-IDE help model: Tier 1 (brief) and Tier 2 (more detail) are tooltips; Tier 3 is a full offline web page reached from a button on the tooltip. Plugins participate through `DocumentationExtension` (all symbols verifiable in `plugin-api.jar`). This is separate from the 6.6 install-decision page — grade them independently.

- **Implements the extension**: confirm the main class (or one of the plugin's classes) implements `DocumentationExtension`. `grep -rlE 'DocumentationExtension' src --include='*.kt' --include='*.java'`. Absent → **Fail** (no in-app help at all).
- **Every UI element has a tooltip**: reuse the extension enumeration from *Manifest completeness* below. For each contributed `NavigationItem` / `MenuItem` / `TabItem` / FAB / toolbar action, confirm `tooltipTag` is set; for each `EditorTabItem`, confirm `tooltip` is set. Any custom `View` the plugin shows (dialog, fragment, bottom sheet) must be wired to the tooltip system (`IdeTooltipService.showTooltip(...)` or `View.displayTooltipOnLongPress(...)`). One or more contributed elements with no tooltip → **Fail** ("all UI elements" is the bar); a stray non-interactive view missing one → **Partial**.
- **No dangling tags**: collect every `tooltipTag`/`tooltip` value used on UI elements and every `PluginTooltipButton` referenced, then confirm each has a matching `PluginTooltipEntry.tag` returned from `getTooltipEntries()`. A tag with no entry (or an entry with empty `summary`) → **Fail**. `getTooltipCategory()` should return the `plugin_<pluginId>` form.
- **Tier 1 + Tier 2 present**: each `PluginTooltipEntry` should supply a non-empty `summary` (Tier 1) and a `detail` (Tier 2). Summary-only entries across the board → **Partial**.
- **Complete in-app help (Tier 3)**: `getTier3DocsAssetPath()` returns non-null AND the named `assets/<path>/` directory exists with real HTML content (not a stub), and at least one `PluginTooltipButton.uri` links into it. Tier 3 must be served locally/offline — a button that only opens a public `https://` URL is not in-app help. Missing or stub Tier 3 → **Fail**; present but thin/partial coverage → **Partial**.
- Tooltips are wired in code via `DocumentationExtension`, **not** through an `AndroidManifest` `plugin.*` key — do not flag the absence of a manifest entry for documentation.
- Content must be English and readable (light background, dark text), same as 6.6.

#### Manifest completeness
- **Every extension**: enumerate what the main class returns from `getMainEditorTabs()`, `getSideMenuItems()`, `getMainMenuItems()`, `getFabActions()`, `getToolbarActions()`, `getContextMenuItems()`, etc. Each contributed extension type must have a corresponding `plugin.*` meta-data entry (e.g. `plugin.sidebar_items`, `plugin.editor_tabs`). Returning items from a method without declaring the corresponding manifest entry is a fail.
- **Every permission**: every `plugin.*` permission actually used by code paths must appear in `plugin.permissions` (comma-separated). Cross-reference against the permission keys enumerated in `plugin-api.jar`'s `PluginPermission` enum (currently: `filesystem.read`, `filesystem.write`, `network.access`, `system.commands`, `ide.settings`, `ide.environment.write`, `project.structure`, `native.code`).
- **IDE version range**: both `plugin.min_ide_version` and `plugin.max_ide_version` present.
- **minSdk**: either `<uses-sdk>` in the source manifest or `minSdk =` in `build.gradle.kts` (AGP merges the latter into the final manifest, which is conventional and acceptable).

#### 6.8 Plugin icons and imagery
The Plugin Manager shows a day/night icon per plugin; template-installer plugins also show a per-variant thumbnail on the New Project screen. Symbols verifiable in `plugin-api.jar` (`PluginMetadata.iconDayPath`/`iconNightPath`, `templates.CgtTemplateBuilder.thumbnailFromAssets`).

- **Icon meta-data present**: `grep -n 'plugin.icon_day\|plugin.icon_night' src/main/AndroidManifest.xml`. Both keys must be `<meta-data>` on `<application>`. Missing either → **Fail** for debug installs (release builds skip the icon check, so a release-only submission with no icon is at most **Partial** — note it either way). A single `plugin.icon` or reliance on `android:icon`/`res/drawable/ic_plugin.xml` does **not** satisfy this — the Plugin Manager reads the zip path from `plugin.icon_day`/`_night`.
- **Icon path resolves to a real image**: each `android:value` must be an in-`.cgp` path (`assets/<name>.png`). Confirm the file exists at `src/main/assets/<name>.png` (the `assets/` prefix maps to `src/main/assets/`). Run `file src/main/assets/icon_day.png` (and `_night`) — a value pointing at a nonexistent path, or a "PNG" that is actually a Git LFS pointer / non-PNG, → **Fail**. Conventional size ~192×192 (96–256 seen); flag tiny (e.g. 24×24) or clearly-broken images as **Partial**.
- **Template variant thumbnails** (only for template-installer plugins — those calling `IdeTemplateService`/`CgtTemplateBuilder`): for each variant registered via `thumbnailFromAssets("…/thumb.png", context)`, confirm the referenced `thumb.png` exists under `src/main/assets/templates/<Variant>/…`. A missing `thumb.png` for a registered variant → **Fail**. Then check the thumbnails are **distinct**, not identical placeholders — `md5sum src/main/assets/templates/*/template/thumb.png | sort` (adjust the glob to the real layout); two or more variants sharing one byte-identical `thumb.png` → **Fail** (this is the exact defect fixed in commit `0363237`). Conventional size 512×512 PNG. Non-template plugins: **N/A**, say so.
- On-device re-verification caveat: the Plugin Manager caches icons via Glide keyed by path without mtime invalidation, so an updated icon under the same plugin id won't visibly refresh until the Glide disk cache is cleared or you install on a clean device (see CLAUDE.md).

### Phase 4 — Compose the report

One combined report, three sections:

1. **Build** — pass/fail, artifact path, notable warnings, environment quirks.
2. **Security risks** — numbered findings, each with file:line + remediation. If clean, say so explicitly.
3. **Rubric scorecard** — table:

   | Clause | Verdict | Evidence |
   |---|---|---|
   | 6.1 Compatibility | Pass/Partial/Fail | file:line |
   | 6.2 Resource discipline | … | … |
   | 6.3 Build reproducibility | … | … |
   | 6.4 Native binaries | … | … |
   | 6.5 No reflection | … | … |
   | 6.6 Html documentation | … | … |
   | 6.7 Tooltips & in-app help | … | … |
   | 6.8 Icons & imagery | … | … |
   | Manifest declarations | … | … |

End with **Overall verdict**: green-light / conditional (list blockers) / block. A Fail on any clause is a blocker; a Partial is conditional.

## Mandatory rules

- Do not modify the plugin's source unless the user explicitly asks for fixes.
- Cite `file:line` for every finding. No bare assertions.
- Distinguish rubric violations from actual security risks — they can overlap but are graded separately and reported in separate sections.
- Never disable Gradle checks or skip hooks to make a build pass; diagnose root cause instead.
- If a check is N/A (e.g. no LSP server in this plugin), say so explicitly rather than silently omitting it.
- Don't recommend "add a CI workflow", "add unit tests", "write more docs", or other scope expansion beyond what the rubric requires.
