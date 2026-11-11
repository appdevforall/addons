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
    echo "Check that MCP control is on in Share Your Apps Server, and that MCP_TOKEN is this phone's token."
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
