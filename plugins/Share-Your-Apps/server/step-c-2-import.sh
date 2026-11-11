#!/bin/sh
# step-c-2-import.sh: step C, script 2 of 6. Merges ImportJob.kt (A + B) and gives B's ImportActivity.kt A's
# public defaultPhoneName(context), which McpTools uses.
#
# Run:   sh "/storage/emulated/0/CodeOnTheGoProjects/Local Store Server Combined/step-c-2-import.sh"
# Undo:  sh "/storage/emulated/0/CodeOnTheGoProjects/Local Store Server Combined/step-c-2-import.sh" undo
#
# ImportJob.kt (A's file, patched):
#   1. Snapshot gets storeId (B's stale import result fix).
#   2. start() doc: the owner's tones; the network parameter.
#   3. start() takes network (B) before byMcp (A), so both projects' calls still work.
#   4. start() passes network to run().
#   5. run() uses the joined network first: joined ?: wifiNetwork(context).
#   6. DONE carries storeId; adds clearFinished() (synchronized with start) and onOwnersNetworkNow().
# ImportActivity.kt (B's file, from script 1, patched):
#   7. The private defaultPhoneName() calls the companion's.
#   8. The companion gets A's public defaultPhoneName(context), cut to 40 characters.
#
# Before changing anything it checks: this file ends with its END line; script 1 has run; this one hasn't;
# every text to replace is in its file exactly once; every patch keeps { } and ( ) balanced. Then it backs up
# (step-c-backup/2), writes each file through a temporary file, reads it back and compares SHA-256. On any
# failure it puts every file back. Checksums: step-c-checksums.txt.
# After this script the project should compile: do a Standard Run. Plain sh, no bash; needs Python.

set -u
NAME="step-c-2-import.sh"
STEP=2

# ---- Not cut off: the last line of this file must be its END line.
if [ "$(tail -n 1 "$0" 2>/dev/null)" != "# END OF $NAME" ]; then
  echo "STOPPED: $0 is incomplete: its last line isn't \"# END OF $NAME\"."
  echo "Nothing was changed. Ask the agent to write it again."
  exit 2
fi

HERE="$(cd "$(dirname "$0")" && pwd)" || { echo "STOPPED: couldn't find this script's folder. Nothing was changed."; exit 2; }
cd "$HERE" || exit 2

fail() { echo "STOPPED: $*"; echo "Nothing was changed."; exit 1; }

BK="step-c-backup/$STEP"
SUMS="step-c-checksums.txt"

undo() {
  [ -d "$BK" ] || { echo "Nothing to undo: $BK doesn't exist."; return 1; }
  UNDO_OK=yes
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    if cp "$BK/files/$f" "$f"; then echo "  put back: $f"
    else echo "  COULDN'T PUT BACK: $f (its copy is in $BK/files)"; UNDO_OK=no; fi
  done < "$BK/replaced.txt"
  if [ -f "$SUMS" ]; then grep -v "^step$STEP " "$SUMS" > "$SUMS.tmp"; mv "$SUMS.tmp" "$SUMS"; fi
  if [ "$UNDO_OK" = yes ]; then
    mv "$BK" "$BK.undone-$(date +%Y%m%d-%H%M%S)"
    echo "Undone."
    return 0
  fi
  echo "NOT fully undone: see the lines above. The backup stays in $BK."
  return 1
}

case "${1:-}" in
  "") ;;
  undo)
    for n in 3 4 5 6; do
      if [ -d "step-c-backup/$n" ]; then
        echo "STOPPED: step C script $n has run since this one. Undo it first (its script, with undo). Nothing was changed."
        exit 1
      fi
    done
    echo "Undoing $NAME..."
    undo; exit $? ;;
  *) fail "unknown option \"$1\". Run it with nothing, or with undo." ;;
esac

[ -d "step-c-backup/1" ] || fail "step-c-1-copy.sh hasn't run yet (step-c-backup/1 isn't here). Run it first."
[ -e "$BK" ] && fail "this script has already run ($BK exists). To run it again, undo it first: sh $NAME undo"

PY=""
if command -v python3 >/dev/null 2>&1; then PY=python3
elif command -v python >/dev/null 2>&1; then PY=python
fi
[ -n "$PY" ] || fail "neither python3 nor python was found."

