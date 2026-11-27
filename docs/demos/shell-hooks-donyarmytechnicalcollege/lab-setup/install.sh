#!/usr/bin/env bash
# Don Yarmy Technical College: CS 140 lab setup for Code on the Go.
#
# IT keeps this folder (install.sh and hooks/) in a git repository on the course server. Each
# student runs it once, in Code on the Go's Terminal:
#
#   git clone <course server>/lab-setup.git ~/lab-setup && bash ~/lab-setup/install.sh
#
# It asks for the student's name and ID, saves them in ~/.config/donyarmy/lab.conf, and copies
# the college's scripts into Shell Hooks' folder, ~/.config/codeonthego/hooks. Every hook goes in
# a <hook>.d/ folder, so it never replaces a script the student wrote. Running it again updates
# the scripts and keeps the answers.
#
# Shell Hooks itself must be on: Preferences > Shell Hooks > Run hooks.
#
# What an institution would change: the course name, the e-mail domain and the ID format below.

set -eu

course="CS 140"
domain="students.donyarmytechnicalcollege.edu"
id_format='^DY[0-9]{2}-[0-9]{4}$'   # as in DY24-0173

kit=$(cd "$(dirname "$0")" && pwd)
conf="$HOME/.config/donyarmy/lab.conf"
hooks="$HOME/.config/codeonthego/hooks"

# The course server is wherever this folder was cloned from: <server>/lab-setup.git.
origin=$(git -C "$kit" remote get-url origin 2>/dev/null) || {
    echo "Clone lab-setup from the course server first; see the top of this script." >&2
    exit 1
}
server=${origin%/lab-setup.git}

# The lab's config file uses git's config format, so any value is stored safely, with no quoting.
setting() { git config --file "$conf" "$1" 2>/dev/null || true; }

ask() {   # ask <question> <current answer>
    local answer
    if [ -n "$2" ]; then
        read -r -p "$1 [$2]: " answer
        echo "${answer:-$2}"
    else
        read -r -p "$1: " answer
        echo "$answer"
    fi
}

echo "Don Yarmy Technical College: $course lab setup"
name=$(ask "Your name" "$(setting student.name)")
id=$(ask "Your student ID (DY..-....)" "$(setting student.id)")
id=$(printf '%s' "$id" | tr '[:lower:]' '[:upper:]')
if [ -z "$name" ] || ! printf '%s' "$id" | grep -Eq "$id_format"; then
    echo "A name and a student ID such as DY24-0173 are needed. Nothing was changed." >&2
    exit 1
fi
email="$(printf '%s' "$id" | tr '[:upper:]' '[:lower:]')@$domain"

mkdir -p "$(dirname "$conf")"
git config --file "$conf" student.name "$name"
git config --file "$conf" student.id "$id"
git config --file "$conf" student.email "$email"
git config --file "$conf" course.name "$course"
git config --file "$conf" course.server "$server"

# place <script in hooks/> <where it goes in the hooks folder>
place() {
    mkdir -p "$hooks/$(dirname "$2")"
    cp "$kit/hooks/$1" "$hooks/$2"
    chmod 755 "$hooks/$2"   # Shell Hooks runs only executable files from this folder
    echo "  $2"
}
echo "Installing the college's hooks in $hooks:"
place lab-check ide-started.d/lab-check
place journal build-started.d/journal
place journal build-succeeded.d/journal
place journal build-failed.d/journal
place wip-backup project-closed.d/wip-backup
place check-file actions/check-file
place submit actions/submit

# Run the lab check once now, so git is set up before the student clones the assignment. From
# now on, Shell Hooks runs it each time Code on the Go starts.
echo "Running the lab check:"
bash "$hooks/ide-started.d/lab-check"
