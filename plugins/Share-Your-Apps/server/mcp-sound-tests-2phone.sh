#!/bin/sh
# mcp-sound-tests-2phone.sh: step C's MCP sound tools on two phones, through mcp-call.sh (a curl MCP client).
# One phone shares, the other imports: run this script on each, with its role.
#
# Before running, on BOTH phones:
#   - the combined build installed; MCP control on; the microphone allowed (the line under the switch has no
#     "Microphone" warning);
#   - this script and mcp-call.sh in the same folder (copy both to the other phone; any folder works);
#   - curl and python3 installed (in Termux: pkg install curl python);
#   - that phone's OWN token exported (Copy token, under its MCP control switch), pasted after the first line:
#         stty -echo; read -r MCP_TOKEN; stty echo; export MCP_TOKEN
#
# Then, in this order:
#   1. Sharing phone:   sh mcp-sound-tests-2phone.sh share    (runs until it says to start the import side)
#   2. Importing phone: sh mcp-sound-tests-2phone.sh import   (press Enter there to listen)
#   3. Sharing phone:   press Enter at once, to play the tones. Hold the phones close, media volume up.
#   Then follow both screens: a person taps the notification and Allow on the sharing phone.
#
# Run it twice to cover both ways of joining:
#   a. both phones on the same ordinary Wi-Fi (the import reaches the sharing phone directly);
#   b. the sharing phone on its own hotspot (Wi-Fi on but not joined to a network), the importing phone on another
#      network: Android should ask the importing phone to Connect to the hotspot (Android 10 and later).
# Instead of the share side, the sharing phone's person may open Share this store... and tap Play sound; then run
# only the import side.
#
# The share side leaves its server serving; the import side leaves its server stopped, with the imported copy.
# An imported copy already on the importing phone may be replaced.
# Results go to the screen and to mcp-sound-tests-2phone-<role>-result.txt beside this script: paste both.
#
# Share side:  S1 server_start  S2 share_start  S3 share_play_sound  S4 a phone connects  S5 the tones stop by
#              themselves  S6 the "asks for a copy" notification  S7 the copy is sent  S8 View logs
# Import side: I1 server_stop  I2 import_listen  I3 the code is heard  I4 the Connect prompt (hotspot only)
#              I5 the import starts  I6 the check code matches  I7 the import is done  I8 copy_on_phone
#              I9 on_owners_network is right  I10 View logs
# Plain sh, no bash.

set -u
NAME="mcp-sound-tests-2phone.sh"

# ---- Not cut off: the last line of this file must be its END line.
if [ "$(tail -n 1 "$0" 2>/dev/null)" != "# END OF $NAME" ]; then
  echo "STOPPED: $0 is incomplete: its last line isn't \"# END OF $NAME\". Ask the agent to write it again."
  exit 2
fi

ROLE="${1:-}"
case "$ROLE" in
  share|import) ;;
  *) echo "Usage: sh $NAME share     (on the sharing phone, first)"
     echo "       sh $NAME import    (on the importing phone)"
     exit 2 ;;
esac

HERE="$(cd "$(dirname "$0")" && pwd)" || { echo "STOPPED: couldn't find this script's folder."; exit 2; }
cd "$HERE" || exit 2
[ -f mcp-call.sh ] || { echo "STOPPED: mcp-call.sh isn't in $HERE. Copy it beside this script."; exit 2; }
command -v curl >/dev/null 2>&1 || { echo "STOPPED: curl isn't installed. In Termux: pkg install curl"; exit 2; }
PY=""
if command -v python3 >/dev/null 2>&1; then PY=python3
elif command -v python >/dev/null 2>&1; then PY=python
fi
[ -n "$PY" ] || { echo "STOPPED: python3 isn't installed. In Termux: pkg install python"; exit 2; }
if [ -z "${MCP_TOKEN:-}" ]; then
  echo "STOPPED: MCP_TOKEN isn't set, or isn't exported. Run these, pasting this phone's token after the first line:"
  echo "    stty -echo; read -r MCP_TOKEN; stty echo; export MCP_TOKEN"
  exit 2
