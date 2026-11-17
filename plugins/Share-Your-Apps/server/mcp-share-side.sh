#!/bin/sh
# Project A second-phone tests on the SHARING phone (phone 1). Opens shares with MCP for the importing
# phone's tests (6, 9, 11) and checks test 10: the "asks for a copy" notification for a share MCP opened.
#   sh mcp-share-side.sh 2>&1 | tee share-result.txt
# Needs MCP_TOKEN (this phone's token), and this phone's own store published.
set -u
. "$(dirname "$0")/mcp-lib.sh"
echo "Sharing phone, $(date)"
start_session || exit 1

call server_status
if has '"serving":"copy"'; then
  echo "This phone is serving an imported copy, which can't be shared."
  echo "Tap Stop in the app, then run this again: it starts this phone's own store."
  exit 1
fi
if [ "$(field state)" != serving ]; then
  info "The server isn't serving; starting it with this phone's own store."
  call server_start '{"store":"own","wait_seconds":25}'
  if [ "$(field state)" = serving ]; then pass "server_start serves this phone's own store"
  else fail "server_start: $(field state)"; show; summary; fi
fi

T10=0
while :; do
  call share_start
  if is_error; then fail "share_start opened a share"; show; break; fi
  n=0
  while [ "$(field state)" = preparing ] && [ "$n" -lt 4 ]; do
    info "the store is being prepared; waiting for the code…"
    call share_status '{"wait_seconds":20}'
    n=$((n + 1))
  done
  CODE="$(field code)"; LINK="$(field link)"
  if [ -z "$CODE" ]; then fail "share_start gave a code"; show; break; fi
  pass "share_start gave a code"
  if has '"already_open":true'; then info "a share was already open; this is that share"; fi
  if [ -n "$(field warning)" ]; then info "warning: $(field warning)"; fi
  echo
  echo "   =========================================="
  echo "     Code:  $CODE"
  echo "   =========================================="
  echo "     Link:  $LINK"
  echo "   Type the code into mcp-import-side.sh on phone 2. It works once, for 10 minutes."

  LAST=; S=; ENTERED=0; END=$(( $(date +%s) + 720 ))
  while :; do
    call share_status '{"wait_seconds":20}'
    if is_error; then info "share_status failed:"; show; S=; break; fi
    S="$(field state)"
    if [ "$S" != "$LAST" ]; then
      info "share: $S $(field phone)"
      LAST="$S"
      if [ "$S" = waiting_for_allow ]; then
        CHECK="$(field check_code)"; PHONE="$(field phone)"; STORE="$(field store)"
        echo
        echo "   =========================================="
        echo "     Check code:  $CHECK   (compare with phone 2)"
        echo "   =========================================="
        if has 'Notifications are off'; then info "notifications are off for this app on this phone"; fi
        if [ "$T10" = 0 ]; then
          T10=1
          by_eye "test 10: the \"asks for a copy\" notification appeared" \
            "Did a notification \"${PHONE:-A phone} asks for a copy of ${STORE:-your store}\" appear on this phone?"
          by_eye "test 10: tapping it opened the share screen with Allow" \
            "Tap it. Did the share screen open with Allow and the check code $CHECK?"
        fi
        echo ">>> Tap Allow (or Deny) on this phone. For test 9, tap nothing and let phone 2 cancel."
        wait_enter "When you've tapped, or phone 2 has cancelled,"
        ENTERED=1
      fi
    fi
    case "$S" in done|denied|expired|closed|not_serving|cant_share) break ;; esac
    if [ "$ENTERED" = 1 ] && { [ "$S" = waiting_for_allow ] || [ "$S" = phone_connecting ]; }; then
      if confirm "The share is still $S. Close it now?"; then
        call share_stop; info "share_stop: $(field reason)"; S=closed; break
      fi
    fi
    if [ "$(date +%s)" -ge "$END" ]; then info "still $S after 12 minutes"; break; fi
  done
  info "the share ended: ${S:-unknown} $(field reason)"
  case "$S" in done|denied|expired|closed|not_serving|cant_share) ;; *) call share_stop ;; esac
  confirm "Open a new share for the next test?" || break
done
summary
