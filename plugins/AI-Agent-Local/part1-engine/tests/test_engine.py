"""Desk tests for the Part 1 engine (job P1-03). No phone, no qemu, no network.

Run: python3 -I -B -m unittest discover -s tests -v      (from the part1-engine directory)

How it works. No qemu-aarch64 is installed, so the AArch64 binaries cannot run here. Instead the SAME sources are compiled for the host
(gcc, -DP1_HOST_BUILD) with two substitutions that exist only in the host build:
  * src/host_stub.h replaces the AArch64 asm kernels K01..K12 and the cntvct/dc-zva helpers with plain C (membw.c selects it with #ifdef __aarch64__);
  * tests/host/fakevk.c answers the first two Vulkan calls of gpubw ("no device"), so gpubw's option parsing, gate, guard, time limit and refusal run.
Option parsing, the foreground gate, the memory guard, pinning/unpinned handling, the time limit, the JSON writer and the exit codes are therefore the
engine's real code. Not covered here (needs a phone): the asm kernels, real Vulkan dispatch, real /proc/self/cpuset values.
The environment variables P1_CPUSET_PATH and P1_MEMINFO_PATH redirect the two /proc files in the host build only.
The JSON lines are checked against schemas/part1-rows-v1.schema.json, the copy of descriptor-v02's schema (jsonschema, already installed) AS EMITTED (P1-03F B1: the
schema has the optional fields `reason` and `minflt_timed` since descriptor-v02 P24; nothing is removed or added). The one documented exception is a usage
error reported before `--tag` was parsed: that record has no `tag` (the app adds it), so only then is a placeholder tag put in before the check.
P1-03F: set the environment variable P1_SRC_ROOT to another engine tree (for example history/pre-P1-03F) to build the host binaries from that tree and see
which tests fail on the 0.1.0 code (raw/old-code-tests.txt of the report).
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest


def rd(path, mode="r"):
    with open(path, mode, **({} if "b" in mode else {"errors": "ignore"})) as f:
        return f.read()

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.environ.get("P1_SRC_ROOT") or ROOT      # tree whose src/ the host binaries are built from (default: this tree)
SCHEMA = os.environ.get("P1_ROWS_SCHEMA") or os.path.join(ROOT, "schemas", "part1-rows-v1.schema.json")   # P1_ROWS_SCHEMA: only to show that the B1 tests fail with the schema of before P1-03F
# The kit-dependent checks take the kit location from PART1_KIT_DIR (the kit's `intake` directory, which holds src/ and bin/);
# without it they skip with a clear message. The repository holds no copy of the kit.
KIT_DIR = os.environ.get("PART1_KIT_DIR") or ""
KIT_SKIP = "kit sources not present: set PART1_KIT_DIR to the kit's intake directory"
KIT_MEMBW = os.path.join(KIT_DIR, "src/membw.c") if KIT_DIR else ""
KIT_GPUBW = os.path.join(KIT_DIR, "src/gpubw/gpubw.c") if KIT_DIR else ""
KIT_PIN = os.path.join(KIT_DIR, "src/pinretry.h") if KIT_DIR else ""
KIT_SPV = os.path.join(KIT_DIR, "src/gpubw/shader_spv.h") if KIT_DIR else ""
# The lab's build outputs (out/) and raw logs (raw/) are not part of the repository copy; the checks that read them skip without them.
HAVE_OUT = os.path.exists(os.path.join(ROOT, "out", "arm64-v8a", "libpart1_membw.so"))
HAVE_RAW = os.path.isdir(os.path.join(SRC, "raw"))
OUT_SKIP = "the lab's out/arm64-v8a/ (built programs) is not part of the repository copy"
RAW_SKIP = "the lab's raw/ (recorded logs) is not part of the repository copy"
PINPROBE_SCHEMA = os.path.join(ROOT, "part1-pinprobe-v1.schema.json")
KIT_BIN_FOR_TOOL = os.path.join(KIT_DIR, "bin/membw") if KIT_DIR else ""
TMP = None
MEMBW = GPUBW = GPUBW_FULL = FAKEAFF = FAKESPAM = FAKEOPEN = MEMBW_PROD = None
NDK = None
FAKEOCL = {}


def find_ndk():
    for base in (os.environ.get("ANDROID_NDK_HOME"), os.path.expanduser("~/Android/Sdk/ndk/*"), "/opt/android-sdk/ndk/*"):
        if not base:
            continue
        import glob
        c = sorted(glob.glob(base))
        if c:
            return c[-1]
    return None


def setUpModule():
    global TMP, MEMBW, GPUBW, GPUBW_FULL, FAKEAFF, FAKESPAM, FAKEOPEN, MEMBW_PROD, NDK
    TMP = tempfile.mkdtemp(prefix="p1engine-test-")
    gcc = shutil.which("gcc")
    if not gcc:
        raise unittest.SkipTest("no host gcc")
    common = [gcc, "-std=gnu11", "-O1", "-w", "-DP1_HOST_BUILD", "-DP1_ENGINE_VERSION=\"host-test\"", "-I" + os.path.join(SRC, "src")]
    MEMBW = os.path.join(TMP, "membw")
    subprocess.check_call(common + ["-o", MEMBW, os.path.join(SRC, "src", "membw.c"), "-lpthread", "-lm"])
    MEMBW_PROD = os.path.join(TMP, "membw-prod")      # the same source without -DP1_HOST_BUILD: the /proc paths and the PINRETRY_* variables are production behaviour
    subprocess.check_call([c for c in common if c != "-DP1_HOST_BUILD"] + ["-o", MEMBW_PROD, os.path.join(SRC, "src", "membw.c"), "-lpthread", "-lm"])
    FAKESPAM = os.path.join(TMP, "fakespam.so")
    subprocess.check_call([gcc, "-shared", "-fPIC", "-o", FAKESPAM, os.path.join(HERE, "host", "fakespam.c"), "-ldl", "-lpthread"])
    FAKEAFF = os.path.join(TMP, "fakeaff.so")
    subprocess.check_call([gcc, "-shared", "-fPIC", "-o", FAKEAFF, os.path.join(HERE, "host", "fakeaff.c"), "-ldl"])
    FAKEOPEN = os.path.join(TMP, "fakeopen.so")      # P1-03H: logs the CPU and the affinity at the moment the MIDR files are opened
    subprocess.check_call([gcc, "-shared", "-fPIC", "-o", FAKEOPEN, os.path.join(HERE, "host", "fakeopen.c"), "-ldl"])
    global FAKEOCL
    FAKEOCL = {}
    for name, mode in (("crash", 1), ("hang", 2), ("chatter", 3), ("abort", 4), ("exit0", 5), ("gc_load", 6), ("gc_hang", 7), ("gc_crash", 8)):      # P1-03I item 5: vendor-library stand-ins, host tests only
        FAKEOCL[name] = os.path.join(TMP, "fakeocl-%s.so" % name)
        subprocess.check_call([gcc, "-shared", "-fPIC", "-DFAKE_OCL_MODE=%d" % mode, "-o", FAKEOCL[name], os.path.join(HERE, "host", "fakeocl.c")])
    global FAKELATE
    FAKELATE = os.path.join(TMP, "fakelate.so")      # P1-03J: delays dlerror() in the helper and an empty read() in the probe (the read/reap race)
    subprocess.check_call([gcc, "-shared", "-fPIC", "-o", FAKELATE, os.path.join(HERE, "host", "fakelate.c"), "-ldl"])
    NDK = find_ndk()
    vk = os.path.join(NDK, "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include") if NDK else None
    if vk and os.path.isdir(os.path.join(vk, "vulkan")) and os.path.isdir(os.path.join(vk, "vk_video")):
        inc = os.path.join(TMP, "inc")
        os.makedirs(inc)
        os.symlink(os.path.join(vk, "vulkan"), os.path.join(inc, "vulkan"))     # only the Vulkan headers: the NDK libc headers do not compile with gcc
        os.symlink(os.path.join(vk, "vk_video"), os.path.join(inc, "vk_video"))
        GPUBW = os.path.join(TMP, "gpubw")
        subprocess.check_call(common + ["-I" + inc, "-I" + os.path.join(SRC, "src", "gpubw"), "-o", GPUBW, os.path.join(SRC, "src", "gpubw", "gpubw.c"),
                                        os.path.join(HERE, "host", "fakevk.c"), "-lpthread", "-lm", "-ldl", "-Wl,--unresolved-symbols=ignore-all"])
        GPUBW_FULL = os.path.join(TMP, "gpubw-full")     # P1-03G: the same gpubw source against a fuller fake Vulkan (tests/host/fakevk_full.c, synthetic) that reaches the dispatch loop
        subprocess.check_call(common + ["-I" + inc, "-I" + os.path.join(SRC, "src", "gpubw"), "-o", GPUBW_FULL, os.path.join(SRC, "src", "gpubw", "gpubw.c"),
                                        os.path.join(HERE, "host", "fakevk_full.c"), "-lpthread", "-lm", "-ldl"])


def tearDownModule():
    if TMP:
        shutil.rmtree(TMP, ignore_errors=True)


def uniq(name):
    """P1-03J (F05): a path for a fixture file that belongs to one call only: a fresh directory under TMP, so no two tests or threads ever share (or truncate) a fixture file."""
    return os.path.join(tempfile.mkdtemp(dir=TMP), name)


def write(name, text):
    p = uniq(name)
    with open(p, "w") as f:
        f.write(text)
    return p


def meminfo(total_mib, avail_mib):
    return write("meminfo-%d-%d" % (total_mib, avail_mib), "MemTotal:       %d kB\nMemFree:        1 kB\nMemAvailable:   %d kB\n" % (total_mib * 1024, avail_mib * 1024))


TOP = None
FG = None


def cpuset_top():
    return write("cpuset-top", "/top-app\n")


def cpuset_fg():
    return write("cpuset-fg", "/foreground\n")


def run(exe, args, env=None, timeout=120):
    e = dict(os.environ)
    e.update({"P1_CPUSET_PATH": cpuset_top(), "P1_MEMINFO_PATH": meminfo(8000, 6000)})
    e.update(env or {})
    t = subprocess.run([exe] + args, env=e, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout, text=True)
    cap = os.environ.get("P1_CAPTURE_FILE")      # P1-03I: when set, the full stdout of every engine run is appended (one JSON line per run) so tools/validate_captured.py can check the raw lines as emitted
    if cap:
        with open(cap, "a") as cf:
            cf.write(json.dumps({"exe": os.path.basename(exe), "args": args, "rc": t.returncode, "stdout": t.stdout}) + "\n")
    lines = [ln for ln in t.stdout.splitlines() if ln.strip()]
    rec = None
    if lines and lines[-1].startswith("{"):
        rec = json.loads(lines[-1])
    return t.returncode, rec, t.stdout, t.stderr


def schema_check(tc, rec, _tag_unused=None, usage_error=False):
    """Validate a record against part1-rows-v1 exactly as emitted. Only a usage error reported before --tag was parsed (usage_error=True) lacks `tag`;
    for that one documented case a placeholder tag is added (the app adds the real one) and nothing else is changed."""
    import jsonschema
    schema = json.loads(rd(SCHEMA))
    r = dict(rec)
    if "tag" not in r:
        tc.assertTrue(usage_error, "a record without a tag is only allowed for a usage error before --tag: %s" % json.dumps(rec)[:200])
        r["tag"] = "C2-read-k0"
    errs = [e.message for e in jsonschema.Draft202012Validator(schema).iter_errors(r)]
    tc.assertEqual(errs, [], "schema errors for %s" % json.dumps(rec)[:300])


def probe_check(tc, rec):
    import jsonschema
    schema = json.loads(rd(PINPROBE_SCHEMA))
    errs = [e.message for e in jsonschema.Draft202012Validator(schema).iter_errors(rec)]
    tc.assertEqual(errs, [], "pin-probe schema errors for %s" % json.dumps(rec)[:300])


# fast single-core row on CPU 0 with a tiny buffer (the host stub kernels are not measurements)
FAST = ["--kernel", "K02", "--threads", "1", "--cores", "0", "--size-mb", "4", "--allow-cached", "--warmup-s", "0.1", "--window-s", "0.2", "--runs", "1", "--tag", "C2-read-k0", "--json"]


class TestMembwJson(unittest.TestCase):
    def test_ok_row_validates_and_has_fields(self):
        rc, rec, out, err = run(MEMBW, FAST)
        self.assertEqual(rc, 0, err)
        schema_check(self, rec)
        self.assertEqual(out.splitlines()[-1][0], "{")
        self.assertEqual(rec["status"], "ok")
        self.assertEqual(rec["tag"], "C2-read-k0")
        self.assertEqual(rec["kind"], "read")
        self.assertEqual(rec["cpu_set"], [0])
        self.assertEqual(rec["threads"], 1)
        self.assertEqual(rec["buffer_mib"], 4)
        self.assertGreater(rec["gbs_median"], 0)
        self.assertIsNone(rec["gbs_min"])          # single shot
        self.assertEqual(rec["repeats"], 1)
        self.assertTrue(rec["single_shot"])
        self.assertEqual(rec["pin"]["result"], "ok")
        self.assertGreaterEqual(rec["pin"]["attempts"], 1)
        self.assertEqual(rec["exit_code"], 0)
        self.assertEqual(rec["gate_basis"], "cpuset_top_app")
        self.assertEqual(rec["conditions"]["foreground_cpuset"], "/top-app")
        self.assertEqual(rec["engine_version"], "part1-engine/host-test")
        self.assertEqual(rec["origin"], "measured_app")
        self.assertIn("minflt_timed", rec)
        self.assertEqual(rec["labels"], [])
        for absent in ("thermal_status", "battery_pct", "display_refresh_hz"):      # fields only the app knows are omitted, not invented
            self.assertNotIn(absent, rec["conditions"])

    def test_human_output_without_json(self):
        rc, rec, out, err = run(MEMBW, [a for a in FAST if a != "--json"])
        self.assertEqual(rc, 0)
        self.assertIsNone(rec)                      # no JSON on success without --json
        self.assertIn("GB/s_med", out)
        self.assertIn("C2-read-k0-mom", out)

    def test_three_runs_have_min_max(self):
        args = [a for a in FAST]
        args[args.index("--runs") + 1] = "3"
        rc, rec, out, err = run(MEMBW, args)
        self.assertEqual(rc, 0, err)
        schema_check(self, rec)
        self.assertEqual(rec["repeats"], 3)
        self.assertFalse(rec["single_shot"])
        self.assertLessEqual(rec["gbs_min"], rec["gbs_median"])
        self.assertLessEqual(rec["gbs_median"], rec["gbs_max"])
        self.assertEqual(rec["conditions"]["regime"], "median_of_repeats")

    def test_write_copy_and_prefetch_kinds(self):
        for kern, kind, extra in (("K07", "write", []), ("K10", "copy", []), ("K04", "prefetch", ["--prefetch-dist", "512"])):
            args = list(FAST)
            args[args.index("--kernel") + 1] = kern
            args += extra
            rc, rec, out, err = run(MEMBW, args)
            self.assertEqual(rc, 0, err)
            self.assertEqual(rec["kind"], kind)
            schema_check(self, rec)
        # a prefetch row's variant carries the distance
        self.assertIn("pfdist=512", rec["variant"])

    def test_two_threads(self):
        args = list(FAST)
        args[args.index("--threads") + 1] = "2"
        args[args.index("--cores") + 1] = "0+1"
        rc, rec, out, err = run(MEMBW, args)
        self.assertEqual(rc, 0, err)
        self.assertEqual(rec["cpu_set"], [0, 1])
        self.assertEqual(rec["threads"], 2)
        self.assertEqual(rec["conditions"]["cpu_set"], "0+1")
        schema_check(self, rec)

    def test_sweep_row_value_is_largest_size(self):
        rc, rec, out, err = run(MEMBW, ["--sweep", "--sizes", "0.5,1", "--kernel", "K02", "--threads", "1", "--cores", "0", "--warmup-s", "0", "--window-s", "0.1",
                                        "--tag", "C1-sweep-k0", "--json"])
        self.assertEqual(rc, 0, err)
        schema_check(self, rec)
        self.assertEqual(rec["kind"], "sweep")
        self.assertEqual(rec["buffer_mib"], 1)
        self.assertIn("0.5=", rec["conditions"]["note"])
        self.assertIn("1=", rec["conditions"]["note"])

    def test_pacer_idle_row(self):
        rc, rec, out, err = run(MEMBW, ["--pace-idle", "--cores", "0", "--pace-ns", "0", "--duration-s", "0.2", "--cycles", "2", "--tag", "PC-pacer-idle-off", "--json"])
        self.assertEqual(rc, 0, err)
        schema_check(self, rec)
        self.assertEqual(rec["kind"], "pacer")
        self.assertEqual(rec["repeats"], 2)
        self.assertIsNotNone(rec["gbs_min"])

    def test_pmu_not_required(self):
        # the pacer asks for perf_event_open counters; a denied call must not fail the row (the host build may or may not be allowed to open them)
        rc, rec, out, err = run(MEMBW, ["--pace-idle", "--cores", "0", "--duration-s", "0.1", "--tag", "PC-pacer-idle-off", "--json"])
        self.assertEqual(rc, 0, err)
        self.assertIn("# pmu:", out)
        self.assertNotIn("ipm_pmu", json.dumps(rec))

    def test_json_is_last_line_and_single_object(self):
        rc, rec, out, err = run(MEMBW, FAST)
        objs = [ln for ln in out.splitlines() if ln.startswith("{")]
        self.assertEqual(len(objs), 1)
        self.assertEqual(out.rstrip("\n").splitlines()[-1], objs[0])


class TestGate(unittest.TestCase):
    def test_not_top_app_exits_97_and_measures_nothing(self):
        rc, rec, out, err = run(MEMBW, FAST, env={"P1_CPUSET_PATH": cpuset_fg()})
        self.assertEqual(rc, 97)
        self.assertEqual(rec["status"], "skipped")
        self.assertEqual(rec["exit_code"], 97)
        self.assertIsNone(rec["gbs_median"])
        self.assertEqual(rec["conditions"]["foreground_cpuset"], "/foreground")
        self.assertIn("not_foreground", rec["labels"])
        self.assertNotIn("# pin", out)               # it returned before pinning or allocating
        schema_check(self, rec)

    def test_unreadable_cpuset_exits_97(self):
        rc, rec, out, err = run(MEMBW, FAST, env={"P1_CPUSET_PATH": os.path.join(TMP, "no-such-file")})
        self.assertEqual(rc, 97)
        self.assertIn("could not be read", rec["reason"])

    def test_top_app_prefix_is_not_enough(self):
        p = write("cpuset-sub", "/top-app/sub\n")
        rc, rec, out, err = run(MEMBW, FAST, env={"P1_CPUSET_PATH": p})
        self.assertEqual(rc, 97)

    def test_gate_off_is_recorded(self):
        rc, rec, out, err = run(MEMBW, FAST + ["--gate", "off"], env={"P1_CPUSET_PATH": cpuset_fg()})
        self.assertEqual(rc, 0, err)
        self.assertEqual(rec["gate_basis"], "gate_off")
        self.assertIn("gate_off", rec["labels"])
        self.assertIn("gate_off", rec["conditions"]["labels"])
        schema_check(self, rec)

    def test_gpubw_gate(self):
        if not GPUBW:
            self.skipTest("no Vulkan headers for the host gpubw build")
        rc, rec, out, err = run(GPUBW, ["--json", "--tag", "C7-gpu-alone", "--modes", "0", "--per-wg", "4096"], env={"P1_CPUSET_PATH": cpuset_fg()})
        self.assertEqual(rc, 97)
        self.assertEqual(rec["status"], "skipped")
        schema_check(self, rec, "C7-gpu-alone")


class TestMemoryGuard(unittest.TestCase):
    def args(self, threads, size, cores="0+1+2+3", extra=()):
        return ["--kernel", "K02", "--threads", str(threads), "--cores", cores, "--size-mb", str(size), "--warmup-s", "0", "--window-s", "0.1", "--runs", "1",
                "--tag", "C4-read-all", "--json"] + list(extra)

    def test_refuses_over_55_percent_of_memtotal(self):
        # MemTotal 400 MiB -> 55 % = 220 MiB; MemAvailable 399 -> 70 % = 279; 2 x 128 = 256 > 220
        rc, rec, out, err = run(MEMBW, self.args(2, 128), env={"P1_MEMINFO_PATH": meminfo(400, 399)})
        self.assertEqual(rc, 88)
        self.assertEqual(rec["status"], "skipped")
        self.assertIsNone(rec["buffer_mib"])
        self.assertAlmostEqual(rec["conditions"]["memtotal_mib"], 400, places=1)
        self.assertNotIn("# pin", out)               # nothing allocated, nothing pinned
        schema_check(self, rec, "C4-read-all")

    def test_refuses_over_70_percent_of_memavailable(self):
        # MemTotal 1000 -> 550; MemAvailable 100 -> 70; 1 x 128 > 70
        rc, rec, out, err = run(MEMBW, self.args(1, 128), env={"P1_MEMINFO_PATH": meminfo(1000, 100)})
        self.assertEqual(rc, 88)

    def test_refuses_over_caller_limit(self):
        # plenty of memory, but --mem-limit-mib 100 < 2 x 64
        rc, rec, out, err = run(MEMBW, self.args(2, 64, extra=["--mem-limit-mib", "100"]), env={"P1_MEMINFO_PATH": meminfo(8000, 6000)})
        self.assertEqual(rc, 88)
        self.assertIn("--mem-limit-mib", rec["reason"])

    def test_exactly_at_the_limit_is_allowed(self):
        rc, rec, out, err = run(MEMBW, self.args(2, 64, extra=["--mem-limit-mib", "128"]), env={"P1_MEMINFO_PATH": meminfo(8000, 6000)})
        self.assertEqual(rc, 0, err)
        self.assertEqual(rec["buffer_mib"], 64)
        self.assertEqual(rec["labels"], [])

    def test_fit_buffer_shrinks_and_labels(self):
        # limit = min(55 % of 400 = 220, 70 % of 399 = 279) = 220; 2 x 128 = 256 does not fit; floor(220 / 2) = 110 MiB per thread
        rc, rec, out, err = run(MEMBW, self.args(2, 128, extra=["--fit-buffer"]), env={"P1_MEMINFO_PATH": meminfo(400, 399)})
        self.assertEqual(rc, 0, err)
        self.assertEqual(rec["buffer_mib"], 110)
        self.assertIn("smaller buffer", rec["labels"])
        self.assertIn("smaller buffer", rec["conditions"]["labels"])
        schema_check(self, rec, "C4-read-all")

    def test_fit_buffer_respects_the_64_mib_floor(self):
        # limit 70 MiB for 2 threads: floor(70/2) = 35 < 64 -> refused
        rc, rec, out, err = run(MEMBW, self.args(2, 128, extra=["--fit-buffer"]), env={"P1_MEMINFO_PATH": meminfo(1000, 100)})
        self.assertEqual(rc, 88)
        self.assertIn("64 MiB", rec["reason"])

    def test_fit_buffer_does_nothing_when_it_fits(self):
        rc, rec, out, err = run(MEMBW, self.args(1, 64, extra=["--fit-buffer"]), env={"P1_MEMINFO_PATH": meminfo(8000, 6000)})
        self.assertEqual(rc, 0, err)
        self.assertEqual(rec["buffer_mib"], 64)
        self.assertNotIn("smaller buffer", rec["labels"])

    def test_unreadable_meminfo_refuses(self):
        rc, rec, out, err = run(MEMBW, self.args(1, 64), env={"P1_MEMINFO_PATH": os.path.join(TMP, "no-such-meminfo")})
        self.assertEqual(rc, 88)

    def test_sweep_over_limit_refused_and_cut_with_fit(self):
        base = ["--sweep", "--sizes", "1,300", "--kernel", "K02", "--threads", "1", "--cores", "0", "--warmup-s", "0", "--window-s", "0.1", "--tag", "C1-sweep-k0", "--json"]
        env = {"P1_MEMINFO_PATH": meminfo(400, 399)}      # limit 220
        rc, rec, out, err = run(MEMBW, base, env=env)
        self.assertEqual(rc, 88)
        rc, rec, out, err = run(MEMBW, base + ["--fit-buffer"], env=env)
        self.assertEqual(rc, 0, err)
        self.assertIn("smaller buffer", rec["labels"])
        self.assertEqual(rec["buffer_mib"], 1)

    def test_pacer_fixed_buffer_refused(self):
        rc, rec, out, err = run(MEMBW, ["--pace-chase", "--cores", "0", "--duration-s", "0.1", "--tag", "PC-pacer-chase-off", "--json", "--fit-buffer"], env={"P1_MEMINFO_PATH": meminfo(400, 399)})
        self.assertEqual(rc, 88)

    def test_gpubw_guard(self):
        if not GPUBW:
            self.skipTest("no Vulkan headers for the host gpubw build")
        base = ["--json", "--tag", "C7-gpu-alone", "--modes", "0", "--per-wg", "4096"]
        rc, rec, out, err = run(GPUBW, base, env={"P1_MEMINFO_PATH": meminfo(1000, 900)})      # default 2 x 512 MiB > 550
        self.assertEqual(rc, 88)
        schema_check(self, rec, "C7-gpu-alone")
        # with --fit-buffer: floor(550 / 2) = 275 MiB per buffer; the host stub then reports "no Vulkan device" (exit 3) and the record carries the size and label
        rc, rec, out, err = run(GPUBW, base + ["--fit-buffer"], env={"P1_MEMINFO_PATH": meminfo(1000, 900)})
        self.assertEqual(rc, 3)
        self.assertEqual(rec["buffer_mib"], 275)
        self.assertIn("smaller buffer", rec["labels"])


class TestPinning(unittest.TestCase):
    ENV = {"LD_PRELOAD": None, "FAKE_BAD_CPU": "1", "PINRETRY_MAX_MS": "300", "PINRETRY_STEP_MS": "50"}

    def env(self):
        return dict(self.ENV, LD_PRELOAD=FAKEAFF)

    def args(self, extra=()):
        a = list(FAST)
        a[a.index("--cores") + 1] = "1"
        return a + list(extra)

    def test_pin_failure_exits_87(self):
        rc, rec, out, err = run(MEMBW, self.args(), env=self.env())
        self.assertEqual(rc, 87)
        self.assertEqual(rec["status"], "failed")
        self.assertEqual(rec["pin"]["result"], "failed")
        self.assertGreater(rec["pin"]["attempts"], 1)
        schema_check(self, rec)

    def test_allow_unpinned_runs_and_says_so(self):
        rc, rec, out, err = run(MEMBW, self.args(["--allow-unpinned"]), env=self.env())
        self.assertEqual(rc, 0, err)
        self.assertEqual(rec["status"], "ok")
        self.assertEqual(rec["pin"]["result"], "unpinned")
        self.assertEqual(rec["conditions"]["pin"], "unpinned")
        self.assertEqual(rec["cpu_set"], [])
        self.assertIn("unpinned", rec["labels"])
        self.assertIn("pin: unpinned", out)
        schema_check(self, rec)

    def test_pacer_pin_failure_and_allow_unpinned(self):
        a = ["--pace-idle", "--cores", "1", "--duration-s", "0.1", "--tag", "PC-pacer-idle-off", "--json"]
        rc, rec, out, err = run(MEMBW, a, env=self.env())
        self.assertEqual(rc, 87)
        rc, rec, out, err = run(MEMBW, a + ["--allow-unpinned"], env=self.env())
        self.assertEqual(rc, 0, err)
        self.assertEqual(rec["pin"]["result"], "unpinned")


class TestTimeLimit(unittest.TestCase):
    def test_membw_timeout_exits_124_without_outside_timeout(self):
        import time
        t0 = time.time()
        rc, rec, out, err = run(MEMBW, ["--kernel", "K02", "--threads", "1", "--cores", "0", "--size-mb", "4", "--allow-cached", "--warmup-s", "0", "--window-s", "30",
                                        "--runs", "1", "--tag", "C2-read-k0", "--json", "--max-seconds", "1"])
        self.assertEqual(rc, 124)
        self.assertLess(time.time() - t0, 8)
        self.assertEqual(rec["status"], "failed")
        self.assertEqual(rec["exit_code"], 124)
        self.assertIn("timeout", rec["labels"])
        self.assertIn("--max-seconds", rec["reason"])
        schema_check(self, rec)

    def test_a_row_that_finishes_in_time_is_not_killed(self):
        rc, rec, out, err = run(MEMBW, FAST + ["--max-seconds", "30"])
        self.assertEqual(rc, 0, err)
        self.assertEqual(rec["status"], "ok")

    def test_gpubw_timeout(self):
        if not GPUBW:
            self.skipTest("no Vulkan headers for the host gpubw build")
        rc, rec, out, err = run(GPUBW, ["--json", "--tag", "C7-gpu-alone", "--modes", "0", "--per-wg", "4096", "--size-mb", "64", "--max-seconds", "1"], env={"FAKE_VK_SLEEP": "5"})
        self.assertEqual(rc, 124)
        self.assertEqual(rec["status"], "failed")
        schema_check(self, rec, "C7-gpu-alone")


class TestUsageAndRefusals(unittest.TestCase):
    def test_unknown_option(self):
        rc, rec, out, err = run(MEMBW, ["--bogus", "1", "--json"])
        self.assertEqual(rc, 2)
        self.assertEqual(rec["status"], "failed")
        self.assertIn("--bogus", rec["reason"])
        self.assertNotIn("tag", rec)                  # the engine does not know the tag before parsing; the app adds it
        schema_check(self, rec, usage_error=True)

    def test_usage_errors_are_json_even_without_json_flag(self):
        rc, rec, out, err = run(MEMBW, ["--threads", "1"])
        self.assertEqual(rc, 2)
        self.assertIsNotNone(rec)

    def test_bad_values(self):
        for bad in (["--runs", "9"], ["--runs", "0"], ["--max-seconds", "0"], ["--max-seconds", "x"], ["--mem-limit-mib", "-1"], ["--gate", "maybe"], ["--tier", "T9"], ["--cycles", "0"]):
            rc, rec, out, err = run(MEMBW, FAST + bad)
            self.assertEqual(rc, 2, bad)
            self.assertEqual(rec["status"], "failed")
            schema_check(self, rec)                  # P1-03G (review F02): every record produced here validates as emitted
            if bad[0] == "--tier":
                self.assertNotIn("tier", rec)

    def test_small_buffer_needs_allow_cached(self):
        rc, rec, out, err = run(MEMBW, [a for a in FAST if a != "--allow-cached"])
        self.assertEqual(rc, 2)

    def test_split_and_suite_not_in_json_mode(self):
        rc, rec, out, err = run(MEMBW, FAST + ["--split", "K07"])
        self.assertEqual(rc, 2)
        rc, rec, out, err = run(MEMBW, ["--suite", "--json"])
        self.assertEqual(rc, 2)

    def test_dma_heap_option_is_gone(self):
        rc, rec, out, err = run(MEMBW, FAST + ["--map", "uncached"])
        self.assertEqual(rc, 2)

    def test_gpubw_json_needs_one_variant(self):
        if not GPUBW:
            self.skipTest("no Vulkan headers for the host gpubw build")
        rc, rec, out, err = run(GPUBW, ["--json", "--tag", "C7-gpu-alone", "--modes", "0,3"])
        self.assertEqual(rc, 2)

    def test_gpubw_no_device_is_unsupported(self):
        if not GPUBW:
            self.skipTest("no Vulkan headers for the host gpubw build")
        rc, rec, out, err = run(GPUBW, ["--json", "--tag", "C7-gpu-alone", "--modes", "0", "--per-wg", "4096", "--size-mb", "64"])
        self.assertEqual(rc, 3)
        self.assertEqual(rec["status"], "unsupported")
        self.assertEqual(rec["pin"]["result"], "not_applicable")
        schema_check(self, rec, "C7-gpu-alone")

    def test_gpubw_row_kind_pacer(self):
        if not GPUBW:
            self.skipTest("no Vulkan headers for the host gpubw build")
        rc, rec, out, err = run(GPUBW, ["--json", "--tag", "PC-pacer-x1spin-on", "--row-kind", "pacer", "--modes", "0", "--per-wg", "4096", "--size-mb", "64"])
        self.assertEqual(rec["kind"], "pacer")
        schema_check(self, rec, "PC-pacer-x1spin-on")

    def test_gpubw_cpu_stream_labels(self):
        if not GPUBW:
            self.skipTest("no Vulkan headers for the host gpubw build")
        rc, rec, out, err = run(GPUBW, ["--json", "--tag", "C8-gpu-with-cpustream", "--modes", "0", "--per-wg", "4096", "--size-mb", "64", "--cpu-set", "6+7", "--cpu-threads", "2"])
        self.assertEqual(rec["cpu_set"], [6, 7])
        self.assertEqual(rec["threads"], 2)
        schema_check(self, rec, "C8-gpu-with-cpustream")


class TestDocsAndSources(unittest.TestCase):
    def test_every_exit_code_is_documented(self):
        hdr = rd(os.path.join(ROOT, "src", "part1_common.h"))
        codes = {int(m.group(1)) for m in re.finditer(r"#define P1_EXIT_\w+\s+(\d+)", hdr)}
        doc = rd(os.path.join(ROOT, "EXIT-CODES.md"))
        self.assertEqual(codes, {0, 1, 2, 3, 87, 88, 97, 124})
        for c in codes:
            self.assertRegex(doc, r"\|\s*%d\s*\|" % c)

    def test_version_file_matches_build_script(self):
        v = rd(os.path.join(ROOT, "VERSION")).strip()
        self.assertRegex(v, r"^\d+\.\d+\.\d+$")
        self.assertIn("VERSION", rd(os.path.join(ROOT, "build.sh")))

    @unittest.skipUnless(KIT_MEMBW and os.path.exists(KIT_MEMBW), KIT_SKIP)
    def test_kernel_text_is_the_kits(self):
        kit = rd(KIT_MEMBW).split("\n")
        mine = rd(os.path.join(ROOT, "src", "membw.c")).split("\n")
        def region(lines, start_pat, end_pat):
            i = next(n for n, l in enumerate(lines) if l.startswith(start_pat))
            j = next(n for n, l in enumerate(lines) if n > i and l.startswith(end_pat))
            return lines[i:j]
        # K01..K12 (asm kernels): from "#define V_CLOB" up to the line before "typedef struct { const char *id" (kit) / the "#else" host-stub branch (mine)
        kk = region(kit, "#define V_CLOB", "typedef struct { const char *id")
        mm = region(mine, "#define V_CLOB", "#else")
        self.assertEqual([l for l in kk if l.strip()], [l for l in mm if l.strip()])
        # chase_loop asm body
        kc = region(kit, "static void *chase_loop", "static int perf_open")
        mc = region(mine, "static void *chase_loop", "#else")
        self.assertEqual(kc, mc)
        # the kernel table (kern_t, KERN[]) is byte-for-byte
        kt = region(kit, "typedef struct { const char *id", "static uint64_t now_ns")
        mt = region(mine, "typedef struct { const char *id", "static uint64_t now_ns")
        self.assertEqual(kt, mt)

    @unittest.skipUnless(KIT_PIN and os.path.exists(KIT_PIN), KIT_SKIP)
    def test_pinretry_and_shader_are_byte_copies(self):
        self.assertEqual(rd(KIT_PIN, "rb"), rd(os.path.join(ROOT, "src", "pinretry.h"), "rb"))
        self.assertEqual(rd(KIT_SPV, "rb"), rd(os.path.join(ROOT, "src", "gpubw", "shader_spv.h"), "rb"))

    def test_no_forbidden_dependencies_in_sources(self):
        for rel in ("src/membw.c", "src/gpubw/gpubw.c", "src/part1_common.h"):
            text = rd(os.path.join(ROOT, rel))
            code = "\n".join(l for l in text.split("\n") if not l.lstrip().startswith("/*") and not l.lstrip().startswith("*"))
            for bad in ("dumpsys", "taskset", "/dev/dma_heap", "dma-heap.h", "system("):
                self.assertNotIn(bad, re.sub(r"/\*.*?\*/", "", code, flags=re.S), "%s uses %s" % (rel, bad))

    def test_no_device_identifiers_in_the_tree(self):
        pat = re.compile(r"\b(?:[0-9A-F]{2}:){5}[0-9A-F]{2}\b|\b(?:\d{1,3}\.){3}\d{1,3}\b", re.I)
        for dp, dn, fn in os.walk(ROOT):
            if "/out" in dp or "/raw" in dp or "__pycache__" in dp:
                continue
            for f in fn:
                p = os.path.join(dp, f)
                try:
                    t = rd(p)
                except OSError:
                    continue
                for m in pat.finditer(t):
                    self.fail("%s contains a MAC- or IP-like string: %s" % (p, m.group(0)))


@unittest.skipUnless(find_ndk() and HAVE_OUT, "no NDK, or " + OUT_SKIP)
class TestCrossBuiltBinaries(unittest.TestCase):
    """ELF facts of the shipped arm64 binaries (they cannot be run here: no qemu-aarch64)."""

    def test_elf_rules(self):
        outd = os.path.join(ROOT, "out", "arm64-v8a")
        files = [os.path.join(outd, n) for n in ("libpart1_membw.so", "libpart1_gpubw.so")]
        for f in files:
            self.assertTrue(os.path.exists(f), f)
        r = subprocess.run([os.path.join(ROOT, "tools", "elf-facts.sh")] + files, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        self.assertEqual(r.returncode, 0, r.stdout)
        self.assertNotIn("RULE FAIL", r.stdout)
        self.assertIn("AArch64", r.stdout)
        self.assertIn("android_api_level_in_note: 33", r.stdout)


# ---------------------------------------------------------------------------------------------------------------------------------------------
# P1-03F: tests for the fixes B1-B10 and the document changes C1-C5. Each B test fails on the 0.1.0 sources (see raw/old-code-tests.txt of the report).
# ---------------------------------------------------------------------------------------------------------------------------------------------
class TestB1RawLinesValidate(unittest.TestCase):
    """B1 (review F01): every raw last line validates against part1-rows-v1 with nothing removed and nothing added."""

    def test_measured_row_with_minflt_validates_raw(self):
        rc, rec, out, err = run(MEMBW, FAST)
        self.assertIn("minflt_timed", rec)
        self.assertIsInstance(rec["minflt_timed"], int)
        schema_check(self, rec)

    def test_every_refusal_and_error_validates_raw_with_reason(self):
        cases = [
            (97, FAST, {"P1_CPUSET_PATH": cpuset_fg()}),
            (88, FAST + ["--mem-limit-mib", "1"], {}),
            (2, FAST + ["--runs", "9"], {}),
            (124, ["--kernel", "K02", "--threads", "1", "--cores", "0", "--size-mb", "4", "--allow-cached", "--warmup-s", "0", "--window-s", "30", "--runs", "1",
                   "--tag", "C2-read-k0", "--json", "--max-seconds", "1"], {}),
        ]
        for code, args, env in cases:
            rc, rec, out, err = run(MEMBW, args, env=env)
            self.assertEqual(rc, code, (code, err))
            self.assertIn("reason", rec)
            self.assertIsInstance(rec["reason"], str)
            schema_check(self, rec)

    def test_exit_87_validates_raw(self):
        rc, rec, out, err = run(MEMBW, FAST[:FAST.index("--cores") + 1] + ["1"] + FAST[FAST.index("--cores") + 2:], env=dict(TestPinning.ENV, LD_PRELOAD=FAKEAFF))
        self.assertEqual(rc, 87)
        self.assertIn("reason", rec)
        schema_check(self, rec)

    def test_usage_error_after_tag_has_the_tag(self):
        rc, rec, out, err = run(MEMBW, ["--tag", "C2-read-k0", "--bogus", "1", "--json"])
        self.assertEqual(rc, 2)
        self.assertEqual(rec["tag"], "C2-read-k0")
        schema_check(self, rec)

    def test_only_a_usage_error_before_tag_may_lack_the_tag(self):
        rc, rec, out, err = run(MEMBW, ["--bogus", "1", "--tag", "C2-read-k0", "--json"])
        self.assertEqual(rc, 2)
        self.assertNotIn("tag", rec)
        schema_check(self, rec, usage_error=True)

    def test_pacer_and_gpu_raw_lines_validate(self):
        rc, rec, out, err = run(MEMBW, ["--pace-idle", "--cores", "0", "--duration-s", "0.1", "--tag", "PC-pacer-idle-off", "--json"])
        schema_check(self, rec)
        if GPUBW:
            rc, rec, out, err = run(GPUBW, ["--json", "--tag", "C7-gpu-alone", "--modes", "0", "--per-wg", "4096", "--size-mb", "64"])
            schema_check(self, rec)


class TestB2SweepFailureRecords(unittest.TestCase):
    """B2 (review F02): a sweep that fails or times out at a later size writes a valid record, with nothing left over from the earlier size."""

    SW = ["--sweep", "--sizes", "0.5,1", "--kernel", "K02", "--threads", "1", "--cores", "0", "--warmup-s", "0", "--window-s", "0.1", "--tag", "C1-sweep-k0", "--json"]

    def test_timeout_at_a_later_size_exit_124(self):
        rc, rec, out, err = run(MEMBW, self.SW + ["--max-seconds", "6.5"])
        self.assertEqual(rc, 124)
        self.assertIn("C1-sweep-k0-mom", out)       # the first size finished: a leftover count exists in the old code
        schema_check(self, rec)
        self.assertEqual(rec["repeats"], 1)
        self.assertTrue(rec["single_shot"])
        self.assertIsNone(rec["gbs_median"])
        self.assertIsNone(rec["gbs_min"])

    def test_pin_failure_at_a_later_size_exit_87(self):
        env = dict(TestPinning.ENV, LD_PRELOAD=FAKEAFF, FAKE_BAD_FROM="3")      # the three runs of the first size pin, the first run of the second size cannot
        a = list(self.SW)
        a[a.index("--cores") + 1] = "1"
        rc, rec, out, err = run(MEMBW, a, env=env)
        self.assertEqual(rc, 87)
        self.assertIn("C1-sweep-k0-mom", out)
        schema_check(self, rec)
        self.assertEqual(rec["repeats"], 1)
        self.assertTrue(rec["single_shot"])
        self.assertEqual(rec["pin"]["result"], "failed")


class TestB3PinStatePerCpu(unittest.TestCase):
    """B3 (review F03, F06): pin state per CPU for the whole process under --allow-unpinned."""

    def args(self, runs="3", extra=()):
        a = list(FAST)
        a[a.index("--cores") + 1] = "1"
        a[a.index("--runs") + 1] = runs
        return a + ["--allow-unpinned"] + list(extra)

    def test_a_failed_cpu_is_not_retried_in_later_runs(self):
        rc, rec, out, err = run(MEMBW, self.args("3"), env=dict(TestPinning.ENV, LD_PRELOAD=FAKEAFF))
        self.assertEqual(rc, 0, err)
        self.assertEqual(out.count("result=repin_failed"), 1)           # one 5 s wait (here 300 ms) per CPU per process, not one per run
        self.assertEqual(out.count("# pin: unpinned"), 3)               # but the row says so every run
        self.assertEqual(rec["pin"]["result"], "unpinned")
        self.assertIn("unpinned", rec["labels"])
        self.assertEqual(rec["cpu_set"], [])
        schema_check(self, rec)

    def test_a_failed_cpu_is_not_retried_in_later_sweep_sizes(self):
        a = ["--sweep", "--sizes", "0.5,1", "--kernel", "K02", "--threads", "1", "--cores", "1", "--warmup-s", "0", "--window-s", "0.1", "--tag", "C1-sweep-k1", "--json", "--allow-unpinned"]
        rc, rec, out, err = run(MEMBW, a, env=dict(TestPinning.ENV, LD_PRELOAD=FAKEAFF))
        self.assertEqual(rc, 0, err)
        self.assertEqual(out.count("result=repin_failed"), 1)
        self.assertEqual(rec["pin"]["result"], "unpinned")
        schema_check(self, rec)

    def test_any_unpinned_run_makes_the_row_unpinned(self):
        # run 1 pins; run 2 cannot (calls 1..8 fail); run 3 would pin again in the 0.1.0 code and the row would say `ok`
        env = dict(TestPinning.ENV, LD_PRELOAD=FAKEAFF, FAKE_BAD_FROM="1", FAKE_BAD_TO="9")
        rc, rec, out, err = run(MEMBW, self.args("3"), env=env)
        self.assertEqual(rc, 0, err)
        self.assertEqual(rec["pin"]["result"], "unpinned")
        self.assertEqual(rec["conditions"]["pin"], "unpinned")
        self.assertIn("unpinned", rec["labels"])
        self.assertEqual(rec["cpu_set"], [])
        schema_check(self, rec)

    def test_without_allow_unpinned_the_first_failure_is_exit_87(self):
        a = self.args("3")
        a.remove("--allow-unpinned")
        rc, rec, out, err = run(MEMBW, a, env=dict(TestPinning.ENV, LD_PRELOAD=FAKEAFF))
        self.assertEqual(rc, 87)
        self.assertEqual(out.count("result=repin_failed"), 1)
        self.assertNotIn("# pin: unpinned", out)


class TestB4GpuModesAndPerWg(unittest.TestCase):
    """B4 (review F04): a mode or per-wg that measures nothing is a usage error with --json."""

    def setUp(self):
        if not GPUBW:
            self.skipTest("no Vulkan headers for the host gpubw build")

    def g(self, modes, perwg, size="64"):
        return run(GPUBW, ["--json", "--tag", "C7-gpu-alone", "--modes", modes, "--per-wg", perwg, "--size-mb", size])

    def test_bad_values_are_usage_errors(self):
        for modes, perwg in (("9", "4096"), ("6", "4096"), ("x", "4096"), ("-1", "4096"), ("0", "7"), ("0", "0"), ("0", "x"), ("0", "-4096"), ("4", "1024"), ("5", "2048"), ("0", "1"), ("0", "")):
            rc, rec, out, err = self.g(modes, perwg)
            self.assertEqual(rc, 2, (modes, perwg, out))
            self.assertEqual(rec["status"], "failed")
            self.assertTrue(rec["reason"])
            self.assertEqual(rec["kind"], "gpu")
            schema_check(self, rec)

    def test_per_wg_too_large_for_the_buffer(self):
        rc, rec, out, err = self.g("0", "4194304", size="64")       # 64 MiB / 16 = 4194304 units: one workgroup would be fine; 8388608 gives zero
        self.assertEqual(rc, 3)                                      # accepted: it reaches the (fake) Vulkan start
        rc, rec, out, err = self.g("0", "8388608", size="64")
        self.assertEqual(rc, 2)

    def test_valid_values_reach_vulkan(self):
        for modes, perwg in (("0", "4096"), ("1", "1024"), ("4", "2048"), ("5", "4096"), ("3", "16384")):
            rc, rec, out, err = self.g(modes, perwg)
            self.assertEqual(rc, 3, (modes, perwg, rec))             # the host stub has no Vulkan device: unsupported, after the option checks

    def test_a_bad_value_is_refused_before_the_buffers_are_sized_by_fit_buffer(self):
        rc, rec, out, err = run(GPUBW, ["--json", "--tag", "C7-gpu-alone", "--modes", "0", "--per-wg", "7", "--fit-buffer"], env={"P1_MEMINFO_PATH": meminfo(1000, 900)})
        self.assertEqual(rc, 2)


class TestB5Exit87Record(unittest.TestCase):
    """B5 (review F05): the exit-87 record says what happened: pin failed, CPUs requested, no `unpinned`, no --allow-unpinned note."""

    def test_record(self):
        a = list(FAST)
        a[a.index("--cores") + 1] = "1"
        rc, rec, out, err = run(MEMBW, a, env=dict(TestPinning.ENV, LD_PRELOAD=FAKEAFF))
        self.assertEqual(rc, 87)
        self.assertNotIn("unpinned", rec["labels"])
        self.assertNotIn("unpinned", rec["conditions"]["labels"])
        self.assertNotIn("--allow-unpinned", json.dumps(rec))
        self.assertEqual(rec["cpu_set"], [1])
        self.assertEqual(rec["conditions"]["cpu_set"], "1")
        self.assertEqual(rec["pin"]["result"], "failed")
        self.assertEqual(rec["conditions"]["pin"], "failed")
        self.assertGreater(rec["pin"]["attempts"], 1)
        schema_check(self, rec)

    def test_two_threads_names_both_cpus(self):
        a = list(FAST)
        a[a.index("--cores") + 1] = "0+1"
        a[a.index("--threads") + 1] = "2"
        rc, rec, out, err = run(MEMBW, a, env=dict(TestPinning.ENV, LD_PRELOAD=FAKEAFF))
        self.assertEqual(rc, 87)
        self.assertEqual(rec["cpu_set"], [0, 1])


class TestB6KindAndRowKind(unittest.TestCase):
    """B6 (review F07): kind comes from the program before an option error; --row-kind takes only the schema's enum values."""

    ENUM = ("pinprobe", "read", "write", "copy", "gpu", "sweep", "prefetch", "pacer", "sustained")

    def test_gpubw_usage_error_is_kind_gpu(self):
        if not GPUBW:
            self.skipTest("no Vulkan headers for the host gpubw build")
        for bad in (["--gate", "maybe"], ["--tier", "T9"], ["--max-seconds", "x"], ["--bogus", "1"]):
            rc, rec, out, err = run(GPUBW, ["--json"] + bad)
            self.assertEqual(rc, 2, bad)
            self.assertEqual(rec["kind"], "gpu", bad)

    def test_row_kind_only_the_schema_enum(self):
        for exe, extra in ((MEMBW, FAST), (GPUBW, ["--json", "--tag", "C7-gpu-alone", "--modes", "0", "--per-wg", "4096", "--size-mb", "64"])):
            if not exe:
                continue
            for bad in ("bogus", "READ", "", "gpu,pacer", "sweeep"):
                rc, rec, out, err = run(exe, extra + ["--row-kind", bad])
                self.assertEqual(rc, 2, (exe, bad))
                self.assertEqual(rec["status"], "failed")
                self.assertIn("--row-kind", rec["reason"])
                schema_check(self, rec, usage_error="tag" not in rec)

    def test_every_enum_value_is_accepted(self):
        for k in self.ENUM:
            rc, rec, out, err = run(MEMBW, FAST + ["--row-kind", k])
            self.assertEqual(rc, 0, (k, err))
            self.assertEqual(rec["kind"], k)
        if GPUBW:
            for k in self.ENUM:
                rc, rec, out, err = run(GPUBW, ["--json", "--tag", "C7-gpu-alone", "--modes", "0", "--per-wg", "4096", "--size-mb", "64", "--row-kind", k])
                self.assertEqual(rc, 3, k)                        # past the option checks (no Vulkan device in the host build)
                self.assertEqual(rec["kind"], k)


