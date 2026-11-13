#!/bin/sh
# step-c-4-mic.sh: step C, script 4 of 6. The microphone for MCP control: manifest, McpService, the MCP switch.
#
# Run:   sh "/storage/emulated/0/CodeOnTheGoProjects/Share Your Apps Server Combined/step-c-4-mic.sh"
# Undo:  sh "/storage/emulated/0/CodeOnTheGoProjects/Share Your Apps Server Combined/step-c-4-mic.sh" undo
#
# AndroidManifest.xml:
#   1. RECORD_AUDIO and FOREGROUND_SERVICE_MICROPHONE.
#   2. ImportActivity gets project B's configChanges (turning the phone doesn't stop Listen).
#   3. McpService's type becomes specialUse|microphone.
# McpService.kt:
#   4. A micType field: whether the service runs with the microphone type.
#   5. Started again while running (the app opened, the microphone allowed): the line under the switch is updated.
#   6. The line under the switch says whether import_listen can use the microphone.
#   7. goForeground: the microphone type when the microphone is allowed; if Android refuses it (the app not on
#      screen), it runs as before, without it. The old code is kept for that case, unchanged.
# MainActivity.kt:
#   8. A request code for the microphone.
#   9. The "Turn on MCP control?" message gets the microphone paragraph; Turn on then asks for the microphone.
#  10. The microphone answered: MCP control starts again, with or without the microphone type.
#
# Same checks as scripts 2 and 3, plus: the manifest must still parse as XML. Backup in step-c-backup/4.
# Plain sh, no bash; needs Python.

set -u
NAME="step-c-4-mic.sh"
STEP=4

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
    for n in 5 6; do
      if [ -d "step-c-backup/$n" ]; then
        echo "STOPPED: step C script $n has run since this one. Undo it first (its script, with undo). Nothing was changed."
        exit 1
      fi
    done
    echo "Undoing $NAME..."
    undo; exit $? ;;
  *) fail "unknown option \"$1\". Run it with nothing, or with undo." ;;
esac

[ -d "step-c-backup/3" ] || fail "step-c-3-share.sh hasn't run yet (step-c-backup/3 isn't here). Run it first."
[ -e "$BK" ] && fail "this script has already run ($BK exists). To run it again, undo it first: sh $NAME undo"

PY=""
if command -v python3 >/dev/null 2>&1; then PY=python3
elif command -v python >/dev/null 2>&1; then PY=python
fi
[ -n "$PY" ] || fail "neither python3 nor python was found."

"$PY" - "$STEP" "$BK" "$SUMS" <<'PYEOF'
import hashlib, os, shutil, sys
step, bk, sums_path = sys.argv[1], sys.argv[2], sys.argv[3]
SRC = "app/src/main/java/org/appdevforall/shareyourapps/server/"
MANIFEST = "app/src/main/AndroidManifest.xml"
P = []
def patch(f, what, old, new): P.append((f, what, old, new))

patch(MANIFEST, "RECORD_AUDIO and FOREGROUND_SERVICE_MICROPHONE", r'''    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
''', r'''    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <!-- Hearing the share code a sharing phone plays (the sound handshake): Listen on the import screen, and
         import_listen for MCP control. Asked the first time someone taps Listen, or turns MCP control on. -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <!-- MCP control may listen with the app off screen, so McpService also has the microphone type. -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
''')

patch(MANIFEST, "ImportActivity configChanges (project B)", r'''        <!-- Importing someone else's store: from Manage Server, or from a share's QR link, which
             a camera app offers to open. -->
        <activity android:name=".ImportActivity" android:exported="true">''', r'''        <!-- Importing someone else's store: from Manage Server, or from a share's QR link, which
             a camera app offers to open.
             configChanges: turning the phone, resizing the window or folding it doesn't recreate the
             screen, so Listen (the microphone, the countdown and the meter) carries on. The screen is
             built in code with no landscape layouts, so it just lays itself out again. Other changes,
             such as dark mode, still recreate it. -->
        <activity android:name=".ImportActivity" android:exported="true"
            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboard|keyboardHidden">''')

patch(MANIFEST, "McpService type specialUse|microphone", r'''            android:name=".McpService"
            android:exported="false"
            android:foregroundServiceType="specialUse">''', r'''            android:name=".McpService"
            android:exported="false"
            android:foregroundServiceType="specialUse|microphone">''')

patch(SRC + "McpService.kt", "micType field", r'''    @Volatile private var http: McpHttp? = null
''', r'''    @Volatile private var http: McpHttp? = null
    /** Whether the service runs with the microphone type, so import_listen can hear tones with the app off screen. */
    @Volatile private var micType = false
''')

patch(SRC + "McpService.kt", "started again while running: update the line", r'''        if (http != null || starting) return START_NOT_STICKY''',
r'''        if (http != null || starting) {
            // Already running: the microphone type may have changed (the app opened, the microphone allowed or refused).
            http?.let { McpStatus.set(McpStatus.State.ON, onText(it.boundPort)) }
            return START_NOT_STICKY
        }''')

