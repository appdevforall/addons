#!/usr/bin/env bash
#
# Puts Share Your Apps Server's release key where its build finds it, for a CI job.
# Writes the keystore from SERVER_SIGNING_KEYSTORE_BASE64 to $RUNNER_TEMP and exports the four
# SERVER_SIGNING_* variables that plugins/Share-Your-Apps/server/app/build.gradle.kts reads, through
# $GITHUB_ENV. The workflow passes the four repository secrets in as environment variables.
#
# Without the secrets it does nothing: plugins other than Share-Your-Apps build as before, and
# Share-Your-Apps' downloadAssets stops in CI rather than bundle an unsigned server.

set -euo pipefail

if [ -z "${SERVER_SIGNING_KEYSTORE_BASE64:-}" ]; then
    echo "No SERVER_SIGNING_KEYSTORE_BASE64 secret: Share Your Apps Server can't be signed in this run."
    exit 0
fi

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