class TestB7TimeoutIsTheLastLine(unittest.TestCase):
    """B7 (review F11): the timeout object is the last stdout line: stdout stays locked from the emit until _exit. The shim prints a line from another
    thread as the program leaves (the 0.1.0 code had released the lock by then)."""

    def test_last_line_is_the_timeout_object(self):
        for _ in range(3):
            rc, rec, out, err = run(MEMBW, ["--kernel", "K02", "--threads", "1", "--cores", "0", "--size-mb", "4", "--allow-cached", "--warmup-s", "0", "--window-s", "30", "--runs", "1",
                                            "--tag", "C2-read-k0", "--json", "--max-seconds", "1"], env={"LD_PRELOAD": FAKESPAM})
            self.assertEqual(rc, 124)
            lines = out.splitlines()
            self.assertTrue(lines[-1].startswith("{"), lines[-3:])
            self.assertEqual(json.loads(lines[-1])["exit_code"], 124)
            schema_check(self, json.loads(lines[-1]))

    def test_source_holds_the_lock(self):
        src = rd(os.path.join(ROOT, "src", "part1_common.h"))
        i = src.index("static void *p1_watchdog")
        body = src[i:src.index("static void p1_start_watchdog")]
        self.assertIn("p1_emit(P1_FAILED, P1_EXIT_TIMEOUT, b, 1)", body)
        self.assertNotIn("funlockfile", body)


