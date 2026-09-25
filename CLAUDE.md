# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository purpose

Reference addons for [Code on the Go](https://github.com/appdevforall/CodeOnTheGo). Addons live under four area directories — `plugins/`, `templates/`, `snippets/` and `code-actions/`. Two unmigrated addons (`cotg-ndk`, `pebble-custom-function-template-installer`) are still at the repository root; that is the only reason `addons discover` still globs `*/build.gradle.kts`.

**The four areas are not alike.** A plugin is a Gradle project that compiles to a `.cgp`; a template is a plain-text tree that zips to a `.cgt`. They share the gallery metadata (`addon.json`), the naming rule and the publish path, and almost nothing else.

## Which kind of addon?

Read the area's own README before working in it. This file holds only what is true for all four.

| Area | What it is | Artifact | Guidance |
|---|---|---|---|
| `plugins/` | Code that runs inside the IDE. An Android application module | `.cgp` | [`plugins/README.md`](plugins/README.md) |
| `templates/` | A project skeleton on the New Project screen. Plain text, no build | `.cgt` | [`templates/README.md`](templates/README.md) |
| `snippets/` | Reusable editor snippets. Placeholder — nothing here yet | `.cgs` | [`snippets/README.md`](snippets/README.md) |
| `code-actions/` | Editor quick-fixes. Placeholder — nothing here yet | — | [`code-actions/README.md`](code-actions/README.md) |

Working on a plugin means reading `plugins/README.md` first: the `libs/` coupling, the credential store, the manifest shape, build provenance and the tooltip wiring all live there, and each has a failure that a green build does not catch.

`templates/README.md` is both halves: how to write a template, then the `.cgt` format reference. The format has no other written specification.

## Git workflow

- **Always work on a branch.** Never commit directly to `main` — branch first (`git switch -c ...`) even for a one-line fix. Working on `main` is almost never right for this repo.
- **Fetch before you diff against `main`.** Any time you compute or reason about a diff against `main` (code review, PR base, "what changed"), run `git fetch origin` first and compare against `origin/main`. A stale local `main` produces phantom findings — a `/code-review` here once flagged 3 issues that were outside the actual PR diff because local `main` was ~50 files behind `origin/main`. When a diff-against-main is requested, suggest fetching first.

## The check every addon passes

```sh
uv run --directory tools/addons addons --root "$PWD" check
```

Run it from the repository root. `--root` must be absolute: `--directory` moves uv into `tools/addons`, so `--root .` resolves there and finds no addons. It is the same gate `check-toolchain.yml` runs on every pull request, it costs a second, and it names the exact file and value it wants. **Treat it as the authority — do not restate its rules here, or the two copies drift.**

Naming is derived, not chosen twice: the directory name in MixedCase with single hyphens is the one human decision, and the slug, display name, page file name and artifact name all follow from it. See `docs/addon-naming-standards.md`.

## Publishing

Every addon publishes to `addons.appdevforall.org` through Cloudflare R2, by the **Publish addons** workflow. The catalog, the gallery page and the download all derive from the directory name and `addon.json`. Nothing needs wiring up by hand.

Build artifacts are **never committed**. A `.cgp` is built by Gradle; a `.cgt` is zipped by the Action.

## Verification

**A successful build is not verification.** It only proves the addon compiles or packages. Real verification is device-level: install on a connected emulator or device, exercise the feature end to end, and observe the expected behaviour (UI element appears, file written, build hook fires, DB row replaced, template generates a project that opens).

If device verification isn't possible in-session, say so explicitly rather than calling the change verified. This applies especially to addons that mutate IDE state (`documentation.db`, settings, filesystem, project structure).

**Launching Code on the Go via adb.** Do **not** launch with `monkey` or a bare LAUNCHER intent (`adb shell monkey -p com.itsaky.androidide …`) — debug builds bundle **LeakCanary**, which registers its own launcher activity, so the intent can open LeakCanary's "Leaks" screen or a disambiguation chooser instead of the IDE. Start the explicit component: `adb shell am start -n com.itsaky.androidide/.activities.SplashActivity`. When re-verifying a plugin **icon** change under the same plugin id, note that the Plugin Manager caches icons via Glide (`cache/image_manager_disk_cache`) keyed by path without mtime invalidation — the old icon persists until that cache is cleared (`adb root`, delete the dir, restart) or you install on a clean device.

## Plugin review skill

`.claude/skills/plugin-review/` contains the `cogo-plugin-review` skill — invoke via `/plugin-review` or `/cotg-plugin-review` when the user asks to review, audit, or check a Code on the Go plugin for submission readiness. It builds, audits security, and scores against the submission rubric.

**Proactively offer `/plugin-review`** (don't wait for the user to ask) after any substantive change to a plugin: importing a new plugin folder, modifying dependencies, touching the `IPlugin`/`PluginContext` API surface, adding shipped assets, or updating `libs/`. It has caught real defects (resource leaks, missing manifest entries, missing in-IDE help HTML) that aren't visible from a clean `assemblePlugin` build.

The rubric is written for plugins. It does not apply to a template.