fi
[ -r /dev/tty ] || { echo "STOPPED: this needs a terminal, for its questions."; exit 2; }

RES="mcp-sound-tests-2phone-$ROLE-result.txt"
PASSN=0; FAILN=0; SKIPN=0; OUT=""

line() { printf '%s\n' "$1" | tee -a "$RES"; }
pass() { PASSN=$((PASSN+1)); line "PASS  $1"; }
fail() { FAILN=$((FAILN+1)); line "FAIL  $1"; }
skip() { SKIPN=$((SKIPN+1)); line "SKIP  $1"; }
# call TOOL [ARGUMENTS]: one tool call; its answer is shown, logged, and kept in $OUT.
call() {
  line ""
  line "\$ sh mcp-call.sh $*"
  OUT="$(sh ./mcp-call.sh "$@" 2>&1)"
  printf '%s\n' "$OUT" | tee -a "$RES"
}
has() { printf '%s\n' "$OUT" | grep -Eq "$1"; }
# jget a.b: a field of the last answer, if it was JSON; empty otherwise. true/false for booleans.
jget() {
  printf '%s' "$OUT" | "$PY" -c '
import json, sys
try:
    d = json.loads(sys.stdin.read())
except Exception:
    sys.exit(0)
for k in sys.argv[1].split("."):
    if isinstance(d, dict) and k in d: d = d[k]
    else: sys.exit(0)
if isinstance(d, bool): print("true" if d else "false")
elif d is not None: print(d)
' "$1"
}
ask() {
  printf '\n>>> %s [y/n] ' "$1" > /dev/tty
  read -r a < /dev/tty
  printf '%s\n' "QUESTION: $1 -> ${a:-}" >> "$RES"
  case "$a" in y|Y|yes|Yes|YES) return 0 ;; *) return 1 ;; esac
}
note() { printf '\n>>> %s\n' "$1" > /dev/tty; printf '%s\n' "NOTE: $1" >> "$RES"; }
enter() {
  printf '\n>>> %s\n>>> Press Enter when ready. ' "$1" > /dev/tty
  read -r _ < /dev/tty
  printf '%s\n' "WAITED: $1" >> "$RES"
}
finish() {
  line ""
  line "Done ($ROLE side): $PASSN passed, $FAILN failed, $SKIPN skipped."
  line "$1"
  line "Results: $HERE/$RES  Paste that file, and the other phone's, to the agent."
  exit 0
}
mcp_ok() {
  call server_status
  if has "Couldn't start an MCP session|The request failed|No token given"; then
    line "STOPPED: MCP control didn't answer. Is it on? Is MCP_TOKEN this phone's own token, from Copy token?"
    exit 1
  fi
}

