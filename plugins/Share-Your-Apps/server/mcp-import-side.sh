#!/bin/sh
# Project A second-phone tests on the IMPORTING phone (phone 2). Tests from mcp-session-state.md:
#   sh mcp-import-side.sh 6        a real MCP import; the check code is compared with phone 1
#   sh mcp-import-side.sh 9mcp     Cancel in the import notification, for an import MCP started
#   sh mcp-import-side.sh 9person  the same, for an import a person started on the import screen
#   sh mcp-import-side.sh 11       server_start and import_start with the app off screen
# Keep the results:   sh mcp-import-side.sh 6 2>&1 | tee result-t6.txt
# Needs MCP_TOKEN (this phone's token). Optional: MCP_PHONE_NAME, the name phone 1's owner sees.
# Phone 1 runs mcp-share-side.sh, which opens a share and shows its code.
set -u
TEST="${1:-}"
case "$TEST" in 6|9mcp|9person|11) ;; *) echo "Say which test: sh mcp-import-side.sh 6 | 9mcp | 9person | 11"; exit 2 ;; esac
. "$(dirname "$0")/mcp-lib.sh"
echo "Test $TEST on the importing phone, $(date)"

# The imported copy on this phone, to check a cancelled import leaves it as it was.
copy_sig() {
  call store_info
  if has '"imported_copy":null'; then echo "none"; else echo "$(field store_id) $(field created) $(num bytes)"; fi
}

# An import needs the server stopped and no import running.
ensure_stopped() {
  call server_status
  st="$(field state)"
  case "$st" in
    serving|starting|paused)
      info "The server is $st on this phone; stopping it (an import needs it stopped)."
      call server_stop '{"wait_seconds":20}'
      st="$(field state)"
      if [ "$st" = stopped ] || [ "$st" = failed ]; then pass "server_stop stopped the server"
      else fail "server_stop: the server is $st"; show; return 1; fi ;;
  esac
  call import_status
  if has '"busy":true'; then
    echo "An import is already running on this phone ($(field phase)). End it on the import screen, then run this again."
    return 1
  fi
  return 0
}

read_code() {
  CODE_ARGS=
  while [ -z "$CODE_ARGS" ]; do
    ask "Type the code shown on phone 1 (LS-XXXX-XXXX), or its link, then press Enter:"
    r="$(printf '%s' "$a_ans" | tr -d ' \r')"
    case "$r" in
      localstore-share://*) CODE_ARGS="\"link\":\"$(printf '%s' "$r" | tr -cd 'A-Za-z0-9:/.-')\"" ;;
      ?*) CODE_ARGS="\"code\":\"$(printf '%s' "$r" | tr -cd 'A-Za-z0-9-')\"" ;;
    esac
  done
}

start_import() {
  s_args="{$CODE_ARGS"
  if [ -n "${MCP_PHONE_NAME:-}" ]; then
    s_args="$s_args,\"phone_name\":\"$(printf '%s' "$MCP_PHONE_NAME" | tr -d '"\\')\""
  fi
  call import_start "$s_args}"
}

# follow_import STOP_PHASES MAX_SECONDS: follows import_status, printing each change, until the phase
# is one of STOP_PHASES or MAX_SECONDS pass. Sets PHASE and CHECK.
follow_import() {
  f_stop=" $1 "; f_end=$(( $(date +%s) + $2 )); f_last=; f_wait=0
  while :; do
    call import_status "{\"wait_seconds\":$f_wait}"
    f_wait=20
    if is_error; then info "import_status failed:"; show; PHASE=; return 1; fi
    PHASE="$(field phase)"; CHECK="$(field check_code)"
    if needs_person; then f_q=yes; else f_q=; fi
    f_now="$PHASE|$CHECK|$f_q"
    if [ "$f_now" != "$f_last" ]; then
      info "import: $PHASE, started by $(field started_by): $(field message)"
      if [ -n "$f_q" ]; then info "person needed: $(field person_needed)"; fi
      f_last="$f_now"
    fi
    case "$f_stop" in *" $PHASE "*) return 0 ;; esac
    if [ "$(date +%s)" -ge "$f_end" ]; then info "still $PHASE after $2 seconds"; return 1; fi
  done
}

show_check() {
  echo
  echo "   =========================================="
  echo "     Check code on this phone:  ${CHECK:-(none yet)}"
  echo "   =========================================="
}

# After the import reaches waiting: a person taps Cancel in this phone's import notification.
cancel_step() {
  echo ">>> Don't tap Allow on phone 1."
  echo ">>> On THIS phone, pull down the notifications and tap Cancel in the import notification."
  follow_import "cancelled failed denied done idle" 300
  if [ "$PHASE" = cancelled ]; then pass "Cancel in the notification cancelled $1 import"
  else fail "Cancel in the notification ($1 import): it ended as ${PHASE:-unknown}"; fi
  by_eye "the import notification went away after Cancel" "Has the import notification gone from this phone?"
  after="$(copy_sig)"
  if [ "$after" = "$BEFORE" ]; then pass "the copy already on this phone is unchanged ($after)"
  else fail "the copy on this phone changed: before $BEFORE, after $after"; fi
  echo ">>> On phone 1, answer the share script's question to close the share."
}