class TestB8ProductionIgnoresPinRetryEnv(unittest.TestCase):
    """B8 (review F16): a production build ignores PINRETRY_STEP_MS and PINRETRY_MAX_MS (the host-test build still honours them)."""

    def test_production_build_uses_the_kit_5_s_window(self):
        import time
        a = ["--kernel", "K02", "--threads", "1", "--cores", "1", "--size-mb", "4", "--allow-cached", "--warmup-s", "0.1", "--window-s", "0.2", "--runs", "1", "--tag", "C2-read-k1",
             "--json", "--gate", "off", "--allow-unpinned"]
        t0 = time.time()
        rc, rec, out, err = run(MEMBW_PROD, a, env=dict(TestPinning.ENV, LD_PRELOAD=FAKEAFF))      # PINRETRY_MAX_MS=300 is set and must be ignored
        self.assertEqual(rc, 0, err)
        self.assertGreater(time.time() - t0, 4.5)
        self.assertGreaterEqual(rec["pin"]["attempts"], 90)         # 0, 50, ..., 5000 ms = 101 attempts
        self.assertGreaterEqual(rec["pin"]["time_to_pin_ms"], 4900)
        self.assertEqual(rec["pin"]["result"], "unpinned")
        schema_check(self, rec)

    def test_host_test_build_still_honours_it(self):
        import time
        a = list(FAST)
        a[a.index("--cores") + 1] = "1"
        t0 = time.time()
        rc, rec, out, err = run(MEMBW, a + ["--allow-unpinned"], env=dict(TestPinning.ENV, LD_PRELOAD=FAKEAFF))
        self.assertLess(time.time() - t0, 3)

    def test_both_programs_clear_the_variables_at_start(self):
        for rel in ("src/membw.c", "src/gpubw/gpubw.c"):
            self.assertIn("p1_clear_host_env()", rd(os.path.join(ROOT, rel)))
        h = rd(os.path.join(ROOT, "src", "part1_common.h"))
        self.assertRegex(h, r"#ifndef P1_HOST_BUILD\s+unsetenv\(\"PINRETRY_STEP_MS\"\); unsetenv\(\"PINRETRY_MAX_MS\"\);")


