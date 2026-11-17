#!/usr/bin/env bash
#
# Puts Share Your Apps Server's release key where its build finds it, for a CI job.
# Writes the keystore from SERVER_SIGNING_KEYSTORE_BASE64 to $RUNNER_TEMP and exports the four
# SERVER_SIGNING_* variables that plugins/Share-Your-Apps/server/app/build.gradle.kts reads, through
# $GITHUB_ENV. The workflow passes the four repository secrets in as environment variables.
#
# Each workflow's Pre-flight checks step has already failed the run if any of the four is missing.

set -euo pipefail

store="$RUNNER_TEMP/share-your-apps-server.p12"
printf '%s' "$SERVER_SIGNING_KEYSTORE_BASE64" | base64 --decode > "$store"
chmod 600 "$store"
{
    echo "SERVER_SIGNING_STORE_FILE=$store"
    echo "SERVER_SIGNING_STORE_PASSWORD=$SERVER_SIGNING_STORE_PASSWORD"
    echo "SERVER_SIGNING_KEY_ALIAS=$SERVER_SIGNING_KEY_ALIAS"
    echo "SERVER_SIGNING_KEY_PASSWORD=$SERVER_SIGNING_KEY_PASSWORD"
} >> "$GITHUB_ENV"
echo "Share Your Apps Server's release key is in place."
