# Differences from the kit (kit v4d-5x sources under `intake-v4d5x/intake/src/`, read only)

Full unified diffs: `raw/diff-membw.txt` (kit `membw.c` to `src/membw.c`) and `raw/diff-gpubw.txt`. Proof that the kernels are untouched: `raw/kernel-identity.txt` (object-code comparison) and `tests/test_engine.py::TestDocsAndSources`.

## File by file

| File | Status |
|---|---|
| `src/pinretry.h` | **byte-for-byte the kit's** (`cmp` equal; tested). `pin_retry` is used unchanged: retry every 50 ms for up to 5 s, mask and running CPU verified. |
| `src/gpubw/shader.comp`, `src/gpubw/shader_spv.h` | **byte-for-byte the kit's** (tested). The compute shader and its SPIR-V are unchanged. |
| `src/part1_common.h` | **new**: engine id, exit codes, foreground gate, memory guard, watchdog, JSON writer, option number parsing. No kernel code. |
| `src/host_stub.h` | **new, host tests only**: plain-C stand-ins for K01..K12 used when `__aarch64__` is not defined. Never part of an Android binary. |
| `src/membw.c` | changed as listed below. |
| `src/gpubw/gpubw.c` | changed as listed below. |
| `build.sh`, `tools/`, `tests/` | new. The kit's `Makefile`/`build-v4d5k.sh` are not used (they build five bin sets, copy other binaries and use API 28/31). |

## `membw.c` (non-kernel changes)

