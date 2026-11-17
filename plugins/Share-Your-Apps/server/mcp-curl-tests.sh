#!/usr/bin/env bash
# Tests MCP control (project A, step 2) with curl, on the phone running Share Your Apps Server.
#
# Before running:
#   1. Do a Standard Run of "Share Your Apps Server MCP", open the app, turn on MCP control,
#      and wait until the line under the switch says "On, at http://127.0.0.1:6176/mcp".
#   2. Tap Copy token.
#
# Run it from a terminal on the same phone (Code on the Go's terminal, or Termux):
#     bash mcp-curl-tests.sh
# It asks for the token: paste it and press Enter (it isn't shown). Or set MCP_TOKEN first.
#
# From a computer instead: run  adb forward tcp:6176 tcp:6176  and then this script there.
# MCP_URL changes the address (default http://127.0.0.1:6176/mcp).
# Optional: set MCP_WIFI_IP to this phone's Wi-Fi address, to check MCP can't be reached on it.
#
# Each check prints PASS or FAIL with the HTTP status. A FAIL also prints the reply.
# The token is never printed and never put on curl's command line (it's read from a private
# temporary file, deleted at the end).
#
# Checks to do by hand, on the phone (not covered here):
#   - Turning the switch on shows "Turn on MCP control?" first; Cancel leaves it off.
#   - The "MCP control is on" notification's Turn off button turns the switch off; after that
#     this script's requests fail with HTTP 000 (connection refused).
#   - Typing 6176 in the server's Port field and tapping Start says the port is kept for MCP control.
#   - Long-press the switch: the new help shows (after running help/build_help_db.sh and a Standard Run).

set -u
URL="${MCP_URL:-http://127.0.0.1:6176/mcp}"
BASE="${URL%/mcp}"
command -v curl >/dev/null || { echo "curl isn't installed. In Termux: pkg install curl"; exit 2; }

if [ -z "${MCP_TOKEN:-}" ]; then
  # The prompt and the typing use the terminal itself (/dev/tty), so sending the output to a file
  # (> result.txt, or | tee result.txt) can't hide the prompt. With no terminal, stop rather than wait.
  # The check runs in a subshell, so a failed open can't end the script itself.
  if ! (exec < /dev/tty) 2>/dev/null; then
    echo "No token given, and no terminal to type it on. Run this in a terminal, or set MCP_TOKEN first."
    exit 2
  fi
  printf 'Paste the MCP token (from Copy token), then press Enter: ' > /dev/tty
  # Plain sh has no read -s: stty hides the typing instead.
  stty -echo < /dev/tty 2>/dev/null
  read -r MCP_TOKEN < /dev/tty
  stty echo < /dev/tty 2>/dev/null; echo > /dev/tty
fi
[ -n "$MCP_TOKEN" ] || { echo "No token given."; exit 2; }

umask 077
WORK="$(mktemp -d "${TMPDIR:-/tmp}/mcp-test.XXXXXX")" || { echo "Couldn't make a temporary folder."; exit 2; }
trap 'rm -rf "$WORK"' EXIT
printf 'Authorization: Bearer %s\n' "$MCP_TOKEN" > "$WORK/auth"
printf 'Authorization: Bearer %s\n' "not-the-token" > "$WORK/badauth"

PASS=0
FAIL=0

show() {
  echo "      reply: $(head -c 3000 "$WORK/body")"
  if [ -s "$WORK/err" ]; then echo "      curl: $(cat "$WORK/err")"; fi
}

