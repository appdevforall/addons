#!/bin/sh
# mcp-sound-tests-1phone.sh: step C's MCP sound tools on one phone, through mcp-call.sh (a curl MCP client).
#
# Run it with this phone's token exported first (Copy token, under the MCP control switch). Paste the token after
# the first line; it isn't shown:
#     stty -echo; read -r MCP_TOKEN; stty echo; export MCP_TOKEN
#     sh "/storage/emulated/0/CodeOnTheGoProjects/Share Your Apps Server Combined/mcp-sound-tests-1phone.sh"
#
# It starts the server, opens a share, then stops the server; at the end the server is left stopped.
# It asks a few questions (do you hear tones?), and asks you to talk or tap near the phone for 30 s.
# While it runs, Share Your Apps Server is off screen: so the listening tests are also the off-screen tests.
# Everything goes to the screen and to mcp-sound-tests-1phone-result.txt in this folder: paste that to the agent.
#
#  1 tools lists share_play_sound and import_listen     9 server_stop
#  2 server_start serves                                10 import_listen listens
#  3 share_start: a share waiting for a phone           11 import_start refused while listening
#  4 share_play_sound plays (you hear tones)            12 import_status follows listening to its end:
#  5 share_play_sound again: already playing               nothing_heard = the microphone works off screen
#  6 share_play_sound stop (the tones stop)             13 import_cancel stops listening
#  7 import_listen refused while serving                14 import_listen stop, nothing listening
#  8 share_stop; share_play_sound then refused          15 View logs has the [sound] lines (you check)
# Plain sh, no bash.

set -u
NAME="mcp-sound-tests-1phone.sh"

# ---- Not cut off: the last line of this file must be its END line.
if [ "$(tail -n 1 "$0" 2>/dev/null)" != "# END OF $NAME" ]; then
  echo "STOPPED: $0 is incomplete: its last line isn't \"# END OF $NAME\". Ask the agent to write it again."
  exit 2
fi

HERE="$(cd "$(dirname "$0")" && pwd)" || { echo "STOPPED: couldn't find this script's folder."; exit 2; }
cd "$HERE" || exit 2
[ -f mcp-call.sh ] || { echo "STOPPED: mcp-call.sh isn't in $HERE."; exit 2; }
command -v curl >/dev/null 2>&1 || { echo "STOPPED: curl isn't installed. In Termux: pkg install curl"; exit 2; }
if [ -z "${MCP_TOKEN:-}" ]; then
  echo "STOPPED: MCP_TOKEN isn't set, or isn't exported. Run these, pasting the token after the first line:"
  echo "    stty -echo; read -r MCP_TOKEN; stty echo; export MCP_TOKEN"
  exit 2
fi
[ -r /dev/tty ] || { echo "STOPPED: this needs a terminal, for its questions."; exit 2; }

RES="mcp-sound-tests-1phone-result.txt"
P=0; F=0; S=0; OUT=""

line() { printf '%s\n' "$1" | tee -a "$RES"; }
pass() { P=$((P+1)); line "PASS  $1"; }
fail() { F=$((F+1)); line "FAIL  $1"; }
skip() { S=$((S+1)); line "SKIP  $1"; }
# call TOOL [ARGUMENTS]: one tool call; its answer is shown, logged, and kept in $OUT.
call() {
  line ""
  line "\$ sh mcp-call.sh $*"
  OUT="$(sh ./mcp-call.sh "$@" 2>&1)"
  printf '%s\n' "$OUT" | tee -a "$RES"
}
# has REGEX: whether the last answer matches (extended regex; spaces after : allowed).
has() { printf '%s\n' "$OUT" | grep -Eq "$1"; }
# ask QUESTION: yes or no, from the terminal; the answer is logged.
ask() {
  printf '\n>>> %s [y/n] ' "$1" > /dev/tty
  read -r a < /dev/tty
  printf '%s\n' "QUESTION: $1 -> ${a:-}" >> "$RES"
  case "$a" in y|Y|yes|Yes|YES) return 0 ;; *) return 1 ;; esac
}
note() { printf '\n>>> %s\n' "$1" > /dev/tty; printf '%s\n' "NOTE: $1" >> "$RES"; }

: > "$RES"
line "mcp-sound-tests-1phone.sh, $(date)"
if ! ask "This starts the server, opens a share, then stops the server and leaves it stopped. Go on?"; then
  line "Not run: the answer was no."; exit 0
fi

# ---- 0. MCP control answers.
call server_status
if has "Couldn't start an MCP session|The request failed"; then
  line "STOPPED: MCP control didn't answer. Is it on? Is MCP_TOKEN this phone's token, from Copy token?"
  exit 1
fi

# ---- 1. The tool list.
call tools
if has 'share_play_sound' && has 'import_listen'; then pass "1 tools lists share_play_sound and import_listen"
else fail "1 tools doesn't list share_play_sound and import_listen: is this the step C build?"; fi

# ---- 2. Serving.
SERVING=no
call server_start '{"wait_seconds":25}'
if has '"state": *"serving"'; then pass "2 server_start serves"; SERVING=yes
else fail "2 server_start didn't serve (see above): tests 3 to 8 are skipped"; fi