1. `#include <linux/dma-heap.h>`, `heap_alloc()`, the `g_heap` global, the `--map anon|uncached|system` option and the dma-buf branch of `worker()` removed (requirement 7: no `/dev/dma_heap`). The anonymous-memory branch (`mmap`, `madvise(MADV_HUGEPAGE)`) is the kit's lines unchanged.
2. `#include "part1_common.h"` added.
3. `worker()`: the pin call keeps `pin_retry`; its result is stored (`g_pr[]`) for the output. With `--allow-unpinned` a failed pin prints `# pin: unpinned ...` and the thread runs unpinned, and the "not running on cpuN" check is skipped for that thread (it cannot hold). Without the option the kit's behaviour is unchanged (`g_pinfail`, exit 87).
4. `detect_cpus_p1()` added: calls the kit's `detect_cpus()` and, if it finds no `CPU part` lines, falls back to the affinity mask (needed for the host tests and robust against a restricted `/proc/cpuinfo`). `detect_cpus()` itself is unchanged. If neither source gives a CPU, the program now exits 1 (`failed`, with a JSON line), **not 2 as the kit did** (corrected in 0.2.0: the 0.1.0 text did not mention this). The pin probe exits 1 for the same reason.
5. `do_runs()`: keeps the per-run medians in `P1` before the kit's sort, records passes and minor faults, `p1_pin_summary()`, and on failure ends through `p1_fail_run()` (exit 87 for a pin failure as before, else exit 1 as before, but with a JSON line). The statistics (median of medians) are the kit's. `do_split()`'s failure path likewise.
6. `pace_main()`: pin result kept; `--allow-unpinned` support; per-cycle GB/s kept for the median/min/max; the return value 3 for a broken chain (which collided with the "unsupported" code 3) is now exit 1 with a reason. The kernel `chase_loop`, `chase_build`, `chase_walk` and the pacer arithmetic are unchanged. `perf_event_open` use is unchanged (already optional).
7. `main()`: option loop extended (`--json --gate --allow-unpinned --mem-limit-mib --fit-buffer --max-seconds --tier --row-kind`); `usage(); return 2` replaced by `USAGE(...)`, which prints the usage and a JSON refusal and exits 2; `--runs` limited to 1..8 (the kit's `double gm[8]` overflowed above 8) and `--cycles` to 1..64; `--suite` refused; after parsing and before any allocation or pinning: foreground gate, `DC ZVA` check (exit 3 as in the kit), memory guard; `--fit-buffer` and the sweep size cut; the JSON record is emitted at the end. `sweep()` keeps the kit's loop and `do_runs(.., 3)` per size and records the last size's value and the points.
8. `--tag`: kit behaviour kept (`g_tag` is the file/run-name prefix, truncated to 15 characters); the full text is also stored as the row tag.
9. Small guards for non-ARM hosts: `#ifdef __aarch64__ ... #else` around the asm of `zva_ok()`, `cntfrq()`, `cntvct()` and `chase_loop()` and around the block K01..K12 (the `#else` branches are host stubs).

## `membw.c`: what is byte-for-byte the kit's

Verified by test (`test_kernel_text_is_the_kits`) and object-code comparison (`raw/kernel-identity.txt`):
* the whole text of the twelve kernels K01..K12 (kit lines 36-156: the macros `V_CLOB, ZERO, FINISH, EOR16, LDP8, STP8, SETPAT` and the functions `k01..k12`) and the `kern_t` table `KERN[]` (kit lines 158-166);
* `chase_loop()` (both asm variants, `dsb sy` padding and `isb`/`mrs` spin);
* `pin_retry()` (whole file);
* the timed-pass logic in `worker()` after the pin call, except the one `sched_getcpu` condition described in change 3 (barriers, warm-up, window, `stamp_lines`, spot checks), `run_config()`, `lines_ok`, `stamp_lines`, the sweep size arithmetic, `emit_row`, `do_split`'s kernels.
* Object code: the disassembly of `k01..k12` is **identical** in (a) the kit source rebuilt with the kit's recipe, (b) the engine source built with that same recipe, (c) the shipped engine binary (armv8-a, API 33) and (d) the kit's shipped `bin/membw`. The three chase loops (`chase_loop()` is inlined into `main()` three times) have the kit's instruction sequence in all four builds, with these register differences in the engine builds (b) and (c), found by the P1-03F review and **accepted by the planner as the object code is (PLAN-025; the compiler's register choice is not forced)**:
  * **unpaced loop** (`ldr; subs; b.ne`): identical as written;
  * **spin loop** (`isb` + `mrs`, 9 instructions): one rename, **x27 to x19** (the previous release timestamp, in `sub` and `mov`; callee-saved to callee-saved);
  * **paced loop** (`dsb sy` x8, 17 instructions): **two renames, x27 to x19** (the previous release timestamp, `sub`/`mov`) **and x25 to x12** (the `groups` padding counter, `add x12, x12, #0x1`). x12 is caller-saved, so `groups` is loaded from the stack and stored back **once per chunk, outside the paced inner loop**; the timed inner loop retires the kit's instructions in the kit's order and contains no call. Its effect on the pacer rate is untested on arm64 and is compared with the kit's binary on a phone in P1-06;
  * `K01..K12`: identical.

  `tools/kernel_identity.py` checks exactly this map (`EXPECTED_RENAMES`): each engine loop must equal the kit's loop after the rename map above and nothing else, and the tool fails on any other difference (`raw/kernel-identity.txt`). The 0.2.0 text of this document said one register changed; that was wrong. The source text is unchanged (tested). The kit's shipped binary as a whole is not byte-equal to a fresh rebuild of the kit source (different build inputs); the kernels are.

* **Chunk set-up and tear-down around the loops (P1-03H, review F03 and F04 of P1-03G).** The code just outside each inlined loop differs from the kit's too, and `tools/kernel_identity.py` now compares it against an exact list (`EXPECTED_CHUNK_DIFFS`). The block compared runs from `mov x8, <chunk count>` to the first following `b`/`bl` (the branch to the common exit, or the `clock_gettime` call after the paced loop); branch targets and stack offsets are normalised. The differences, engine against kit, are exactly:
  * **chunk-count register, all three loops:** the register that holds the chunk length and is copied into the loop counter is **x28 in the kit and x27 in the engine** (`mov x8, x28` is `mov x8, x27`: "x28 to x27"); in the spin and paced blocks the previous-release timestamp is x27 in the kit and x19 in the engine (the rename already listed above, `sub x10, x9, x27` and `mov x27, x9`);
  * **paced block only:** the `groups` counter is x25 in the kit and x12 in the engine (`add x25, x25, #0x1` is `add x12, x12, #0x1`); the engine adds **one load `ldr x12, [sp, #SP]` before the loop and one store `str x12, [sp, #SP]` after it**, the same stack slot (checked), **once per chunk**, outside the paced inner loop;
  * nothing else: the unpaced block differs only by the chunk-count register; in the kit's shipped binary the three blocks equal the kit rebuild's (empty list).
  The same 6 + 3 + 1 differences are listed in the tool's `EXPECTED_CHUNK_DIFFS`; it fails on any other (a missing store, another register, another slot, another opcode). `tests/test_engine.py` patches a copy of the engine object code (the store replaced by a nop, the chunk-count register changed, the load changed, the unpaced set-up changed) and shows that the tool then fails with exit 1; `--shipped PATH` points the tool at such a copy.

