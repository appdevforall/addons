#!/usr/bin/env bash
#
# check-toolchain.sh (ADFA-4907)
#
# Assert that every Gradle module in this repo declares the one standard
# toolchain. Pure text inspection -- no Gradle run, no JDK, no network, so it
# is fast enough to gate every pull request.
#
# WHY THESE NUMBERS: they are not "latest". They track the toolchain that Code
# On The Go itself ships on-device, because these plugins are meant to be built
# *with* Code on the Go (ADFA-4693). Verified against CoGo stage c263653bcf:
#
#   * org.adfa.constants pins AGP 9.3.1, Gradle 9.6.1, Kotlin 2.3.21, Java 17
#   * the localMvnRepository asset onboarding installs carries exactly one AGP
#     (9.3.1) and one kotlin-gradle-plugin (2.3.21), so a plugin pinned to any
#     other version cannot resolve its own buildscript offline
#   * the bundled Gradle 9.6.1 is what runs on device by default, not the
#     project wrapper (BuildPreferences.gradleInstallationDir)
#   * android-36 is still the only installed platform in CoGo's bundled SDK
#
# AGP 9 compiles Kotlin itself and refuses org.jetbrains.kotlin.android, so no
# module here applies it. Kotlin is pinned on the buildscript classpath in
# settings.gradle.kts instead, which is where AGP 9 takes its compiler from
# (ADFA-6246).
#
# Bumping any value below therefore means re-verifying against a real CoGo
# build first -- not just picking something newer.
#
# SCOPE: this walks *every* build.gradle.kts, deliberately broader than the
# "top-level dir applying com.itsaky.androidide.plugins.build" definition of a
# plugin used by .githooks/pre-push (plugin_dirs()), scripts/update-libs.sh and
# CLAUDE.md. Subprojects such as plugins/AI-Agent-Local/llama-api and
# plugins/AI-Agent-Local/llama-impl compile into a plugin and must agree on the
# toolchain, but are invisible to that definition -- which is exactly how
# plugins/AI-Agent-Local/llama-impl kept compileSdk 34 through a dedicated
# standardization pass. Do not "unify" discovery onto
# plugin_dirs(): it would silently drop those modules from coverage.
#
# Usage:
#   scripts/check-toolchain.sh           # check; exit 1 and print a report on drift
#   scripts/check-toolchain.sh --list    # print the versions it checks, exit 0
#
set -euo pipefail

# ---------------------------------------------------------------------------
# The standard
# ---------------------------------------------------------------------------
EXPECTED_COMPILE_SDK="36"
EXPECTED_TARGET_SDK="36"
EXPECTED_AGP="9.3.1"
EXPECTED_KOTLIN="2.3.21"
EXPECTED_GRADLE="9.6.1"
EXPECTED_GRADLE_DIST="bin"        # gradle-<ver>-bin.zip, not -all.zip
EXPECTED_JAVA="JavaVersion.VERSION_17"
EXPECTED_JVM_TARGET="JVM_17"

# minSdk is deliberately NOT checked. It legitimately varies by what a plugin
# needs (21 for the template installers, 26 for most, 28 for Voice-Alerts and
# sketch-to-ui, 33 for the AI plugins which rely on API-33 runtime behavior).
# Do not "standardize" it here.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

failures=()   # drift reports, printed on exit 1
declared=()   # "<module> <label>=<value>" facts, printed by --list

fail() { failures+=("$1"); }

# ---------------------------------------------------------------------------
# Matching helpers
#
# Everything below matches with bash's own =~ rather than grep|sed. Piping
# these regexes through sed breaks on BSD/macOS sed (the escaped "\:" in
# distributionUrl) and on greedy ".*" before the capture group; BASH_REMATCH
# has neither problem, and keeps the whole script to one matching idiom and
# zero subprocesses.
# ---------------------------------------------------------------------------