# check NAME WANT_STATUS WANT_TEXT METHOD BODY [more curl arguments]
#   WANT_TEXT: text the reply (body or headers) must contain, any case; "" for none.
#   BODY: JSON to send, @file to send a file, or "" for none.
#   Settings for one call: AUTH=good|bad|none, CTYPE=<content type>, TARGET=<url>, SHOW=1.
#   Plain sh, no arrays: the extra curl arguments stay in "$@", and this puts its own in front.
#   The settings are read, then cleared, so they never carry over to the next check, whichever
#   way the shell treats VAR=value before a function call.
check() {
  c_name="$1"; c_want="$2"; c_text="$3"; c_method="$4"; c_body="$5"; shift 5
  c_auth="${AUTH:-good}"; c_ctype="${CTYPE:-application/json}"; c_target="${TARGET:-$URL}"; c_show="${SHOW:-0}"
  AUTH=; CTYPE=; TARGET=; SHOW=
  if [ -n "$c_body" ]; then set -- -H "Content-Type: $c_ctype" --data-binary "$c_body" "$@"; fi
  case "$c_auth" in
    good) set -- -H "@$WORK/auth" "$@" ;;
    bad)  set -- -H "@$WORK/badauth" "$@" ;;
  esac
  : > "$WORK/body"; : > "$WORK/head"; : > "$WORK/err"
  c_got="$(curl -sS -X "$c_method" -o "$WORK/body" -D "$WORK/head" -w '%{http_code}' --max-time 30 "$@" "$c_target" 2>"$WORK/err")"
  [ -n "$c_got" ] || c_got=000
  c_ok=1
  [ "$c_got" = "$c_want" ] || c_ok=0
  if [ -n "$c_text" ] && ! grep -qiF -- "$c_text" "$WORK/body" "$WORK/head"; then c_ok=0; fi
  if [ $c_ok = 1 ]; then
    PASS=$((PASS + 1)); echo "PASS  $c_name (HTTP $c_got)"
    if [ "$c_show" = 1 ]; then show; fi
  else
    FAIL=$((FAIL + 1)); echo "FAIL  $c_name: wanted HTTP $c_want${c_text:+ containing $c_text}, got HTTP $c_got"; show
  fi
  return 0
}

# has NAME TEXT: the last reply contains TEXT. hasnt NAME TEXT: it doesn't.
has() {
  if grep -qiF -- "$2" "$WORK/body" "$WORK/head"; then PASS=$((PASS + 1)); echo "PASS  $1"
  else FAIL=$((FAIL + 1)); echo "FAIL  $1: the reply doesn't contain $2"; show; fi
}
hasnt() {
  if grep -qiF -- "$2" "$WORK/body" "$WORK/head"; then FAIL=$((FAIL + 1)); echo "FAIL  $1: the reply contains $2"; show
  else PASS=$((PASS + 1)); echo "PASS  $1"; fi
}

PING='{"jsonrpc":"2.0","id":1,"method":"ping"}'
INIT='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl-test","version":"1"}}}'

echo "Testing $URL"
echo
echo "== Who may ask"
AUTH=none check "no token is refused" 401 "Missing or wrong token" POST "$PING"
has "…and the refusal names the Bearer scheme" "WWW-Authenticate"
AUTH=bad check "a wrong token is refused" 401 "Missing or wrong token" POST "$PING"
check "a Host other than 127.0.0.1 or localhost is refused" 403 "Host header" POST "$PING" -H 'Host: example.com'
check "a request from a web page (Origin) is refused" 403 "web pages" POST "$PING" -H 'Origin: https://example.com'
TARGET="$BASE/other" check "another path is not found" 404 "/mcp" POST "$PING"
check "GET is refused: no event stream" 405 "event stream" GET ""
CTYPE=text/plain check "a body that isn't JSON's content type is refused" 415 "application/json" POST "$PING"
check "broken JSON is a parse error" 400 "-32700" POST '{"jsonrpc":'
check "a request before initialize is refused" 400 "initialize first" POST "$PING"
check "an unknown session is refused" 404 "initialize again" POST "$PING" -H 'Mcp-Session-Id: no-such-session'
head -c 1100000 /dev/zero | tr '\0' a > "$WORK/big"
check "a request over 1 MB is refused" 413 "" POST "@$WORK/big"

