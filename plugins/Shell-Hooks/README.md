# Shell Hooks

Runs the user's shell scripts at Code on the Go's integration points, git-hooks style, so IT
staff can fit the IDE into existing workflows without Android development (ADFA-3570).

Off until the user turns it on. Requires Code on the Go **26.47** (`plugin.min_ide_version`),
the first release with `IdeProjectService.addProjectLifecycleListener` and
`IdeEditorService.addFileSaveListener`.

The user-facing guide is `src/main/assets/docs/index.html` (the Tier 3 page) and the gallery
page is `shell-hooks.html`. Both document the hook table, environment, layout and trust model;
keep them in step with `engine/Hook.kt` and `engine/HookRunner.kt`.

## Hooks

| Script | Source | `$*` |
|---|---|---|
| `ide-started` | `IPlugin.activate` | none |
| `project-opened` / `project-closed` | `ProjectLifecycleListener` | `$1` = project folder |
| `file-opened` / `file-closed` | `FileOpenExtension.onFileOpened` / `onFileClosed` | `$1` = file |
| `file-activated` | `FileChangeListener`, last of a 500 ms burst | `$1` = file or empty |
| `file-saved` | `FileSaveListener` | `$1` = file |
| `build-started` / `build-succeeded` / `build-failed` | `BuildStatusListener` | failed: `$1` = error or `cancelled` |

Environment: `COGO_HOOK`, `COGO_PROJECT_DIR`, `COGO_HOOKS_DIR`, `COGO_HOOK_SCRIPT`,
`COGO_HOOK_SOURCE` (`global`/`project`), `COGO_IDE_VERSION` when known.

Layout: `~/.config/codeonthego/hooks/<hook>`, then `<hook>.d/*` in byte order; executable files
only; `*.sample`, dotfiles and `*~` skipped. `hooks/actions/*` become "Run <name>" file-menu
entries that run in the Terminal. A project's own hooks live in `.codeonthego/hooks/`.

## Design decisions

- **Values travel as argv.** Headless hooks run as
  `ShellCommand("sh", [cogo-hook-run.sh, script, args...])`; no value is ever part of a shell
  string. Only actions go through a command line (the Terminal takes one), built with
  `ShellQuote`.
- **`cogo-hook-run.sh`** maps a shebang onto `$PREFIX/bin` the way termux-exec would, because the
  IDE's command service does not preload termux-exec and Android has no `/usr/bin/env` or
  `/bin/bash`. It also `cd`s to `COGO_PROJECT_DIR`: the host refuses a working directory outside
  the *open* project, which `project-closed` would hit.
- **One script at a time**, from a 64-event queue that drops the oldest. The host allows three
  concurrent commands per plugin and throws beyond that; serial execution also keeps a burst of
  saves from starting a burst of processes. Per-script timeout from settings (default 60 s).
- **Project hooks need trust.** Any plugin with `filesystem.write`, or an AI agent, can write into
  a project, so a project hook that ran unprompted would let them run commands the user never
  approved. Trust is keyed on project + relative path + SHA-256; a trusted script runs from a
  private copy of the trusted bytes (`<plugin data>/verified/<sha>`) to close the
  check-then-run window.
- **Notifications only.** No hook can veto a save or a build; the host callbacks report the past.
- **Paths.** `HOME` and `PREFIX` are `<app files>/home` and `<app files>/usr`, the values
  `TermuxProcessEnvironment` gives every command, derived from `PluginContext.getAppFilesDir()`.

## Build and test

```sh
cd plugins/Shell-Hooks
../../gradlew assemblePlugin        # build/plugin/shell-hooks.cgp
../../gradlew testDebugUnitTest
```

`WrapperScriptTest` runs the wrapper under every POSIX shell it finds (`/bin/sh`, dash, bash,
busybox ash). Android runs it with mksh, which those tests do not cover.
