#!/bin/sh
# Unpacks the project A second-phone test scripts (tests 6, 9, 10 and 11) next to this file:
#   mcp-lib.sh           shared parts, read by the other two
#   mcp-import-side.sh   for the importing phone (phone 2): tests 6, 9 and 11
#   mcp-share-side.sh    for the sharing phone (phone 1): opens shares, and test 10
# Run once on each phone:   sh mcp-tests-bundle.sh
# It never overwrites: if one of the three files is already here, it stops and unpacks nothing.
set -u
cd "$(dirname "$0")" || exit 2
for f in mcp-lib.sh mcp-import-side.sh mcp-share-side.sh; do
  if [ -e "$f" ]; then
    echo "$f is already here, so nothing was unpacked. Move or delete it, then run this again."
    exit 2
  fi
done

cat > mcp-lib.sh <<'MCP_LIB_EOF'
# Shared parts of the MCP second-phone tests. Read by mcp-import-side.sh and mcp-share-side.sh;
# not run on its own. Plain sh: no bash, no jq.
URL="${MCP_URL:-http://127.0.0.1:6176/mcp}"
command -v curl >/dev/null 2>&1 || { echo "curl isn't installed."; exit 2; }
if [ -z "${MCP_TOKEN:-}" ]; then
  echo "MCP_TOKEN isn't set. In the app, tap Copy token, then run:  export MCP_TOKEN='<paste here>'"
  echo "and run this again. Each phone has its own token."
  exit 2
fi
if ! (exec < /dev/tty) 2>/dev/null; then echo "These tests ask questions, so they need a terminal."; exit 2; fi
umask 077
WORK="$(mktemp -d "${TMPDIR:-/tmp}/mcp-2p.XXXXXX")" || { echo "Couldn't make a temporary folder."; exit 2; }
trap 'end_session; rm -rf "$WORK"' EXIT
trap 'exit 130' INT TERM
# The token goes to curl from a private file: never on its command line, never printed.
printf 'Authorization: Bearer %s\n' "$MCP_TOKEN" > "$WORK/auth"
PASS=0; FAIL=0; CALL_ID=10; HTTP=000; SESSION=; PHASE=; CHECK=; a_ans=

pass() { PASS=$((PASS + 1)); echo "PASS  $1"; }
fail() { FAIL=$((FAIL + 1)); echo "FAIL  $1"; }
info() { echo "      $1"; }
show() {
  echo "      reply (HTTP $HTTP): $(head -c 1500 "$WORK/body")"
  if [ -s "$WORK/err" ]; then echo "      curl: $(cat "$WORK/err")"; fi
}

# Questions use the terminal itself, so  | tee result.txt  can't hide them. The answers are
# recorded as PASS or FAIL lines, which do go to the result file.
confirm() {
  while :; do
    printf '\n>>> %s [y/n] ' "$1" > /dev/tty
    read -r c_ans < /dev/tty || return 1
    case "$c_ans" in y|Y|yes|Yes|YES) return 0 ;; n|N|no|No|NO) return 1 ;; esac
  done
}
by_eye() { if confirm "$2"; then pass "$1"; else fail "$1"; fi; }
ask() { printf '\n>>> %s ' "$1" > /dev/tty; read -r a_ans < /dev/tty || a_ans=; }
wait_enter() { printf '\n>>> %s (press Enter) ' "$1" > /dev/tty; read -r w_ans < /dev/tty || true; }

# post BODY: one request in the session; the reply is left in $WORK/body, its status in $HTTP.
post() {
  printf '%s' "$1" > "$WORK/call"
  : > "$WORK/body"; : > "$WORK/head"; : > "$WORK/err"
  HTTP="$(curl -sS -X POST -o "$WORK/body" -D "$WORK/head" -w '%{http_code}' --max-time 45 \
    -H "@$WORK/auth" -H "@$WORK/session" -H 'Content-Type: application/json' \
    --data-binary "@$WORK/call" "$URL" 2>"$WORK/err")"
  [ -n "$HTTP" ] || HTTP=000
}
# call TOOL [ARGS-JSON]
call() {
  CALL_ID=$((CALL_ID + 1))
  c_args="${2:-}"; [ -n "$c_args" ] || c_args='{}'
  post "$(printf '{"jsonrpc":"2.0","id":%s,"method":"tools/call","params":{"name":"%s","arguments":%s}}' "$CALL_ID" "$1" "$c_args")"
}

start_session() {
  : > "$WORK/session"
  post '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"two-phone-test","version":"1"}}}'
  SESSION="$(grep -i '^mcp-session-id:' "$WORK/head" | head -n 1 | cut -d: -f2- | tr -d ' \r')"
  if [ "$HTTP" != 200 ] || [ -z "$SESSION" ]; then
    echo "MCP control didn't answer at $URL."
    echo "Check that MCP control is on in Local Store Server, and that MCP_TOKEN is this phone's token."
    show
    return 1
  fi
  printf 'Mcp-Session-Id: %s\nMCP-Protocol-Version: 2025-06-18\n' "$SESSION" > "$WORK/session"
  post '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  return 0
}
end_session() {
  if [ -n "$SESSION" ] && [ -d "$WORK" ]; then
    curl -sS -X DELETE -o /dev/null --max-time 5 -H "@$WORK/auth" -H "@$WORK/session" "$URL" 2>/dev/null || true
    SESSION=
  fi
}

