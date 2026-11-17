#!/bin/sh
# Fetches the demo catalog's pictures into Pictures/ShareYourAppsDemo: icons and screenshots
# only, from F-Droid, GitHub and appdevforall.org. It never downloads an APK or source code.
D=/storage/emulated/0/Pictures/ShareYourAppsDemo
missing=0

get() { # url, file
  if curl -fsSL -o "$2" "$1"; then echo "  ok   $2"; else echo "  FAIL $1"; missing=$((missing+1)); fi
}

fdroid() { # package, folder
  mkdir -p "$D/$2"; echo "$2"
  page=$(curl -fsSL "https://f-droid.org/packages/$1/") || { echo "  FAIL couldn't read the F-Droid page for $1"; missing=$((missing+3)); return; }
  icon=$(printf '%s' "$page" | grep -o "repo/$1/en-US/icon_[^\"]*\.png" | head -1)
  shots=$(printf '%s' "$page" | grep -o "repo/$1/en-US/phoneScreenshots/[^\"]*" | awk '!seen[$0]++' | head -2)
  if [ -n "$icon" ]; then get "https://f-droid.org/$icon" "$D/$2/icon.png"; else echo "  FAIL no icon on the page"; missing=$((missing+1)); fi
  n=0; for s in $shots; do n=$((n+1)); get "https://f-droid.org/$s" "$D/$2/screenshot-$n.png"; done
  while [ $n -lt 2 ]; do n=$((n+1)); echo "  FAIL no screenshot $n on the page"; missing=$((missing+1)); done
}

fdroid org.fdroid.fdroid f-droid
fdroid com.termux termux
fdroid com.aurora.store aurora-store
fdroid org.mozilla.fennec_fdroid fennec
fdroid at.bitfire.davdroid davx5
fdroid net.thunderbird.android thunderbird
fdroid ch.protonvpn.android proton-vpn
fdroid InfinityLoop1309.NewPipeEnhanced pipepipe
fdroid app.organicmaps organic-maps
fdroid com.foobnix.pro.pdf.reader librera

mkdir -p "$D/code-on-the-go" "$D/knowledge-to-go"
echo code-on-the-go
# The app icons come from each app's own GitHub repository, on its default branch.
get https://raw.githubusercontent.com/appdevforall/CodeOnTheGo/HEAD/app/src/main/ic_launcher-playstore.png "$D/code-on-the-go/logo.png"
get https://appdevforall.org/wp-content/uploads/2026/08/SketchToUI.png "$D/code-on-the-go/sketch-to-ui.png"
get https://appdevforall.org/wp-content/uploads/2026/08/plugin-mgr-light.png "$D/code-on-the-go/plugin-manager.png"
echo knowledge-to-go
get https://raw.githubusercontent.com/appdevforall/KnowledgeToGo/HEAD/docs/images/k2go-logo.png "$D/knowledge-to-go/logo.png"
get https://appdevforall.org/wp-content/uploads/2026/09/KnowledgeToGoPressRelease.png "$D/knowledge-to-go/announcement.png"

if [ $missing -eq 0 ]; then echo "All pictures fetched."; else echo "$missing picture(s) missing: remove them from those products in Manage Catalog, or publishing will stop."; fi
