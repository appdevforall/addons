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
| `ide-started` | `IPlugin.activate`, once per IDE launch (`LaunchGate`) | none |
| `project-opened` / `project-closed` | `ProjectLifecycleListener` | `$1` = project folder |
| `file-opened` / `file-closed` | `FileOpenExtension.onFileOpened` / `onFileClosed` | `$1` = file |
| `file-activated` | `FileChangeListener`, last of a 500 ms burst | `$1` = file or empty |
| `file-saved` | `FileSaveListener` | `$1` = file |
| `build-started` / `build-succeeded` / `build-failed` | `BuildStatusListener` | failed: `$1` = the IDE's message, also on a cancel |

Environment: `COGO_HOOK`, `COGO_PROJECT_DIR`, `COGO_HOOKS_DIR`, `COGO_HOOK_SCRIPT`,
`COGO_HOOK_SOURCE` (`global`/`project`), `COGO_IDE_VERSION` when known.

Layout: `~/.config/codeonthego/hooks/<hook>`, then `<hook>.d/*` in byte order; executable files
only; `*.sample`, dotfiles and `*~` skipped. `hooks/actions/*` become "Run <name>" file-menu
entries (files only, not folders) that run in the Terminal. A project's own hooks live in
`.codeonthego/hooks/` and need no executable bit: projects sit on shared storage, which is
mounted noexec, and a trusted project script runs from a private copy the plugin marks executable.

## Design decisions

- **Values travel as argv.** Headless hooks run as
  `ShellCommand("sh", [cogo-hook-run.sh, script, args...])`; no value is ever part of a shell
  string. Only actions go through a command line (the Terminal takes one), built with
  `ShellQuote`.
- **`cogo-hook-run.sh`** maps a shebang under `/bin`, `/usr/bin` or `/usr/local/bin` onto
  `$PREFIX/bin` the way termux-exec would, because the IDE's command service does not preload
  termux-exec and Android has no `/usr/bin/env` or `/bin/bash`. Any other interpreter path
  (`/system/bin/sh`, a virtualenv's python) runs as written. It also `cd`s to `COGO_PROJECT_DIR`:
  the host refuses a working directory outside the *open* project, which `project-closed` would hit.
- **Timeouts stop the whole script.** The wrapper runs the script under the Terminal's GNU
  `timeout` (`COGO_TIMEOUT_SECONDS`), which puts it in its own process group and stops the group,
  so a hung `git` or `gradlew` the script started does not outlive it. The host gets the limit plus
  10 s and the runner gives up 5 s after that. Without `$PREFIX/bin/timeout`, and when the plugin
  is disabled or unloaded mid-run, the IDE SIGKILLs only the script's first process.
- **One script at a time**, from a 64-event queue that drops the oldest, in the order events reach
  the plugin. That is not always the order things happened: the IDE reports project and save events
  through a thread of its own. The host allows three concurrent commands per plugin and throws
  beyond that; serial execution also keeps a burst of saves from starting a burst of processes.
  Per-script timeout from settings (default 60 s).
- **Project hooks need trust.** A script can arrive with a project (a cloned repository, an AI
  agent's edit, a plugin that can only write project files), so a project hook that ran unprompted
  would run commands the user never approved. Trust is keyed on project + relative path + SHA-256;
  a trusted script runs from a private copy of the trusted bytes (`<plugin data>/verified/<sha>`,
  pruned of untrusted hashes on activation) to close the check-then-run window. Scripts over
  256 KB are never read, run or offered for review. The review dialog shows bidi controls,
  zero-width and control characters as `<U+XXXX>` and warns about them.
- **What trust does not cover.** A plugin with `system.commands`, or one that writes files
  in-process, can already write the global hooks folder or the trust store. Global hooks run
  without review because they live in the user's own home folder; install only trusted plugins.
- **Notifications only.** No hook can veto a save or a build; the host callbacks report the past.
- **Paths.** `HOME` and `PREFIX` are `<app files>/home` and `<app files>/usr`, the values
  `TermuxProcessEnvironment` gives every command, derived from `PluginContext.getAppFilesDir()`.
- **Older IDEs.** The 26.47 listeners live in `WorkspaceHooks`, which only loads inside a guarded
  block, so on an older IDE the plugin still loads and runs every hook except project-opened,
  project-closed and file-saved. (The host does not enforce `min_ide_version`.)
- **Permissions.** `system.commands` runs scripts; `filesystem.read` covers the open file, the
  project root and the listeners; `filesystem.write` covers "Create sample hooks".

## Build and test

```sh
cd plugins/Shell-Hooks
../../gradlew assemblePlugin        # build/plugin/shell-hooks.cgp
../../gradlew testDebugUnitTest
```

`WrapperScriptTest` runs the wrapper under every POSIX shell it finds (`/bin/sh`, dash, bash,
busybox ash). Android runs it with mksh, which those tests do not cover.
