# Part 1: device characterization

Part 1 of ADFA-6876 lets the plugin describe the phone it runs on, so that the local model can later choose threads, cores and a backend
that suit the phone. It writes one JSON document, the **device descriptor** (schema v0.2), and the measurements behind it. **It is
experimental and off by default.** Nothing in this document changes how models load or run: the descriptor is only written, nothing in the
plugin reads it yet (`InferenceThreads` and every other behaviour stay as they were).

## How to switch it on

Part 1 runs only when **both** are on:

1. the IDE's **Experiments** switch (the host's `IdeFeatureFlagService.isExperimentsEnabled()`), and
2. the plugin setting **Measure this device for local AI (experimental)** (preference `deviceCharacterization`, default **off**).

The setting is shown on the Local LLM settings pane only while Experiments is on. With either one off, **no Part 1 code runs**: no process
is started, no file is read or written, no dialog is shown. Turning the setting off again stops anything new from starting; files already
written stay where they are.

## What it does, and when

| Tier | When | What |
|---|---|---|
| **T0, static read** | Once per plugin activation, on a background thread. It never delays activation and never throws into the plugin. | Checks the engine version, reads the phone's static facts, matches the phone against the reduced device index shipped in the plugin, records the match, and writes the descriptor. Measured values stay unknown, or come from stored T1 rows when the device key is unchanged. About 0.4 s on the phones measured so far (limit 2 s; a hung probe is killed after 7 s and 10 s). |
| **T1, quick measurement** | At the first local-model load of a session (before the model is loaded, under the plugin's generation lock), when no completed T1 pass is recorded for this device key and you have not skipped it in this session. | A pin probe, one single-core memory read per core group, one read on the two fastest cores, one on all cores, and one GPU read when the phone has a Vulkan compute device. About one minute (the plan's budget without waits is 90 s; the recorded row durations add up to 55 s, 66 s and 77 s for two, three and four core groups). |

**The question.** Before anything is measured, one dialog on the IDE's current screen says what T1 is and that it takes about a minute, with
**Run now** and **Skip**. While it runs, a progress dialog shows "Step *i* of *n*" and what is being measured, with a **Cancel** button. Skip
or Cancel loads the model as before; cancelled and void rows are not stored as measurements; the question comes again at the next session's
first load. With no screen available, T1 is tried again at the next load. The screen is kept on for the duration and released on every way out.

**Gates** (checked before the start and before every row): the app is in the foreground (the engine also refuses by itself with exit 97, which
voids the pass); the IDE is quiet (no build or sync; a busy IDE is waited for up to 60 s, then T1 is deferred to the next load, and a build
that starts during a row voids that row, which is repeated once); the battery is at least 20 % and has not dropped 5 points since the start;
the thermal status is none or light (a hotter phone is waited for up to 60 s per row and the wait is recorded; a fixed 10 s rest only when neither
the status nor the headroom can be read); and the memory limit `min(55 % of MemTotal, 70 % of MemAvailable)` is read just before each row.
A pass is cancelled after 180 s in all. Nothing here can make a model load fail or hang.

## What it stores, and where

Everything is in the plugin's own data directory, under `part1/` (and `part1-work/` as the engine's working directory). Nothing leaves the
phone: the plugin uses no network API for Part 1.

| File | Content |
|---|---|
| `static.json` | The T0 object (`part1-static-v1`), with the pin probe folded in after a T1 pass. Holds the build fingerprint. |
| `rows.jsonl` | One `part1-rows-v1` record per stored T1 row, with the conditions the app knows (thermal status and headroom, battery, charging, display, screen state, wall-clock time of the row). |
| `dbrecord.json` | The device-index match (`exact`, `soc_sibling` or `none`). |
| `device-descriptor-v0.2.json` | The descriptor. |
| `probe-facts.json` | Facts of the T0 probes that have no slot in the static object: each OpenCL candidate as the probe gave it, and whether the vendor lists an OpenCL library as public. |
| `t1-pass.json` | The last T1 pass that ran: status, reason, total time, what each row came to, waits, and the pin probe as the engine gave it. |
| `key.txt` | The device key: sha256 of the build fingerprint, the engine id and the schema version. |
| `old/<12 hex>/` | The files of an earlier key (a system update changes the fingerprint), at most three archives. |

The files are written atomically. All of `part1/` is capped at 1 MiB (see the store's class comment for the exact worst case). Nothing is read
or written before the store is bound to this device in the running process, and a failed key change leaves a marker so that a restart is blocked
too until a bind succeeds. **The build fingerprint and the device key are never logged and never shown in the interface.** Log lines name rows
and outcomes only.

## What it never reads

No serial number, Android ID, IMEI or MEID, account, phone number, IP or MAC address, Wi-Fi name, location, installed-app list, file outside
the plugin's own folder and the fixed system files named in `StaticReader` (`/sys/devices/system/cpu`, `/proc/meminfo`,
`/vendor/etc/public.libraries.txt`), no project content, no model name or file, and no prompt. It does not load a vendor GPU library into the
IDE's process.

## File formats

By reference; this document does not repeat them.

- Rows: `part1-rows-v1`, `part1-engine/schemas/part1-rows-v1.schema.json`; the engine's output and exit codes are in `part1-engine/ENGINE.md`
  and `part1-engine/EXIT-CODES.md`; the probes' schemas sit next to them.
- Static object (`part1-static-v1`) and the descriptor (schema v0.2): kept with the Python reference tools (`descriptor-v02`, outside this
  repository); the Kotlin port in `src/main/kotlin/.../device/descriptor` is proven equal to it on a committed golden set.
- Native build, the two programs and their pinned hashes: `NATIVE-BUILD.md`, section "Part 1 engine".

## Known limits

- **OpenCL is unknown.** A child process cannot show whether OpenCL loads inside the app, so `opencl_loadable` stays unknown. The child's
  result and the public-libraries hint are recorded as facts only.
- **The GPU-alone value may be a range.** On phones whose memory clock idles down, the GPU read varies by about a factor of two between dispatches.
  The engine labels such a row `gpu_rate_unstable` (largest/smallest at least 1.25); the descriptor leaf keeps the label, and consumers must read it
  as a range.
- **Not in scope:** 32-bit phones and Android 12 or older (the plugin itself needs Android 13+ and arm64). NNAPI devices are not read.
- **Quiet IDE.** With the host's build-status service the gate sees builds and project syncs, not indexing inside the IDE. Without the service, a scan
  of the app's own processes for a Gradle or Java daemon stands in (and counts an idle daemon as busy).
- **Foreground.** The check is the app process's importance plus the engine's own cpuset check; it is proven on a phone only by the smoke test.
- Part 1 is validated only on the five phones of the plan; the tiers T2 (full), T3 (sustained) and the contribution flow are not part of this change.
