#!/usr/bin/env bash
# A stand-in for the college's server, so the whole demo runs on one phone. Not part of the
# story: a real course server is a git host the phones reach over the network.
#
# Usage, in Code on the Go's Terminal:
#
#   bash college-server/stand-in.sh <project folder> <student ID>
#
# <project folder> is a new Code on the Go project that becomes the assignment's starter. It is
# copied, never changed. The script makes, in ~/college-server:
#
#   lab-setup.git           IT's repository, from this demo's lab-setup/ folder
#   students/<id>.git       the student's repository: the starter project, plus ASSIGNMENT.md,
#                           the project hooks and a header in each source file, with this
#                           folder's post-receive hook installed
#   instructor-inbox/       where post-receive files the receipts
#
# and prints the commands the student runs next.

set -eu

if [ $# -ne 2 ]; then
    echo "Usage: bash stand-in.sh <project folder> <student ID>" >&2
    exit 2
fi
demo=$(cd "$(dirname "$0")/.." && pwd)
project=$(cd "$1" && pwd)
id=$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')
server="$HOME/college-server"
student_repo="$server/students/$id.git"

if [ -e "$server" ]; then
    echo "$server already exists. Move it away to start again." >&2
    exit 1
fi
mkdir -p "$server/students" "$server/instructor-inbox"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
as_staff() {
    git -c user.name="CS 140 staff" -c user.email="cs140@donyarmytechnicalcollege.edu" "$@"
}

# IT's repository.
git init --quiet --bare --initial-branch=main "$server/lab-setup.git"
cp -R "$demo/lab-setup" "$work/lab-setup"
git -C "$work/lab-setup" init --quiet --initial-branch=main
git -C "$work/lab-setup" add -A
as_staff -C "$work/lab-setup" commit --quiet -m "CS 140 lab setup"
git -C "$work/lab-setup" push --quiet "$server/lab-setup.git" main

# The starter: a copy of the project, without what a build or the IDE made.
cp -R "$project" "$work/starter"
cd "$work/starter"
rm -rf .git .gradle .cg .androidide build ./*/build
# The template's example tests are not part of the assignment.
rm -rf app/src/test app/src/androidTest
cp "$demo/starter-project/ASSIGNMENT.md" .
mkdir -p .codeonthego/hooks
cp "$demo/starter-project/.codeonthego/hooks/"* .codeonthego/hooks/
for ignored in .gradle/ build/ .cg/ local.properties; do
    grep -qxF "$ignored" .gitignore 2>/dev/null || echo "$ignored" >> .gitignore
done
find . -path ./.codeonthego -prune -o -type f \( -name '*.java' -o -name '*.kt' \) -print |
    while IFS= read -r source; do
        {
            echo "/*"
            echo " * CS 140, Assignment 3: Tip Calculator"
            echo " * Student ID: (yours here, as in DY24-0173)"
            echo " */"
            echo "// TODO(submit): replace the sample screen with the tip calculator."
            cat "$source"
        } > "$source.new"
        mv "$source.new" "$source"
    done
git init --quiet --initial-branch=main
git add -A
as_staff commit --quiet -m "Assignment 3 starter"

git init --quiet --bare --initial-branch=main "$student_repo"
git push --quiet "$student_repo" main
git -C "$student_repo" config cs140.inbox "$server/instructor-inbox"

# On the phone, /bin/sh does not exist and git runs hooks without Shell Hooks' wrapper, so the
# hook's first line names the Terminal's own sh.
hook="$student_repo/hooks/post-receive"
if [ -n "${PREFIX:-}" ]; then
    sed "1s|^#!/bin/sh\$|#!$PREFIX/bin/sh|" "$demo/college-server/post-receive" > "$hook"
else
    cp "$demo/college-server/post-receive" "$hook"
fi
chmod 755 "$hook"

echo "Stand-in course server ready in $server. Next, in the Terminal:"
echo "  git clone $server/lab-setup.git ~/lab-setup && bash ~/lab-setup/install.sh"
echo "  git clone $student_repo <your projects folder>/Assignment3"