## `gpubw.c`

1. `#include "../part1_common.h"` added.
2. `CK()` (Vulkan error check) used to `exit(1)` after a message: now prints a JSON error and exits 1. The `exit(1)` for a memory type that is not allowed for a buffer likewise exits 1 with a JSON line. **Three situations now exit 3 (`unsupported`), as `EXIT-CODES.md` says:** no Vulkan device, no compute queue, and no usable Vulkan memory type; the kit exited 1 for all three. (Corrected in 0.2.0: the 0.1.0 text said the last of these exits 1.)
3. Option loop: the kit's pairs-of-arguments parser is kept; value-less flags `--json --fit-buffer --allow-unpinned` and the options `--gate --mem-limit-mib --max-seconds --tier --cpu-set --cpu-threads --row-kind` added; `--tag` also stores the row tag; failures exit 2 with JSON.
4. After parsing and before the first Vulkan call: `--json` single-variant check, watchdog, foreground gate, memory guard (2 or 3 buffers of `--size-mb`; `--fit-buffer` shrinks, floor 64 MiB).
5. After the kit's `mom` is computed, the median GB/s (`bytes / mom_ns`), the dispatch count and the variant text are stored; a verification failure with `--json` exits 1 with JSON immediately; the final return is through `p1_done_ok()`. Label `host_clock_timing` when the device has no timestamp support.
6. Nothing in the pipeline, descriptor sets, shader, dispatch, timestamp, verification or buffer code changed.

## Build recipe differences

| | Kit | Engine |
|---|---|---|
| membw target / ISA | `aarch64-linux-android31`, `-march=armv8.2-a+fp16` (kit-arm64 set) | `aarch64-linux-android33`, **`-march=armv8-a`** (build.sh `MEMBW_MARCH`): the in-scope phones are Android 13+ but a plain-A53 phone without fp16 is not excluded by D0-1, and the kernels use only base AArch64 and NEON instructions. Identical kernel object code was verified for both ISAs (`raw/kernel-identity.txt`). |
| gpubw | API 31, `-O2 -pie` | API 33, `-O2 -pie` |
| link | default | `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`, `-fPIE -pie`, build-id; `-lm` (membw), `-lvulkan -lm` (gpubw) |
| output name | `membw`, `gpubw` | `libpart1_membw.so`, `libpart1_gpubw.so` |

## Changes of 0.2.0 (job P1-03F), none in a measurement kernel

| Area | Change (review finding) |
|---|---|
| Pin state (`membw.c` `worker()`, `p1_pin_acc`, `p1_pin_summary`, `p1_fail_run`) | kept per CPU for the whole process: a CPU that failed `pin_retry` under `--allow-unpinned` is not retried in later runs or sweep sizes (F06); the row's `pin` is `unpinned` when any timed run ran unpinned (F03); the exit-87 record has `pin.result failed`, the CPUs asked for in `cpu_set`, no `unpinned` label and no note (F05). `pin_retry` itself is the kit's, unchanged. |
| `do_runs()` | starts by clearing the values of an earlier sweep size (F02); `part1_common.h` writes `repeats 1`, `single_shot true` for any record without a value (F02). |
| `part1_common.h` | `reason` and `minflt_timed` are now optional row-schema fields, written as before (F01); the timeout object is written with stdout held locked until `_exit` (F11); `p1_clear_host_env()` clears `PINRETRY_STEP_MS` / `PINRETRY_MAX_MS` in a production build (F16); `--row-kind` values are checked against the schema enum (F07); a row can never be `ok` without a number (F04); the `part1-pinprobe-v1` writer. |
| `gpubw.c` | `kind gpu` set before any option is parsed (F07); with `--json` the mode and per-wg are parsed strictly and checked with the kit loop's own skip arithmetic before any Vulkan call: exit 2 instead of `ok` without a value (F04). |
| `membw.c` | new `--pin-probe` mode, `pinprobe_main()` (B10; uses `pin_retry` unchanged). |
| `build.sh` | logs under `OUTDIR/logs/`, header corrected (F13). |
| Not changed | `pinretry.h`, `shader.comp`, `shader_spv.h`, K01..K12, `KERN[]`, `chase_loop()` (byte-identical, tested; `raw/kernel-identity.txt`). |