echo
echo "== Starting a session"
check "initialize answers with the version asked for" 200 '"protocolVersion":"2025-06-18"' POST "$INIT"
SESSION="$(grep -i '^mcp-session-id:' "$WORK/head" | head -n 1 | cut -d: -f2- | tr -d ' \r')"
has "…with a session id" "Mcp-Session-Id"
has "…the server's name" '"name":"share-your-apps-server"'
has "…and the tools capability" '"tools":{'
check "initialize with an unknown version offers the newest" 200 '"protocolVersion":"2025-06-18"' POST \
  '{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"1999-01-01","capabilities":{}}}'
if [ -z "$SESSION" ]; then
  echo
  echo "No session id came back, so the rest can't run."
  echo "$PASS passed, $((FAIL + 1)) failed. Paste the FAIL lines into the chat."
  exit 1
fi
# The session's headers, in a file curl reads with -H @file (plain sh has no arrays).
printf 'Mcp-Session-Id: %s\nMCP-Protocol-Version: 2025-06-18\n' "$SESSION" > "$WORK/session"

echo
echo "== In the session"
check "notifications/initialized needs no answer" 202 "" POST '{"jsonrpc":"2.0","method":"notifications/initialized"}' -H "@$WORK/session"
check "ping" 200 '"result":{}' POST "$PING" -H "@$WORK/session"
check "tools/list lists server_status" 200 '"name":"server_status"' POST '{"jsonrpc":"2.0","id":3,"method":"tools/list"}' -H "@$WORK/session"
has "…and store_info" '"name":"store_info"'
has "…marked read-only" '"readOnlyHint":true'
SHOW=1 check "server_status" 200 '"isError":false' POST \
  '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"server_status"}}' -H "@$WORK/session"
has "…reports a state" '"state":'
hasnt "…and no password" 'password'
SHOW=1 check "store_info" 200 '"own_store"' POST \
  '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"store_info","arguments":{}}}' -H "@$WORK/session"
has "…and the imported copy" '"imported_copy"'
check "an unknown tool is an invalid-params error" 200 '-32602' POST \
  '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"delete_store"}}' -H "@$WORK/session"
check "arguments that aren't an object are refused" 200 '-32602' POST \
  '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"server_status","arguments":[1]}}' -H "@$WORK/session"
check "an unknown method is method-not-found" 200 '-32601' POST '{"jsonrpc":"2.0","id":8,"method":"resources/list"}' -H "@$WORK/session"
check "a batch of two pings gets two answers" 200 '"id":21' POST \
  '[{"jsonrpc":"2.0","id":21,"method":"ping"},{"jsonrpc":"2.0","id":22,"method":"ping"}]' -H "@$WORK/session"
has "…the second one too" '"id":22'
check "a batch of only notifications needs no answer" 202 "" POST \
  '[{"jsonrpc":"2.0","method":"notifications/initialized"}]' -H "@$WORK/session"
check "Origin http://localhost is accepted" 200 '"result":{}' POST "$PING" -H "@$WORK/session" -H 'Origin: http://localhost'
check "Host localhost is accepted" 200 '"result":{}' POST "$PING" -H "@$WORK/session" -H 'Host: localhost'
check "an unknown MCP-Protocol-Version is refused" 400 "Unsupported" POST "$PING" \
  -H "Mcp-Session-Id: $SESSION" -H 'MCP-Protocol-Version: 1999-01-01'
AUTH=bad check "a wrong token is refused, even with a session" 401 "" POST "$PING" -H "@$WORK/session"

echo
echo "== Share and server tools (step 3)"
check "tools/list lists share_start" 200 '"name":"share_start"' POST '{"jsonrpc":"2.0","id":30,"method":"tools/list"}' -H "@$WORK/session"
has "…share_status" '"name":"share_status"'
has "…share_stop" '"name":"share_stop"'
has "…server_start" '"name":"server_start"'
has "…server_stop" '"name":"server_stop"'
hasnt "…and no tool to allow a copy" '"name":"share_allow"'
SHOW=1 check "share_status answers, whatever is being served" 200 '"state":' POST \
  '{"jsonrpc":"2.0","id":31,"method":"tools/call","params":{"name":"share_status"}}' -H "@$WORK/session"