patch(SRC + "McpService.kt", "the line under the switch", r'''                    McpStatus.set(McpStatus.State.ON, "On, at http://127.0.0.1:${h.boundPort}${McpHttp.PATH}")''',
r'''                    McpStatus.set(McpStatus.State.ON, onText(h.boundPort))''')

patch(SRC + "McpService.kt", "goForeground: the microphone type when allowed", r'''        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION, n)
        }
    }''', r'''        // The microphone type too, when the app may use the microphone, so import_listen hears tones with the app off
        // screen. Android refuses that type unless the app is on screen (it isn't after a reboot, or from the
        // background): then MCP runs as before, without it, and the next opening of the app adds it (onResume).
        // Android 9 and 10 have no microphone type: any foreground service may use the microphone there.
        val mic = SoundIo.canListen(this)
        if (Build.VERSION.SDK_INT >= 30 && mic) {
            val special = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            try {
                startForeground(NOTIFICATION, n, special or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                micType = true
                return
            } catch (e: Exception) {
                Log.w(TAG, "the microphone type was refused (${e.javaClass.simpleName}); running without it until the app is opened")
            }
        }
        micType = mic && Build.VERSION.SDK_INT < 30
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION, n)
        }
    }

    /** The line under the switch while it's on: the address, and whether import_listen can use the microphone. */
    private fun onText(port: Int): String = "On, at http://127.0.0.1:$port${McpHttp.PATH}" + when {
        !SoundIo.canListen(this) -> "\nMicrophone not allowed: hearing a sharing phone's tones needs a person."
        !micType -> "\nMicrophone: works once the app is opened."
        else -> ""
    }''')

patch(SRC + "MainActivity.kt", "request code for the microphone", r'''        private const val IMPORT_CAPTION = "Serve a copy of someone else's store, with their permission. You'll need to be near their phone, on the same network."
    }''', r'''        private const val IMPORT_CAPTION = "Serve a copy of someone else's store, with their permission. You'll need to be near their phone, on the same network."
        /** Asking for the microphone after MCP control is turned on, for import_listen. */
        private const val REQUEST_MCP_MIC = 4
    }''')

patch(SRC + "MainActivity.kt", "switch message: microphone paragraph, then ask", r'''                .setMessage("Apps on this phone will be able to start and stop the server, share this store and import a store.\n\n" +
                    "They can't change the store or approve a copy. You still tap Allow.\n\n" +
                    "Each app needs the token: use Copy token, below the switch.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Turn on") { _, _ -> McpService.setEnabled(this@MainActivity, true); showMcp() }''',
r'''                .setMessage("Apps on this phone will be able to start and stop the server, share this store and import a store.\n\n" +
                    "They can't change the store or approve a copy. You still tap Allow.\n\n" +
                    "This also lets the app use the microphone to hear the sound a sharing phone plays. It only listens while " +
                    "it's receiving a handshake, and Android shows its microphone indicator while it does.\n\n" +
                    "Each app needs the token: use Copy token, below the switch.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Turn on") { _, _ ->
                    McpService.setEnabled(this@MainActivity, true); showMcp()
                    // The microphone, for import_listen: asked now, while the person is here. Refused: MCP is on anyway.
                    if (!SoundIo.canListen(this@MainActivity))
                        this@MainActivity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MCP_MIC)
                }''')

patch(SRC + "MainActivity.kt", "microphone answered: start MCP again", r'''            else status.text = "Without that permission, the server can't start a hotspot. Connect to Wi-Fi instead, or tap Start to be asked again."
        }
    }''', r'''            else status.text = "Without that permission, the server can't start a hotspot. Connect to Wi-Fi instead, or tap Start to be asked again."
        }
        // Allowed or refused: MCP control starts again, with or without the microphone type, and its line says which.
        if (requestCode == REQUEST_MCP_MIC) McpService.start(this)
    }''')

def stop(msg):
    print("STOPPED: " + msg)
    print("Nothing was changed.")
    sys.exit(1)
def sha(b): return hashlib.sha256(b).hexdigest()
def bal(t): return (t.count("{") - t.count("}"), t.count("(") - t.count(")"))

GUARDS = {MANIFEST: "FOREGROUND_SERVICE_MICROPHONE", SRC + "McpService.kt": "micType", SRC + "MainActivity.kt": "REQUEST_MCP_MIC"}
files = []
for p in P:
    if p[0] not in files: files.append(p[0])
raw, out = {}, {}
for f in files:
    if not os.path.isfile(f): stop(f + " isn't here.")
    with open(f, "rb") as h: raw[f] = h.read()
    t = raw[f].decode("utf-8")
    if GUARDS[f] in t: stop(f + " already has %s in it. Has this script, or a hand edit, been applied already?" % GUARDS[f])
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
try:
    import xml.etree.ElementTree as ET
    ET.fromstring(out[MANIFEST])
except Exception as e:
    stop("the patched manifest isn't valid XML (%s): the script is wrong." % e)
print("The patched manifest is valid XML.")
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
echo "Next: a Standard Run, then :app:testDebugUnitTest. Then on the phone: turn MCP control off and on again,"
echo "read the message, answer the microphone question, and note the line under the switch."
echo "Paste all of this output, and what you saw, to the agent."
exit 0
# END OF step-c-4-mic.sh