# ======================================================================== the sharing phone
share_side() {
  ask "This phone SHARES: it starts its server, opens a share and plays its code. The server is left serving. Go on?" \
    || { line "Not run: the answer was no."; exit 0; }
  mcp_ok

  call server_start '{"wait_seconds":25}'
  [ "$(jget state)" = serving ] || { fail "S1 server_start didn't serve (see above)"; finish "Stopped early."; }
  pass "S1 server_start serves, on network kind $(jget network.kind)"

  call share_start
  n=0
  while [ "$(jget state)" = preparing ] && [ $n -lt 3 ]; do n=$((n+1)); call share_status '{"wait_seconds":25}'; done
  [ "$(jget state)" = waiting_for_phone ] || { fail "S2 no share waiting for a phone (see above)"; finish "Stopped early."; }
  pass "S2 share_start: share $(jget code) waiting for a phone"

  OWN=unknown; MOVED=no; try=0
  while [ $try -lt 3 ] && [ "$MOVED" = no ]; do
    try=$((try+1))
    enter "On the importing phone, run: sh $NAME import, and press Enter there to start listening. Then press Enter here AT ONCE to play. Hold the phones close; media volume up."
    call share_play_sound
    [ "$(jget playing)" = true ] || { fail "S3 share_play_sound didn't play (see above)"; finish "Stopped early."; }
    if [ $try = 1 ]; then OWN="$(jget own_hotspot)"; pass "S3 share_play_sound plays (own_hotspot $OWN)"; fi
    n=0
    while [ $n -lt 3 ]; do
      n=$((n+1)); call share_status '{"wait_seconds":25}'
      [ "$(jget state)" = waiting_for_phone ] || { MOVED=yes; break; }
    done
    if [ "$MOVED" = no ]; then
      call share_play_sound '{"stop":true}'
      ask "No phone has connected yet. Try again? (Start listening on the importing phone again first.)" || break
    fi
  done
  [ "$MOVED" = yes ] || { fail "S4 no phone connected"; call share_stop; finish "The share was closed; the server is still serving."; }
  pass "S4 a phone connected (share state $(jget state))"

  # The watcher looks once a second: give it a moment, then ask to stop. Nothing should be playing any more.
  sleep 3
  call share_play_sound '{"stop":true}'
  if [ "$(jget stopped)" = false ]; then pass "S5 the tones stopped by themselves once the phone connected"
  else fail "S5 the tones were still playing after the phone connected (stopped now)"; fi

  call share_status
  n=0
  while [ "$(jget state)" = phone_connecting ] && [ $n -lt 4 ]; do n=$((n+1)); call share_status '{"wait_seconds":25}'; done
  if [ "$(jget state)" = waiting_for_allow ]; then
    note "A notification should say \"$(jget phone) asks for a copy of $(jget store)\". Tap it, check the four digits match the importing phone's, choose an expiry, and tap Allow."
    if ask "Did the notification appear, and open the Allow screen?"; then pass "S6 the \"asks for a copy\" notification opened the Allow screen"
    else fail "S6 the \"asks for a copy\" notification didn't appear, or didn't open the Allow screen"; fi
  else
    fail "S6 the share didn't reach waiting_for_allow (state $(jget state))"
  fi

  n=0; ST=""
  while [ $n -lt 24 ]; do
    n=$((n+1)); call share_status '{"wait_seconds":25}'
    ST="$(jget state)"
    case "$ST" in done|denied|expired|closed|transfer_failed|not_serving) break ;; esac
  done
  if [ "$ST" = done ]; then pass "S7 the copy was sent: done"
  else fail "S7 the share ended $ST, not done (see above)"; fi

  note "Open Share Your Apps Server, tap View logs, and look for: \"[sound] played the code ..., for an MCP client\" and \"[share] allowed ... a copy\"."
  if ask "Are both lines in View logs?"; then pass "S8 View logs has the played and allowed lines"
  else fail "S8 a line is missing from View logs: tell the agent which"; fi
  finish "The server is left serving."
}