check "share_status refuses wait_seconds that isn't a number" 200 '"isError":true' POST \
  '{"jsonrpc":"2.0","id":32,"method":"tools/call","params":{"name":"share_status","arguments":{"wait_seconds":"5"}}}' -H "@$WORK/session"
check "server_start refuses a store other than own or copy" 200 'store must be' POST \
  '{"jsonrpc":"2.0","id":33,"method":"tools/call","params":{"name":"server_start","arguments":{"store":"both"}}}' -H "@$WORK/session"
has "…as a tool error" '"isError":true'

# Helpers for the optional sections below.
# call ID TOOL [ARGS]: one tools/call; its reply is left in $WORK/body for has/hasnt, but isn't counted as a check.
call() {
  c_args="${3:-}"; [ -n "$c_args" ] || c_args='{}'
  printf '{"jsonrpc":"2.0","id":%s,"method":"tools/call","params":{"name":"%s","arguments":%s}}' "$1" "$2" "$c_args" > "$WORK/call"
  : > "$WORK/body"; : > "$WORK/head"; : > "$WORK/err"
  curl -sS -X POST -o "$WORK/body" -D "$WORK/head" --max-time 40 -H "@$WORK/auth" -H "@$WORK/session" \
    -H "Content-Type: application/json" --data-binary "@$WORK/call" "$URL" 2>"$WORK/err" || true
}
# serving_own ID: whether the server is serving this phone's own store (an imported copy can't be shared).
serving_own() { call "$1" server_status; grep -qF '"serving":"own"' "$WORK/body"; }
# wait_for_code ID: a share opened just after a start or a publish is "preparing" while the store's files
# are compressed. Waits for its code with share_status, up to 3 × 20 seconds.
wait_for_code() {
  w_n=0
  while grep -qF '"state":"preparing"' "$WORK/body" && [ "$w_n" -lt 3 ]; do
    echo "      the store is being prepared; waiting for the code…"
    call "$1" share_status '{"wait_seconds":20}'
    w_n=$((w_n + 1))
  done
}

echo
echo "== Import tools (step 4)"
# Every refusal here comes before an import would start, so nothing is imported or searched for.
check "tools/list lists import_start" 200 '"name":"import_start"' POST '{"jsonrpc":"2.0","id":70,"method":"tools/list"}' -H "@$WORK/session"
has "…import_status" '"name":"import_status"'
has "…import_cancel" '"name":"import_cancel"'
SHOW=1 check "import_status answers" 200 '"phase":' POST \
  '{"jsonrpc":"2.0","id":71,"method":"tools/call","params":{"name":"import_status"}}' -H "@$WORK/session"
check "import_cancel with no import running cancels nothing" 200 '"cancelled":false' POST \
  '{"jsonrpc":"2.0","id":72,"method":"tools/call","params":{"name":"import_cancel"}}' -H "@$WORK/session"
check "import_start refuses both code and link" 200 'exactly one' POST \
  '{"jsonrpc":"2.0","id":73,"method":"tools/call","params":{"name":"import_start","arguments":{"code":"LS-AB12-CD34","link":"shareyourapps-share://192.168.1.5:6175/AB12CD34"}}}' -H "@$WORK/session"
has "…as a tool error" '"isError":true'
check "import_start refuses neither code nor link" 200 'exactly one' POST \
  '{"jsonrpc":"2.0","id":74,"method":"tools/call","params":{"name":"import_start","arguments":{}}}' -H "@$WORK/session"
check "import_start refuses a code that isn't a share code" 200 'a share code: LS' POST \
  '{"jsonrpc":"2.0","id":75,"method":"tools/call","params":{"name":"import_start","arguments":{"code":"hello"}}}' -H "@$WORK/session"
check "import_start refuses a link that isn't a share link" 200 'a share link: it looks' POST \
  '{"jsonrpc":"2.0","id":76,"method":"tools/call","params":{"name":"import_start","arguments":{"link":"https://example.com/"}}}' -H "@$WORK/session"
