# Get AI Models

A Code on the Go plugin that adds a **Get AI Models** tab to the project editor's bottom drawer. The
tab lists a bundled catalog of small GGUF language-model files; each row downloads one `.gguf`
to `/sdcard/Download` and verifies its SHA-256.

**Download only.** Loading or running a model is the job of a separate AI plugin built around
llama.cpp. This plugin never loads a model and never checks the device's RAM. It does keep one small
record — which entries passed the checksum — so a verified file is not forgotten on restart; see
[Remembering what is verified](#remembering-what-is-verified).

## Build

```sh
cd get-ai-models
../gradlew assemblePlugin        # release .cgp -> build/plugin/get-ai-models.cgp
../gradlew assemblePluginDebug   # debug variant
../gradlew test                  # re-validates the shipped catalog asset
```

Uses the shared repo-root Gradle wrapper and the shared repo-root `libs/` jars — it carries no
wrapper or jars of its own. A `local.properties` containing `sdk.dir=<path-to-android-sdk>` is
required.

Install the resulting `.cgp` through Code on the Go's Plugin Manager, then open a project and look in
the editor's bottom drawer.

## Supported IDE versions

`26.29`–`26.31` (`plugin.min_ide_version` / `plugin.max_ide_version`) — covers the current stable
release and the two before it.

## What a row shows

Each row is **one file** — a model at one quantization — so the same model can appear more than once.

- Compact line: `parameters · quantization · on-disk size`
- Tap to expand: strengths-and-weaknesses paragraph, publisher, minimum RAM, licence, context window
- Minimum RAM is informational only; the device's memory is never read

## What happens on Download

1. **No connection** → blocked with a message, nothing queued.
2. **Metered connection** → warned with the file size; proceed or cancel.
3. **Transfer** → Android's `DownloadManager`. The Download button is replaced by a **Cancel**
   control whose background fills as bytes arrive, and the status line reads e.g.
   `Downloading 1.4 GB of 4.68 GB`. Tapping it cancels and discards the partial file.
4. **Paused is reported, not hidden.** A download started on an unmetered link will not roll over
   onto cellular by itself — it pauses, and the row says `Paused — waiting for Wi-Fi`. Same for
   `no connection` and `retrying`.
5. **Verification** → the finished file is streamed and hashed. Match: kept in `/sdcard/Download`.
   Mismatch: **deleted**, with a re-download offered.

Transfers and verification are plugin-scoped, so closing the tab mid-download does not abandon
either.

### Approved deviation: progress on the row

The ticket's locked decision is *"progress shown in the system notification... No in-app per-row
progress bar."* The row does show progress — as a fill behind the Cancel control — so **this is a
deviation, not a compliant reading of that rule.**

It was requested and approved after on-device testing: the system notification is dismissible, and
once dismissed the row could not distinguish a slow download from one paused indefinitely waiting for
Wi-Fi. There is no separate progress-bar widget, but that is a mitigation, not compliance.

## Remembering what is verified

A passed checksum is recorded in the plugin's own SharedPreferences (`entryId → {absolutePath,
sizeBytes, verifiedAt}`), because hashing costs seconds to minutes and its result is not cheaply
re-derivable. On activation each record is revalidated with **one `stat`** — a remembered badge is
never shown without checking the disk still agrees:

| On disk | Row shows |
| --- | --- |
| Path gone | `Download` — the record is dropped (you deleted or moved the file) |
| Present, size matches the record | `Downloaded` |
| Present, size differs | `Verify` — the checksum no longer holds, so it is not claimed |

The size check catches every truncated or interrupted file for free. The one case it cannot see is a
file swapped for a *different* file of identical length, so an expanded row offers **Verify file
again**, which re-hashes on demand. A failed re-verify **does not delete** the file — it was not
written by this download, so removing it is the user's call, unlike the hard delete on a failed
*download*.

### When Android reports no path

Everything above needs the finished download's absolute path, which comes from DownloadManager's
`COLUMN_LOCAL_URI`. That column is not guaranteed to be a `file://` URI — on newer platforms it can be
`content://downloads/all_downloads/<id>`, whose path (`/all_downloads/42`) is **not** a filesystem
path. Resolution therefore runs in three tiers: the `file://` URI, then `COLUMN_LOCAL_FILENAME` (which
still carries the real path), and finally the `content://` URI on its own.

