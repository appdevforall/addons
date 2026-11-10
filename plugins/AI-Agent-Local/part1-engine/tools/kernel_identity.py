#!/usr/bin/env python3
"""kernel_identity.py: prove that the measurement kernels of the engine are the kit's, at object-code level (job P1-03).

Builds three AArch64 membw binaries into a scratch directory (nothing is written to the kit tree):
  KIT-REBUILD  the kit's membw.c compiled with the kit-arm64 recipe (aarch64-linux-android31-clang -O2 -march=armv8.2-a+fp16 -fno-tree-vectorize -pie)
  ENG-KITFLAGS the engine's membw.c compiled with exactly that recipe (so any difference is a source difference)
  ENG-SHIPPED  out/arm64-v8a/libpart1_membw.so as shipped (-march=armv8-a, API 33)
and compares the disassembly (addresses and branch targets normalised to symbol+offset) of k01..k12 (identical required) and of the three inlined chase loops (the exact rename map EXPECTED_RENAMES required, nothing else may differ), and the byte equality of the
kit's shipped binary with KIT-REBUILD when it exists. usage: python3 -I -B tools/kernel_identity.py SCRATCHDIR [--shipped PATH]
P1-03H: the code AROUND each paced loop (the chunk set-up before it and the tear-down after it: the chunk-count register, the per-chunk load and store of the groups counter) is compared too, against an
exact expected difference list (EXPECTED_CHUNK_DIFFS); --shipped PATH checks another copy of the engine binary (the tests patch a copy of the object code and show that the tool then fails).
"""
import difflib
import glob
import hashlib
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KIT_SRC = "intake-v4d5x/intake/src"
KIT_BIN = "intake-v4d5x/intake/bin/membw"
NDK = sorted(glob.glob(os.path.expanduser("~/Android/Sdk/ndk/*")))[-1]
TC = os.path.join(NDK, "toolchains/llvm/prebuilt/linux-x86_64/bin")
FUNCS = ["k%02d" % i for i in range(1, 13)]
# P1-03G (planner decision PLAN-025): the exact register renames of the inlined chase loops in the engine builds, kit register -> engine register, found by the P1-03F review.
EXPECTED_RENAMES = {"unpaced": {}, "spin": {"x27": "x19"}, "dsb": {"x27": "x19", "x25": "x12"}}
# P1-03H (review F03, F04 of P1-03G): the chunk block of each loop kind = from `mov x8, <chunk count>` (the chunk-count register is loaded into the loop counter x8) through the first following `b` or `bl`:
# set-up, the loop, and the tear-down up to the branch to the common exit (or the clock_gettime call after the paced loop). Branch targets inside the block become <L+n> (instruction index), targets outside
# <EXIT> (all of them must be the same address in one build), `[sp, #imm]` and `sp, #imm` immediates become #SP (stack frame layout is the compiler's); the groups slot of the paced loop is checked separately.
# EXPECTED_CHUNK_DIFFS is the exact list of differences engine against kit (difflib opcodes of the normalised instruction lists, kit part | engine part). Nothing else may differ. In words:
#   every kind: the chunk-count register x28 (kit) is x27 (engine); spin and paced: the timestamp register x27 is x19; paced only: the groups counter x25 is x12, `ldr x12, [sp]` is added before the loop
#   (per-chunk load) and `str x12, [sp]` after it (per-chunk store), the same stack slot.
EXPECTED_CHUNK_DIFFS = {
    "unpaced": ["- mov x8, x28 | + mov x8, x27"],
    "spin": ["- mov x8, x28 | + mov x8, x27", "- sub x10, x9, x27 | + sub x10, x9, x19", "- mov x27, x9 | + mov x19, x9"],
    "dsb": ["- mov x8, x28 | + mov x8, x27", "- | + ldr x12, [sp, #SP]", "- add x25, x25, #0x1 | + add x12, x12, #0x1", "- sub x10, x9, x27 | + sub x10, x9, x19", "- mov x27, x9 | + mov x19, x9", "- | + str x12, [sp, #SP]"],
}


def sha(p):
    return hashlib.sha256(open(p, "rb").read()).hexdigest()