if [ "$TEST" = 11 ]; then
  echo ">>> Test 11: Local Store Server must stay off screen. You're in the terminal, so it is."
  echo ">>> Don't open the app until this script ends."
  wait_enter "Ready? The script waits 15 seconds, then starts."
  sleep 15
fi

if ! start_session; then
  if [ "$TEST" = 11 ]; then fail "MCP control answered with the app off screen"; summary; fi
  exit 1
fi

case "$TEST" in
6)
  ensure_stopped || exit 1
  read_code
  start_import
  if is_error; then fail "import_start started the import"; show; summary; fi
  pass "import_start started the import"
  if [ "$(field started_by)" = mcp ]; then pass "import_status says MCP started it"
  else fail "started_by is \"$(field started_by)\", not mcp"; fi
  follow_import "waiting done failed denied cancelled" 180
  if [ "$PHASE" != waiting ]; then fail "the import reached waiting (it is ${PHASE:-unknown})"; summary; fi
  show_check
  if [ -n "$CHECK" ]; then
    by_eye "the check code matches phone 1" "Does phone 1 show the same check code, $CHECK?"
  else
    fail "import_status gave a check code while waiting"
  fi
  echo ">>> Now on phone 1: choose an expiry and tap Allow."
  follow_import "done failed denied cancelled" 600
  if [ "$PHASE" = done ]; then pass "the import finished"; else fail "the import ended as ${PHASE:-unknown}"; summary; fi
  STORE="$(field store)"
  if [ -n "$(field note)" ]; then info "note: $(field note)"; fi
  call store_info
  if has '"imported_copy":null'; then fail "store_info shows the imported copy"
  elif [ -n "$STORE" ] && has "\"name\":\"$STORE\""; then pass "store_info shows the imported copy of $STORE"
  else fail "store_info's imported copy is $STORE"; show; fi
  ;;
9mcp)
  ensure_stopped || exit 1
  BEFORE="$(copy_sig)"
  info "imported copy before: $BEFORE"
  read_code
  start_import
  if is_error; then fail "import_start started the import"; show; summary; fi
  pass "import_start started the import"
  follow_import "waiting done failed denied cancelled" 180
  if [ "$PHASE" != waiting ]; then fail "the import reached waiting (it is ${PHASE:-unknown})"; summary; fi
  show_check
  cancel_step "an MCP"
  ;;
9person)
  ensure_stopped || exit 1
  BEFORE="$(copy_sig)"
  info "imported copy before: $BEFORE"
  echo ">>> On this phone: open Local Store Server, tap Import a store…, type phone 1's code and tap Continue."
  echo ">>> When it says it's waiting for phone 1's owner, come back to this terminal."
  wait_enter "Back here?"
  call import_status
  if ! has '"busy":true'; then fail "a person's import is running (phase $(field phase))"; show; summary; fi
  if [ "$(field started_by)" = person ]; then pass "import_status says a person started it"
  else fail "started_by is \"$(field started_by)\", not person"; fi
  follow_import "waiting done failed denied cancelled" 180
  if [ "$PHASE" != waiting ]; then fail "the import reached waiting (it is ${PHASE:-unknown})"; summary; fi
  show_check
  cancel_step "a person's"
  ;;
11)
  pass "MCP control answered with the app off screen"
  call server_status
  case "$(field state)" in
    serving|starting|paused) call server_stop '{"wait_seconds":20}'; info "stopped the server first: $(field state)" ;;
  esac
  call server_start '{"wait_seconds":25}'
  st="$(field state)"
  if ! is_error && [ "$st" = starting ]; then sleep 10; call server_status; st="$(field state)"; fi
  if needs_person; then
    pass "server_start off screen: reports person needed"; info "$(field person_needed)"
  elif ! is_error && [ "$st" = serving ]; then
    pass "server_start off screen: serving"
    call server_stop '{"wait_seconds":20}'
    if [ "$(field state)" = stopped ]; then pass "server_stop off screen"; else fail "server_stop off screen: $(field state)"; show; fi
  else
    fail "server_start off screen: neither serving nor person needed"; show
  fi
  ensure_stopped || summary
  echo ">>> On phone 1, open a new share and read its code."
  read_code
  start_import
  if needs_person; then
    pass "import_start off screen: reports person needed"; info "$(field person_needed)"
  elif is_error; then
    fail "import_start off screen: neither started nor person needed"; show
  else
    pass "import_start off screen: the import started"
    follow_import "waiting done failed denied cancelled" 180
    show_check
    echo ">>> On phone 1: check the code is $CHECK, choose an expiry and tap Allow."
    follow_import "done failed denied cancelled" 600
    if [ "$PHASE" = done ]; then pass "the import finished with the app off screen"
    else fail "the import with the app off screen ended as ${PHASE:-unknown}"; fi
  fi
  ;;
esac
summary