The third tier still verifies — the bytes are hashed through a `ContentResolver` — but there is no path
to record or delete, so that row shows `Downloaded` for the session, says the location is unknown, and
offers neither **Verify file again** nor **Delete file**. A checksum failure is still deleted, via
`DownloadManager.remove()`, which does not need a path. The download is never simply failed for want of
a path, which is what a naive `File(uri.path)` produced.

### Approved deviation: this record is a model registry

The ticket lists *"any 'installed' state, model registry"* as out of scope. This record is one, so it
is a deviation — **requested and approved by the ticket owner**, not an author decision, after a
verified 4 GB download lost its `Downloaded` badge on every IDE restart while the file sat on disk.
Keeping the badge without revalidating it would instead have made it lie once the file was deleted.

It stays small and bounded: one JSON string per entry, cleared on uninstall. **Delete (below) extends
this same deviation** — it is what the record makes possible — rather than opening a new one. The
neighbouring exclusion, *"handling pre-existing files"*, is still honoured: nothing without a record
is ever read, verified, or deleted.

## Deleting a downloaded file

An expanded row with a recorded download also offers **Delete file** (red, destructive), which frees
the space and returns the row to `Download`. It asks first, naming the file and its full path.

The safety rule: it deletes **only the recorded absolute path** — a file this plugin wrote and hashed
— never a path composed from the catalogued file name. A same-named file you put in
`/sdcard/Download` yourself is never a candidate, which also keeps the plugin out of "handling
pre-existing files". The record is dropped only once the file is actually gone, so a failed delete
leaves the row claiming what is still true. Deletion runs on the IO dispatcher and is refused while a
verification is hashing.

Two caveats: if an AI plugin currently has the model loaded, the row clears but the space is not
reclaimed until that plugin releases the mapping; and the system Downloads list can keep showing the
file until Android rescans the folder.

## Catalog

A static JSON asset (`src/main/assets/catalog/models.json`). No remote catalog — a revised model list
ships as a new plugin version.

The ticket's hard gates are: instruction-tuned; fully open licence verified on both the base model
and the GGUF re-upload; loads and generates coherent output in our AI plugin; passes the fixed
tool-use eval at ≥ 80%; valid chat template; single file from a reputable uploader with the SHA-256
pinned to a specific revision; context window ≥ 16k tokens. Quantization is *not* restricted.

**The current catalog does not satisfy all of them.** The maintainer selected these six models
explicitly, waiving the instruction-tuned, open-licence and 16k-context gates for specific entries —
so `CatalogLoader` no longer enforces the licence or context gates, and the unit tests no longer
assert them. `docs/CURATION.md` records exactly which gate each entry breaks.

The catalog lists 7 files: Qwen2.5 0.5B (base), Qwen3 0.6B, Qwen3.5 0.8B, Llama 3.2 1B Instruct,
SmolLM2 360M Instruct, H2O Danube3 500M (base), all under 1 GB, and Qwen2.5-Coder 1.5B Instruct
(1.04 GiB), the only code-trained model. Six are Apache-2.0;
Llama 3.2 1B is under the Llama 3.2 Community Licence.

**The two behavioural gates are not yet proven.** Per the ticket, the admission harness that runs a
candidate through the AI plugin is downstream work, so the shipped catalog is the set of candidates
clearing every statically checkable gate. See [`docs/CURATION.md`](docs/CURATION.md) for the
per-entry evidence, the rejected candidates and why, and the procedure for adding an entry.

## In-IDE help

Long-press the tab, any row, or the Download / Cancel / Verify / Delete controls for Tier 1/Tier 2
tooltips (six entries); the tab's tooltip links to the full offline guide bundled at
`src/main/assets/docs/index.html`. Registered through `DocumentationExtension` under the category
`plugin_org.appdevforall.getaimodels`.

## Permissions

`network.access` (the HTTPS fetch), `filesystem.read` (re-read the download to hash it), and
`filesystem.write` (delete a download that fails the checksum, or one the user asks to remove). No
native code, no system commands, no IDE settings, no project-structure access.

## Full documentation

[`get-ai-models.html`](get-ai-models.html) — overview, architecture, and rationale.