## Changes of 0.2.1 (job P1-03G), none in a measurement kernel

| Area | Change (P1-03F review finding) |
|---|---|
| `part1_common.h` `p1_done_ok()` | the guard "never `ok` without a number" applies only with `--json`; without it `libpart1_gpubw.so` behaves as the kit binary: a good `--loop-seconds` run and a run in which every variant is skipped exit 0 with no `failed` line (F03) |
| `--tier` (both programs) | validated before it is stored; a bad value gives a schema-valid exit-2 record without `tier` (F02) |
| Timeout record (`part1_common.h` watchdog, `membw.c` `p1_pin_timeout_hook`) | a timeout after an earlier run of the same row ran unpinned reports `pin.result unpinned`, the label `unpinned` and the requested `cpu_set` (F05) |
| `p1_pin_summary()` | the pin sentence is added to the note once per process, so an `--allow-unpinned` sweep keeps its `sweep total_MiB=GB/s` points (F04) |
| `membw.c` `main()` | `--cores` with the same CPU twice is a usage error (exit 2) for rows (F06); the `kind` of a usage-error record follows `--sweep` / `--pace-chase` / `--pace-idle` seen anywhere on the command line (F08) |
| `tools/kernel_identity.py`, this document | the exact rename map of the inlined chase loops is stated and checked (F01) |
| `EXIT-CODES.md`, `ENGINE.md`, `raw/build-*.log` | pin probe exit 1; `--max-seconds` on the no-Vulkan pacer row; repeated `--fit-buffer` removed; current build logs (F09, F11) |
| Not changed | `pinretry.h`, `shader.comp`, `shader_spv.h`, K01..K12, `KERN[]`, `chase_loop()` source (byte-identical, tested) |

## Changes of 0.2.2 (job P1-03H), none in a measurement kernel

| Area | Change (review finding or decision) |
|---|---|
| `part1_common.h` writer; `membw.c` `p1_pin_summary()`, `pace_main()`, `p1_pin_timeout_hook()` | **One rule for `cpu_set`** (F02): `P1.ncpu` always holds the CPUs asked for; the writer lists none only for an `ok` row whose pin result is `unpinned`. `p1_pin_summary()` and `pace_main()` no longer clear `ncpu`, so a sweep that times out after an unpinned size reports the requested `cpu_set` (A1, F01). The timeout hook also reads the pin result of the run in progress and `g_cpu_failed[]`, so a timeout during the first run, already unpinned, says `unpinned` (A2, F06). |
| `tools/kernel_identity.py`, this document | the chunk set-up and tear-down around the three loops are compared against `EXPECTED_CHUNK_DIFFS` (A4, F03 and F04); `--shipped PATH`; tests that patch a copy of the object code. |
| `part1_common.h` note | a note that does not fit is never cut silently: label `note_truncated`; priority sentences (sweep points, dispatch rates) drop the oldest ordinary sentences first (A5, F05). `membw.c` `sweep()`: point buffer 600 characters, a cut there is labelled too. `P1_MAXLABELS` 8 to 12. |
| `gpubw.c` | with `--json`: `dispatch GB/s: a, b, c, ...` in `conditions.note`, label `gpu_rate_unstable` at largest rate >= 1.25 x smallest; the row value is still the kit's `mom` (B1, P1-03S F205). |
| `membw.c` `--static-probe`, `p1_static_probe_entry()` and helpers | new mode (C1): `part1-staticprobe-v1`, the fixed property list, no gate. `part1_common.h`: `p1_read_cpuset()` split out of `p1_gate()`, `p1_custom_writer` for the two new objects. |
| `membw.c` `pinprobe_main()` | `midr` and `midr_source` per CPU, read while pinned (C2). |
| `gpubw.c` `--probe` | new mode (C3): `part1-gpuprobe-v1`: Vulkan queries only, `dlopen` of the four OpenCL candidates in the child (`opencl_child`). |
| schemas | `part1-staticprobe-v1.schema.json` and `part1-gpuprobe-v1.schema.json` new; `part1-pinprobe-v1.schema.json` gets the optional `midr` and `midr_source`. |
| Not changed | `pinretry.h`, `shader.comp`, `shader_spv.h`, K01..K12, `KERN[]`, `chase_loop()` source (byte-identical, tested). The object code of K01..K12 is identical to the kit's; the three loops show the accepted rename map; the chunk blocks show the list above (`raw/kernel-identity.txt`). New code is in `noinline` helpers so `main()` compiles as before. |

