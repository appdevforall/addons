# Worker Exec Probe (ADFA-7281, spike 1)

Throwaway plugin answering one question for AI-Agent-Local v2: **can a plugin start,
talk to and supervise a native executable it ships as `lib<name>.so`, from inside
Code on the Go's process?** If not, the worker-process design in ADFA-7281 needs
another way to start the worker.

It lives under `spikes/`, which `addons discover` does not search
(`tools/addons/src/addons/discover.py`), so it is never built by CI or published.

## What it ships

- `src/main/cpp/exec_probe.c`: a tiny PIE executable, compiled by the NDK's clang
  (the `buildExecProbe` task) to `lib/arm64-v8a/libcogo_exec_probe.so`.
- `ExecProbePlugin`: runs the checks below on activation and from the main menu
  item **Run worker exec probe (ADFA-7281)**.

The manifest declares `native.code` but deliberately **not** `system.commands`.

## Checks

| Check | What it proves for the worker design |
|---|---|
| exec `--identify` | the extracted `.so` can be executed (W^X, SELinux, mount flags) |
| same SELinux domain | the child gets no more and no less access than CoGo |
| `--env` | the socket name / build id can be passed in the environment |
| `--abort` → exit 134 | a `GGML_ASSERT`-style abort kills only the child |
| `--stdin-eof` | EOF on stdin works as the shutdown signal |
| `--socket` + `SO_PEERCRED` | abstract unix socket IPC works, and the peer uid authenticates the worker |
| build id matches `versionName` | the extracted binary is this build's, not a stale one |
| `Os.kill(pid, SIGKILL)` → 137 | the supervisor can hard-kill a wedged worker |
| spawn latency (10 runs) | cost of starting the worker, separate from loading a model |

## Running it

```sh
../../gradlew assemblePluginDebug        # -> build/plugin/worker-exec-probe-debug.cgp
adb push build/plugin/worker-exec-probe-debug.cgp /sdcard/Download/
```

Install it from the Plugin Manager, then either use the main menu item (the dialog
has a Copy button) or read logcat:

```sh
adb logcat -s CogoExecProbe
```

Also try it by hand from CoGo's terminal, which runs in the same app domain:

```sh
ls -l /data/data/com.itsaky.androidide/app_plugin_native_libs/org.appdevforall.spikes.execprobe/
/data/data/com.itsaky.androidide/app_plugin_native_libs/org.appdevforall.spikes.execprobe/libcogo_exec_probe.so --identify
```

Run it on the newest device you have (API 34–36) and the oldest one CoGo supports.

## Results

| Device | API | CoGo build | Result |
|---|---|---|---|
| Samsung Galaxy Z Fold5 (SM-F946U) | 37 | C-d-1020-1725 (debug, targetSdk 28) | **all checks pass**, on install, on Replace and after a cold restart |

Run on 2026-11-23. What it established for the worker design:

- **Exec works.** CoGo runs as `untrusted_app_27`. The extracted file is
  `-rwx--x--x` on an f2fs mount without `noexec`, and the child runs in the same SELinux
  domain and uid as the IDE. `start()` takes 4–5 ms; spawn, run and exit take about 8–13 ms
  (median of 10). Starting the worker costs nothing next to loading a model.
- **`system.commands` is not needed** to start a process: the probe declares only
  `native.code`. Whether to declare it for disclosure is still a policy decision.
- **An abort stays in the child.** The parent sees exit 134 and keeps running.
- **IPC:** abstract unix socket round trips work, and `LocalSocket.getPeerCredentials()`
  reports the child's pid and our uid. The worker can be authenticated by peer uid
  instead of a bearer token.
- **Shutdown:** EOF on stdin works as the clean shutdown signal.
- **Gotcha: `Process.destroyForcibly()` sends SIGTERM on Android (exit 143), not SIGKILL.**
  The supervisor must hard-kill with `Os.kill(pid, OsConstants.SIGKILL)`, using the pid the
  worker reports in its hello (exit 137). The ~100 ms measured for the kill is the polling
  of `waitFor(timeout)`, not the kill itself.
- **Plugin updates re-extract native libs.** Replacing v0.1.0 with v0.1.1 through the
  installer replaced the binary (md5 changed, and the probe reports the new build id).
  Reading `PluginLoader.extractNativeLibs`, which returns early if the folder exists,
  had suggested otherwise, so the replace path must clear the folder somewhere (not traced).
  A plugin updated some other way could still differ. Keep the build-id
  check in the real `hello` anyway: it's cheap.
- **No orphans** after a restart (`ps -A` shows no probe processes).
- CPU: `dotprod` and `i8mm` present, no SVE, 4 KB pages, which is useful for choosing
  llama.cpp CPU flags.

Not covered here: a long-running child surviving CoGo being force-stopped (an
ADFA-7281 acceptance criterion), and an older device. Spike 2 (fd passing) is next.