# Reading the last reply. field NAME: a string value (empty for null). num NAME: a number.
field() { sed -n 's/.*"'"$1"'":"\([^"]*\)".*/\1/p' "$WORK/body" | head -n 1 | sed 's#\\/#/#g'; }
num() { sed -n 's/.*"'"$1"'":\([0-9][0-9]*\).*/\1/p' "$WORK/body" | head -n 1; }
has() { grep -qF -- "$1" "$WORK/body"; }
is_error() { [ "$HTTP" != 200 ] || has '"isError":true' || has '"error":{'; }
needs_person() { has 'person_needed' || has 'A person is needed'; }

summary() {
  echo
  echo "$PASS passed, $FAIL failed."
  if [ "$FAIL" = 0 ]; then echo "All checks passed."; exit 0; fi
  echo "Paste the FAIL lines into the chat."
  exit 1
}
MCP_LIB_EOF

cat > mcp-import-side.sh <<'MCP_IMPORT_EOF'
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
MCP_IMPORT_EOF

cat > mcp-share-side.sh <<'MCP_SHARE_EOF'
#!/bin/sh
# Project A second-phone tests on the SHARING phone (phone 1). Opens shares with MCP for the importing
# phone's tests (6, 9, 11) and checks test 10: the "asks for a copy" notification for a share MCP opened.
#   sh mcp-share-side.sh 2>&1 | tee share-result.txt
# Needs MCP_TOKEN (this phone's token), and this phone's own store published.
set -u
. "$(dirname "$0")/mcp-lib.sh"
echo "Sharing phone, $(date)"
start_session || exit 1

call server_status
if has '"serving":"copy"'; then
  echo "This phone is serving an imported copy, which can't be shared."
  echo "Tap Stop in the app, then run this again: it starts this phone's own store."
  exit 1
fi
if [ "$(field state)" != serving ]; then
  info "The server isn't serving; starting it with this phone's own store."
  call server_start '{"store":"own","wait_seconds":25}'
  if [ "$(field state)" = serving ]; then pass "server_start serves this phone's own store"
  else fail "server_start: $(field state)"; show; summary; fi
fi

T10=0
while :; do
  call share_start
  if is_error; then fail "share_start opened a share"; show; break; fi
  n=0
  while [ "$(field state)" = preparing ] && [ "$n" -lt 4 ]; do
    info "the store is being prepared; waiting for the code…"
    call share_status '{"wait_seconds":20}'
    n=$((n + 1))
  done
  CODE="$(field code)"; LINK="$(field link)"
  if [ -z "$CODE" ]; then fail "share_start gave a code"; show; break; fi
  pass "share_start gave a code"
  if has '"already_open":true'; then info "a share was already open; this is that share"; fi
  if [ -n "$(field warning)" ]; then info "warning: $(field warning)"; fi
  echo
  echo "   =========================================="
  echo "     Code:  $CODE"
  echo "   =========================================="
  echo "     Link:  $LINK"
  echo "   Type the code into mcp-import-side.sh on phone 2. It works once, for 10 minutes."

  LAST=; S=; ENTERED=0; END=$(( $(date +%s) + 720 ))
  while :; do
    call share_status '{"wait_seconds":20}'
    if is_error; then info "share_status failed:"; show; S=; break; fi
    S="$(field state)"
    if [ "$S" != "$LAST" ]; then
      info "share: $S $(field phone)"
      LAST="$S"
      if [ "$S" = waiting_for_allow ]; then
        CHECK="$(field check_code)"; PHONE="$(field phone)"; STORE="$(field store)"
        echo
        echo "   =========================================="
        echo "     Check code:  $CHECK   (compare with phone 2)"
        echo "   =========================================="
        if has 'Notifications are off'; then info "notifications are off for this app on this phone"; fi
        if [ "$T10" = 0 ]; then
          T10=1
          by_eye "test 10: the \"asks for a copy\" notification appeared" \
            "Did a notification \"${PHONE:-A phone} asks for a copy of ${STORE:-your store}\" appear on this phone?"
          by_eye "test 10: tapping it opened the share screen with Allow" \
            "Tap it. Did the share screen open with Allow and the check code $CHECK?"
        fi
        echo ">>> Tap Allow (or Deny) on this phone. For test 9, tap nothing and let phone 2 cancel."
        wait_enter "When you've tapped, or phone 2 has cancelled,"
        ENTERED=1
      fi
    fi
    case "$S" in done|denied|expired|closed|not_serving|cant_share) break ;; esac
    if [ "$ENTERED" = 1 ] && { [ "$S" = waiting_for_allow ] || [ "$S" = phone_connecting ]; }; then
      if confirm "The share is still $S. Close it now?"; then
        call share_stop; info "share_stop: $(field reason)"; S=closed; break
      fi
    fi
    if [ "$(date +%s)" -ge "$END" ]; then info "still $S after 12 minutes"; break; fi
  done
  info "the share ended: ${S:-unknown} $(field reason)"
  case "$S" in done|denied|expired|closed|not_serving|cant_share) ;; *) call share_stop ;; esac
  confirm "Open a new share for the next test?" || break
done
summary
MCP_SHARE_EOF

chmod 700 mcp-lib.sh mcp-import-side.sh mcp-share-side.sh 2>/dev/null
echo "Unpacked mcp-lib.sh, mcp-import-side.sh and mcp-share-side.sh."
echo "Phone 1:  sh mcp-share-side.sh 2>&1 | tee share-result.txt"
echo "Phone 2:  sh mcp-import-side.sh 6 2>&1 | tee result-t6.txt   (or 9mcp, 9person, 11)"