def disasm(binary):
    out = subprocess.run([os.path.join(TC, "llvm-objdump"), "-d", "--no-show-raw-insn", "--demangle", binary], stdout=subprocess.PIPE, text=True, check=True).stdout
    funcs, cur = {}, None
    for ln in out.splitlines():
        m = re.match(r"^[0-9a-f]+ <(\w+)>:$", ln)
        if m:
            cur = m.group(1)
            funcs[cur] = []
            funcs[cur + "@addr"] = []
            continue
        if cur and re.match(r"^\s+[0-9a-f]+:\s", ln):
            ins = re.sub(r"^\s+[0-9a-f]+:\s+", "", ln)
            ins = re.sub(r"0x[0-9a-f]+ <(\w+)([+0-9a-fx]*)>", r"<\1\2>", ins)     # branch target: symbol+offset only
            funcs[cur].append(re.sub(r"\s+", " ", ins.strip()))
            funcs[cur + "@addr"].append(int(re.match(r"^\s+([0-9a-f]+):", ln).group(1), 16))
            funcs.setdefault(cur + "@raw", []).append(re.sub(r"\s+", " ", re.sub(r"\s*//.*$", "", re.sub(r"^\s+[0-9a-f]+:\s+", "", ln)).strip()))
    return funcs


def build(cc_api, march, src, out, extra=()):
    cmd = [os.path.join(TC, "aarch64-linux-android%s-clang" % cc_api), "-O2", "-march=" + march, "-fno-tree-vectorize", "-pie", "-w", "-I" + os.path.dirname(src), "-I" + os.path.join(ROOT, "src")] + list(extra) + ["-o", out, src, "-lm"]
    subprocess.run(cmd, check=True, cwd=os.path.dirname(src))


