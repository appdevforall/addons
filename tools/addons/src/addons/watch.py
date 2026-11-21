"""Watch the published catalog and tell Slack what was added or removed (ADFA-7231).

The published catalog, not this repo, is the record of what users see: not
every addon here is published. See docs/catalog-watcher-design.md.
"""
import json
import os
import urllib.request
from pathlib import Path

from addons import catalog

URL = f"{catalog.BASE}/v{catalog.LATEST}/catalog.json"
TIMEOUT = 30
# Cloudflare answers urllib's default User-Agent, Python-urllib/3.x, with a 403.
USER_AGENT = "appdevforall-addons-catalog-watcher"


class WatchError(Exception):
    pass


def fetch(url: str) -> dict:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            body = response.read()
    except OSError as e:  # URLError, HTTPError (non-2xx) and timeouts
        raise WatchError(f"could not fetch {url}: {e}") from e
    try:
        return json.loads(body)
    except ValueError as e:
        raise WatchError(f"{url} is not valid JSON: {e}") from e


def validate(document) -> None:
    """Refuse anything that would be misread as a mass removal or addition."""
    addons = document.get("addons") if isinstance(document, dict) else None
    if not isinstance(addons, list):
        raise WatchError("catalog has no addons array")
    if not addons:
        raise WatchError("catalog has 0 add-ons")
    seen = set()
    for i, addon in enumerate(addons):
        missing = [k for k in ("addonId", "name", "type")
                   if not isinstance(addon, dict) or not addon.get(k)]
        if missing:
            raise WatchError(f"add-on #{i} is missing {', '.join(missing)}")
        if addon["addonId"] in seen:
            raise WatchError(f"duplicate addonId {addon['addonId']}")
        seen.add(addon["addonId"])


def diff(old: dict, new: dict) -> tuple[list[dict], list[dict]]:
    """Added and removed by addonId, each sorted by name. Edits are not changes."""
    before = {a["addonId"]: a for a in old["addons"]}
    after = {a["addonId"]: a for a in new["addons"]}

    def by_name(ids, source):
        return sorted((source[i] for i in ids), key=lambda a: a["name"].casefold())
    return (by_name(after.keys() - before.keys(), after),
            by_name(before.keys() - after.keys(), before))


def message(added: list[dict], removed: list[dict]) -> str:
    lines = [f"➕ Added    {a['type']:<9} {a['name']}" for a in added]
    lines += [f"➖ Removed  {a['type']:<9} {a['name']}" for a in removed]
    return "*Add-ons catalog changes*\n```\n" + "\n".join(lines) + "\n```"


def post(webhook: str, text: str) -> None:
    request = urllib.request.Request(
        webhook, data=json.dumps({"text": text}).encode(),
        headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT):
            pass
    except OSError as e:
        # The webhook URL is the secret, so it never goes in the message.
        raise WatchError(f"Slack post failed: {e}") from e


def save(path: Path, document: dict) -> None:
    # Pretty and key-sorted, so the snapshot's git history reads as a changelog.
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(document, indent=2, sort_keys=True,
                               ensure_ascii=False) + "\n")


def run(url: str, snapshot: Path, webhook: str) -> str:
    """One check. The snapshot moves only after any Slack post succeeds."""
    current = fetch(url)
    validate(current)
    if not snapshot.exists():
        save(snapshot, current)
        return f"no snapshot; saved a baseline of {len(current['addons'])} add-ons"
    added, removed = diff(json.loads(snapshot.read_text()), current)
    if added or removed:
        post(webhook, message(added, removed))
    save(snapshot, current)
    return f"{len(added)} added, {len(removed)} removed"


def main(url: str, snapshot: Path) -> int:
    webhook = os.environ.get("SLACK_WEBHOOK_URL", "")
    if not webhook:
        print("ERROR: SLACK_WEBHOOK_URL is not set", flush=True)
        return 1
    try:
        print(run(url, snapshot, webhook))
        return 0
    except Exception as e:  # any failure, crash included, is reported to Slack
        reason = str(e) if isinstance(e, WatchError) else f"{type(e).__name__}: {e}"
        print(f"ERROR: {reason}", flush=True)
        logs = os.environ.get("RUN_URL", "")
        try:
            post(webhook, f"⚠️ Add-ons catalog check failed: {reason}. "
                          f"Snapshot not updated. {logs}".strip())
        except WatchError as again:
            print(f"ERROR: {again}", flush=True)
        return 1