# ======================================================================== the importing phone
import_side() {
  ask "This phone IMPORTS: it stops its server, listens for the sharing phone's tones and imports its store. A copy already on this phone may be replaced. Go on?" \
    || { line "Not run: the answer was no."; exit 0; }
  mcp_ok

  call server_stop '{"wait_seconds":25}'
  case "$(jget state)" in
    stopped|failed) pass "I1 server_stop: not serving" ;;
    *) fail "I1 server_stop: still serving or stopping (see above)"; finish "Stopped early." ;;
  esac

  HEARD=no; JOINED=no; LP=""; try=0
  while [ $try -lt 3 ] && [ "$HEARD" = no ]; do
    try=$((try+1))
    enter "Get the sharing phone to its \"Press Enter\" to play. Press Enter HERE to start listening, then press Enter THERE at once."
    call import_listen
    [ "$(jget phase)" = listening ] || { fail "I2 import_listen didn't listen (see above)"; finish "Stopped early."; }
    [ $try = 1 ] && pass "I2 import_listen listens"
    n=0
    while [ $n -lt 8 ]; do
      n=$((n+1)); call import_status '{"wait_seconds":25}'
      LP="$(jget listen.phase)"
      case "$LP" in
        listening|checking) ;;
        joining)
          if [ "$JOINED" = no ]; then
            JOINED=yes
            note "Android should now ask to connect to the sharing phone's hotspot, $(jget listen.owner_network). Tap Connect within 30 s."
          fi ;;
        *) break ;;
      esac
    done
    case "$LP" in
      import_started) HEARD=yes ;;
      nothing_heard) line "Nothing heard this time."; ask "Try listening again?" || break ;;
      *) break ;;
    esac
  done
  if [ "$HEARD" = no ]; then
    fail "I3 the code wasn't heard, or the import didn't start: listen phase $LP: $(jget listen.message)"
    finish "Stopped early. The server is left stopped."
  fi
  pass "I3 heard the code $(jget listen.code)"
  if [ "$JOINED" = yes ]; then
    if ask "Did Android's Connect prompt appear, and did you tap Connect?"; then pass "I4 joined the sharing phone's hotspot after Connect"
    else fail "I4 the import started, but the Connect prompt wasn't seen: tell the agent what you saw"; fi
  else
    skip "I4 (no hotspot to join: this phone reached the sharing phone on its own network)"
  fi
  pass "I5 the import started by itself"

  # Follow the import: the person checks the digits and taps Allow on the sharing phone.
  ASKED=no; QSHOWN=""; PH=""; n=0
  while [ $n -lt 30 ]; do
    n=$((n+1))
    PH="$(jget phase)"
    case "$PH" in done|failed|denied|cancelled) break ;; esac
    CHECK="$(jget check_code)"
    if [ "$ASKED" = no ] && [ -n "$CHECK" ]; then
      ASKED=yes
      note "This phone's check code is $CHECK. On the sharing phone, tap the \"asks for a copy\" notification."
      if ask "Does the sharing phone's Allow screen show the same digits, $CHECK?"; then pass "I6 the check code matches the sharing phone's"
      else fail "I6 the check codes don't match: tap Deny on the sharing phone"; fi
      note "Now tap Allow on the sharing phone, if the digits matched."
    fi
    if [ "$(jget state)" = person_needed ] && [ "$QSHOWN" != "$(jget question.title)" ]; then
      QSHOWN="$(jget question.title)"
      note "A person is needed on THIS phone: $(jget person_needed)"
    fi
    call import_status '{"wait_seconds":25}'
  done
  [ "$ASKED" = yes ] || skip "I6 (no check code was seen)"
  if [ "$PH" != done ]; then
    fail "I7 the import ended $PH, not done: $(jget message)"
    finish "The server is left stopped."
  fi
  pass "I7 imported $(jget store): $(jget files) files, $(jget total) bytes"

  if [ "$(jget copy_on_phone)" = true ]; then pass "I8 copy_on_phone: true"
  else fail "I8 copy_on_phone isn't true right after the import"; fi

  OWNERS="$(jget on_owners_network)"
  if ask "Is this phone connected to the sharing phone's OWN hotspot right now? (Look in Wi-Fi settings.)"; then EXP=true; else EXP=false; fi
  if [ "$OWNERS" = "$EXP" ]; then pass "I9 on_owners_network is $OWNERS, which matches Wi-Fi settings"
  else fail "I9 on_owners_network is $OWNERS, but Wi-Fi settings say $EXP (open item 8: tell the agent both phones' networks)"; fi

  note "Open Share Your Apps Server, tap View logs, and look for: \"[sound] listening for a share code, for an MCP client\" and \"[sound] heard code $(jget listen.code) ...\"."
  if ask "Are both lines in View logs?"; then pass "I10 View logs has the listening and heard lines"
  else fail "I10 a line is missing from View logs: tell the agent which"; fi
  finish "The server is left stopped. To serve the copy: Serve this store, or sh mcp-call.sh server_start '{\"store\":\"copy\"}'."
}

: > "$RES"
line "$NAME $ROLE, $(date)"
if [ "$ROLE" = share ]; then share_side; else import_side; fi
exit 0
# END OF mcp-sound-tests-2phone.sh