@unittest.skipUnless(find_ndk(), "no NDK")
class TestB9BuildScriptLogs(unittest.TestCase):
    """B9 (review F13): build.sh writes its logs under the chosen output directory and leaves raw/ alone; its header does not mention an ELF check."""

    @unittest.skipUnless(HAVE_RAW, RAW_SKIP)
    def test_logs_go_to_outdir(self):
        out = uniq("build-out")
        before = {n: os.stat(os.path.join(SRC, "raw", n)).st_mtime_ns for n in os.listdir(os.path.join(SRC, "raw")) if n.startswith("build-")}
        r = subprocess.run([os.path.join(SRC, "build.sh"), out], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        self.assertEqual(r.returncode, 0, r.stdout)
        for n in ("libpart1_membw.so", "libpart1_gpubw.so"):
            self.assertTrue(os.path.exists(os.path.join(out, n)))
        logs = [os.path.join(dp, n) for dp, dn, fn in os.walk(out) for n in fn if n.endswith(".log")]
        self.assertTrue(any("membw" in n for n in logs), logs)
        self.assertTrue(any("gpubw" in n for n in logs), logs)
        after = {n: os.stat(os.path.join(SRC, "raw", n)).st_mtime_ns for n in os.listdir(os.path.join(SRC, "raw")) if n.startswith("build-")}
        self.assertEqual(before, after)

    def test_header_is_correct(self):
        head = "\n".join(rd(os.path.join(SRC, "build.sh")).split("\n")[:8])
        self.assertNotIn("or ELF check", head)
        self.assertIn("runs no ELF check", head)
        self.assertIn("compile or link step failed", head)


class TestB10PinProbe(unittest.TestCase):
    """B10: libpart1_membw.so --pin-probe --cores LIST [--spin-ms N]: one part1-pinprobe-v1 object, not a row."""

    def P(self, extra, env=None, timeout=120):
        return run(MEMBW, ["--pin-probe", "--json"] + extra, env=env, timeout=timeout)

    def test_schema_file_is_a_valid_schema(self):
        import jsonschema
        sch = json.loads(rd(PINPROBE_SCHEMA))
        jsonschema.Draft202012Validator.check_schema(sch)
        self.assertEqual(sch["properties"]["format"]["const"], "part1-pinprobe-v1")

    def test_ok_probe(self):
        rc, rec, out, err = self.P(["--cores", "0,1", "--spin-ms", "200"])
        self.assertEqual(rc, 0, err)
        probe_check(self, rec)
        self.assertEqual(rec["format"], "part1-pinprobe-v1")
        self.assertEqual(rec["status"], "ok")
        self.assertEqual(rec["exit_code"], 0)
        self.assertEqual(rec["gate_basis"], "cpuset_top_app")
        self.assertEqual(rec["foreground_cpuset"], "/top-app")
        self.assertEqual(rec["engine_version"], "part1-engine/host-test")
        self.assertEqual([c["index"] for c in rec["cpus"]], [0, 1])
        for c in rec["cpus"]:
            self.assertEqual(c["result"], "ok")
            self.assertGreaterEqual(c["attempts"], 1)
        self.assertNotIn("tag", rec)
        self.assertNotIn("gbs_median", rec)
        self.assertEqual(out.rstrip("\n").splitlines()[-1][0], "{")
        self.assertEqual(len([ln for ln in out.splitlines() if ln.startswith("{")]), 1)

    def test_order_is_the_list_order(self):
        rc, rec, out, err = self.P(["--cores", "3+1+2", "--spin-ms", "30"])
        self.assertEqual([c["index"] for c in rec["cpus"]], [3, 1, 2])

    def test_spin_budget_is_divided_over_the_cpus(self):
        import time
        t0 = time.time()
        rc, rec, out, err = self.P(["--cores", "0,1,2,3", "--spin-ms", "800"])
        el = time.time() - t0
        self.assertEqual(rc, 0, err)
        self.assertGreater(el, 0.7)
        self.assertLess(el, 2.5)                  # not 4 x 800 ms
        self.assertGreater(rec["duration_ms"], 700)
        self.assertLess(rec["duration_ms"], 2500)

    def test_a_failed_cpu_does_not_fail_the_probe(self):
        rc, rec, out, err = self.P(["--cores", "0,1,2", "--spin-ms", "90"], env=dict(TestPinning.ENV, LD_PRELOAD=FAKEAFF))
        self.assertEqual(rc, 0, err)
        probe_check(self, rec)
        self.assertEqual(rec["status"], "ok")
        self.assertEqual([c["result"] for c in rec["cpus"]], ["ok", "failed", "ok"])     # the retry window of the failing CPU does not stop the next CPU
        self.assertGreater(rec["cpus"][1]["attempts"], 1)
        self.assertGreaterEqual(rec["cpus"][1]["time_to_pin_ms"], 250)

    def test_a_failed_pin_costs_at_most_the_retry_window(self):
        import time
        t0 = time.time()
        rc, rec, out, err = run(MEMBW_PROD, ["--pin-probe", "--json", "--gate", "off", "--cores", "1", "--spin-ms", "100"], env=dict(TestPinning.ENV, LD_PRELOAD=FAKEAFF))
        el = time.time() - t0
        self.assertEqual(rc, 0, err)
        self.assertEqual(rec["cpus"][0]["result"], "failed")
        self.assertGreaterEqual(el, 4.8)
        self.assertLess(el, 7.5)
        self.assertEqual(rec["gate_basis"], "gate_off")

    def test_gate_applies(self):
        rc, rec, out, err = self.P(["--cores", "0,1"], env={"P1_CPUSET_PATH": cpuset_fg()})
        self.assertEqual(rc, 97)
        probe_check(self, rec)
        self.assertEqual((rec["status"], rec["exit_code"], rec["cpus"]), ("skipped", 97, []))
        self.assertEqual(rec["foreground_cpuset"], "/foreground")
        self.assertNotIn("# pin", out)

    def test_usage_errors_are_probe_objects(self):
        for args in (["--cores", "0", "--tag", "x"], [], ["--cores", "0,0"], ["--cores", "0", "--spin-ms", "0"], ["--cores", "0", "--spin-ms", "x"], ["--cores", "abc"], ["--cores", "0", "--kernel", "K02"]):
            rc, rec, out, err = self.P(args)
            self.assertEqual(rc, 2, args)
            probe_check(self, rec)
            self.assertEqual(rec["status"], "failed")
            self.assertIn("reason", rec)

    def test_max_seconds_applies(self):
        import time
        t0 = time.time()
        rc, rec, out, err = self.P(["--cores", "0,1", "--spin-ms", "50", "--max-seconds", "1"], env=dict(TestPinning.ENV, LD_PRELOAD=FAKEAFF, PINRETRY_MAX_MS="4000"))
        self.assertEqual(rc, 124)
        self.assertLess(time.time() - t0, 3)
        probe_check(self, rec)
        self.assertEqual((rec["status"], rec["exit_code"]), ("failed", 124))

    def test_without_json_a_success_prints_no_object(self):
        rc, rec, out, err = run(MEMBW, ["--pin-probe", "--cores", "0", "--spin-ms", "30"])
        self.assertEqual(rc, 0)
        self.assertIsNone(rec)
        self.assertIn("# pinprobe cpu=0 result=ok", out)

    def test_gpubw_has_no_probe(self):
        if not GPUBW:
            self.skipTest("no Vulkan headers for the host gpubw build")
        rc, rec, out, err = run(GPUBW, ["--pin-probe", "--json"])
        self.assertEqual(rc, 2)

    def test_schema_rejects_extra_and_wrong_fields(self):
        import jsonschema
        rc, rec, out, err = self.P(["--cores", "0", "--spin-ms", "30"])
        v = jsonschema.Draft202012Validator(json.loads(rd(PINPROBE_SCHEMA)))
        for mut in (lambda r: r.update(extra=1), lambda r: r.update(status="void"), lambda r: r["cpus"][0].update(result="pinned"), lambda r: r.pop("duration_ms"),
                    lambda r: r["cpus"][0].update(attempts=-1), lambda r: r.update(format="part1-rows-v1"), lambda r: r.update(engine_version="kit v4d-5w")):
            bad = json.loads(json.dumps(rec))
            mut(bad)
            self.assertNotEqual(list(v.iter_errors(bad)), [])


class TestG(unittest.TestCase):
    """P1-03G: closing fixes (review items F03, F02, F05, F04, F06, F08 of the P1-03F review). Each test fails on the 0.2.0 code."""
    G = ["--size-mb", "64", "--dispatches", "1", "--warmup-dispatches", "1", "--hot-ms", "0", "--modes", "0", "--per-wg", "4096", "--tag", "C7-gpu-alone", "--gate", "on"]

    def need_full(self):
        if GPUBW_FULL is None:
            self.skipTest("no Vulkan headers for the gpubw host build")

    def test_f03_gpubw_without_json_is_the_kit_loop_seconds_exit_0(self):
        self.need_full()
        rc, rec, out, err = run(GPUBW_FULL, self.G + ["--loop-seconds", "0.2"])
        self.assertEqual(rc, 0, out[-300:] + err)
        self.assertIsNone(rec)                                          # no `failed` JSON line
        self.assertIn("verification passed for every variant", out)

    def test_f03_gpubw_without_json_every_variant_skipped_exit_0(self):
        self.need_full()
        a = list(self.G)
        a[a.index("--modes") + 1] = "9"
        rc, rec, out, err = run(GPUBW_FULL, a)
        self.assertEqual(rc, 0, out[-300:] + err)
        self.assertIsNone(rec)

    def test_f03_with_json_the_rule_stays(self):
        self.need_full()
        rc, rec, out, err = run(GPUBW_FULL, self.G + ["--json"])      # a good single-variant row is ok with a number
        self.assertEqual(rc, 0, err)
        self.assertEqual(rec["status"], "ok")
        self.assertGreater(rec["gbs_median"], 0)
        schema_check(self, rec)
        rc, rec, out, err = run(GPUBW_FULL, self.G + ["--json", "--per-wg", "1000"])   # measures nothing: usage error, never ok without a number
        self.assertEqual(rc, 2)
        self.assertEqual(rec["status"], "failed")

    def test_f02_bad_tier_gives_a_schema_valid_exit_2_record_in_both_programs(self):
        rc, rec, out, err = run(MEMBW, FAST + ["--tier", "T9"])
        self.assertEqual(rc, 2)
        self.assertNotIn("tier", rec)
        self.assertIn("T9", rec["reason"])
        schema_check(self, rec)
        if GPUBW:
            rc, rec, out, err = run(GPUBW, ["--json", "--tag", "C7-gpu-alone", "--tier", "T9"])
            self.assertEqual(rc, 2)
            self.assertNotIn("tier", rec)
            schema_check(self, rec)
        for t in ("T1", "T2", "T3"):
            rc, rec, out, err = run(MEMBW, FAST + ["--tier", t])
            self.assertEqual(rc, 0, err)
            self.assertEqual(rec["tier"], t)

    def test_f05_timeout_after_an_unpinned_run_reports_it(self):
        a = ["--kernel", "K02", "--threads", "1", "--cores", "1", "--size-mb", "4", "--allow-cached", "--warmup-s", "0.1", "--window-s", "0.2", "--runs", "3", "--tag", "C2-read-k0", "--json",
             "--allow-unpinned", "--max-seconds", "2.5"]
        rc, rec, out, err = run(MEMBW, a, env=dict(TestPinning.ENV, LD_PRELOAD=FAKEAFF))
        self.assertEqual(rc, 124, err)
        self.assertEqual(rec["pin"]["result"], "unpinned")
        self.assertEqual(rec["conditions"]["pin"], "unpinned")
        self.assertIn("unpinned", rec["labels"])
        self.assertIn("timeout", rec["labels"])
        self.assertEqual(rec["cpu_set"], [1])                            # the CPUs asked for
        self.assertIsNone(rec["gbs_median"])
        schema_check(self, rec)

    def test_f05_timeout_without_an_unpinned_run_is_unchanged(self):
        rc, rec, out, err = run(MEMBW, ["--kernel", "K02", "--threads", "1", "--cores", "0", "--size-mb", "4", "--allow-cached", "--warmup-s", "0", "--window-s", "30", "--runs", "1",
                                        "--tag", "C2-read-k0", "--json", "--max-seconds", "1"])
        self.assertEqual(rc, 124)
        self.assertEqual(rec["pin"]["result"], "not_applicable")
        self.assertNotIn("unpinned", rec["labels"])

    def test_f04_unpinned_sweep_keeps_the_points_and_one_pin_sentence(self):
        a = ["--sweep", "--sizes", "0.5,1,2,3", "--kernel", "K02", "--threads", "1", "--cores", "1", "--warmup-s", "0", "--window-s", "0.1", "--tag", "C1-sweep-k1", "--json", "--allow-unpinned"]
        rc, rec, out, err = run(MEMBW, a, env=dict(TestPinning.ENV, LD_PRELOAD=FAKEAFF), timeout=240)
        self.assertEqual(rc, 0, err)
        note = rec["conditions"]["note"]
        self.assertEqual(note.count("pin failed within the retry window"), 1, note)
        self.assertRegex(note, r"sweep total_MiB=GB/s: 0\.5=[0-9.]+,1=[0-9.]+,2=[0-9.]+,3=[0-9.]+")
        self.assertEqual(rec["pin"]["result"], "unpinned")
        schema_check(self, rec)

    def test_f06_duplicate_cpu_in_cores_is_a_usage_error_for_rows(self):
        for cores, thr in (("0+0", "2"), ("0,0", "1"), ("0,1,0", "1")):
            a = list(FAST)
            a[a.index("--cores") + 1] = cores
            a[a.index("--threads") + 1] = thr
            rc, rec, out, err = run(MEMBW, a)
            self.assertEqual(rc, 2, cores)
            self.assertEqual(rec["status"], "failed")
            self.assertIn("twice", rec["reason"])
            schema_check(self, rec)

    def test_f08_usage_error_kind_follows_the_invocation(self):
        cases = (("sweep", ["--sweep", "--pace-mode", "bogus"]), ("pacer", ["--pace-chase", "--pace-mode", "bogus"]), ("pacer", ["--pace-mode", "bogus", "--pace-idle"]),
                 ("sweep", ["--bogus", "1", "--sweep"]), ("read", ["--pace-mode", "bogus"]))
        for kind, a in cases:
            rc, rec, out, err = run(MEMBW, a + ["--json"])
            self.assertEqual(rc, 2, a)
            self.assertEqual(rec["kind"], kind, a)
            schema_check(self, rec, usage_error=True)
        rc, rec, out, err = run(MEMBW, ["--tag", "C1-sweep-k1", "--sweep", "--tier", "T9", "--json"])
        self.assertEqual(rec["kind"], "sweep")
        schema_check(self, rec)


# ======================================================================================================================================================
# P1-03H (engine 0.2.2): carried fixes A1-A5, GPU rate stability B1, static probes C1-C3. Every A and B test fails on the 0.2.1 code (raw/unittest-new-tests-on-0.2.1-code.txt).
# ======================================================================================================================================================
SCHEMA_DIR = ROOT
STATIC_SCHEMA = os.path.join(ROOT, "part1-staticprobe-v1.schema.json")
GPUPROBE_SCHEMA = os.path.join(ROOT, "part1-gpuprobe-v1.schema.json")
PROP_NAMES = ["ro.board.platform", "ro.hardware", "ro.product.board", "ro.product.device", "ro.soc.model", "ro.soc.manufacturer", "ro.build.version.sdk"]


def probe_schema_check(tc, rec, path):
    import jsonschema
    errs = [e.message for e in jsonschema.Draft202012Validator(json.loads(rd(path))).iter_errors(rec)]
    tc.assertEqual(errs, [], "schema errors (%s) for %s" % (os.path.basename(path), json.dumps(rec)[:300]))


def cpu_set_rule(tc, rec, requested):
    """The one rule for cpu_set (P1-03H A3): a row WITH a value whose pin result is `unpinned` lists no CPU (cpu_set [] and conditions.cpu_set null);
    every other record names the CPUs asked for (`requested`, a list) in both places, whatever its pin result."""
    if rec["status"] == "ok" and rec["pin"]["result"] == "unpinned":
        tc.assertEqual(rec["cpu_set"], [])
        tc.assertIsNone(rec["conditions"]["cpu_set"])
    else:
        tc.assertEqual(rec["cpu_set"], requested, json.dumps(rec)[:400])
        tc.assertEqual(rec["conditions"]["cpu_set"], "+".join(str(c) for c in requested))


class TestH_A1_A2_A3_PinFactsInEveryRecord(unittest.TestCase):
    def env(self, **kw):
        return dict(TestPinning.ENV, LD_PRELOAD=FAKEAFF, **kw)

    def test_a1_sweep_timeout_after_an_unpinned_size_reports_the_requested_cpu_set(self):
        a = ["--sweep", "--sizes", "0.5,1,2", "--kernel", "K02", "--threads", "1", "--cores", "1", "--warmup-s", "0", "--window-s", "0.1", "--tag", "C1-sweep-k1", "--json", "--allow-unpinned", "--max-seconds", "8"]
        rc, rec, out, err = run(MEMBW, a, env=self.env(), timeout=60)
        self.assertEqual(rc, 124, err)
        self.assertGreaterEqual(out.count("# pin: unpinned"), 3)            # at least one size finished unpinned before the limit
        self.assertEqual(rec["pin"]["result"], "unpinned")
        self.assertIn("unpinned", rec["labels"])
        self.assertIn("timeout", rec["labels"])
        self.assertEqual(rec["cpu_set"], [1])                                # 0.2.1: [] (P1.ncpu was cleared at the end of every unpinned sweep size)
        self.assertEqual(rec["conditions"]["cpu_set"], "1")
        self.assertIsNone(rec["gbs_median"])
        cpu_set_rule(self, rec, [1])
        schema_check(self, rec)

    def test_a2_timeout_during_the_first_run_that_is_already_unpinned(self):
        a = ["--kernel", "K02", "--threads", "1", "--cores", "1", "--size-mb", "4", "--allow-cached", "--warmup-s", "0.1", "--window-s", "30", "--runs", "1", "--tag", "C2-read-k0", "--json", "--allow-unpinned", "--max-seconds", "2"]
        rc, rec, out, err = run(MEMBW, a, env=self.env())
        self.assertEqual(rc, 124, err)
        self.assertIn("# pin: unpinned", out)
        self.assertEqual(rec["pin"]["result"], "unpinned")                   # 0.2.1: not_applicable (no run had finished)
        self.assertEqual(rec["conditions"]["pin"], "unpinned")
        self.assertIn("unpinned", rec["labels"])
        self.assertGreater(rec["pin"]["attempts"], 1)
        cpu_set_rule(self, rec, [1])
        schema_check(self, rec)

    def test_a2_a_pinned_run_that_times_out_is_not_called_unpinned(self):
        a = ["--kernel", "K02", "--threads", "1", "--cores", "0", "--size-mb", "4", "--allow-cached", "--warmup-s", "0.1", "--window-s", "30", "--runs", "1", "--tag", "C2-read-k0", "--json", "--allow-unpinned", "--max-seconds", "1.5"]
        rc, rec, out, err = run(MEMBW, a, env=self.env())
        self.assertEqual(rc, 124, err)
        self.assertNotIn("unpinned", rec["labels"])
        self.assertEqual(rec["pin"]["result"], "not_applicable")
        cpu_set_rule(self, rec, [0])

    def test_a3_one_rule_for_cpu_set_in_every_record(self):
        base = ["--kernel", "K02", "--threads", "1", "--cores", "1", "--size-mb", "4", "--allow-cached", "--warmup-s", "0.1", "--window-s", "0.2", "--runs", "1", "--tag", "C2-read-k0", "--json"]
        # ok row, pinned CPU: the CPU is listed
        rc, rec, out, err = run(MEMBW, base)
        self.assertEqual((rc, rec["pin"]["result"]), (0, "ok"))
        cpu_set_rule(self, rec, [1])
        # ok row, unpinned: no CPU
        rc, rec, out, err = run(MEMBW, base + ["--allow-unpinned"], env=self.env())
        self.assertEqual((rc, rec["status"], rec["pin"]["result"]), (0, "ok", "unpinned"))
        cpu_set_rule(self, rec, [])
        schema_check(self, rec)
        # exit 87: no value, the CPUs asked for
        rc, rec, out, err = run(MEMBW, base, env=self.env())
        self.assertEqual((rc, rec["pin"]["result"]), (87, "failed"))
        cpu_set_rule(self, rec, [1])
        # exit 97 and 88: refusals name the CPUs asked for
        rc, rec, out, err = run(MEMBW, base, env={"P1_CPUSET_PATH": cpuset_fg()})
        self.assertEqual(rc, 97)
        cpu_set_rule(self, rec, [1])
        rc, rec, out, err = run(MEMBW, [("256" if a == "4" else a) for a in base], env={"P1_MEMINFO_PATH": meminfo(100, 80)})
        self.assertEqual(rc, 88)
        cpu_set_rule(self, rec, [1])
        # exit 124 after an unpinned run: the CPUs asked for
        rc, rec, out, err = run(MEMBW, base[:base.index("--window-s") + 1] + ["30"] + base[base.index("--window-s") + 2:] + ["--allow-unpinned", "--max-seconds", "2"], env=self.env())
        self.assertEqual((rc, rec["pin"]["result"]), (124, "unpinned"))
        cpu_set_rule(self, rec, [1])
        # pacer: ok row unpinned lists none; its timeout lists the CPU asked for
        pa = ["--pace-idle", "--cores", "1", "--duration-s", "0.2", "--tag", "PC-pacer-idle-off", "--json", "--allow-unpinned"]
        rc, rec, out, err = run(MEMBW, pa, env=self.env())
        self.assertEqual((rc, rec["pin"]["result"]), (0, "unpinned"))
        cpu_set_rule(self, rec, [])
        pa[pa.index("--duration-s") + 1] = "30"
        rc, rec, out, err = run(MEMBW, pa + ["--max-seconds", "2"], env=self.env())
        self.assertEqual((rc, rec["pin"]["result"]), (124, "unpinned"))
        self.assertIn("unpinned", rec["labels"])
        cpu_set_rule(self, rec, [1])
        schema_check(self, rec)

    def test_a3_documents_state_the_rule(self):
        eng, ex = rd(os.path.join(ROOT, "ENGINE.md")), rd(os.path.join(ROOT, "EXIT-CODES.md"))
        self.assertIn("One rule for `cpu_set` and `pin`", eng)
        self.assertIn("One rule for `cpu_set` and `pin`", ex)
        self.assertNotIn("`cpu_set` is then `[]`", eng)                      # the 0.2.1 sentence, no longer true for the 124 record
        for frag in ("an `ok` row whose `pin.result` is `unpinned`", "Every record without a value", "`conditions.cpu_set`"):
            self.assertIn(frag, eng)
        self.assertIn("124 record", ex)


class TestH_A4_ChunkSetupAndTeardown(unittest.TestCase):
    TOOL = os.path.join(ROOT, "tools", "kernel_identity.py")
    SHIPPED = os.path.join(ROOT, "out", "arm64-v8a", "libpart1_membw.so")

    def setUp(self):
        if not (KIT_DIR and os.path.exists(KIT_MEMBW) and os.path.exists(KIT_BIN_FOR_TOOL) and NDK and os.path.exists(self.SHIPPED)):
            self.skipTest(KIT_SKIP + " (and the NDK and out/arm64-v8a/libpart1_membw.so must be present)")
        self.bin_dir = os.path.join(NDK, "toolchains/llvm/prebuilt/linux-x86_64/bin")

    def tool(self, shipped, tag):
        sc = tempfile.mkdtemp(prefix="p1ki-", dir=TMP)
        r = subprocess.run([sys.executable, "-I", "-B", self.TOOL, os.path.join(sc, tag), "--shipped", shipped], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        return r.returncode, r.stdout

    def patched_copy(self, name, edits):
        """Copy of the shipped object code with 32-bit instruction words replaced. edits: list of (regex of the instruction text, occurrence selector, new word hex, expected old word hex)."""
        dst = uniq(name)
        shutil.copy(self.SHIPPED, dst)
        dis = subprocess.run([os.path.join(self.bin_dir, "llvm-objdump"), "-d", dst], stdout=subprocess.PIPE, text=True, check=True).stdout.splitlines()
        loads = []
        for ln in subprocess.run([os.path.join(self.bin_dir, "llvm-readelf"), "-lW", dst], stdout=subprocess.PIPE, text=True, check=True).stdout.splitlines():
            f = ln.split()
            if f and f[0] == "LOAD":
                loads.append((int(f[1], 16), int(f[2], 16), int(f[4], 16)))      # offset, vaddr, filesz
        ins = []
        for ln in dis:
            m = re.match(r"^\s+([0-9a-f]+):\s+([0-9a-f]{8})\s+(.*)$", ln)
            if m:
                ins.append((int(m.group(1), 16), m.group(2), re.sub(r"\s+", " ", m.group(3)).strip()))
        data = bytearray(open(dst, "rb").read())
        for rx, sel, new, old in edits:
            hits = [i for i, x in enumerate(ins) if re.fullmatch(rx, x[2])]
            self.assertTrue(hits, rx)
            i = sel(hits, ins)
            va, word, _ = ins[i]
            self.assertEqual(word, old, "unexpected instruction word at %x (%s)" % (va, ins[i][2]))
            off = next(o + (va - v) for o, v, sz in loads if v <= va < v + sz)
            self.assertEqual(bytes(data[off:off + 4]), bytes.fromhex(old)[::-1])
            data[off:off + 4] = bytes.fromhex(new)[::-1]
        with open(dst, "wb") as f:
            f.write(data)
        return dst

    def test_the_unpatched_copy_passes_and_lists_the_chunk_differences(self):
        rc, out = self.tool(self.SHIPPED, "ctl")
        self.assertEqual(rc, 0, out[-2000:])
        self.assertEqual(len(re.findall(r"chunk \w+\s*:.* OK", out)), 9, out)
        self.assertIn("exactly the expected list", out)

    def test_patching_the_per_chunk_store_makes_the_tool_fail(self):
        p = self.patched_copy("m-nostore", [(r"str x12, \[sp, #0x[0-9a-f]+\]", lambda h, ins: h[0], "d503201f", "f9008bec")])      # the documented store of the groups counter replaced by a nop
        rc, out = self.tool(p, "nostore")
        self.assertEqual(rc, 1, out[-1500:])
        self.assertRegex(out, r"ENG-SHIPPED\s+chunk dsb\s*:.*FAIL")
        self.assertNotRegex(out, r"ENG-KITFLAGS\s+chunk dsb\s*:.*FAIL")      # only the patched binary fails, not the rebuilt engine

    def test_patching_the_chunk_count_register_makes_the_tool_fail(self):
        def before_groups_load(h, ins):
            j = next(i for i, x in enumerate(ins) if re.fullmatch(r"ldr x12, \[sp, #0x[0-9a-f]+\]", x[2]))
            return j - 1
        p = self.patched_copy("m-chunkreg", [(r"mov x8, x27", before_groups_load, "aa1c03e8", "aa1b03e8")])      # mov x8, x27 -> mov x8, x28 in the paced block
        rc, out = self.tool(p, "chunkreg")
        self.assertEqual(rc, 1, out[-1500:])
        self.assertRegex(out, r"ENG-SHIPPED\s+chunk dsb\s*:.*FAIL")

    def test_patching_the_per_chunk_load_makes_the_tool_fail(self):
        p = self.patched_copy("m-load", [(r"ldr x12, \[sp, #0x[0-9a-f]+\]", lambda h, ins: h[0], "f9408bed", "f9408bec")])      # ldr x12 -> ldr x13
        rc, out = self.tool(p, "load")
        self.assertEqual(rc, 1, out[-1500:])
        self.assertRegex(out, r"ENG-SHIPPED\s+chunk dsb\s*:.*FAIL")

    def test_patching_the_unpaced_chunk_setup_makes_the_tool_fail(self):
        p = self.patched_copy("m-unpaced", [(r"mov x8, x27", lambda h, ins: h[0], "aa1c03e8", "aa1b03e8")])      # first block in main = the unpaced loop
        rc, out = self.tool(p, "unpaced")
        self.assertEqual(rc, 1, out[-1500:])
        self.assertRegex(out, r"ENG-SHIPPED\s+chunk unpaced\s*:.*FAIL")

    def test_diff_from_kit_lists_the_chunk_differences(self):
        d = rd(os.path.join(ROOT, "DIFF-FROM-KIT.md"))
        t = rd(self.TOOL)
        for frag in ("chunk set-up and tear-down", "chunk-count register", "x28 to x27", "ldr x12, [sp, #SP]", "str x12, [sp, #SP]", "EXPECTED_CHUNK_DIFFS", "once per chunk"):
            self.assertIn(frag, d, frag)
        for frag in ('"- mov x8, x28 | + mov x8, x27"', '"- | + ldr x12, [sp, #SP]"', '"- | + str x12, [sp, #SP]"', "--shipped"):
            self.assertIn(frag, t, frag)


class TestH_A5_NoteIsNeverCutSilently(unittest.TestCase):
    def sweep(self, tokens):
        a = ["--sweep", "--sizes", ",".join(tokens), "--kernel", "K02", "--threads", "1", "--cores", "1", "--warmup-s", "0", "--window-s", "0.1", "--tag", "C1-sweep-k1", "--json", "--allow-unpinned", "--gate", "off"]
        return run(MEMBW, a, env=dict(TestPinning.ENV, LD_PRELOAD=FAKEAFF), timeout=240)

    def test_a5_points_have_priority_over_the_gate_and_pin_sentences(self):
        import concurrent.futures as cf
        t1 = ["1." + "0" * 27 + str(i) for i in range(5)]             # 5 points of 30-character sizes: 207 characters with the prefix; gate + pin sentences would make 375 > 319
        t2 = ["1." + "0" * 42 + str(i) for i in range(9)]             # 9 points of 45-character sizes: 490 characters, more than the whole note
        with cf.ThreadPoolExecutor(2) as ex:
            f1, f2 = ex.submit(self.sweep, t1), ex.submit(self.sweep, t2)
            (rc1, rec1, out1, err1), (rc2, rec2, out2, err2) = f1.result(), f2.result()
        self.assertEqual(rc1, 0, err1)
        n1 = rec1["conditions"]["note"]
        for t in t1:
            self.assertIn("%s=" % t, n1)                              # every point is there (0.2.1: the last ones were cut silently)
        self.assertIn("note_truncated", rec1["labels"])               # the older sentences gave way, and the record says so
        self.assertIn("note_truncated", rec1["conditions"]["labels"])
        self.assertNotIn("foreground gate was switched off", n1)
        self.assertLess(len(n1), 320)
        schema_check(self, rec1)
        self.assertEqual(rc2, 0, err2)
        n2 = rec2["conditions"]["note"]
        self.assertTrue(n2.startswith("sweep total_MiB=GB/s: %s=" % t2[0]), n2)   # the points that do not fit are cut, never silently
        self.assertIn("note_truncated", rec2["labels"])
        self.assertLess(len(n2), 320)
        self.assertNotIn(t2[8], n2)
        schema_check(self, rec2)

    def test_a5_a_note_that_fits_has_no_label(self):
        rc, rec, out, err = run(MEMBW, FAST + ["--gate", "off"])
        self.assertEqual(rc, 0, err)
        self.assertNotIn("note_truncated", rec["labels"])
        self.assertIn("foreground gate was switched off", rec["conditions"]["note"])

    def test_a5_helper_is_documented(self):
        e = rd(os.path.join(ROOT, "ENGINE.md"))
        self.assertIn("`note_truncated`", e)
        self.assertIn("Sweep points have priority", e)


class TestH_B1_GpuRateStability(unittest.TestCase):
    G = ["--size-mb", "64", "--warmup-dispatches", "1", "--hot-ms", "0", "--modes", "0", "--per-wg", "4096", "--tag", "C7-gpu-alone", "--gate", "on", "--json", "--dispatches", "5"]
    BYTES = 64 * 1048576

    def setUp(self):
        if GPUBW_FULL is None:
            self.skipTest("no Vulkan headers for the gpubw host build")

    def run_ns(self, ns, extra=()):
        a = list(self.G) + list(extra)
        return run(GPUBW_FULL, a, env={"FAKE_VK_NS_LIST": ",".join(str(n) for n in ns)})

    def rates(self, ns):
        return ", ".join("%.2f" % (self.BYTES / n) for n in ns)

    def test_b1_every_dispatch_rate_is_in_the_note_in_dispatch_order(self):
        ns = [1000000, 1100000, 1240000, 1000000, 1200000]
        rc, rec, out, err = self.run_ns(ns)
        self.assertEqual(rc, 0, err)
        self.assertEqual(rec["conditions"]["note"], "dispatch GB/s: " + self.rates(ns))
        schema_check(self, rec)

    def test_b1_below_the_threshold_no_label(self):
        rc, rec, out, err = self.run_ns([1000000, 1100000, 1240000, 1000000, 1200000])      # largest / smallest rate = 1.24
        self.assertNotIn("gpu_rate_unstable", rec["labels"])
        self.assertNotIn("gpu_rate_unstable", rec["conditions"]["labels"])

    def test_b1_at_the_threshold_the_label(self):
        rc, rec, out, err = self.run_ns([1000000, 1250000, 1100000, 1000000, 1200000])      # exactly 1.25
        self.assertEqual(rc, 0, err)
        self.assertIn("gpu_rate_unstable", rec["labels"])
        self.assertIn("gpu_rate_unstable", rec["conditions"]["labels"])
        schema_check(self, rec)

    def test_b1_above_the_threshold_the_label(self):
        rc, rec, out, err = self.run_ns([1000000, 2000000, 1000000, 1000000, 1000000])
        self.assertIn("gpu_rate_unstable", rec["labels"])

    def test_b1_the_row_value_stays_the_kits_mom(self):
        ns = [1000000, 2000000, 1100000, 1000000, 1200000]                                   # sorted times 1.0 1.0 1.1 1.2 2.0 ms: mom = the median, 1.1 ms
        rc, rec, out, err = self.run_ns(ns)
        self.assertAlmostEqual(rec["gbs_median"], self.BYTES / 1100000, places=3)
        self.assertEqual(rec["repeats"], 1)
        self.assertEqual(rec["passes"], 5)

    def test_b1_one_dispatch_has_a_list_and_no_label(self):
        rc, rec, out, err = self.run_ns([1000000], ["--dispatches", "1"])
        self.assertEqual(rec["conditions"]["note"], "dispatch GB/s: %.2f" % (self.BYTES / 1000000))
        self.assertNotIn("gpu_rate_unstable", rec["labels"])

    def test_b1_the_list_has_priority_over_the_gate_sentence(self):
        a = ["--size-mb", "256", "--warmup-dispatches", "1", "--hot-ms", "0", "--modes", "0", "--per-wg", "4096", "--tag", "C7-gpu-alone", "--gate", "off", "--json", "--dispatches", "32"]
        rc, rec, out, err = run(GPUBW_FULL, a, env={"FAKE_VK_NS": "1000000"}, timeout=180)
        self.assertEqual(rc, 0, err)
        note = rec["conditions"]["note"]
        self.assertEqual(note.count("268.44"), 32, note)                                     # all 32 dispatches
        self.assertIn("note_truncated", rec["labels"])                                       # the gate sentence gave way
        self.assertNotIn("foreground gate was switched off", note)

    def test_b1_without_json_no_object_and_no_list(self):
        a = [x for x in self.G if x != "--json"]
        rc, rec, out, err = run(GPUBW_FULL, a, env={"FAKE_VK_NS_LIST": "1000000,3000000,1000000,1000000,1000000"})
        self.assertEqual(rc, 0, err)
        self.assertIsNone(rec)

    def test_b1_documented(self):
        e = rd(os.path.join(ROOT, "ENGINE.md"))
        self.assertIn("dispatch GB/s: a, b, c", e)
        self.assertIn("`gpu_rate_unstable`", e)
        self.assertIn("1.25", e)


class TestH_C1_StaticProbe(unittest.TestCase):
    def sysfs(self, possible="0-7\n", present="0-7\n", online="0-3,5-7\n", name="sysfs-static"):
        d = uniq(name)
        shutil.rmtree(d, ignore_errors=True)
        os.makedirs(d)
        for n, v in (("possible", possible), ("present", present), ("online", online)):
            if v is not None:
                with open(os.path.join(d, n), "w") as f:
                    f.write(v)
        return d

    def S(self, args=("--static-probe", "--json"), env=None, exe=None):
        e = {"P1_SYSFS_CPU_DIR": self.sysfs()}
        e.update(env or {})
        return run(exe or MEMBW, list(args), env=e)

    def test_ok_object_validates_and_has_every_field(self):
        import time
        t0 = time.time()
        rc, rec, out, err = self.S()
        self.assertLess(time.time() - t0, 1.0)
        self.assertEqual(rc, 0, err)
        probe_schema_check(self, rec, STATIC_SCHEMA)
        self.assertEqual(rec["format"], "part1-staticprobe-v1")
        self.assertEqual(rec["engine_version"], "part1-engine/host-test")
        self.assertEqual((rec["status"], rec["exit_code"]), ("ok", 0))
        self.assertEqual(rec["page_size"], os.sysconf("SC_PAGE_SIZE"))
        self.assertRegex(rec["hwcap"], r"^0x[0-9a-f]+$")
        self.assertRegex(rec["hwcap2"], r"^0x[0-9a-f]+$")
        self.assertEqual(sorted(rec["features"]), sorted(["asimd", "asimdhp", "asimddp", "i8mm", "bf16", "sve", "sve2", "sme"]))
        self.assertEqual((rec["cpus_possible"], rec["cpus_present"], rec["cpus_online"]), ("0-7", "0-7", "0-3,5-7"))
        self.assertEqual(rec["foreground_cpuset"], "/top-app")
        self.assertEqual(len([ln for ln in out.splitlines() if ln.startswith("{")]), 1)
        for k in ("tag", "kind", "gbs_median", "cpu_set", "pin"):
            self.assertNotIn(k, rec)                                       # not a row

    def test_hwcap_decoding(self):
        rc, rec, out, err = self.S(env={"P1_FAKE_HWCAP": "500402", "P1_FAKE_HWCAP2": "806002"})
        self.assertEqual((rec["hwcap"], rec["hwcap2"]), ("0x500402", "0x806002"))
        self.assertEqual(rec["features"], {"asimd": True, "asimdhp": True, "asimddp": True, "i8mm": True, "bf16": True, "sve": True, "sve2": True, "sme": True})
        for hw, hw2, want in (("2", "0", {"asimd"}), ("400", "0", {"asimdhp"}), ("100000", "0", {"asimddp"}), ("400000", "0", {"sve"}), ("0", "2000", {"i8mm"}), ("0", "4000", {"bf16"}),
                              ("0", "2", {"sve2"}), ("0", "800000", {"sme"}), ("0", "0", set()), ("ffffffff", "0", {"asimd", "asimdhp", "asimddp", "sve"})):
            rc, rec, out, err = self.S(env={"P1_FAKE_HWCAP": hw, "P1_FAKE_HWCAP2": hw2})
            self.assertEqual({k for k, v in rec["features"].items() if v}, want, (hw, hw2))
            probe_schema_check(self, rec, STATIC_SCHEMA)

    def test_properties_are_exactly_the_fixed_list_and_nothing_else_is_read(self):
        env = {"P1_PROP_RO_HARDWARE": "hwname", "P1_PROP_RO_BOARD_PLATFORM": "plat", "P1_PROP_RO_BUILD_VERSION_SDK": "34", "P1_PROP_RO_SOC_MODEL": "SoC \"1\"",
               "P1_PROP_RO_SERIALNO": "SERIAL-SECRET-1", "P1_PROP_RO_BUILD_FINGERPRINT": "FINGERPRINT-SECRET-2", "P1_PROP_NET_HOSTNAME": "HOST-SECRET-3", "P1_PROP_RO_PRODUCT_NAME": "NAME-SECRET-4"}
        rc, rec, out, err = self.S(env=env)
        self.assertEqual(list(rec["properties"]), PROP_NAMES)                  # exactly this list, in this order
        self.assertEqual(rec["properties"]["ro.hardware"], "hwname")
        self.assertEqual(rec["properties"]["ro.soc.model"], 'SoC "1"')
        self.assertEqual(rec["properties"]["ro.product.board"], "")            # unset: empty string
        for secret in ("SECRET-1", "SECRET-2", "SECRET-3", "SECRET-4"):
            self.assertNotIn(secret, out + err)
        probe_schema_check(self, rec, STATIC_SCHEMA)

    def test_the_list_is_compiled_in_and_the_binaries_name_no_other_property(self):
        src = rd(os.path.join(SRC, "src", "membw.c")) + rd(os.path.join(SRC, "src", "part1_common.h"))
        lits = set(re.findall(r'"((?:ro|persist|sys|vendor|net|gsm|dhcp|init|service)\.[A-Za-z0-9._]+)"', src))
        self.assertEqual(sorted(lits), sorted(PROP_NAMES))
        binary = os.path.join(ROOT, "out", "arm64-v8a", "libpart1_membw.so")
        if os.path.exists(binary):
            found = set(m.decode() for m in re.findall(rb"(?:ro|persist|sys|vendor|net|gsm|dhcp|init|service)\.[A-Za-z0-9._]{3,}", open(binary, "rb").read()))
            self.assertEqual(sorted(found), sorted(PROP_NAMES))
        for extra in (["--prop", "ro.serialno"], ["--property", "ro.build.fingerprint"], ["--props", "ro.serialno,ro.x"]):
            rc, rec, out, err = self.S(["--static-probe", "--json"] + extra)
            self.assertEqual(rc, 2, extra)                                      # not extendable from the command line
            self.assertNotIn("serialno", json.dumps(rec.get("properties", {})))

    def test_the_gate_does_not_apply_and_the_cpuset_seen_is_recorded(self):
        rc, rec, out, err = self.S(env={"P1_CPUSET_PATH": cpuset_fg()})
        self.assertEqual(rc, 0, err)
        self.assertEqual(rec["foreground_cpuset"], "/foreground")
        probe_schema_check(self, rec, STATIC_SCHEMA)
        rc, rec, out, err = self.S(env={"P1_CPUSET_PATH": os.path.join(TMP, "no-such-cpuset")})
        self.assertEqual(rc, 0, err)
        self.assertIsNone(rec["foreground_cpuset"])
        probe_schema_check(self, rec, STATIC_SCHEMA)

    def test_no_memory_guard_and_no_pinning(self):
        rc, rec, out, err = self.S(env={"P1_MEMINFO_PATH": os.path.join(TMP, "no-such-meminfo"), "LD_PRELOAD": FAKEAFF, "FAKE_BAD_CPU": "0"})
        self.assertEqual(rc, 0, err)                                            # an unreadable meminfo and a CPU that cannot be pinned do not matter
        self.assertNotIn("# pin", out)

    def test_cpu_text_is_as_read_or_null(self):
        rc, rec, out, err = self.S(env={"P1_SYSFS_CPU_DIR": self.sysfs(possible="0-11\n", present=None, online="0-5,7\n", name="sysfs-static2")})
        self.assertEqual((rec["cpus_possible"], rec["cpus_present"], rec["cpus_online"]), ("0-11", None, "0-5,7"))
        probe_schema_check(self, rec, STATIC_SCHEMA)

    def test_without_json_a_success_prints_no_object(self):
        rc, rec, out, err = self.S(["--static-probe"])
        self.assertEqual(rc, 0)
        self.assertIsNone(rec)
        self.assertIn("# static-probe page_size=", out)

    def test_usage_errors_are_one_object_with_exit_2(self):
        for args in (["--static-probe", "--json", "--kernel", "K02"], ["--static-probe", "--cores", "0", "--json"], ["--static-probe", "--pin-probe", "--json"], ["--static-probe", "--json", "--max-seconds", "x"],
                     ["--static-probe", "--json", "--max-seconds"], ["--static-probe", "--json", "--gate", "off"]):
            rc, rec, out, err = self.S(args)
            self.assertEqual(rc, 2, args)
            probe_schema_check(self, rec, STATIC_SCHEMA)
            self.assertEqual((rec["status"], rec["exit_code"]), ("failed", 2))
            self.assertIn("reason", rec)
            self.assertNotIn("properties", rec)
            self.assertEqual(len([ln for ln in out.splitlines() if ln.startswith("{")]), 1)

    def test_max_seconds_is_accepted(self):
        rc, rec, out, err = self.S(["--static-probe", "--json", "--max-seconds", "5"])
        self.assertEqual(rc, 0, err)

    def test_schema_rejects_extra_and_wrong_fields(self):
        import jsonschema
        rc, rec, out, err = self.S()
        v = jsonschema.Draft202012Validator(json.loads(rd(STATIC_SCHEMA)))
        for mut in (lambda r: r.update(extra=1), lambda r: r["properties"].update({"ro.serialno": "x"}), lambda r: r["properties"].pop("ro.hardware"), lambda r: r["features"].pop("sme"),
                    lambda r: r.update(hwcap="12"), lambda r: r.pop("page_size"), lambda r: r.update(format="part1-pinprobe-v1"), lambda r: r["features"].update(asimd=1), lambda r: r.update(engine_version="kit v4d-5w")):
            bad = json.loads(json.dumps(rec))
            mut(bad)
            self.assertNotEqual(list(v.iter_errors(bad)), [])


class TestH_C2_PinProbeMidr(unittest.TestCase):
    CPUINFO = ("processor\t: 0\nBogoMIPS\t: 50.00\nCPU implementer\t: 0x41\nCPU architecture: 8\nCPU variant\t: 0x0\nCPU part\t: 0xd0b\nCPU revision\t: 1\n\n"      # cpu0 would be 0x410fd0b1 here; sysfs must win
               "processor\t: 1\nBogoMIPS\t: 50.00\nCPU implementer\t: 0x41\nCPU architecture: 8\nCPU variant\t: 0x1\nCPU part\t: 0xd44\nCPU revision\t: 0\n\n"
               "processor\t: 3\nBogoMIPS\t: 50.00\nCPU implementer\t: 0x41\nCPU architecture: 8\nCPU variant\t: 0x2\nCPU part\t: 0xd05\nCPU revision\t: 0\n\n")

    def setUp(self):
        self.sysd = uniq("sysfs-midr")
        shutil.rmtree(self.sysd, ignore_errors=True)
        d = os.path.join(self.sysd, "cpu0", "regs", "identification")
        os.makedirs(d)
        with open(os.path.join(d, "midr_el1"), "w") as f:
            f.write("0x00000000412fd050\n")
        self.cpuinfo = write("cpuinfo-fake", self.CPUINFO)

    def P(self, cores, extra_env=None, args=()):
        env = {"P1_SYSFS_CPU_DIR": self.sysd, "P1_CPUINFO_PATH": self.cpuinfo}
        env.update(extra_env or {})
        return run(MEMBW, ["--pin-probe", "--json", "--cores", cores, "--spin-ms", "60"] + list(args), env=env)

    def test_sysfs_then_cpuinfo_then_null(self):
        rc, rec, out, err = self.P("0,1,2,3")
        self.assertEqual(rc, 0, err)
        probe_check(self, rec)
        c = {x["index"]: x for x in rec["cpus"]}
        self.assertEqual((c[0]["midr"], c[0]["midr_source"]), ("0x412fd050", "sysfs"))      # sysfs wins over the cpuinfo entry of the same CPU
        self.assertEqual((c[1]["midr"], c[1]["midr_source"]), ("0x411fd440", "cpuinfo"))    # no sysfs file: assembled from cpuinfo (0x41, variant 1, architecture 0xf, part 0xd44, revision 0)
        self.assertEqual((c[2]["midr"], c[2]["midr_source"]), (None, None))                 # neither
        self.assertEqual((c[3]["midr"], c[3]["midr_source"]), ("0x412fd050", "cpuinfo"))    # the same value as cpu0's sysfs one: Cortex-A55 r2p0
        for x in rec["cpus"]:
            self.assertIn("midr", x)
            self.assertIn("midr_source", x)

    def test_the_midr_is_read_while_pinned_on_that_cpu(self):
        log = uniq("fakeopen.log")
        if os.path.exists(log):
            os.remove(log)
        rc, rec, out, err = self.P("0,1,2", {"LD_PRELOAD": FAKEOPEN, "FAKE_OPEN_LOG": log})
        self.assertEqual(rc, 0, err)
        lines = [ln.split() for ln in rd(log).splitlines()]
        want = [("cpu0/regs/identification/midr_el1", 0), ("cpu1/regs/identification/midr_el1", 1), ("cpuinfo-fake", 1), ("cpu2/regs/identification/midr_el1", 2), ("cpuinfo-fake", 2)]
        self.assertEqual(len(lines), len(want), lines)
        for (path, cpu), ln in zip(want, lines):
            self.assertTrue(ln[0].endswith(path), (path, ln))
            self.assertEqual(ln[1], "cpu=%d" % cpu, ln)               # the CPU the thread was running on when the file was opened
            self.assertEqual(ln[2], "affinity=1", ln)                  # pinned to exactly that CPU

    def test_a_cpu_that_did_not_pin_keeps_its_identity_read_unpinned(self):      # P1-03I item 4 (PLAN-034); 0.2.2 gave null and null here
        log = uniq("fakeopen2.log")
        if os.path.exists(log):
            os.remove(log)
        env = dict(TestPinning.ENV, LD_PRELOAD=FAKEOPEN + ":" + FAKEAFF, FAKE_OPEN_LOG=log)
        rc, rec, out, err = self.P("0,1,3", env)
        self.assertEqual(rc, 0, err)
        c = {x["index"]: x for x in rec["cpus"]}
        self.assertEqual(c[1]["result"], "failed")
        self.assertEqual((c[1]["midr"], c[1]["midr_source"]), ("0x411fd440", "cpuinfo_unpinned"))    # no sysfs file for cpu1: its cpuinfo entry, read unpinned
        self.assertEqual((c[0]["midr"], c[0]["midr_source"]), ("0x412fd050", "sysfs"))               # pinned CPUs are unchanged
        self.assertEqual((c[3]["midr"], c[3]["midr_source"]), ("0x412fd050", "cpuinfo"))
        probe_check(self, rec)
        for ln in rd(log).splitlines():                                                              # the sysfs file of the failed CPU is tried with the original (wide) affinity
            f = ln.split()
            if "cpu1/regs" in f[0]:
                self.assertGreater(int(f[2].split("=")[1]), 1, ln)
        self.assertTrue([ln for ln in rd(log).splitlines() if "cpu1/regs" in ln], "the sysfs file of the failed CPU was tried")

    def test_a_failed_cpu_uses_its_sysfs_file_unpinned_when_there_is_one(self):
        d = os.path.join(self.sysd, "cpu1", "regs", "identification")
        os.makedirs(d)
        with open(os.path.join(d, "midr_el1"), "w") as f:
            f.write("0x00000000413fd0c1\n")
        env = dict(TestPinning.ENV, LD_PRELOAD=FAKEAFF)
        rc, rec, out, err = self.P("0,1", env)
        c = {x["index"]: x for x in rec["cpus"]}
        self.assertEqual((c[1]["result"], c[1]["midr"], c[1]["midr_source"]), ("failed", "0x413fd0c1", "sysfs_unpinned"))
        probe_check(self, rec)

    def test_a_failed_cpu_with_no_source_has_null(self):
        env = dict(TestPinning.ENV, LD_PRELOAD=FAKEAFF)
        rc, rec, out, err = self.P("0,2", dict(env, FAKE_BAD_CPU="2"))                               # cpu2: no sysfs file, no cpuinfo entry
        c = {x["index"]: x for x in rec["cpus"]}
        self.assertEqual((c[2]["result"], c[2]["midr"], c[2]["midr_source"]), ("failed", None, None))
        probe_check(self, rec)

    def test_schema_has_the_unpinned_sources_and_documents_them(self):
        import jsonschema
        v = jsonschema.Draft202012Validator(json.loads(rd(PINPROBE_SCHEMA)))
        rc, rec, out, err = self.P("0,1")
        for src in ("sysfs_unpinned", "cpuinfo_unpinned"):
            ok = json.loads(json.dumps(rec)); ok["cpus"][0].update(midr="0x411fd440", midr_source=src)
            self.assertEqual(list(v.iter_errors(ok)), [])
        self.assertIn("`sysfs_unpinned`", rd(os.path.join(ROOT, "ENGINE.md")))
        self.assertIn("`cpuinfo_unpinned`", rd(os.path.join(ROOT, "ENGINE.md")))

    def test_no_sources_at_all_gives_null_and_still_validates(self):
        rc, rec, out, err = self.P("0,1", {"P1_SYSFS_CPU_DIR": os.path.join(TMP, "none"), "P1_CPUINFO_PATH": os.path.join(TMP, "none-cpuinfo")})
        self.assertEqual(rc, 0, err)
        for x in rec["cpus"]:
            self.assertEqual((x["midr"], x["midr_source"]), (None, None))
        probe_check(self, rec)

    def test_schema_accepts_old_objects_and_rejects_bad_midr(self):
        import jsonschema
        rc, rec, out, err = self.P("0,1")
        old = json.loads(json.dumps(rec))
        for x in old["cpus"]:
            x.pop("midr")
            x.pop("midr_source")
        probe_check(self, old)                                          # an object of engine 0.2.1 and earlier still validates
        v = jsonschema.Draft202012Validator(json.loads(rd(PINPROBE_SCHEMA)))
        for mut in (lambda r: r["cpus"][0].update(midr="0x12"), lambda r: r["cpus"][0].update(midr_source="proc"), lambda r: r["cpus"][0].update(midr=None, midr_source="sysfs"),
                    lambda r: r["cpus"][0].update(midr="0x412fd050", midr_source=None), lambda r: r["cpus"][0].update(midr=5)):
            bad = json.loads(json.dumps(rec))
            mut(bad)
            self.assertNotEqual(list(v.iter_errors(bad)), [], bad["cpus"][0])


class TestH_C3_GpuProbe(unittest.TestCase):
    def setUp(self):
        if GPUBW_FULL is None:
            self.skipTest("no Vulkan headers for the gpubw host build")

    def G(self, env=None, args=("--probe", "--json"), exe=None):
        e = {"FAKE_VK_FORBID_ALLOC": "1"}                               # the probe must not create a device, a buffer or memory
        e.update(env or {})
        return run(exe or GPUBW_FULL, list(args), env=e)

    def test_default_object_validates(self):
        import time
        t0 = time.time()
        rc, rec, out, err = self.G()
        self.assertLess(time.time() - t0, 1.0)
        self.assertEqual(rc, 0, err)
        probe_schema_check(self, rec, GPUPROBE_SCHEMA)
        self.assertEqual(rec["format"], "part1-gpuprobe-v1")
        self.assertEqual(rec["engine_version"], "part1-engine/host-test")
        v = rec["vulkan"]
        self.assertEqual((v["available"], v["device_name"], v["device_type"], v["api_version"], v["driver_version"], v["compute_queue"]), (True, "fake", "INTEGRATED_GPU", "1.1.0", 42, True))
        self.assertEqual((v["vendor_id"], v["device_id"]), (0x13b5, 0x92000000))
        self.assertEqual(v["memory_heaps_mib"], [16384, 512])
        self.assertEqual((v["shader_float16"], v["shader_int8"], v["integer_dot_product"]), (None, None, None))     # a 1.1 device without the extensions: the queries are not available
        self.assertEqual(len([ln for ln in out.splitlines() if ln.startswith("{")]), 1)
        for k in ("tag", "kind", "gbs_median", "cpu_set", "pin"):
            self.assertNotIn(k, rec)

    def test_feature_queries(self):
        ext = "VK_KHR_shader_float16_int8,VK_KHR_shader_integer_dot_product"
        cases = (({"FAKE_VK_API": "3", "FAKE_VK_F16": "1", "FAKE_VK_DOT": "1"}, (True, False, True), "core 1.3 structs"),
                 ({"FAKE_VK_API": "3", "FAKE_VK_I8": "1"}, (False, True, False), "core 1.3 structs, other bits"),
                 ({"FAKE_VK_API": "2", "FAKE_VK_F16": "1", "FAKE_VK_I8": "1"}, (True, True, None), "1.2 device: dot product not available"),
                 ({"FAKE_VK_API": "1", "FAKE_VK_EXTS": ext, "FAKE_VK_F16": "1", "FAKE_VK_DOT": "1"}, (True, False, True), "1.1 device with the extensions"),
                 ({"FAKE_VK_API": "1", "FAKE_VK_EXTS": "VK_KHR_shader_float16_int8", "FAKE_VK_I8": "1"}, (False, True, None), "only the float16/int8 extension"),
                 ({"FAKE_VK_API": "3", "FAKE_VK_INST_VER": "1", "FAKE_VK_F16": "1", "FAKE_VK_DOT": "1"}, (None, None, None), "a 1.1 instance limits the core structs"))
        for env, want, why in cases:
            rc, rec, out, err = self.G(env)
            self.assertEqual(rc, 0, (why, err))
            v = rec["vulkan"]
            self.assertEqual((v["shader_float16"], v["shader_int8"], v["integer_dot_product"]), want, why)
            probe_schema_check(self, rec, GPUPROBE_SCHEMA)

    def test_identity_fields_and_queue(self):
        rc, rec, out, err = self.G({"FAKE_VK_NAME": "Test GPU 7", "FAKE_VK_VENDOR": "4098", "FAKE_VK_DEVICE": "77", "FAKE_VK_DRIVER": "123456", "FAKE_VK_API": "3"})
        v = rec["vulkan"]
        self.assertEqual((v["device_name"], v["vendor_id"], v["device_id"], v["driver_version"], v["api_version"]), ("Test GPU 7", 4098, 77, 123456, "1.3.128"))
        rc, rec, out, err = self.G({"FAKE_VK_NOCOMPUTE": "1"})
        self.assertEqual(rc, 0, err)
        self.assertIs(rec["vulkan"]["compute_queue"], False)
        probe_schema_check(self, rec, GPUPROBE_SCHEMA)

    def test_no_vulkan_device_is_not_an_error(self):
        rc, rec, out, err = self.G({"FAKE_VK_NODEV": "1"})
        self.assertEqual(rc, 0, err)
        self.assertEqual((rec["status"], rec["exit_code"], rec["vulkan"]["available"]), ("ok", 0, False))
        self.assertNotIn("device_name", rec["vulkan"])
        self.assertIn("reason", rec["vulkan"])
        self.assertEqual(len(rec["opencl_child"]), 4)                  # the OpenCL check still runs
        probe_schema_check(self, rec, GPUPROBE_SCHEMA)
        if GPUBW:                                                       # the plain fake (no device at all)
            rc, rec, out, err = self.G(exe=GPUBW)
            self.assertEqual(rc, 0, err)
            self.assertIs(rec["vulkan"]["available"], False)
            probe_schema_check(self, rec, GPUPROBE_SCHEMA)

    def test_opencl_child_has_the_four_names_and_dlerror_text(self):
        rc, rec, out, err = self.G()
        oc = rec["opencl_child"]
        self.assertEqual([x["name"] for x in oc], ["libOpenCL.so", "libOpenCL-pixel.so", "/vendor/lib64/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so"])
        for x in oc:
            self.assertIsInstance(x["loaded"], bool)
            if x["loaded"]:
                self.assertIsNone(x["error"])
            else:
                self.assertTrue(x["error"])
        self.assertFalse([x for x in oc if x["name"].startswith("/vendor") and x["loaded"]])    # a desk machine has no /vendor
        self.assertIn(os.path.basename(oc[2]["name"]), oc[2]["error"])                          # dlerror's own text

    def test_usage_errors(self):
        for args in (["--probe", "--json", "--size-mb", "64"], ["--probe", "--json", "--modes", "0"], ["--probe", "--json", "--tag", "x"], ["--probe", "--json", "--max-seconds", "x"]):
            rc, rec, out, err = self.G(args=args)
            self.assertEqual(rc, 2, args)
            probe_schema_check(self, rec, GPUPROBE_SCHEMA)
            self.assertEqual((rec["status"], rec["exit_code"]), ("failed", 2))
            self.assertIn("reason", rec)
            self.assertNotIn("vulkan", rec)

    def test_max_seconds_applies_when_the_driver_hangs(self):
        import time
        t0 = time.time()
        rc, rec, out, err = self.G({"FAKE_VK_SLEEP": "5"}, args=("--probe", "--json", "--max-seconds", "1"), exe=GPUBW)
        self.assertEqual(rc, 124, err)
        self.assertLess(time.time() - t0, 3)
        probe_schema_check(self, rec, GPUPROBE_SCHEMA)
        self.assertEqual((rec["status"], rec["exit_code"]), ("failed", 124))

    def test_without_json_a_success_prints_no_object(self):
        rc, rec, out, err = self.G(args=("--probe",))
        self.assertEqual(rc, 0, err)
        self.assertIsNone(rec)
        self.assertIn("# gpu-probe vulkan available=1", out)

    def test_documented(self):
        e = rd(os.path.join(ROOT, "ENGINE.md"))
        self.assertIn("`opencl_child`", e)
        self.assertRegex(e, r"result for a child process[^\n]*may differ from loading inside the app process")
        self.assertIn("part1-gpuprobe-v1", e)
        self.assertIn("part1-staticprobe-v1", e)

    def test_schema_rejects_extra_and_wrong_fields(self):
        import jsonschema
        rc, rec, out, err = self.G()
        v = jsonschema.Draft202012Validator(json.loads(rd(GPUPROBE_SCHEMA)))
        for mut in (lambda r: r.update(extra=1), lambda r: r["vulkan"].update(api_version="1.3"), lambda r: r["vulkan"].pop("driver_version"), lambda r: r["opencl_child"].pop(),
                    lambda r: r["opencl_child"][0].update(loaded=True), lambda r: r["opencl_child"][0].update(name="libX.so"), lambda r: r["vulkan"].update(shader_int8="yes"), lambda r: r.pop("opencl_child")):
            bad = json.loads(json.dumps(rec))
            mut(bad)
            self.assertNotEqual(list(v.iter_errors(bad)), [])


class TestH_Docs(unittest.TestCase):
    def test_version_and_docs_mention_0_2_2(self):
        self.assertEqual(rd(os.path.join(ROOT, "VERSION")).strip(), "0.2.4")
        for f in ("ENGINE.md", "EXIT-CODES.md", "DIFF-FROM-KIT.md"):
            self.assertIn("0.2.4", rd(os.path.join(ROOT, f)), f)

    def test_exit_codes_document_the_probes(self):
        e = rd(os.path.join(ROOT, "EXIT-CODES.md"))
        self.assertIn("--static-probe", e)
        self.assertIn("--probe", e)

    def test_documented_static_probe_fields(self):
        e = rd(os.path.join(ROOT, "ENGINE.md"))
        for frag in ("page_size", "hwcap2", "features", "cpus_possible", "foreground_cpuset", "`midr_source`", "The foreground gate does not apply", "exactly this fixed list"):
            self.assertIn(frag, e, frag)



class TestVersion(unittest.TestCase):
    def test_version_file_is_0_2_4(self):
        self.assertEqual(rd(os.path.join(ROOT, "VERSION")).strip(), "0.2.4")


class TestC_DocsStatements(unittest.TestCase):
    """C1-C5: statements in ENGINE.md, EXIT-CODES.md and DIFF-FROM-KIT.md (documents, so the tests read them)."""

    def setUp(self):
        self.eng = rd(os.path.join(ROOT, "ENGINE.md"))
        self.exitc = rd(os.path.join(ROOT, "EXIT-CODES.md"))
        self.diff = rd(os.path.join(ROOT, "DIFF-FROM-KIT.md"))

    def rows(self):
        return [ln for ln in self.eng.split("\n") if re.match(r"^\| `(C\d|PC-pacer)", ln)]

    def test_c1_every_t1_t2_command_has_fit_buffer(self):
        rows = self.rows()
        self.assertEqual(len(rows), 11)
        for ln in rows:
            cmd = re.split(r"(?<!\\)\|", ln)[2]
            if "+ tail" in cmd:
                self.assertNotIn("--fit-buffer", cmd, ln[:80])      # P1-03G (review F11): carried by the tail, not repeated
            else:
                self.assertIn("--fit-buffer", cmd, ln[:80])
        self.assertIn("`smaller buffer` row is labelled and is not comparable", self.eng)

    def test_c2_sweep_600(self):
        ln = [r for r in self.rows() if r.startswith("| `C1-sweep")][0]
        self.assertIn("--max-seconds 600", ln)
        self.assertNotIn("--max-seconds 300", ln)

    def test_c3_c8_guard_and_memtype(self):
        ln = [r for r in self.rows() if r.startswith("| `C8")][0]
        self.assertIn("--mem-limit-mib L", ln)
        self.assertNotIn("minus", ln)
        self.assertRegex(self.eng, r"computed after the (CPU )?stream has started")
        self.assertRegex(self.eng, r"`--memtype` without `--stage` is a research option")

    def test_c4_helper_processes_are_not_rows(self):
        self.assertIn("Helper processes are not rows", self.eng)
        self.assertIn("free text", self.eng)
        self.assertIn("PC-pacer-<id>-on", self.eng)

    def test_c4_exit_code_statements_corrected(self):
        self.assertNotIn('"no usable memory type" now exits 1', self.diff)
        self.assertIn("Three situations now exit 3", self.diff)
        self.assertIn("no usable Vulkan memory type", self.diff)
        self.assertRegex(self.diff, r"(CPU list|/proc/cpuinfo)[^\n]*exit[^\n]*1[^\n]*(instead of|not)[^\n]*2")

    def test_c5_confirmed_answers(self):
        self.assertRegex(self.eng, r"defaults of the programs are the kit binary.s")
        self.assertIn("-march=armv8-a", self.eng + self.diff)
        self.assertRegex(self.eng, r"T3 is not an engine mode")

    def test_g_f01_documents_state_the_exact_rename_map(self):
        d = rd(os.path.join(ROOT, "DIFF-FROM-KIT.md"))
        for frag in ("x27 to x19", "x25 to x12", "caller-saved", "once per chunk, outside the paced inner loop", "unpaced loop", "identical as written"):
            self.assertIn(frag, d)
        self.assertNotIn("for one value", d)
        t = rd(os.path.join(ROOT, "tools", "kernel_identity.py"))
        self.assertIn('EXPECTED_RENAMES = {"unpaced": {}, "spin": {"x27": "x19"}, "dsb": {"x27": "x19", "x25": "x12"}}', t)
        self.assertIn("x25->x12", t)
        self.assertNotIn("def canon", t)                              # the any-rename check is gone

    @unittest.skipUnless(KIT_MEMBW and os.path.exists(KIT_MEMBW) and os.path.exists(KIT_BIN_FOR_TOOL) and HAVE_OUT, KIT_SKIP + " (and " + OUT_SKIP + ")")
    def test_g_f01_tool_is_strict(self):
        tool = os.path.join(ROOT, "tools", "kernel_identity.py")
        src = rd(tool)
        sc = tempfile.mkdtemp(prefix="p1ki-", dir=TMP)
        ok = subprocess.run([sys.executable, "-I", "-B", tool, os.path.join(sc, "a")], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        self.assertEqual(ok.returncode, 0, ok.stdout[-1500:])
        self.assertIn("exactly the expected rename map", ok.stdout)
        for bad_map in ('"dsb": {"x27": "x19"}', '"dsb": {"x27": "x19", "x25": "x13"}', '"spin": {}'):
            variants = {'"dsb": {"x27": "x19", "x25": "x12"}': bad_map} if "dsb" in bad_map else {'"spin": {"x27": "x19"}': bad_map}
            mod = src
            for a, b in variants.items():
                self.assertIn(a, mod)
                mod = mod.replace(a, b)
            p = os.path.join(sc, "ki_bad.py")
            with open(p, "w") as f:
                f.write(mod.replace("ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))", "ROOT = %r" % ROOT))
            r = subprocess.run([sys.executable, "-I", "-B", p, os.path.join(sc, "b")], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            self.assertEqual(r.returncode, 1, bad_map + r.stdout[-800:])
            self.assertIn("FAIL", r.stdout)

    def test_g_docs_exit_codes_engine_md(self):
        e = rd(os.path.join(ROOT, "EXIT-CODES.md"))
        self.assertRegex(e, r"0, 1, 2, 97 and 124")
        self.assertNotRegex(e, r"0, 2, 97 and 124")
        eng = rd(os.path.join(ROOT, "ENGINE.md"))
        ln = [r for r in eng.split("\n") if r.startswith("| `PC-pacer-<id>-on`")][0]
        fallback = ln[ln.index("Without Vulkan"):]
        self.assertIn("--max-seconds", fallback)
        for r in eng.split("\n"):
            if r.startswith("| `C") or r.startswith("| `PC-pacer"):
                for cmd in re.findall(r"`([^`]*)`", r):
                    self.assertLessEqual(cmd.count("--fit-buffer"), 1, cmd)

    @unittest.skipUnless(HAVE_RAW and HAVE_OUT, RAW_SKIP + " / " + OUT_SKIP)
    def test_g_raw_build_logs_are_the_current_build(self):
        for n in ("membw", "gpubw"):
            a = rd(os.path.join(ROOT, "raw", "build-%s.log" % n))
            b = rd(os.path.join(ROOT, "out", "arm64-v8a", "logs", "build-%s.log" % n))
            self.assertEqual(a, b)
            self.assertNotIn("0.1.0", a)

    def test_helper_tags_are_accepted_by_the_engine(self):
        for tag in ("C8-cpu", "PC-pacer-x1spin-pc", "free text tag!"):
            rc, rec, out, err = run(MEMBW, [a if a != "C2-read-k0" else tag for a in FAST])
            self.assertEqual(rc, 0, (tag, err))
            self.assertEqual(rec["tag"], tag)

    def test_pinprobe_mapping_documented(self):
        self.assertIn("part1-pinprobe-v1", self.eng)
        self.assertIn("cpus[].pin", self.eng)
        self.assertIn("time_to_pin_s", self.eng)
        self.assertIn("part1-inputs-v1.md", self.eng)

    def test_exit_codes_doc_has_new_statements(self):
        self.assertIn("--pin-probe", self.exitc)
        self.assertIn("0.2.2", self.exitc)
        self.assertNotIn("(engine 0.1.0)", self.exitc)

    def test_engine_md_no_longer_lists_engine_only_fields(self):
        self.assertNotIn("Two **engine-only** top-level fields", self.eng)
        self.assertIn("minflt_timed", self.eng)




# ---------------------------------------------------------------------------------------------------------------------------------------------------------------
# P1-03I (engine 0.2.3): closing fixes. Items 1, 2, 4 and 5 have a test that fails on 0.2.2 (raw/unittest-new-tests-on-0.2.2-code.txt).
class TestI1_GpuProbeCpuset(unittest.TestCase):
    def setUp(self):
        if GPUBW_FULL is None:
            self.skipTest("no Vulkan headers for the gpubw host build")

    def test_foreground_cpuset_is_recorded(self):                      # 0.2.2: no such field
        rc, rec, out, err = run(GPUBW_FULL, ["--probe", "--json"], env={"P1_CPUSET_PATH": cpuset_fg()})
        self.assertEqual(rc, 0, err)
        self.assertEqual(rec["foreground_cpuset"], "/foreground")
        probe_schema_check(self, rec, GPUPROBE_SCHEMA)
        rc, rec, out, err = run(GPUBW_FULL, ["--probe", "--json"], env={"P1_CPUSET_PATH": cpuset_top()})
        self.assertEqual(rec["foreground_cpuset"], "/top-app")
        rc, rec, out, err = run(GPUBW_FULL, ["--probe", "--json"], env={"P1_CPUSET_PATH": os.path.join(TMP, "no-such-cpuset")})
        self.assertIsNone(rec["foreground_cpuset"])                    # unreadable: null
        probe_schema_check(self, rec, GPUPROBE_SCHEMA)

    def test_schema_requires_it_and_documents_it(self):
        import jsonschema
        rc, rec, out, err = run(GPUBW_FULL, ["--probe", "--json"])
        v = jsonschema.Draft202012Validator(json.loads(rd(GPUPROBE_SCHEMA)))
        bad = json.loads(json.dumps(rec)); bad.pop("foreground_cpuset")
        self.assertNotEqual(list(v.iter_errors(bad)), [])
        bad = json.loads(json.dumps(rec)); bad["foreground_cpuset"] = 5
        self.assertNotEqual(list(v.iter_errors(bad)), [])


class TestI2_SweepSizeReason(unittest.TestCase):
    SW = ["--sweep", "--kernel", "K02", "--threads", "1", "--cores", "0", "--warmup-s", "0", "--window-s", "0.1", "--tag", "C1-sweep-k0", "--json"]

    def test_reason_names_the_offending_size(self):                    # 0.2.2: garbage bytes instead of the size (use after free)
        for size in ("abc", "0.0001", "0"):
            rc, rec, out, err = run(MEMBW, self.SW + ["--sizes", "0.5," + size])
            self.assertEqual(rc, 2, err)
            self.assertEqual(rec["reason"], "sweep size %s too small" % size)
            self.assertIn("sweep size %s too small" % size, rec["conditions"]["note"])
            self.assertIn("sweep size %s too small" % size, err)
            self.assertEqual(rec["cpu_set"], [0])                       # raised after the CPUs were known: F04's rule
            schema_check(self, rec)

    def test_a_very_long_token_is_cut_not_overrun(self):
        rc, rec, out, err = run(MEMBW, self.SW + ["--sizes", "x" * 500])
        self.assertEqual(rc, 2, err)
        self.assertTrue(rec["reason"].startswith("sweep size " + "x" * 40))
        schema_check(self, rec)

    def test_no_message_uses_a_pointer_into_a_freed_buffer(self):
        """Source check: in both programs every `free(<buf>)` of a strdup buffer is not followed, in the same statement list, by a use of a token taken from it."""
        for f in ("src/membw.c", "src/gpubw/gpubw.c"):
            txt = rd(os.path.join(ROOT, f))
            for m in re.finditer(r"free\((dup|d)\);([^\n]*)", txt):
                self.assertNotRegex(m.group(2), r"(USAGE|p1_exitf|fprintf|printf)\([^;]*\btok\b", f + ": " + m.group(0)[:100])


class TestI3_DocsRule(unittest.TestCase):
    def test_rule_names_exit_2_and_the_exit_1_before_cores(self):
        for f in ("ENGINE.md", "EXIT-CODES.md"):
            d = rd(os.path.join(ROOT, f))
            self.assertRegex(d, r"exit 1, 2, 3, 87, 88, 97", f)
            self.assertIn("before `--cores` is parsed", d, f)
            self.assertIn("sweep size that is too small", d, f)

    def test_the_stale_comment_is_gone(self):
        h = rd(os.path.join(ROOT, "src", "part1_common.h"))
        self.assertNotIn("cleared when the row ran unpinned", h)
        self.assertIn("always kept", h)

    def test_exit_1_record_has_empty_cpu_set(self):
        # P1-03J (P1-03I F07): a real exit 1 raised before --cores is parsed: no readable /proc/cpuinfo and no affinity mask, so there is no CPU list
        env = {"P1_CPUINFO_PATH": os.path.join(TMP, "no-such-cpuinfo"), "LD_PRELOAD": FAKEAFF, "FAKE_AFF_FAIL_GET": "1"}
        for args in (["--kernel", "K02", "--threads", "1", "--cores", "1", "--tag", "C2-read-k1", "--json"],
                     ["--sweep", "--sizes", "1", "--kernel", "K02", "--threads", "1", "--cores", "1", "--tag", "C1-sweep-k1", "--json"],
                     ["--pace-chase", "--cores", "1", "--pace-ns", "1000", "--duration-s", "1", "--tag", "PC-pacer-x1-on", "--json"]):
            rc, rec, out, err = run(MEMBW, args, env=env)
            self.assertEqual(rc, 1, (args, out, err))
            self.assertEqual(rec["exit_code"], 1)
            self.assertEqual(rec["status"], "failed")
            self.assertEqual(rec["cpu_set"], [])
            self.assertIn("cannot read the CPU list", rec["reason"])
            self.assertNotIn("tag", rec)                                # raised before --tag is parsed: the record has no tag (the documented exception to "valid as emitted")
            schema_check(self, rec, usage_error=True)

    def test_exit_2_before_cores_has_empty_cpu_set_and_exit_2_after_has_it(self):
        rc, rec, out, err = run(MEMBW, ["--kernel", "K02", "--cores", "0", "--json", "--tag", "C2-read-k0", "--bogus"])
        self.assertEqual(rc, 2)
        self.assertEqual(rec["cpu_set"], [])                           # an option error comes before the CPUs are known
        rc, rec, out, err = run(MEMBW, ["--sweep", "--sizes", "0,1", "--kernel", "K02", "--threads", "1", "--cores", "0", "--json", "--tag", "C1-sweep-k0"])
        self.assertEqual((rc, rec["cpu_set"]), (2, [0]))               # exit 2 raised after them names them


class TestI5_OpenclHelper(unittest.TestCase):
    def setUp(self):
        if GPUBW_FULL is None:
            self.skipTest("no Vulkan headers for the gpubw host build")

    def G(self, paths, extra=None, args=("--probe", "--json")):
        env = {"P1_OCL_PATH_%d" % i: p for i, p in paths.items()}
        env.update(extra or {})
        return run(GPUBW_FULL, list(args), env=env)

    def test_a_crashing_constructor_cannot_kill_the_probe(self):       # 0.2.2: the probe died with 139 and no JSON
        rc, rec, out, err = self.G({0: FAKEOCL["crash"], 1: FAKEOCL["abort"]})
        self.assertEqual(rc, 0, err)
        probe_schema_check(self, rec, GPUPROBE_SCHEMA)
        oc = rec["opencl_child"]
        self.assertEqual((oc[0]["loaded"], oc[0]["outcome"], oc[0]["signal"], oc[0]["error"]), (False, "crashed", 11, None))
        self.assertEqual((oc[1]["loaded"], oc[1]["outcome"], oc[1]["signal"]), (False, "crashed", 6))
        self.assertEqual(oc[2]["outcome"], "dlerror")                   # the other candidates are still tried
        self.assertTrue(rec["vulkan"]["available"])                     # and the Vulkan part still runs
        self.assertEqual(len([ln for ln in out.splitlines() if ln.startswith("{")]), 1)
        self.assertTrue(out.rstrip().splitlines()[-1].startswith("{"))

    def test_a_hanging_helper_times_out_and_is_gone(self):
        import time
        pidf = uniq("ocl-hang.pid")
        if os.path.exists(pidf):
            os.remove(pidf)
        t0 = time.time()
        rc, rec, out, err = self.G({2: FAKEOCL["hang"]}, {"FAKE_OCL_PIDFILE": pidf})
        dt = time.time() - t0
        self.assertEqual(rc, 0, err)
        self.assertGreaterEqual(dt, 1.9)
        self.assertLess(dt, 4.0)
        probe_schema_check(self, rec, GPUPROBE_SCHEMA)
        oc = rec["opencl_child"]
        self.assertEqual((oc[2]["loaded"], oc[2]["outcome"], oc[2]["error"], oc[2]["signal"]), (False, "timeout", None, None))
        pid = int(rd(pidf).strip())
        with self.assertRaises(ProcessLookupError):                     # the helper was killed and collected: nothing is left behind
            os.kill(pid, 0)

    def test_the_probe_dying_early_takes_the_helper_with_it(self):
        import time, signal
        pidf = uniq("ocl-hang2.pid")
        if os.path.exists(pidf):
            os.remove(pidf)
        rc, rec, out, err = self.G({0: FAKEOCL["hang"]}, {"FAKE_OCL_PIDFILE": pidf}, args=("--probe", "--json", "--max-seconds", "0.5"))
        self.assertEqual(rc, 124, err)                                  # the watchdog fires while the helper hangs; the object is still the last line
        probe_schema_check(self, rec, GPUPROBE_SCHEMA)
        self.assertEqual((rec["status"], rec["exit_code"]), ("failed", 124))
        pid = int(rd(pidf).strip())
        for _ in range(50):
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                break
            time.sleep(0.05)
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)

    def test_helper_output_never_reaches_the_probe_stdout(self):
        rc, rec, out, err = self.G({0: FAKEOCL["chatter"]})
        self.assertEqual(rc, 0, err)
        self.assertNotIn("not-the-probe", out)
        self.assertNotIn("vendor chatter", err)
        self.assertEqual((rec["opencl_child"][0]["loaded"], rec["opencl_child"][0]["outcome"]), (True, "loaded"))
        probe_schema_check(self, rec, GPUPROBE_SCHEMA)

    def test_normal_case_is_fast_and_outcomes_are_consistent(self):
        import time
        t0 = time.time()
        rc, rec, out, err = run(GPUBW_FULL, ["--probe", "--json"])
        self.assertLess(time.time() - t0, 1.0)
        for x in rec["opencl_child"]:
            self.assertEqual((x["loaded"], x["outcome"]), (False, "dlerror"))
            self.assertTrue(x["error"])
            self.assertIsNone(x["signal"])

    def test_schema_rejects_inconsistent_outcomes(self):
        import jsonschema
        rc, rec, out, err = self.G({0: FAKEOCL["crash"]})
        v = jsonschema.Draft202012Validator(json.loads(rd(GPUPROBE_SCHEMA)))
        for mut in (lambda r: r["opencl_child"][0].update(signal=None), lambda r: r["opencl_child"][0].update(loaded=True),
                    lambda r: r["opencl_child"][1].update(outcome="loaded"), lambda r: r["opencl_child"][1].update(error=None),
                    lambda r: r["opencl_child"][1].update(signal=9), lambda r: r["opencl_child"][1].update(outcome="exploded"),
                    lambda r: r["opencl_child"][1].update(outcome="timeout")):
            bad = json.loads(json.dumps(rec))
            mut(bad)
            self.assertNotEqual(list(v.iter_errors(bad)), [], bad["opencl_child"][:2])

    def test_production_build_has_no_test_hook(self):
        src = rd(os.path.join(ROOT, "src", "gpubw", "gpubw.c"))
        i = src.index("static const char *gp_ocl_path")
        self.assertIn("#ifdef P1_HOST_BUILD", src[i:i + 200])
        if os.path.exists(os.path.join(ROOT, "out", "arm64-v8a", "libpart1_gpubw.so")):
            self.assertNotIn(b"P1_OCL_PATH", rd(os.path.join(ROOT, "out", "arm64-v8a", "libpart1_gpubw.so"), "rb"))

class TestJ_OpenclHelper(unittest.TestCase):
    """P1-03J items 3 and 4 (P1-03I F01-F04). The tests of this class fail on 0.2.3."""

    def setUp(self):
        if GPUBW_FULL is None:
            self.skipTest("no Vulkan headers for the gpubw host build")

    def G(self, paths, extra=None, args=("--probe", "--json")):
        env = {"P1_OCL_PATH_%d" % i: p for i, p in paths.items()}
        env.update(extra or {})
        return run(GPUBW_FULL, list(args), env=env)

    @staticmethod
    def alive(pid):
        try:
            with open("/proc/%d/stat" % pid) as f:
                return f.read().rsplit(")", 1)[1].split()[0] != "Z"      # a zombie is dead; only init has still to collect it
        except (FileNotFoundError, ProcessLookupError):
            return False

    def assert_gone(self, pid, what):
        import time
        for _ in range(100):
            if not self.alive(pid):
                return
            time.sleep(0.05)
        os.kill(pid, 9)
        self.fail("%s: process %d is still running after the probe ended" % (what, pid))

    def test_late_dlerror_text_is_not_lost(self):                    # F01; 0.2.3: "helper ended with status 1 without a dlerror text"
        rc, rec, out, err = self.G({}, {"LD_PRELOAD": FAKELATE, "FAKE_DLERROR_DELAY_MS": "40", "FAKE_READ_DELAY_MS": "150"})
        self.assertEqual(rc, 0, err)
        probe_schema_check(self, rec, GPUPROBE_SCHEMA)
        for x in rec["opencl_child"]:
            self.assertEqual(x["outcome"], "dlerror")
            self.assertNotIn("without a dlerror text", x["error"])
            self.assertTrue(x["error"])

    def test_exit_0_in_the_constructor_is_not_loaded(self):          # F04; 0.2.3: loaded true
        rc, rec, out, err = self.G({0: FAKEOCL["exit0"]})
        self.assertEqual(rc, 0, err)
        probe_schema_check(self, rec, GPUPROBE_SCHEMA)
        x = rec["opencl_child"][0]
        self.assertEqual((x["loaded"], x["outcome"], x["signal"]), (False, "dlerror", None))
        self.assertIn("no loaded marker", x["error"])
        self.assertEqual(rec["opencl_child"][1]["outcome"], "dlerror")

    def test_a_library_that_loads_is_loaded_with_the_marker(self):
        rc, rec, out, err = self.G({0: FAKEOCL["chatter"]})
        self.assertEqual((rec["opencl_child"][0]["loaded"], rec["opencl_child"][0]["outcome"]), (True, "loaded"))

    def test_a_process_started_by_the_library_dies_with_the_group(self):   # F02; 0.2.3: the grandchild survives
        for name, outcome in (("gc_load", "loaded"), ("gc_hang", "timeout"), ("gc_crash", "crashed")):
            gcf = uniq("gc-%s.pid" % name)
            rc, rec, out, err = self.G({0: FAKEOCL[name]}, {"FAKE_OCL_GCPIDFILE": gcf})
            self.assertEqual(rc, 0, err)
            probe_schema_check(self, rec, GPUPROBE_SCHEMA)
            self.assertEqual(rec["opencl_child"][0]["outcome"], outcome, name)
            self.assert_gone(int(rd(gcf).strip()), name)

    def test_the_watchdog_also_kills_the_group(self):
        gcf = uniq("gc-wd.pid")
        rc, rec, out, err = self.G({0: FAKEOCL["gc_hang"]}, {"FAKE_OCL_GCPIDFILE": gcf}, args=("--probe", "--json", "--max-seconds", "0.5"))
        self.assertEqual(rc, 124, err)
        self.assert_gone(int(rd(gcf).strip()), "watchdog")

    def test_a_helper_ended_by_its_own_alarm_is_a_timeout(self):     # F03; 0.2.3: crashed with signal 14
        import signal, subprocess as sp, time
        pidf = uniq("alarm.pid")
        e = dict(os.environ, P1_CPUSET_PATH=cpuset_top(), P1_MEMINFO_PATH=meminfo(8000, 6000), P1_OCL_PATH_0=FAKEOCL["hang"], FAKE_OCL_PIDFILE=pidf)
        p = sp.Popen([GPUBW_FULL, "--probe", "--json"], env=e, stdout=sp.PIPE, stderr=sp.PIPE, text=True)
        for _ in range(200):
            if os.path.exists(pidf) and rd(pidf).strip():
                break
            time.sleep(0.01)
        time.sleep(0.2)
        p.send_signal(signal.SIGSTOP)                                # the probe cannot enforce its own limit; the helper's alarm (3 s) ends the helper
        time.sleep(3.6)
        p.send_signal(signal.SIGCONT)
        out, err = p.communicate(timeout=30)
        self.assertEqual(p.returncode, 0, err)
        rec = json.loads(out.strip().splitlines()[-1])
        probe_schema_check(self, rec, GPUPROBE_SCHEMA)
        x = rec["opencl_child"][0]
        self.assertEqual((x["outcome"], x["signal"], x["loaded"]), ("timeout", None, False))

    def test_four_hanging_candidates_end_within_3_s_and_the_rest_are_not_tried(self):   # F03; 0.2.3: 8 s
        import time
        pidf = uniq("hang4.pid")
        t0 = time.time()
        rc, rec, out, err = self.G({i: FAKEOCL["hang"] for i in range(4)}, {"FAKE_OCL_PIDFILE": pidf})
        dt = time.time() - t0
        self.assertEqual(rc, 0, err)
        self.assertLess(dt, 3.0)
        self.assertGreaterEqual(dt, 2.0)
        probe_schema_check(self, rec, GPUPROBE_SCHEMA)
        oc = rec["opencl_child"]
        self.assertEqual([x["outcome"] for x in oc], ["timeout", "timeout", "not_tried", "not_tried"])
        for x in oc:
            self.assertEqual((x["loaded"], x["error"], x["signal"]), (False, None, None))
        self.assertTrue(rec["vulkan"]["available"])                  # Vulkan still ran
        self.assert_gone(int(rd(pidf).strip()), "hang")

    def test_schema_knows_not_tried(self):
        import jsonschema
        rc, rec, out, err = self.G({i: FAKEOCL["hang"] for i in range(4)})
        v = jsonschema.Draft202012Validator(json.loads(rd(GPUPROBE_SCHEMA)))
        self.assertEqual(list(v.iter_errors(rec)), [])
        for mut in (lambda r: r["opencl_child"][3].update(error="x"), lambda r: r["opencl_child"][3].update(signal=9), lambda r: r["opencl_child"][3].update(loaded=True)):
            bad = json.loads(json.dumps(rec))
            mut(bad)
            self.assertNotEqual(list(v.iter_errors(bad)), [])

    def test_text_output_prints_the_cpuset(self):                    # F08
        rc, rec, out, err = run(GPUBW_FULL, ["--probe"], env={"P1_CPUSET_PATH": cpuset_fg()})
        self.assertEqual(rc, 0, err)
        self.assertIn(" cpuset=/foreground ", out.splitlines()[0])
        rc, rec, out, err = run(GPUBW_FULL, ["--probe"], env={"P1_CPUSET_PATH": os.path.join(TMP, "no-such-cpuset")})
        self.assertIn(" cpuset=- ", out.splitlines()[0])

class TestJ_CpuSetRule(unittest.TestCase):
    """P1-03J item 5 (P1-03I F06): what the docs say about `cpu_set` in pacer refusals, first-size sweep errors and long sizes is what the engine does."""
    SW = ["--sweep", "--kernel", "K02", "--threads", "1", "--cores", "0", "--warmup-s", "0", "--window-s", "0.1", "--tag", "C1-sweep-k0", "--json"]

    def test_pacer_refusals_have_no_cpus_and_row_refusals_have_them(self):
        pace = ["--pace-chase", "--cores", "1", "--pace-ns", "1000", "--duration-s", "1", "--tag", "PC-pacer-x1-on", "--json"]
        row = ["--kernel", "K02", "--threads", "1", "--cores", "1", "--size-mb", "64", "--tag", "C2-read-k1", "--json"]
        for args, env, code, cpus in ((pace, {"P1_CPUSET_PATH": cpuset_fg()}, 97, []), (pace + ["--mem-limit-mib", "10"], None, 88, []),
                                      (row, {"P1_CPUSET_PATH": cpuset_fg()}, 97, [1]), (row + ["--mem-limit-mib", "10"], None, 88, [1])):
            rc, rec, out, err = run(MEMBW, args, env=env)
            self.assertEqual((rc, rec["cpu_set"]), (code, cpus), (args, rec["reason"]))
            schema_check(self, rec)

    def test_first_size_that_is_too_small_names_the_cpus(self):
        rc, rec, out, err = run(MEMBW, self.SW + ["--sizes", "abc,1"])
        self.assertEqual((rc, rec["cpu_set"], rec["reason"]), (2, [0], "sweep size abc too small"))

    def test_a_long_size_is_cut_to_48_characters(self):
        size = "0." + "0" * 80 + "1"
        rc, rec, out, err = run(MEMBW, self.SW + ["--sizes", size])
        self.assertEqual(rc, 2)
        self.assertEqual(rec["reason"], "sweep size %s too small" % size[:48])
        schema_check(self, rec)

    def test_docs_say_so(self):
        for f in ("ENGINE.md", "EXIT-CODES.md"):
            d = rd(os.path.join(ROOT, f))
            self.assertIn("first 48 characters", d, f)
            self.assertIn("whether it is the first size or a later one", d, f)
            self.assertRegex(d, r"pacer[^.]*exit 97", f)

    def test_docs_state_the_phone_result_and_the_duration(self):
        e = rd(os.path.join(ROOT, "ENGINE.md"))
        for frag in ("must not be used as \"OpenCL available\"", "not accessible for the namespace (default)", "overall limit of 2.5 s", "four hanging candidates", "`not_tried`", "own process group", "unless it leaves the group"):
            self.assertIn(frag, e, frag)
        self.assertNotIn("so none is left behind", e)


if __name__ == "__main__":
    unittest.main()