if [ "$SERVING" = yes ]; then
  # ---- 3. A share waiting for a phone (it may first be preparing, after a publish).
  call share_start
  n=0
  while ! has '"state": *"waiting_for_phone"' && has '"state": *"preparing"' && [ $n -lt 2 ]; do
    n=$((n+1)); call share_status '{"wait_seconds":25}'
  done
  if has '"state": *"waiting_for_phone"'; then
    pass "3 share_start: a share waiting for a phone"

    # ---- 4. Tones.
    call share_play_sound
    if has '"playing": *true'; then
      if ask "Do you hear tones from this phone? (Media volume up.)"; then pass "4 share_play_sound plays, and the tones are heard"
      else fail "4 share_play_sound says it plays, but no tones were heard"; fi
    else fail "4 share_play_sound didn't play (see above)"; fi

    # ---- 5. Again, while playing.
    call share_play_sound
    if has '"already_playing": *true'; then pass "5 share_play_sound while playing: already_playing"
    else fail "5 share_play_sound while playing didn't say already_playing"; fi

    # ---- 6. Stop.
    call share_play_sound '{"stop":true}'
    if has '"stopped": *true'; then
      if ask "Have the tones stopped?"; then pass "6 share_play_sound stop: stopped"
      else fail "6 share_play_sound stop said stopped, but the tones carried on"; fi
    else fail "6 share_play_sound stop didn't stop (were the tones already over? see above)"; fi

    # ---- 7. Listening is refused while serving.
    call import_listen
    if has 'serving a store'; then pass "7 import_listen refused while serving"
    else fail "7 import_listen wasn't refused while serving"; import_cancel_needed=yes; fi
    [ "${import_cancel_needed:-no}" = yes ] && call import_cancel

    # ---- 8. No share, no sound.
    call share_stop
    call share_play_sound
    if has 'only works while a share is waiting|no_share_waiting'; then pass "8 share_play_sound refused with no share waiting"
    else fail "8 share_play_sound wasn't refused with no share waiting"; fi
  else
    fail "3 share_start: no share waiting for a phone (see above): tests 4 to 8 are skipped"
    for t in 4 5 6 7 8; do skip "$t (no share)"; done
    call share_stop
  fi
else
  for t in 3 4 5 6 7 8; do skip "$t (not serving)"; done
fi

# ---- 9. Stop serving: an import, or listening, needs a stopped server.
call server_stop '{"wait_seconds":25}'
if has '"state": *"(stopped|failed)"'; then pass "9 server_stop: not serving"
else fail "9 server_stop: still serving or stopping (see above)"; fi

# ---- 10 to 12. Listening, with the app off screen.
note "Next, the phone listens for 30 s. TALK OR TAP NEAR THE PHONE the whole time, so the microphone hears something. Don't play any share code."
printf '>>> Press Enter to start listening. ' > /dev/tty; read -r _ < /dev/tty
call import_listen
if has '"phase": *"listening"'; then
  pass "10 import_listen listens"

  call import_start '{"code":"LS-0000-0000"}'
  if has 'import_listen is still listening'; then pass "11 import_start refused while listening"
  else fail "11 import_start wasn't refused while listening"; fi

  n=0
  while [ $n -lt 3 ]; do
    n=$((n+1)); call import_status '{"wait_seconds":25}'
    has '"phase": *"listening"' || break
  done
  if has '"phase": *"nothing_heard"'; then
    pass "12 listening ended nothing_heard: the microphone works with the app off screen"
  elif has 'heard only silence'; then
    if ask "Were you talking or tapping near the phone during the 30 s?"; then
      fail "12 only silence while you made sounds: Android blocked the microphone with the app off screen"
    else
      fail "12 only silence, but no sounds were made: run it again, making sounds"
    fi
  elif has '"phase": *"listening"'; then fail "12 still listening after 3 calls of 25 s"
  elif has '"phase": *"(checking|joining|import_started)"'; then fail "12 heard a share code: was another phone playing one? (see above)"
  else fail "12 listening ended some other way (see above)"; fi
else
  fail "10 import_listen didn't listen (see above): tests 11 and 12 are skipped"
  skip "11 (not listening)"; skip "12 (not listening)"
fi

# ---- 13. import_cancel stops listening.
call import_listen
if has '"phase": *"listening"'; then
  call import_cancel
  if has 'Stopped listening'; then
    call import_status
    if has '"phase": *"stopped"'; then pass "13 import_cancel stops listening; import_status shows stopped"
    else fail "13 import_cancel said it stopped, but import_status doesn't show stopped"; fi
  else fail "13 import_cancel didn't stop listening"; call import_listen '{"stop":true}'; fi
else skip "13 (import_listen didn't listen)"; fi

# ---- 14. Stop with nothing listening.
call import_listen '{"stop":true}'
if has 'Nothing was listening'; then pass "14 import_listen stop with nothing listening: says so"
else fail "14 import_listen stop with nothing listening didn't say so"; fi

# ---- 15. The log, which only the app can show (its folder is private).
note "Open Share Your Apps Server, tap View logs, and look for [sound] lines from just now: 'played ... for an MCP client', 'listening for a share code, for an MCP client', 'heard nothing in 30 s', 'stopped listening: by an MCP client'."
if ask "Are those [sound] lines in View logs?"; then pass "15 View logs has the [sound] lines"
else fail "15 some [sound] lines are missing from View logs: tell the agent which"; fi

line ""
line "Done: $P passed, $F failed, $S skipped. The server is left stopped: tap Start, or sh mcp-call.sh server_start."
line "Results: $HERE/$RES  Paste that file, or this screen, to the agent."
exit 0
# END OF mcp-sound-tests-1phone.sh