check "import_start refuses a phone_name over 40 characters" 200 'at most 40' POST \
  '{"jsonrpc":"2.0","id":77,"method":"tools/call","params":{"name":"import_start","arguments":{"code":"LS-AB12-CD34","phone_name":"abcdefghijabcdefghijabcdefghijabcdefghijk"}}}' -H "@$WORK/session"
check "import_start refuses an empty phone_name" 200 'be empty' POST \
  '{"jsonrpc":"2.0","id":78,"method":"tools/call","params":{"name":"import_start","arguments":{"code":"LS-AB12-CD34","phone_name":"  "}}}' -H "@$WORK/session"
# Only while serving: on a stopped server, a valid code would really start an import.
call 79 server_status
if grep -qF '"state":"serving"' "$WORK/body"; then
  SHOW=1 check "import_start is refused while serving" 200 'Stop the server first' POST \
    '{"jsonrpc":"2.0","id":80,"method":"tools/call","params":{"name":"import_start","arguments":{"code":"LS-AB12-CD34"}}}' -H "@$WORK/session"
  has "…as a tool error" '"isError":true'
else
  echo "SKIP  import_start while serving: the server isn't serving, and a valid code would start a real import."
fi

# Optional: really opens and closes a share. Needs the server serving this phone's own store; skipped otherwise.
#   MCP_SHARE=1 sh mcp-curl-tests.sh
# Run with the server serving, this section also connects the share tools to the server process, so that
# MCP_SERVER=1 below then checks they reach the new one after server_stop and server_start.
if [ "${MCP_SHARE:-0}" = 1 ]; then
  echo
  echo "== A real share (MCP_SHARE=1)"
  if ! serving_own 39; then
    echo "SKIP  the server isn't serving this phone's own store, so there's nothing to share here."
    echo "      Tap Start in the app first, then run this again."
    [ "${MCP_SERVER:-0}" = 1 ] && echo "      (The server section below starts it and shares there, but can't check reconnecting.)"
  else
    SHOW=1 check "share_start opens a share" 200 '"isError":false' POST \
      '{"jsonrpc":"2.0","id":40,"method":"tools/call","params":{"name":"share_start"}}' -H "@$WORK/session"
    wait_for_code 45
    has "…with a code" '"code":'
    # JSON may write / as \/, so only the part before the slashes is checked.
    has "…and a link" '"link":"shareyourapps-share:'
    check "share_start again returns the same share, not a new one" 200 '"already_open":true' POST \
      '{"jsonrpc":"2.0","id":41,"method":"tools/call","params":{"name":"share_start"}}' -H "@$WORK/session"
    SHOW=1 check "share_status waits up to 3 seconds" 200 '"changed":false' POST \
      '{"jsonrpc":"2.0","id":42,"method":"tools/call","params":{"name":"share_status","arguments":{"wait_seconds":3}}}' -H "@$WORK/session"
    check "share_stop closes it" 200 '"stopped":true' POST \
      '{"jsonrpc":"2.0","id":43,"method":"tools/call","params":{"name":"share_stop"}}' -H "@$WORK/session"
    check "…after which share_status says closed" 200 '"state":"closed"' POST \
      '{"jsonrpc":"2.0","id":44,"method":"tools/call","params":{"name":"share_status"}}' -H "@$WORK/session"
  fi
fi