# module_of <file> -- the label a fact is reported under. Wrappers and catalogs
# live under <module>/gradle/..., so strip that to name the module itself.
module_of() {
  local m=${1#./}
  m=${m%/*}
  m=${m%/gradle/wrapper}
  m=${m%/gradle}
  printf '%s' "${m:-<root>}"
}

# file_matches <file> <ere> -- true if any line matches.
file_matches() {
  local line
  while IFS= read -r line || [ -n "$line" ]; do
    [[ $line =~ $2 ]] && return 0
  done < "$1"
  return 1
}

# check_value <file> <label> <ere-with-one-capture> <expected> [hint]
# Checks the FIRST matching line, records the value for --list, and reports
# drift. Returns 1 if the key is absent, so callers can require it.
check_value() {
  local file=$1 label=$2 re=$3 want=$4 hint=${5:-} ln=0 line got
  while IFS= read -r line || [ -n "$line" ]; do
    ln=$((ln + 1))
    [[ $line =~ $re ]] || continue
    got=${BASH_REMATCH[1]}
    declared+=("$(module_of "$file") $label=$got")
    [ "$got" = "$want" ] ||
      fail "$file:$ln  $label $got   (expected $want${hint:+ -- $hint})"
    return 0
  done < "$file"
  return 1
}

# check_every <file> <label> <ere-with-one-capture> <expected> [hint]
# Like check_value but every matching line must hold, not just the first.
check_every() {
  local file=$1 label=$2 re=$3 want=$4 hint=${5:-} ln=0 line got
  while IFS= read -r line || [ -n "$line" ]; do
    ln=$((ln + 1))
    [[ $line =~ $re ]] || continue
    got=${BASH_REMATCH[1]}
    [ "$got" = "$want" ] ||
      fail "$file:$ln  $label $got   (expected $want${hint:+ -- $hint})"
  done < "$file"
  return 0   # a while loop yields its last body command's status; don't leak it to set -e
}

# reject <file> <ere-with-one-capture> <why>
# Any match is drift; the capture is quoted back in the message.
reject() {
  local file=$1 re=$2 why=$3 ln=0 line
  while IFS= read -r line || [ -n "$line" ]; do
    ln=$((ln + 1))
    [[ $line =~ $re ]] && fail "$file:$ln  '${BASH_REMATCH[1]}' -- $why"
  done < "$file"
  return 0   # see check_every
}

# ---------------------------------------------------------------------------
# Per-file-type checks
# ---------------------------------------------------------------------------
check_build_file() {
  local f=$1

  check_value "$f" compileSdk \
    '^[[:space:]]*compileSdk[[:space:]]*=[[:space:]]*([0-9]+)' \
    "$EXPECTED_COMPILE_SDK" || true

  # targetSdk: application modules must declare it; library modules have none.
  if ! check_value "$f" targetSdk \
       '^[[:space:]]*targetSdk[[:space:]]*=[[:space:]]*([0-9]+)' \
       "$EXPECTED_TARGET_SDK"; then
    if file_matches "$f" 'com\.android\.application'; then
      fail "$f  declares com.android.application but no targetSdk"
    fi
  fi

  check_every "$f" Java \
    'Compatibility[[:space:]]*=[[:space:]]*([^[:space:]]+)' \
    "$EXPECTED_JAVA"

  # Kotlin 2.3.0 made the old string DSL (kotlinOptions.jvmTarget = "17") a
  # hard error. That exact pattern broke template-manager during the ADFA-4907
  # bump, so reject it outright rather than waiting for a build to fail.
  reject "$f" '(jvmTarget[[:space:]]*=[[:space:]]*"[^"]*")' \
    "Kotlin $EXPECTED_KOTLIN rejects the string DSL; use kotlin { compilerOptions { jvmTarget.set(JvmTarget.$EXPECTED_JVM_TARGET) } }"

  if file_matches "$f" 'org\.jetbrains\.kotlin|kotlin\("' &&
     ! file_matches "$f" "JvmTarget\.$EXPECTED_JVM_TARGET"; then
    fail "$f  applies a Kotlin plugin but never sets jvmTarget to JvmTarget.$EXPECTED_JVM_TARGET"
  fi

  # An explicit kotlin-stdlib pin older than the Kotlin Gradle plugin is the
  # "version skew" the plugin-review rubric flags. Most modules correctly omit
  # the line and inherit the stdlib; if you do pin it, pin it to the standard.
  check_every "$f" kotlin-stdlib \
    'kotlin-stdlib:([0-9][^"'"'"']*)' \
    "$EXPECTED_KOTLIN" "or drop the line and inherit it"
}

check_settings_file() {
  local f=$1
  check_value "$f" AGP 'com\.android\.tools\.build:gradle:([0-9][^"]*)' \
    "$EXPECTED_AGP" "the only AGP in CoGo's caches" || true
  check_value "$f" Kotlin 'kotlin-gradle-plugin:([0-9][^"]*)' \
    "$EXPECTED_KOTLIN" || true
  check_value "$f" compose-compiler 'compose-compiler-gradle-plugin:([0-9][^"]*)' \
    "$EXPECTED_KOTLIN" "must match Kotlin" || true
}

check_wrapper_file() {
  local f=$1
  check_value "$f" Gradle 'distributions/gradle-([0-9][^-]*)-' \
    "$EXPECTED_GRADLE" "CoGo's Gradle" || true
  check_value "$f" distribution 'gradle-[0-9][^-]*-(bin|all)\.zip' \
    "$EXPECTED_GRADLE_DIST" "use -$EXPECTED_GRADLE_DIST.zip, matching the root wrapper" || true
}

# Only three modules use a version catalog, and two carry a hand-written "Must
# match the kotlin-gradle-plugin classpath pinned in settings.gradle.kts"
# comment. This automates that comment. A stale entry here is not inert: it is
# the number the next person greps for.
check_catalog_file() {
  local f=$1
  check_value "$f" 'catalog agp' '^agp[[:space:]]*=[[:space:]]*"([^"]+)"' \
    "$EXPECTED_AGP" "disagrees with settings.gradle.kts" || true
  check_value "$f" 'catalog kotlin' '^kotlin[[:space:]]*=[[:space:]]*"([^"]+)"' \
    "$EXPECTED_KOTLIN" "disagrees with settings.gradle.kts" || true
}

# ---------------------------------------------------------------------------
# Walk
#
# .cache/ holds the CodeOnTheGo clone that scripts/update-libs.sh makes. It is
# gitignored and is not ours to hold to this standard; CI never sees it, so
# without this a local run reports dozens of failures CI does not.
#
# Files under src/main/assets/ are shipped project *templates* -- skeletons that
# Code on the Go stamps out for the user's own app. They are not our builds and
# must not be held to our toolchain.
# ---------------------------------------------------------------------------
while IFS= read -r -d '' f; do
  case "${f##*/}" in
    build.gradle.kts)          check_build_file "$f" ;;
    settings.gradle.kts)       check_settings_file "$f" ;;
    gradle-wrapper.properties) check_wrapper_file "$f" ;;
    libs.versions.toml)        check_catalog_file "$f" ;;
  esac
