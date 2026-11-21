# Design Spec: Add-ons Catalog Watcher

## Summary

A daily job that checks the published Code on the Go add-ons catalog and posts to the `#documentation` Slack channel when add-ons are added to or removed from it.

## Background

The add-ons gallery is driven by a published catalog file:

`https://addons.appdevforall.org/v2/catalog.json`

A person publishes the catalog by hand whenever it needs updating. Its source lives in `https://github.com/appdevforall/addons`, but not everything in that repo ends up in the published gallery. That makes the published URL the only reliable record of what users can actually see. At the moment nobody gets told when that changes.

## Goals

- Tell the team, once a day, which add-ons were added to or removed from the published catalog since the previous check.
- Tell the team when the check itself fails, so a quiet channel reliably means "no changes."
- Keep a history of what was published and when.

## Non-goals

- Reporting edits to existing add-ons (version bumps, description changes, new downloads, and so on). Deciding which edits matter is out of scope for now.
- Detecting renames. A changed `addonId` is reported as one removal plus one addition.
- Catching changes that are made and then reverted between two checks.
- Watching the `appdevforall/addons` repo.

## Source data

The catalog is a JSON object:

| Field | Notes |
| --- | --- |
| `schemaVersion` | Integer, currently `2` |
| `generated` | ISO 8601 UTC timestamp of when the catalog was built |
| `addons` | Array of add-on records |

Only these fields of each record are used:

| Field | Use |
| --- | --- |
| `addonId` | Unique identity of an add-on (e.g. `org.appdevforall.rainbowbrackets`) |
| `name` | Display name in the Slack message |
| `type` | `plugin` or `template`, shown in the Slack message |

All other fields are ignored.

## Behavior

### Schedule

Runs once a day at 3:17 AM US Eastern. GitHub cron is UTC with no daylight saving, so the workflow schedules both 07:17 and 08:17 UTC and runs only the entry that is 3:17 AM in New York that day (`github.event.schedule` says which fired). The 17 minutes keep it off the top of the hour, when GitHub says scheduled runs are most often delayed or dropped.

### Daily run

1. Fetch the catalog from the published URL.
2. Validate it (see Validation). If validation fails, follow Error handling and stop.
3. Load the previous snapshot. If no snapshot exists (first run), save the current catalog as the baseline, post nothing, and stop.
4. Compare the sets of `addonId` values in the current catalog and the snapshot. **Added** means present now and absent from the snapshot. **Removed** means present in the snapshot and absent now; its name and type come from the snapshot.
5. If anything was added or removed, post one Slack message (see Slack message format).
6. Save the current catalog as the new snapshot, but only after any Slack post has succeeded. If the post fails, keep the old snapshot so the same changes get reported on the next run.

### Validation

A fetch counts as failed if any of these are true:

- The request fails, times out, or returns a non-2xx status.
- The response body is not valid JSON.
- `addons` is missing, isn't an array, or is empty.
- Any record is missing `addonId`, `name`, or `type`.
- Two records share the same `addonId`.

The empty-catalog rule matters most. Without it, a broken publish would show up as every add-on being "removed."

### Error handling

When a run fails for any reason (validation, Slack post failure, or job crash):

- Post an error message to the same Slack channel. It should say what failed and link to the job run's logs.
- Leave the existing snapshot untouched.

If the error message can't be posted either, the job run should still be marked as failed, so the failure shows up in the CI platform.

### Slack message format

One message per run, posted only when there are changes. Each entry shows the action, the type, and the name. Additions come first, then removals, each group sorted alphabetically by name.

Example:

```
Add-ons catalog changes

➕ Added    plugin    Rainbow Brackets
➕ Added    template  Kotlin Console App
➖ Removed  plugin    Old Theme Pack
```

Example error message:

```
⚠️ Add-ons catalog check failed: catalog has 0 add-ons. Snapshot not updated. <link to run logs>
```

Exact wording and emoji are up to the builder.

## Implementation

### Platform

GitHub Actions, on a scheduled workflow.

The reasons for choosing it over n8n:

- Committing the snapshot gives a free, inspectable history of what was published and when.
- The diff logic is plain code under version control, changed through PRs.
- The state is a readable file rather than hidden workflow data.

### State

- Store the snapshot as `catalog-snapshot.json` on the `catalog-watcher-state` branch of this repo, not on `main`. The `main` ruleset requires a reviewed pull request and `GITHUB_TOKEN` is not on its bypass list. The first run creates the branch as an orphan.
- Commit the snapshot on every successful run, even when nothing changed, with a `last-checked` timestamp file so there's always something to commit. GitHub does not define what "repository activity" resets its 60-day schedule timer, so whether these bot commits on a non-default branch count is unverified; this repo's own pull request traffic is what keeps it active.
- Store the snapshot pretty-printed with stable key ordering, so its git history is a readable record of catalog changes.

### Language

Python, standard library only if practical (`urllib`, `json`).

### Secrets

- `SLACK_WEBHOOK_URL`: an incoming webhook for `#documentation`, stored as a GitHub Actions **secret**.
- `ADDONS_CATALOG_URL`: `https://addons.appdevforall.org/v2/catalog.json`, stored as a GitHub Actions **variable**, since it is public.

### Code

The logic is `addons watch` in `tools/addons` (`src/addons/watch.py`), tested by `tests/test_watch.py`, which follows the acceptance criteria below. The workflow is `.github/workflows/watch-catalog.yml`.

### Manual run

Support `workflow_dispatch` so the job can be run by hand to test it or right after a publish.

## Acceptance criteria

1. With no snapshot, a run saves a baseline and posts nothing.
2. With an unchanged catalog, a run posts nothing and commits.
3. When one add-on is added, the channel gets one message listing it with name, type, and "added."
4. When one add-on is removed, the channel gets one message listing it with the name and type from the snapshot, and "removed."
5. When the same day has both additions and removals, they arrive in one message, additions first.
6. A changed `addonId` shows up as one removal and one addition.
7. Edits to other fields of an existing add-on produce no message.
8. An unreachable URL, invalid JSON, an empty `addons` array, or duplicate IDs each produce an error message and leave the snapshot unchanged.
9. If the Slack post fails, the snapshot stays unchanged and the next successful run reports the same changes.
10. The scheduled workflow is still enabled after 60+ days without any other repo activity.

## Decisions

1. The workflow and snapshot live in the `addons` repo, with the snapshot on its own branch (see State). The channel is `#documentation`.
2. A `schemaVersion` other than `2` is not an error. The job carries on as long as the fields it needs are present. The URL is versioned (`v2/`), so a new major is a new URL and the variable changes with it.
