#!/bin/sh
# Calls one tool of Local Store Server's MCP control, for testing by hand: you play the MCP client.
#
# Usage:
#   sh mcp-call.sh TOOL [ARGUMENTS]
# Examples:
#   sh mcp-call.sh tools                                  lists the tools
#   sh mcp-call.sh server_status
#   sh mcp-call.sh share_start                            opens a share; prints its code
#   sh mcp-call.sh share_status                           what the share is doing now
#   sh mcp-call.sh share_status '{"wait_seconds":20}'     waits up to 20 s for a change
#   sh mcp-call.sh share_stop                             closes the share
#
# Before running: MCP control on (the line under the switch says "On, at http://127.0.0.1:6176/mcp").
# It asks for the token (from Copy token) without showing it. To skip the question, set MCP_TOKEN.
# Each run starts its own MCP session and ends it afterwards. A share stays open between runs:
# the app keeps it, not the session.
# MCP_URL changes the address (default http://127.0.0.1:6176/mcp).

set -u
URL="${MCP_URL:-http://127.0.0.1:6176/mcp}"
TOOL="${1:-}"
ARGS="${2:-}"
[ -n "$ARGS" ] || ARGS='{}'

if [ -z "$TOOL" ]; then
  echo "Usage: sh mcp-call.sh TOOL [ARGUMENTS]"
  echo "  e.g. sh mcp-call.sh share_start"
  echo "       sh mcp-call.sh share_status '{\"wait_seconds\":20}'"
  echo "       sh mcp-call.sh tools        (lists the tools)"
  exit 2
fi
command -v curl >/dev/null || { echo "curl isn't installed. In Termux: pkg install curl"; exit 2; }

# The token: asked on the terminal itself, not hidden in the output, and not shown as it's pasted.
if [ -z "${MCP_TOKEN:-}" ] && [ -r /dev/tty ] && [ -w /dev/tty ]; then
  printf 'Paste the MCP token (from Copy token), then press Enter: ' > /dev/tty
  stty -echo < /dev/tty 2>/dev/null
  read -r MCP_TOKEN < /dev/tty
  stty echo < /dev/tty 2>/dev/null
  echo > /dev/tty
fi
[ -n "${MCP_TOKEN:-}" ] || { echo "No token given."; exit 2; }

# The token goes to curl from a private temporary file, never on its command line.
umask 077
WORK="$(mktemp -d "${TMPDIR:-/tmp}/mcp-call.XXXXXX")" || {
  echo "Couldn't make a temporary folder. Try: TMPDIR=\$HOME sh mcp-call.sh $TOOL"; exit 2; }
trap 'rm -rf "$WORK"' EXIT
printf 'Authorization: Bearer %s\n' "$MCP_TOKEN" > "$WORK/auth"

# post BODY [more curl arguments]: sends one request. The reply's body goes to $WORK/body and its
# headers to $WORK/head; prints the HTTP status. 40 s allows for wait_seconds up to 25.
post() {
  p_body="$1"; shift
  printf '%s' "$p_body" > "$WORK/req"
  : > "$WORK/body"; : > "$WORK/head"; : > "$WORK/err"
  curl -sS -X POST -o "$WORK/body" -D "$WORK/head" -w '%{http_code}' --max-time 40 \
    -H "@$WORK/auth" -H "Content-Type: application/json" "$@" --data-binary "@$WORK/req" "$URL" 2>"$WORK/err"
}

# 1. Start a session.
status="$(post '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"mcp-call","version":"1"}}}')"
if [ "$status" != 200 ]; then
  echo "Couldn't start an MCP session (HTTP ${status:-000})."
  if [ -s "$WORK/err" ]; then cat "$WORK/err"; fi
  if [ -s "$WORK/body" ]; then cat "$WORK/body"; echo; fi
  echo "Is MCP control on? Is the token the one from Copy token?"
  exit 1
fi
SESSION="$(grep -i '^mcp-session-id:' "$WORK/head" | head -n 1 | cut -d: -f2- | tr -d ' \r')"
[ -n "$SESSION" ] || { echo "The server didn't give a session id."; exit 1; }
printf 'Mcp-Session-Id: %s\nMCP-Protocol-Version: 2025-06-18\n' "$SESSION" > "$WORK/session"
post '{"jsonrpc":"2.0","method":"notifications/initialized"}' -H "@$WORK/session" > /dev/null

# 2. Call the tool, or list the tools.
if [ "$TOOL" = tools ]; then
  REQ='{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
else
  REQ="$(printf '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"%s","arguments":%s}}' "$TOOL" "$ARGS")"
fi
status="$(post "$REQ" -H "@$WORK/session")"
cp "$WORK/body" "$WORK/reply"

# 3. End the session. The share, if any, stays open.
curl -sS -X DELETE -o /dev/null --max-time 10 -H "@$WORK/auth" -H "@$WORK/session" "$URL" 2>/dev/null

if [ "$status" != 200 ]; then
  echo "The request failed (HTTP ${status:-000})."
  if [ -s "$WORK/err" ]; then cat "$WORK/err"; fi
  if [ -s "$WORK/reply" ]; then cat "$WORK/reply"; echo; fi
  exit 1
fi

# 4. Show the answer: readably with Python, if it's installed; otherwise the raw reply.
if command -v python3 > /dev/null 2>&1; then
  python3 - "$WORK/reply" "$TOOL" <<'EOF'
import json, sys
path, tool = sys.argv[1], sys.argv[2]
raw = open(path, encoding="utf-8", errors="replace").read()
try:
    reply = json.loads(raw)
except Exception:
    print(raw)
    sys.exit(0)
if "error" in reply:
    print("Refused:", reply["error"].get("message"))
    sys.exit(0)
result = reply.get("result", {})
if tool == "tools":
    for t in result.get("tools", []):
        print(f"{t['name']:15} {t.get('title', '')}")
    sys.exit(0)
if result.get("isError"):
    print("The tool reported a problem:")
for c in result.get("content", []):
    if c.get("type") == "text":
        print(c["text"])
EOF
else
  cat "$WORK/reply"; echo
fi
exit 0