## Changes of 0.2.3 (job P1-03I), none in a measurement kernel

| Area | Change (review finding or decision) |
|---|---|
| `gpubw.c` `--probe`, `part1-gpuprobe-v1.schema.json` | records `foreground_cpuset` (review F01 of P1-03H). |
| `membw.c` `sweep()` | the usage error for a sweep size that is too small copies the size before freeing the token buffer; the reason names the size (review F02, a use-after-free present since 0.1.0). The same pattern was searched for in both programs: the only other `strdup` users (`parse_cores`, `cut_sizes`, `largest_size`, the `--cpu-set` list of gpubw) free the buffer after their last use of it; no other message uses a pointer into a freed buffer. |
| `part1_common.h` comment; `ENGINE.md`, `EXIT-CODES.md` | the stale `ncpu` comment is corrected; the `cpu_set` rule names exit 2 and the exit-1 record written before `--cores` is parsed (review F03, F04). |
| `membw.c` `pinprobe_main()`, `pp_midr_unpinned()`, pin-probe schema | a CPU whose pin failed gets its MIDR from sysfs (`sysfs_unpinned`) or its `/proc/cpuinfo` entry (`cpuinfo_unpinned`) read without pinning, else null (PLAN-034). |
| `gpubw.c` `gp_opencl_one()`, gpu-probe schema | each `dlopen` runs in a forked helper with a 2 s limit; per candidate `outcome` `loaded`, `dlerror`, `crashed` (with `signal`), `timeout` or `not_run`; helpers run before Vulkan; silent, killed with the parent, collected (P1-03H report F09, review F06). |
| Not changed | review F05 (the identity tool's documented boundary); `pinretry.h`, `shader.comp`, `shader_spv.h`, K01..K12, the loops (re-checked with `tools/kernel_identity.py`). |

## Changes of 0.2.4 (job P1-03J), none in a measurement kernel

| Area | Change (P1-03I finding) |
|---|---|
| `tests/test_engine.py` | every fixture file (fake `meminfo`, `cpuset`, sysfs and cpuinfo trees, pid files, logs) is written to its own temporary directory (`uniq()`); no two tests or threads share one (F05). Every test class is above the `__main__` guard; the exit-1 test really produces the exit-1 record (`FAKE_AFF_FAIL_GET` in `tests/host/fakeaff.c`) (F07). |
| `gpubw.c` `gp_opencl_one()` | the pipe is read after the helper was collected (F01); the helper writes `L` before `_exit(0)` and `loaded` needs it (F04); the helper has its own process group and the group is killed at every end, also by the `--max-seconds` watchdog (F02); a helper ended by its own alarm is `timeout` (F03); overall limit 2.5 s for the four candidates, `not_tried` for those not started (F03). |
| `gpubw.c` `gp_entry()` | the text output prints `cpuset=` (F08). |
| `part1-gpuprobe-v1.schema.json` | outcome `not_tried` (error null, signal null, loaded false). |
| `ENGINE.md`, `EXIT-CODES.md` | the `cpu_set` rule is exact for pacer refusals (97, 88), first-size sweep errors and long sizes (F06); the worst-case duration; the group-kill statement (F02); the phone result for `opencl_child` (P1-03P F09). |
| Not changed | `pinretry.h`, `shader.comp`, `shader_spv.h`, K01..K12, the loops (re-checked with `tools/kernel_identity.py`). |