"$PY" - "$STEP" "$BK" "$SUMS" <<'PYEOF'
import hashlib, os, shutil, sys
step, bk, sums_path = sys.argv[1], sys.argv[2], sys.argv[3]
SRC = "app/src/main/java/org/appdevforall/localstore/server/"
P = []
def patch(f, what, old, new): P.append((SRC + f, what, old, new))

patch("ImportJob.kt", "Snapshot gets storeId", r'''        /** Imported over the owner's own network: serving it there would depend on the owner's phone. */
        val onOwnersNetwork: Boolean = false,
    ) {''', r'''        /** Imported over the owner's own network: serving it there would depend on the owner's phone. */
        val onOwnersNetwork: Boolean = false,
        /** Once DONE: the imported copy's store ID, so the screen can check the copy is still on this phone. */
        val storeId: String? = null,
    ) {''')

patch("ImportJob.kt", "start() doc", r'''     * Starts importing, from [address] if known — from a QR link — or by searching for the code.''',
r'''     * Starts importing, from [address] if known — from a QR link or the owner's tones — or by searching for the code.
     * [network]: the network to use, such as the owner's hotspot that WifiJoin joined; null uses the phone's Wi-Fi.''')

patch("ImportJob.kt", "start() takes network", r'''    fun start(context: Context, code: String, phoneName: String, address: InetSocketAddress?, byMcp: Boolean = false): String? {''',
r'''    fun start(context: Context, code: String, phoneName: String, address: InetSocketAddress?,
              network: android.net.Network? = null, byMcp: Boolean = false): String? {''')

patch("ImportJob.kt", "start() passes network to run()", r'''            try { run(app, code, phoneName, address) }''',
r'''            try { run(app, code, phoneName, address, network) }''')

patch("ImportJob.kt", "run() uses the joined network first", r'''    private fun run(context: Context, code: String, phoneName: String, known: InetSocketAddress?) {
        // Everything goes over Wi-Fi explicitly: a network without internet, such as the owner's hotspot,
        // isn't Android's default, and requests would otherwise go to mobile data, and fail.
        val wifi = wifiNetwork(context) ?: throw IOException(''', r'''    private fun run(context: Context, code: String, phoneName: String, known: InetSocketAddress?, joined: android.net.Network?) {
        // Everything goes over Wi-Fi explicitly: a network without internet, such as the owner's hotspot,
        // isn't Android's default, and requests would otherwise go to mobile data, and fail. A network WifiJoin
        // joined (the owner's hotspot) comes first: it may not be the first Wi-Fi network Android lists.
        val wifi = joined ?: wifiNetwork(context) ?: throw IOException(''')

patch("ImportJob.kt", "DONE carries storeId; clearFinished(), onOwnersNetworkNow()", r'''        set(Snapshot(Phase.DONE, "Imported", check, desc.storeName, desc.totalBytes, desc.totalBytes, desc.files.size, desc.expires, onOwnersNetwork = owners))
    }''', r'''        set(Snapshot(Phase.DONE, "Imported", check, desc.storeName, desc.totalBytes, desc.totalBytes, desc.files.size, desc.expires,
            onOwnersNetwork = owners, storeId = desc.storeId))
    }

    /**
     * Forgets a finished import (done, denied, cancelled, or failed with no Try again), so the next visit to the import
     * screen shows the empty form, not an old result. A failed transfer that can be tried again is kept, as is a busy one.
     * Synchronized with [start], so an import an MCP client starts at the same moment is never wiped.
     */
    @Synchronized
    fun clearFinished() {
        val s = snapshot
        if (s.busy || s.phase == Phase.IDLE || s.canRetry) return
        set(Snapshot(Phase.IDLE))
    }

    /**
     * Whether this phone is on the owner's network now, not when the import ended: Wi-Fi may have been turned off or
     * changed since. False with no Wi-Fi, or if no owner is known.
     */
    fun onOwnersNetworkNow(context: Context): Boolean =
        wifiNetwork(context.applicationContext)?.let { onOwnersNetwork(context.applicationContext, it) } == true''')

patch("ImportActivity.kt", "private defaultPhoneName() calls the companion's", r'''    private fun defaultPhoneName(): String =
        (if (Build.VERSION.SDK_INT >= 25) Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) else null)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL''',
r'''    /** The phone's own name, at most 40 characters: the companion's [defaultPhoneName], which import_start uses too. */
    private fun defaultPhoneName(): String = defaultPhoneName(this)''')

