#!/bin/sh
# Rebuilds the app's help database from build_help_db.py, then checks the MCP help is in it.
# Run from anywhere:
#     sh "/storage/emulated/0/CodeOnTheGoProjects/Local Store Server MCP/help/build_help_db.sh"
# Then do a Standard Run, so the app picks up the new database.
set -e

# This script's own folder, so the paths below work wherever it's run from.
HERE=$(cd "$(dirname "$0")" && pwd)
DB="$HERE/../app/src/main/assets/web_server_documentation.db"

# python3 if there is one, else python.
if command -v python3 >/dev/null 2>&1; then PY=python3
elif command -v python >/dev/null 2>&1; then PY=python
else echo "Neither python3 nor python was found." >&2; exit 1
fi

echo "Building $DB with $PY ..."
"$PY" "$HERE/build_help_db.py" "$DB"

# A quick check: the new page and both new help entries are in the database.
"$PY" - "$DB" <<'EOF'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
tags = [r[0] for r in db.execute("SELECT tag FROM Tooltips WHERE tag LIKE 'server.mcp%' ORDER BY tag")]
page = db.execute("SELECT length(content) FROM Content WHERE path = 'mcp.html'").fetchone()
link = db.execute("SELECT instr(content, 'href=\"mcp.html\"') FROM Content WHERE path = 'index.html'").fetchone()
total = db.execute("SELECT count(*) FROM Tooltips").fetchone()[0]
print("Help entries:", total)
print("MCP entries:", tags)
print("mcp.html:", f"{page[0]} bytes" if page else "MISSING")
print("index.html links to mcp.html:", "yes" if link and link[0] else "NO")
ok = tags == ["server.mcp", "server.mcp_token"] and page and link and link[0]
print("OK" if ok else "SOMETHING IS MISSING")
sys.exit(0 if ok else 1)
EOF