# Optional: really stops the server, then starts it again. Buyers connected now are cut off.
#   MCP_SERVER=1 sh mcp-curl-tests.sh
if [ "${MCP_SERVER:-0}" = 1 ]; then
  echo
  echo "== Stopping and starting the server (MCP_SERVER=1)"
  call 49 server_status
  if grep -qF '"state":"serving"' "$WORK/body"; then WAS_SERVING=1; else WAS_SERVING=0; fi
  SHOW=1 check "server_stop stops it" 200 '"state":"stopped"' POST \
    '{"jsonrpc":"2.0","id":50,"method":"tools/call","params":{"name":"server_stop","arguments":{"wait_seconds":15}}}' -H "@$WORK/session"
  # server_start restarts a server that's still serving; one that has stopped is simply started.
  if grep -qF '"state":"serving"' "$WORK/body"; then RESTART=1; else RESTART=0; fi
  SHOW=1 check "server_start starts it again" 200 '"state":"serving"' POST \
    '{"jsonrpc":"2.0","id":51,"method":"tools/call","params":{"name":"server_start","arguments":{"wait_seconds":20}}}' -H "@$WORK/session"
  # The new server process is a new connection for the share tools: they must reach it without
  # MCP control being turned off and on (result4.txt: "Couldn't reach the server process").
  SHOW=1 check "share_status reaches the new server process" 200 '"isError":false' POST \
    '{"jsonrpc":"2.0","id":52,"method":"tools/call","params":{"name":"share_status"}}' -H "@$WORK/session"
  hasnt "…without \"Couldn't reach the server process\"" "reach the server process"
  if [ "${MCP_SHARE:-0}" = 1 ]; then
    check "share_start opens a share on the new server process" 200 '"isError":false' POST \
      '{"jsonrpc":"2.0","id":53,"method":"tools/call","params":{"name":"share_start"}}' -H "@$WORK/session"
    wait_for_code 55
    has "…with a code" '"code":'
    check "…and share_stop closes it" 200 '"stopped":true' POST \
      '{"jsonrpc":"2.0","id":54,"method":"tools/call","params":{"name":"share_stop"}}' -H "@$WORK/session"
  fi
  if [ "$WAS_SERVING" = 1 ]; then
    echo "      In View logs, the stop should read: stopped serving …: stopped by an MCP client"
  else
    echo "      The server wasn't serving before server_stop, so no stop line was logged."
  fi
  if [ "$RESTART" = 1 ]; then
    echo "      …and the start should read: restarted by an MCP client"
  else
    echo "      server_start started a stopped server, so no \"restarted by an MCP client\" line is logged."
    echo "      That line appears only when server_start is called while the server is serving: see MCP_RESTART=1."
  fi
fi

# Optional: calls server_start while the server is serving, so it restarts and the log names MCP control.
# Also checks the share tools reach the new server process. Buyers connected now are cut off.
#   MCP_RESTART=1 sh mcp-curl-tests.sh
if [ "${MCP_RESTART:-0}" = 1 ]; then
  echo
  echo "== Restarting a serving server (MCP_RESTART=1)"
  call 60 server_status
  if ! grep -qF '"state":"serving"' "$WORK/body"; then
    echo "SKIP  the server isn't serving, so server_start would start it, not restart it."
    echo "      Tap Start in the app first, then run this again."
  else
    SHOW=1 check "server_start restarts a serving server" 200 '"state":"serving"' POST \
      '{"jsonrpc":"2.0","id":61,"method":"tools/call","params":{"name":"server_start","arguments":{"wait_seconds":20}}}' -H "@$WORK/session"
    check "…and share_status reaches the new server process" 200 '"isError":false' POST \
      '{"jsonrpc":"2.0","id":62,"method":"tools/call","params":{"name":"share_status"}}' -H "@$WORK/session"
    hasnt "…without \"Couldn't reach the server process\"" "reach the server process"
    echo "      In View logs, the restart should read: stopped serving Store_…: restarted by an MCP client (Store_…)"
  fi
fi

echo
echo "== Ending the session"
check "DELETE ends the session" 204 "" DELETE "" -H "@$WORK/session"
check "…after which it's refused" 404 "" POST "$PING" -H "@$WORK/session"
check "…and can't be ended twice" 404 "" DELETE "" -H "@$WORK/session"

if [ -n "${MCP_WIFI_IP:-}" ]; then
  echo
  echo "== Not reachable from other devices"
  TARGET="http://$MCP_WIFI_IP:6176/mcp" check "nothing answers on the Wi-Fi address" 000 "" POST "$PING"
fi

echo
echo "$PASS passed, $FAIL failed."
if [ "$FAIL" = 0 ]; then echo "All checks passed."; exit 0; fi
echo "Paste the FAIL lines into the chat."
exit 1