patch("ImportActivity.kt", "public defaultPhoneName(context) in the companion", r'''        private var pendingByHand: ByHand? = null
    }
}''', r'''        private var pendingByHand: ByHand? = null

        /**
         * The name the owner sees when this phone asks for a copy: the phone's own name, or its model. At most 40
         * characters, as the import screen's field allows. Public so an MCP import (import_start) uses the same default.
         */
        fun defaultPhoneName(context: android.content.Context): String =
            ((if (Build.VERSION.SDK_INT >= 25) Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) else null)
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL).take(40)
    }
}''')

def stop(msg):
    print("STOPPED: " + msg)
    print("Nothing was changed.")
    sys.exit(1)
def sha(b): return hashlib.sha256(b).hexdigest()
def bal(t): return (t.count("{") - t.count("}"), t.count("(") - t.count(")"))

files = []
for p in P:
    if p[0] not in files: files.append(p[0])
raw, out = {}, {}
for f in files:
    if not os.path.isfile(f): stop(f + " isn't here.")
    with open(f, "rb") as h: raw[f] = h.read()
    t = raw[f].decode("utf-8")
    for i, (g, what, old, new) in enumerate(P, 1):
        if g != f: continue
        if old == new: stop("patch %d (%s) changes nothing: the script is wrong." % (i, what))
        n = t.count(old)
        if n != 1: stop("patch %d (%s): its text to replace is in %s %d times, not once. Has the file changed?" % (i, what, f, n))
        if bal(old) != bal(new): stop("patch %d (%s) would unbalance { } or ( ): the script is wrong." % (i, what))
        t = t.replace(old, new)
    out[f] = t.encode("utf-8")
for i, (f, what, old, new) in enumerate(P, 1):
    c = out[f].decode("utf-8").count(new)
    if c != 1: stop("patch %d (%s): after patching, its new text is in %s %d times, not once." % (i, what, f, c))
for f in files:
    if bal(raw[f].decode("utf-8")) != bal(out[f].decode("utf-8")): stop(f + ": { } or ( ) would no longer balance as before.")
print("Checks passed: %d patches to %d files." % (len(P), len(files)))

try:
    os.makedirs(os.path.join(bk, "files"))
    with open(os.path.join(bk, "replaced.txt"), "w") as h:
        for f in files:
            dst = os.path.join(bk, "files", f)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with open(dst, "wb") as o: o.write(raw[f])
            with open(dst, "rb") as o:
                if sha(o.read()) != sha(raw[f]): raise IOError("the backup of %s doesn't match it" % f)
            h.write(f + "\n")
except Exception as e:
    shutil.rmtree(bk, ignore_errors=True)
    stop("couldn't back up: %s." % e)
print("Backed up to %s." % bk)

try:
    for f in files:
        tmp = f + ".step-c.tmp"
        with open(tmp, "wb") as o: o.write(out[f])
        os.replace(tmp, f)
        with open(f, "rb") as o:
            if sha(o.read()) != sha(out[f]): raise IOError("%s doesn't read back as written" % f)
except Exception as e:
    for f in files:
        try:
            with open(f, "wb") as o: o.write(raw[f])
        except Exception: print("  COULDN'T PUT BACK %s: its copy is in %s/files" % (f, bk))
    shutil.rmtree(bk, ignore_errors=True)
    print("FAILED: %s. Every file was put back as it was." % e)
    sys.exit(1)

with open(sums_path, "a") as h:
    for f in files: h.write("step%s %s %s\n" % (step, sha(out[f]), f))
for f in files:
    n = sum(1 for p in P if p[0] == f)
    print("  patched %s: %d changes, %d -> %d bytes, sha256 %s..." % (f, n, len(raw[f]), len(out[f]), sha(out[f])[:12]))
for i, (f, what, _, _) in enumerate(P, 1):
    print("    %d. %s: %s" % (i, os.path.basename(f), what))
print("Every file reads back as written.")
PYEOF
RC=$?
[ "$RC" = 0 ] || exit "$RC"

echo
echo "Backup: $BK   Checksums: $SUMS"
echo "Next: a Standard Run (the project should compile now), then :app:testDebugUnitTest."
echo "Paste all of this output, and the build result, to the agent."
exit 0
# END OF step-c-2-import.sh