done < <(find . \
  \( -name build.gradle.kts \
     -o -name settings.gradle.kts \
     -o -name gradle-wrapper.properties \
     -o -name libs.versions.toml \) \
  -not -path './.git/*' \
  -not -path './.cache/*' \
  -not -path '*/build/*' \
  -not -path '*/src/main/assets/*' \
  -print0)

# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------
if [ "${1:-}" = "--list" ]; then
  printf '%s\n' "${declared[@]}" | sort
  exit 0
fi

if [ ${#failures[@]} -eq 0 ]; then
  echo "toolchain OK - compileSdk/targetSdk $EXPECTED_COMPILE_SDK, AGP $EXPECTED_AGP, Kotlin $EXPECTED_KOTLIN, Gradle $EXPECTED_GRADLE-$EXPECTED_GRADLE_DIST, Java 17"
  exit 0
fi

{
  echo
  echo "Toolchain drift: ${#failures[@]} problem(s)."
  echo
  printf '  %s\n' "${failures[@]}"
  echo
  echo "The standard is defined at the top of scripts/check-toolchain.sh and tracks"
  echo "Code on the Go's own on-device toolchain (ADFA-4907). Fix the files above, or"
  echo "-- if Code on the Go itself has moved -- update the script's constants and say"
  echo "which CoGo build you verified against."
  echo
} >&2
exit 1