def main():
    scratch = sys.argv[1]
    os.makedirs(scratch, exist_ok=True)
    kit_re = os.path.join(scratch, "kit-rebuild-membw")
    eng_kf = os.path.join(scratch, "engine-kitflags-membw")
    build("31", "armv8.2-a+fp16", os.path.join(KIT_SRC, "membw.c"), kit_re)
    build("31", "armv8.2-a+fp16", os.path.join(ROOT, "src", "membw.c"), eng_kf, ["-DP1_ENGINE_VERSION=\"x\""])
    shipped = os.path.join(ROOT, "out/arm64-v8a/libpart1_membw.so")
    if "--shipped" in sys.argv:
        shipped = sys.argv[sys.argv.index("--shipped") + 1]
    print("kit shipped binary   sha256 %s" % (sha(KIT_BIN) if os.path.exists(KIT_BIN) else "(absent)"))
    print("kit source rebuilt   sha256 %s" % sha(kit_re))
    if os.path.exists(KIT_BIN):
        print("rebuild of the kit source reproduces the kit's shipped binary byte for byte: %s" % (sha(kit_re) == sha(KIT_BIN)))
    D = {"KIT-REBUILD": disasm(kit_re), "ENG-KITFLAGS": disasm(eng_kf), "ENG-SHIPPED": disasm(shipped)}
    if os.path.exists(KIT_BIN):
        D["KIT-SHIPPED"] = disasm(KIT_BIN)
    bad = 0
    print("%-11s %8s | %s" % ("function", "insns", " ".join("%-12s" % k for k in D if k != "KIT-REBUILD")))
    for f in FUNCS:
        ref = D["KIT-REBUILD"].get(f)
        row = []
        for k in D:
            if k == "KIT-REBUILD":
                continue
            same = D[k].get(f) == ref and ref is not None
            row.append("%-12s" % ("identical" if same else "DIFFERENT"))
            bad += not same
        print("%-11s %8s | %s" % (f, len(ref) if ref else "missing", " ".join(row)))
    # chase_loop() is inlined into main() three times (unpaced, spin = isb/mrs, dsb = paced). Each loop is a window from its `ldr xN, [xN]` to the closing b.ne.
    # P1-03G: the comparison is STRICT. The engine's loops must equal the kit's loops after applying exactly the rename map in EXPECTED_RENAMES (kit register -> engine
    # register), position by position, and the registers that differ must be exactly the ones in the map. Any other difference (another register, another opcode, another
    # length, a missing or extra loop) is a failure.
    def loops(funcs):
        main_ = funcs.get("main") or []
        addrs = funcs.get("main@addr") or []
        res = {}
        for n, x in enumerate(main_):
            m = re.match(r"ldr (x\d+), \[(x\d+)\]$", x)
            if not m or m.group(1) != m.group(2):
                continue
            for j in range(n + 1, min(n + 40, len(main_))):
                mb = re.match(r"b\.ne <main\+0x([0-9a-f]+)>", main_[j])
                if mb and addrs[0] + int(mb.group(1), 16) == addrs[n]:     # the loop's closing branch goes back to its own first instruction
                    w = main_[n:j + 1]
                    kind = "dsb" if any(y.startswith("dsb") for y in w) else "spin" if any(y.startswith("isb") for y in w) else "unpaced"
                    base = addrs[0]    # branch targets inside the window become loop-relative instruction offsets <L+n>; the symbol's own offset 0 is its first instruction
                    res.setdefault(kind, []).append([re.sub(r"<main\+0x([0-9a-f]+)>", lambda mo: "<L%+d>" % ((int(mo.group(1), 16) + base - addrs[n]) // 4), y) for y in w])
                    break
        return res
    def regs(x):
        return re.findall(r"\b[xw]\d+\b|\bxzr\b|\bwzr\b|\bsp\b", x)
    def rename_map(kitw, engw):
        """Observed kit->engine register map, or None when the windows differ in anything but register names."""
        if len(kitw) != len(engw):
            return None
        mp = {}
        for a, b in zip(kitw, engw):
            if re.sub(r"\b[xw]\d+\b", "R", a) != re.sub(r"\b[xw]\d+\b", "R", b) or a.split(" ")[0] != b.split(" ")[0]:
                return None
            for r1, r2 in zip(regs(a), regs(b)):
                if r1 != r2:
                    if mp.setdefault(r1, r2) != r2:
                        return None
        return mp
    def expected_for(kind, name):
        if name == "KIT-SHIPPED":
            return {}                                   # the kit's shipped binary: identical to the kit rebuild, as written
        return EXPECTED_RENAMES[kind]
    KL = loops(D["KIT-REBUILD"])
    for k in D:
        if k == "KIT-REBUILD":
            continue
        EL = loops(D[k])
        for kind in ("unpaced", "spin", "dsb"):
            kw, ew = KL.get(kind, []), EL.get(kind, [])
            exp = expected_for(kind, k)
            if len(kw) != 1 or len(ew) != 1:
                print("%-12s chase %-7s: FAIL, %d loop(s) in the kit rebuild, %d in this build (exactly one each is required)" % (k, kind, len(kw), len(ew)))
                bad += 1
                continue
            obs = rename_map(kw[0], ew[0])
            ok = obs is not None and obs == exp
            # the window must also equal the kit window with the expected map applied (a one-to-one rename, applied everywhere)
            if ok:
                applied = ["".join(exp.get(t, t) for t in re.split(r"(\b[xw]\d+\b)", x)) for x in kw[0]]
                applied = [re.sub(r"\b[xw](\d+)\b", lambda mo: mo.group(0), y) for y in applied]
                ok = applied == ew[0] and len(set(exp.values())) == len(exp)
            bad += not ok
            print("%-12s chase %-7s: %2d insns, expected rename map %-26s observed %-26s %s" % (k, kind, len(kw[0]), exp or "{} (identical)", "identical" if obs == {} else obs if obs is not None else "NOT a pure rename", "OK" if ok else "FAIL"))
    # ---- P1-03H: the chunk set-up and tear-down around the loops ----
    def chunk_blocks(funcs):
        raw, addrs = funcs.get("main@raw") or [], funcs.get("main@addr") or []
        res, exits = {}, set()
        for n, x in enumerate(raw):
            m = re.match(r"ldr (x\d+), \[(x\d+)\]$", x)
            if not m or m.group(1) != m.group(2):
                continue
            for j in range(n + 1, min(n + 40, len(raw))):
                mb = re.match(r"b\.ne 0x([0-9a-f]+) <", raw[j])
                if not (mb and int(mb.group(1), 16) == addrs[n]):
                    continue
                w = raw[n:j + 1]
                kind = "dsb" if any(y.startswith("dsb") for y in w) else "spin" if any(y.startswith("isb") for y in w) else "unpaced"
                st = next((k for k in range(n - 1, max(n - 6, -1), -1) if re.match(r"mov x8, x\d+$", raw[k])), None)
                en = next((k for k in range(j + 1, min(j + 8, len(raw))) if re.match(r"bl? ", raw[k])), None)
                if st is None or en is None:
                    break
                a0 = {addrs[k]: k - st for k in range(st, en + 1)}
                out, slots = [], []
                for k in range(st, en + 1):
                    x2 = raw[k]
                    mt = re.match(r"(b(?:\.\w+)?) 0x([0-9a-f]+) <", x2)
                    if mt:
                        tgt = int(mt.group(2), 16)
                        if tgt in a0:
                            x2 = "%s <L%+d>" % (mt.group(1), a0[tgt] - (k - st))
                        else:
                            x2 = "%s <EXIT>" % mt.group(1); exits.add(tgt)
                    x2 = re.sub(r"\bbl 0x[0-9a-f]+ <([^>]*)>", r"bl <\1>", x2)
                    sm = re.search(r"sp, #(0x[0-9a-f]+)", x2)
                    if sm:
                        slots.append((k - st, sm.group(1)))
                    out.append(re.sub(r"sp, #0x[0-9a-f]+", "sp, #SP", x2))
                res.setdefault(kind, []).append((out, slots))
                break
        return res, exits
    def chunk_diff(kb, eb):
        sm = difflib.SequenceMatcher(None, kb, eb, autojunk=False)
        out = []
        for tag, i1, i2, j1, j2 in sm.get_opcodes():
            if tag == "equal":
                continue
            kk, ee = kb[i1:i2], eb[j1:j2]
            for a, b in zip(kk, ee):                      # a replaced block is paired instruction by instruction; the surplus is a pure deletion or insertion
                out.append("- %s | + %s" % (a, b))
            for a in kk[len(ee):]:
                out.append("- %s | + " % a)
            for b in ee[len(kk):]:
                out.append("- | + %s" % b)
        return out
    KC, kexits = chunk_blocks(D["KIT-REBUILD"])
    for k in D:
        if k == "KIT-REBUILD":
            continue
        EC, eexits = chunk_blocks(D[k])
        for kind in ("unpaced", "spin", "dsb"):
            kw, ew = KC.get(kind, []), EC.get(kind, [])
            exp = [] if k == "KIT-SHIPPED" else EXPECTED_CHUNK_DIFFS[kind]
            if len(kw) != 1 or len(ew) != 1:
                print("%-12s chunk %-7s: FAIL, %d block(s) in the kit rebuild, %d in this build (exactly one each is required)" % (k, kind, len(kw), len(ew)))
                bad += 1
                continue
            obs = chunk_diff(kw[0][0], ew[0][0])
            ok = obs == exp
            if kind == "dsb" and k != "KIT-SHIPPED":
                ld = [i for i, x in enumerate(ew[0][0]) if x == "ldr x12, [sp, #SP]"]
                stx = [i for i, x in enumerate(ew[0][0]) if x == "str x12, [sp, #SP]"]
                slot = {i: v for i, v in ew[0][1]}
                ok = ok and len(ld) == 1 and len(stx) == 1 and ld[0] < stx[0] and slot.get(ld[0]) is not None and slot.get(ld[0]) == slot.get(stx[0])   # one load before the loop, one store after it, the same stack slot
            ok = ok and len(eexits) == 1 and len(kexits) == 1                                                                                   # one common exit address in each build
            bad += not ok
            print("%-12s chunk %-7s: %2d insns, %d expected difference(s)  %s" % (k, kind, len(ew[0][0]), len(exp), "OK" if ok else "FAIL (observed: %s)" % obs))
    print("expected chunk differences: the chunk-count register x28 -> x27 in all three; the paced loop additionally loads `groups` (x12) from its stack slot before the loop and stores it back after it (once per chunk). Listed in EXPECTED_CHUNK_DIFFS.")
    print("expected renames (kit -> engine, both builds): unpaced loop none; spin loop x27->x19; dsb (paced) loop x27->x19 and x25->x12 (x12 is caller-saved: `groups` is loaded and stored on the stack once per chunk, outside the paced inner loop). Source text of chase_loop() is byte-identical (tests/test_engine.py::TestDocsAndSources::test_kernel_text_is_the_kits).")
    print("result: %s" % ("K01-K12 identical in every build; chase loops equal the kit's with exactly the expected rename map and the chunk set-up and tear-down differ from the kit's by exactly the expected list" if not bad else "%d difference(s) from the expected" % bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
