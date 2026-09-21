# Design — Addon Distribution & Repository Normalization

| | |
|---|---|
| **Status** | Draft for review |
| **Owner** | Hal Eisen, App Dev For All |
| **Date** | 2026-09-04 |
| **Implements** | [PRD — Addon Distribution & Repository Normalization](2026-08-27-addon-distribution-prd.md) |
| **Repo** | `appdevforall/plugin-examples` |

> **Scope of this document.** Mechanism. It says how the requirements in the PRD are met — file layouts, tool contracts, object keys, headers, workflow structure, and migration order. It does not restate the requirements; it references them by ID and closes with a traceability matrix covering all 49.

---

## 1. Summary

Three changes, in this order of importance:

1. **Built addons go straight from the build job to Cloudflare R2.** No GitHub Actions artifact is written at any point. This removes the quota problem permanently rather than managing it, and makes addon size irrelevant to it forever.
2. **R2 becomes the only published home** for artifacts, description pages, source tarballs, the catalog, and the gallery. GreenGeeks is removed from the delivery path.
3. **A single Python tool owns addon identity.** It derives names from directories, assembles source tarballs, generates and validates the catalog, and uploads. Because it runs offline against fixtures, the naming and metadata rules are enforced on **every pull request**, not discovered at release time.

The tool is the load-bearing piece. Everything else is configuration.

---

## 2. Decisions

Each was taken during design and is recorded with its rationale so a later reader does not relitigate it.

| ID | Decision | Rationale |
|---|---|---|
| **D01** | A `uv`-managed Python tool, with workflows as thin drivers. | The repo is bash + Actions YAML + Gradle today, so this adds a third toolchain. It buys the one thing bash cannot: the whole pipeline runs offline against fixtures, so `check-toolchain.yml` can enforce naming and metadata on every PR. Without that, R28 is a convention, not a check. The prototype already proved this shape works. |
| **D02** | Root routing by Cloudflare **Transform Rule**, not a Worker, not a redirect. | Configuration only — no deployable code, nothing to version. **Verified live** (§4, V01). A redirect would work but changes the visible URL to `/index.html`. |
| **D03** | No cache purge. Bounded staleness via `Cache-Control` at upload. | Cloudflare does not edge-cache HTML or JSON by default, so the catalog, the pages, and the downloads are already current the instant a publish completes. Only icons and tarballs need bounding. This removes the second credential that C04 anticipated: the design needs exactly **one** bucket-scoped token. |
| **D04** | Publishing is decoupled from the `libs/` rebuild. | Today shipping one fixed addon means a ~30-minute rebuild of everything. A separate publish workflow makes a one-addon hotfix a short job and gives R09 a natural home. |
| **D05** | A source tarball is **flat**: the archive root is the addon's project root, with the shared jars in its own `libs/`. | Code on the Go reads a folder as a plugin project only when `build.gradle.kts` and `libs/plugin-api.jar` sit in that same folder (`isPluginProject`, `ProjectValidations.kt`), and it picks `assemblePlugin` over `assembleDebug` by the same test (`ProjectManagerImpl.setup`). A two-level mini-repo mirroring this repository needs no path rewriting, but no level of it can be opened on a phone — so the addon that ships the source cannot build it. Rewriting is confined to `(../)+libs/` → `libs/` in the two Gradle files, and §9.6 refuses any archive where a parent reference survives. |
| **D06** | Gallery chrome is injected at publish time, not committed. | The 31 existing pages stay plain HTML that anyone can edit. The published site is still coherent, with no duplicated chrome in git and no manual cache-bust chore. |
| **D07** | Icons publish as-is; CSS sizes the tile. | Ships icons now at no cost. The one 24×24 icon will look soft and gets a follow-up issue rather than blocking this work. |
| **D08** | R04 is amended to bounded staleness. | Follows from D03. New text in §12.4. |
| **D09** | Curated metadata lives in a per-addon `addon.json`; the directory name remains the sole source of identity. | R12 and R23. Anything derivable is derived, never written twice. |
| **D11** | The catalog is a **versioned public contract**, published at a `v1/` path with a `schemaVersion` inside. | Its main consumer is an Android app that cannot be force-updated. A fielded build must keep working indefinitely, so a breaking change needs a second path rather than an edit to the first. |
| **D12** | Every catalog field is always present; no `null`, no omission. | The app deserializes with a bare Gson instance, which silently writes `null` into non-null Kotlin properties for missing keys. A contract that never omits a field cannot trigger that. |
| **D10** | Bucket CORS allows `GET`/`HEAD` from any origin. | The content is public, unauthenticated, and read-only, so an origin allowlist protects nothing — and an Android WebView sends `Origin: null`, which an allowlist would reject. |

---

## 3. What exists today, and what happens to it

| Existing | Disposition |
|---|---|
| `build-plugins.yml` `publish` job (matrix over ~29 plugins) | **Deleted.** This is P02 — ~15 GB of transfer per run producing nothing new. |
| `build-plugins.yml` `all-plugins-cgp` bundle upload | **Deleted.** This is the other half of P01. |
| `update-libs.yml` scp block + `GREENGEEKS_SSH_*` secret and vars | **Deleted.** This is P04. |
| `update-libs.yml` GitHub Release step | **Untouched**, by instruction. Releases keep shipping module-named `.cgp` files as they do now. |
| `update-libs.yml` website-filename staging loop with two legacy `case` arms | **Deleted.** Superseded by the identity rules in §6. |
| `scripts/update-libs.sh` | **Kept**, with one fix (§13.1) and its discovery block replaced by a call to the tool. |
| `scripts/check-toolchain.sh` | **Untouched.** Its broader module walk is deliberate and must not be merged with addon discovery. |
| The 31 top-level `*.html` description pages | **Kept and renamed** to `<slug>.html`. Content unchanged. |
| The 18 `src/main/assets/docs/index.html` in-IDE pages | **Untouched.** Different purpose, shipped inside the `.cgp`. |
| `libs/` | **Kept** as the shared source of truth. Not vendored per addon. |
| Four copies of the discovery predicate, three copies of the skip list | **Replaced** by one implementation (§8.1). |

---

## 4. Verified platform facts

Every fact the design depends on was checked. Nothing below is assumed.

| ID | Fact | Evidence |
|---|---|---|
| **V01** | A Transform Rule rewrites `/` to `/index.html` on the R2 custom domain. | **Verified live** against `https://addons.appdevforall.org/`: 200, byte-identical body and identical `last-modified` to `/index.html`, no 3xx, no `Location`. |
| **V02** | The rule needs no regex, so the Cloudflare **Free** plan suffices (10 active rules). | Expression `http.request.uri.path eq "/"`, action Rewrite → Path → Static → `/index.html`. |
| **V03** | R2 has no static-website hosting. `PutBucketWebsite` is unimplemented. | Cloudflare S3 API compatibility table. This is why V01 is necessary. |
| **V04** | Cloudflare does not edge-cache HTML or JSON by default. | Documented, and **confirmed live**: the root response carried `cf-cache-status: DYNAMIC`. |
| **V05** | R2 stores and honors `Content-Type`, `Cache-Control`, and `Content-Disposition` set as system metadata on `PutObject`. | Cloudflare S3 API compatibility table. |
| **V06** | Files over 512 MB are never edge-cached on Free/Pro/Business. | Affects the three large addons (P03). Irrelevant — egress is unmetered. |
| **V07** | A custom domain connected to a bucket with a CORS policy returns CORS headers automatically. | Cloudflare R2 CORS documentation, verbatim. |
| **V08** | The AWS SDK CRC32 checksum incompatibility is **historical**. | Real in January 2025; Cloudflare removed every workaround note on 2025-02-11 with the rationale "now that R2 is compatible for most clients". Current boto3 `PutObject` needs no flag. **Do not carry that workaround into the code.** |
| **V09** | `PutBucketCors` still rejects the checksum header current SDKs send. | Configure CORS via the dashboard or `wrangler`, never `aws s3api put-bucket-cors`. |
| **V10** | Changing a CORS policy on a bucket already serving traffic requires one cache purge for the hostname. | One-time setup step. HTML and JSON are unaffected (V04). |
| **V11** | The CORS policy in §12.5 is applied and behaves correctly. | **Verified live**: a GET with an `Origin` returns `access-control-allow-origin: *` and the four expected `expose-headers`; an `Origin: null` request (the WebView case) is allowed; an `OPTIONS` preflight requesting `Range` returns 204 with `allow-methods: GET, HEAD` and `max-age: 86400`; a request with no `Origin` returns no CORS headers. |
| **V12** | Cloudflare injects `/cdn-cgi/challenge-platform/scripts/jsd/main.js` into HTML responses on this zone. | Bot Fight Mode JavaScript Detections is enabled. Detecting only, not blocking — a plain `curl` received a clean 200. See risk K10. |

---

## 5. Repository layout

```
plugins/<Addon-Name>/       migrated addons (R30)
  addon.json                curated metadata (§7)
  <slug>.html               gallery description page (R32, R35)
  build.gradle.kts
  settings.gradle.kts
  src/main/...
templates/                  exists, README placeholder
snippets/                   blocked until the addon renames, below
code-actions/               exists, README placeholder
libs/                       shared jars, unchanged
site/                       gallery source (§11)
  index.html
  app.js
  styles.css
  catalog.schema.json
  assets/
tools/addons/               the Python tool (§8)
  pyproject.toml
  uv.lock
  src/addons/
  tests/
  fixtures/
scripts/                    existing bash, unchanged except §13.1
```

`plugins/`, `templates/`, and `code-actions/` already exist as README-only placeholders. **All four addon types live in this repository.** That is settled, not reserved (PRD Q4).

`snippets/` is the exception, and it is an ordering constraint rather than a preference. That name is currently occupied by the Favorite Snippets addon. The type directory cannot be created until that addon is renamed to `Favorite-Snippets` and moved under `plugins/`, so the rename must precede it during migration step 3.

---

## 6. Identity derivation

The directory name is the only input. Everything else is computed. There is no override (R27).

| Derived value | Rule | Example |
|---|---|---|
| Display name | Directory, hyphens replaced by spaces | `Keystore-Generator` → `Keystore Generator` |
| Slug | Directory, lowercased | `Keystore-Generator` → `keystore-generator` |
| Artifact key | `dl/<slug>.cgp` | `dl/keystore-generator.cgp` |
| Page key | `p/<slug>.html` | `p/keystore-generator.html` |
| Tarball key | `src/<slug>-src.tar.gz` | `src/keystore-generator-src.tar.gz` |
| Icon key | `p/<slug>.png` | `p/keystore-generator.png` |
| Type | Parent directory, singularised | `plugins/` → `plugin` |

Directory names use MixedCase words separated by hyphens, with small words left lowercase (R24) — `Project-to-Template` yields "Project to Template", not "Project To Template". The small-word list is a constant in the tool, not a per-addon setting.

This example is deliberate: `Keystore-Generator`'s slug is **unchanged** by the migration, its manifest `plugin.name` already agrees with the derived display name, and it injects `${pluginVersion}` properly rather than hardcoding it. It is what compliance looks like.

`addons check` fails when any of the following disagree with the directory: the `pluginName` configured in `build.gradle.kts`, the `plugin.name` in `AndroidManifest.xml`, the description-page filename, or the `<title>` of that page. This is the enforcement R28 requires, and it runs on every pull request.

**Runtime identity is explicitly out of scope of this derivation.** `plugin.id`, `namespace`, and `applicationId` are never touched (R29). A rename changes what a user reads; it must not change what the IDE keys on. `addons check` treats a changed `plugin.id` as an error.

---

## 7. `addon.json`

One per addon, beside `build.gradle.kts`. It holds only what cannot be derived.

```json
{
  "summary": "Creates and manages app signing keystores on the device.",
  "description": "A longer paragraph shown on the card and the description page.",
  "tags": ["signing", "release"],
  "origin": "appdevforall",
  "license": "AGPL-3.0-or-later",
  "minAppVersion": "1.4.0"
}
```

Community addons carry an author (R15, R36–R38):

```json
{
  "origin": "community",
  "author": { "name": "Aman Khan", "url": "https://github.com/aman-khan-786/cotgx-ndk" }
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `summary` | string, 1–120 chars | yes | One line. Card text. |
| `description` | string | yes | Paragraph. |
| `tags` | array of `^[a-z0-9][a-z0-9-]*$`, ≥1 | yes | Lowercase, hyphenated. |
| `origin` | `appdevforall` \| `community` | yes | |
| `license` | SPDX identifier | yes | |
| `author` | `{ name, url? , email? }` | when `origin` is `community` | `email` only with that person's consent (R37). |
| `minAppVersion` | string | no | |

`additionalProperties` is false. An unknown key is an error, not a warning — a typo that silently drops a field is exactly the failure R16 forbids.

**Never in this file:** name, slug, type, `pluginId`, any URL, any checksum, any size, or a version. Each is derived or computed. Writing one by hand creates a second source of truth, which is what P05 already cost us once.

**Accepting outside contributions is out of scope** (PRD Q3). How a community submission reaches this repository — pull request, proxy, or otherwise — is not designed here. The `origin` and `author` fields remain because one community-originated addon already exists and will be published once its attribution is settled. Crediting correctly is a requirement; building a submission pipeline is not.

### 7.1 Bootstrapping the 31 files

The 31 files are written by hand during the migration, with the existing description page open beside them. A throwaway script may draft them first, but it is not part of the tool and it is not kept. A permanent subcommand for a job that runs once, on 31 files, costs more than it saves.

---

## 8. The tool — `tools/addons/`

One `uv` project, one console entry point, six subcommands. They share the addon model and the identity rules; splitting them into separate scripts would recreate the duplication that F06 already documents.

```
uv run --directory tools/addons addons <subcommand> [options]
```

### 8.1 `addons discover`

The single implementation of the build-file predicate and the skip list. Scans both `*/build.gradle.kts` and `plugins/*/build.gradle.kts` for `com.itsaky.androidide.plugins.build`, so addons can move in batches (R31). Emits JSON on stdout.

`scripts/update-libs.sh` and `.githooks/pre-push` call it instead of carrying their own copies. The skip list moves into `tools/addons/skip.txt`, one name per line with a required reason comment — today's single entry has no recorded reason, which is how it came to look published while never being built.

`scripts/check-toolchain.sh` keeps its own broader module walk. Its header explains why: subprojects such as `plugins/AI-Agent-Local/llama-api` are invisible to a top-level-directory rule yet must still obey the toolchain. **Do not unify these two.**

### 8.2 `addons check`

Offline. No network, no build. Verifies:

- identity agreement across directory, `pluginName`, manifest `plugin.name`, page filename, and page `<title>` (R28);
- `addon.json` present, parseable, and schema-valid (R16);
- `author` present when `origin` is `community` (R15);
- the description page exists and is non-empty (R34).

Exit non-zero with one line per failure naming the file and the disagreement. Runs on every pull request.

### 8.3 `addons tarball <slug>`

Assembles one source tarball (§9).

### 8.4 `addons catalog`

Generates `v1/catalog.json` (§10).

### 8.5 `addons page <slug>`

Wraps a description page in gallery chrome (§11.2).

### 8.6 `addons publish`

Uploads to R2 (§12).

### 8.7 Testing

`pytest` over fixtures, no network. Every subcommand except `publish` runs fully offline. `publish` is tested against a stubbed S3 client; its argument construction — keys, `Content-Type`, `Cache-Control`, `Content-Disposition` — is asserted directly, because those are the values that silently produce the wrong browser behavior (R05) and cannot be checked any other way before a real upload.

The test suite runs in `check-toolchain.yml` alongside `addons check`.

---

## 9. Source tarballs

### 9.1 Shape

A tarball is **flat**: the archive root is the addon's project root.

```
keystore-generator-src/
  BUILDING.md                  generated (§9.4)
  README.md                    the addon's own, untouched
  build.gradle.kts             ../../libs/ rewritten to libs/
  settings.gradle.kts          ../../libs/ rewritten to libs/
  src/...
  gradlew                      the addon's own, or the repo root's
  gradlew.bat
  gradle/wrapper/
  libs/
    plugin-api.jar             only the jars this addon references
    gradle-plugin.jar
```

Build instructions are then `./gradlew assemblePlugin` in the folder as unpacked.

This is the point of D05. Code on the Go tests for `build.gradle.kts` **and** `libs/plugin-api.jar` in the folder it is given, so only a flat archive can be opened on a phone at all. The cost is the one thing X2/R43 warns about — a rewrite — held to `(../)+libs/` → `libs/` in the two Gradle files, which are the only tracked files that reference the shared jars by path. §9.6 fails the run if any parent reference survives, so a missed rewrite cannot be published.

Flattening merges three sources into one directory: the addon's tracked files, the shared jars, and the Gradle wrapper. Every write claims its path first and a second claim is a hard error, so an addon's own `libs/` jar (`Code-Together/libs/shared.jar`) sits beside the shared ones, while a name clash with them stops the run instead of silently overwriting (X2). An addon that tracks its own wrapper keeps it; only the few without one get the repository's.

### 9.2 Deriving the jar set (R41)

A fixed list is not acceptable, because the root `libs/` holds five jars and different addons reference different subsets. The tool parses `build.gradle.kts` and `settings.gradle.kts` for references matching `\.\./libs/([A-Za-z0-9._-]+\.jar)` in `compileOnly(files(...))` and buildscript `classpath(files(...))` positions, and copies exactly that set.

A referenced jar missing from `libs/` is a hard error (R47).

### 9.3 File selection (R44)

The addon's file list comes from `git ls-files` for that directory, **not** from the working tree.

This is deliberate and load-bearing. `local.properties` is gitignored but present on disk in 27 addon directories, and contains a developer's SDK path **and Sentry DSNs**. Sourcing from the index makes that leak structurally impossible rather than filtered — there is no exclusion list to forget to update. `build/`, `.gradle/`, and every other ignored path are excluded by the same mechanism.

### 9.4 Generated `BUILDING.md`

States the build command, the addon's license, its origin and author where applicable, and the URL it came from. For any addon that cannot be made self-contained, it states the prerequisite **at the top**, before the build instructions (R46).

It is not called `README.md`: 18 of the 23 addons ship a README of their own, which now lands at the archive root and is the file a reader wants first. The generated notes sit beside it rather than over it.

Exactly one addon is in that category today: `ai-agent-local` depends on the `subprojects/llama.cpp` git submodule, which `git ls-files` does not descend into. Its `BUILDING.md` leads with the clone command for that submodule. This is the single deliberate exception to R40.

### 9.5 Fetched assets (R45)

Addons that fetch large assets at build time keep their `downloadAssets` task. The assets are not bundled. Those fetches must resolve to a host App Dev For All controls; moving them to the `addons` bucket is in scope of this change and is what makes K08 acceptable.

### 9.6 Verification (R48)

After assembly and before upload, every tarball is checked:

1. every jar named in the build files is present in `libs/`;
2. no member path escapes the tarball root, and no path is absolute;
3. `gradlew` and `gradle/wrapper/gradle-wrapper.properties` are present;
4. no `local.properties`, no `.gradle/`, no `build/`, no file matching a credential pattern;
5. `build.gradle.kts` and `settings.gradle.kts` are present **at the archive root**, which is what makes the folder openable in Code on the Go;
6. neither of those two files still references a parent directory — a `../` that survived the rewrite would resolve outside the archive and fail only once someone unpacked it and ran a build.

Any failure aborts the run (R47). Nothing partial is uploaded.

**What this does not prove.** These checks establish that a tarball is well-formed. They do not establish that it compiles — a missing transitive dependency or a toolchain mismatch would pass all five and still fail at build time. This limit is accepted knowingly and recorded as K07. A periodic build-one-tarball job is the obvious later mitigation and is out of scope here.

---

## 10. The catalog

The gallery reads this file. So, before long, will Code on the Go — and that second consumer is what makes the catalog different from every other object we publish.

The gallery ships from the same bucket as the catalog, so a mistake in the format costs one republish of both. A Code on the Go build cannot be fixed that way. Once a released build parses this document, that build exists in the field indefinitely and can never be corrected. **The catalog is therefore a public contract, not an implementation detail**, and everything below follows from that.

### 10.1 Consumers

| Consumer | Needs | Status |
|---|---|---|
| The gallery | Everything, rendered as cards. Same-origin. | Built here. |
| Code on the Go | To answer three questions: *what exists*, *do I already have it*, and *is mine current*. | **Nothing exists app-side yet** — see §10.8. |
| Third-party tools | A documented, versioned, validatable document. | Enabled by publishing the schema. |

A survey of the app found no remote catalog, no listing model, no version comparison, and no HTTP stack. The format is therefore entirely ours to define — with the corresponding obligation to define it well, because there is no prior art to inherit blame from.

### 10.2 Versioning

Two mechanisms, deliberately both:

- **A versioned path.** The catalog is published at `v1/catalog.json`. A breaking change publishes `v2/catalog.json` while `v1` keeps being written, so a fielded app build keeps working forever. Storage is free; an abandoned `v1` costs nothing.
- **A `schemaVersion` integer inside the document.** A consumer that has cached or logged the payload alone still knows what it is holding.

**The `v1/` prefix versions the contract only.** Download URLs stay at `dl/<slug>.cgp` — R03 requires those be stable and slug-derived, and revising the contract must never move an addon's download.

### 10.3 The document

```json
{
  "schemaVersion": 1,
  "generated": "2026-09-04T18:00:00Z",
  "addons": [
    {
      "type": "plugin",
      "slug": "keystore-generator",
      "pluginId": "com.appdevforall.keygen.plugin",
      "name": "Keystore Generator",
      "version": "1.3.0",
      "summary": "Creates and manages app signing keystores on the device.",
      "description": "A longer paragraph shown on the card and the description page.",
      "origin": "appdevforall",
      "license": "AGPL-3.0-or-later",
      "tags": ["signing", "release"],
      "author": { "name": "App Dev For All", "url": "https://www.appdevforall.org" },
      "minAppVersion": "25.47",
      "iconUrl":   "https://addons.appdevforall.org/p/keystore-generator.png",
      "pageUrl":   "https://addons.appdevforall.org/p/keystore-generator.html",
      "sourceUrl": "https://github.com/appdevforall/plugin-examples/tree/main/plugins/Keystore-Generator",
      "download":      { "url": "https://addons.appdevforall.org/dl/keystore-generator.cgp",     "sha256": "…", "size": 1234567 },
      "sourceTarball": { "url": "https://addons.appdevforall.org/src/keystore-generator-src.tar.gz", "sha256": "…", "size": 234567 }
    }
  ]
}
```

### 10.4 Fields

| Field | Type | Source | Why it exists |
|---|---|---|---|
| `type` | `plugin` \| `template` \| `snippet` \| `code-action` | Parent directory | Lets the other addon types join without a schema change (R13). |
| `slug` | `^[a-z0-9]+(-[a-z0-9]+)*$` | Directory, lowercased | Distribution identity. Unique across the catalog. |
| `pluginId` | string | `AndroidManifest.xml` | **The app's identity key.** It names the installed file, keys the loaded-plugin map, keys the enabled-state store, and drives conflict detection. Without it the app cannot answer "do I already have this". |
| `name` | string | Directory, hyphens to spaces | Display name (R25). |
| `version` | dotted numeric | `build.gradle.kts`, then the manifest, then the Gradle plugin's default | Answers "is mine current". Ordering defined in §10.7. |
| `summary` | string, 1–120 | `addon.json` | Card text. |
| `description` | string | `addon.json` | Paragraph. |
| `origin` | `appdevforall` \| `community` | `addon.json` | R15. |
| `license` | SPDX identifier | `addon.json` | AGPL obligation is visible before download. |
| `tags` | array of strings | `addon.json` | Filtering (R18). |
| `author` | `{ name, url }` | `addon.json` | Credit (R36–R38). Always present, including for first-party addons. |
| `minAppVersion` | `YY.ww` | `addon.json` | Lets a consumer hide what it cannot run. Ordering in §10.7. |
| `iconUrl`, `iconDarkUrl`, `pageUrl` | absolute URL | Derived | Display. Both icon variants ship; the gallery picks by theme. |
| `sourceUrl` | absolute URL | Derived | **Provenance only.** The gallery's Source link points at the tarball, which is the actual source deliverable under R39. This field records where the addon lives in the repository, for consumers that want it. |
| `download` | `{ url, sha256, size }` | Computed at publish | Integrity and progress (R14). |
| `sourceTarball` | `{ url, sha256, size }` | Computed at publish | R49. |

Two properties of this table matter more than any individual row.

**Every URL is absolute.** A relative path is fine for the gallery and hostile to every other consumer — an app that fetches the catalog and then needs to download an artifact would have to reconstruct a base it was never told. Absolute costs a few hundred bytes per entry and removes a whole class of consumer bug.

**`version` is read from plain text, in three steps.** The tool reads `pluginVersion` from `pluginBuilder { }` in `build.gradle.kts`. If that is absent it reads `plugin.version` from the manifest and uses it when it is a literal rather than a `${...}` placeholder. If both are absent it uses `1.0.0`, the Gradle plugin's own default.

This yields exactly what ships, because the Gradle plugin injects that same default. An earlier draft read the version out of the built `.cgp`. That needed `aapt2` to decompile a binary manifest and returned identical values — cost with no benefit — so it is gone.

**Today every addon reports the same version.** No addon sets `pluginVersion`, so 30 of them ship `1.0.0`. Only `icons-repository` differs, at `1.0.1`, because it hardcodes the value. The catalog reports this truthfully. It does not become useful until someone sets real versions (O8).

### 10.5 Compatibility rules

These are the contract. They bind us, not just consumers.

**The producer must not**, within a major version: remove a field, rename a field, change a field's type, change the meaning of a value, or make a previously-always-present field absent.

**The producer may**, within a major version: add a field, add an enum member to `type`, add an addon, remove an addon.

**Every field is always present on every entry.** Where a value is genuinely absent the field carries an explicit empty value — `""` or `[]` — never `null`, and never omitted. This is not aesthetic. The app deserializes with a bare Gson instance, which writes `null` into non-null Kotlin properties for missing keys and does so silently; the app already carries a hand-written normalizer to patch exactly this hazard for its own manifest type. A contract that never omits a field cannot trigger it.

**A consumer must** ignore fields it does not recognise, and must not depend on key order. Gson ignores unknown fields by default, so additive evolution is already safe on the app's side.

Adding an enum member to `type` is additive by the rules above but is a **behavioural** break for a consumer that switches exhaustively on it. Consumers must treat an unrecognised `type` as an addon they cannot install, not as an error.

### 10.6 Publish ordering

There is no multi-object transaction in R2, so ordering supplies the atomicity instead:

1. Upload artifacts, tarballs, icons, pages, and assets.
2. Upload `v1/catalog.json` **last**.

The catalog therefore never references an object that is not already in place. Withdrawal runs in reverse — publish the catalog without the entry, then delete the objects — so no consumer ever holds a catalog pointing at a deleted file.

### 10.7 Comparability

The document carries two version fields with **two different orderings**, and neither can be borrowed from an existing convention: the app never parses `plugin.version` at all, and the IDE's own version is `C-r-MMDD-HHMM` or `YY.ww`, neither of which is semver.

| Field | Format | Ordering |
|---|---|---|
| `version` | dotted numeric, e.g. `1.3.0` | Component-wise numeric comparison, left to right. Missing components are zero. |
| `minAppVersion` | `YY.ww`, e.g. `25.47` | Compared as an ordered pair of integers — year, then week. |

`addons check` enforces both shapes. An addon whose manifest version does not parse is a generation failure, not a silent pass — otherwise "is mine current" becomes undecidable for that addon and the app has no way to say so.

Note that the app does not currently compare `min_ide_version` against anything, despite an API doc claiming it does. `minAppVersion` is therefore forward-looking: it is published so the capability can be built, not because it is enforced today.

### 10.8 Consumer notes

The app has **no HTTP stack**: Retrofit sits on its compile classpath entirely unused, and there is no OkHttp, no response cache, and no conditional-request handling. Building the in-app browser is app-side work outside this repository. Two notes for whoever does it:

- R2 returns an `ETag`, so a conditional `GET` with `If-None-Match` makes a poll cost a 304 and almost no bytes. Worth doing on mobile.
- At 31 entries the catalog is roughly 25 KB. Fetching the whole document is correct at this size. Paging or a split index only becomes worth considering in the hundreds.

**One install path already works end to end, today, with no app change.** The app registers a VIEW intent for `.cgp` files, so a browser download from the gallery reaches the installer. This means the gallery is independently useful the moment it ships, before any in-app browser exists. It should still be verified on a device rather than assumed — content-scheme URIs from a browser's download provider do not always carry a path the intent filter matches.

The app's "Discover plugins" button currently opens the contribute page in a browser. Repointing it at the gallery is a one-string change and the cheapest possible integration (O7).

### 10.9 Publishing the schema

The JSON Schema is published beside the catalog at `v1/catalog.schema.json`, with its `$id` set to that URL. Generation validates against the same file that consumers can fetch, so the contract and its enforcement cannot drift apart.

---

## 11. The gallery

### 11.0 Brand

The gallery follows appdevforall.org, so it reads as part of the same site rather than a bolt-on. Taken from the live site: **Poppins**, indigo **`#233490`** for the header and footer, body text `#333`, and mint `#cce5e2` as the accent. The header carries the same logo and a condensed version of the main navigation; the footer repeats the site's four-column layout, tagline, social links, and copyright.

The logo is **copied into `site/assets/` and published to R2**, not hotlinked from appdevforall.org. Hotlinking would put a GreenGeeks dependency back into the very page built to remove it.

Poppins loads from Google Fonts with a full system fallback stack, which is what the main site does. It is the only external request the gallery makes.

### 11.1 The application

Carried forward from the prototype, whose core decisions were right. **One plain JavaScript file that the browser runs as-is** — no framework, no bundler, no build step, so the file in git is exactly the file that executes. All record text is written with `textContent` and never `innerHTML`, so a description can never inject markup. Filters are mirrored into the URL, which makes a filtered view shareable (R21). Search is debounced. A failed load shows an error, not a blank page (R22).

The absence of a build step is a design choice, not an omission. A bundler would add a fourth toolchain, a dependency tree to keep patched, and a CI stage that can fail — none of which buys anything for a page that lists 31 cards and filters them.

Changes:

| Change | Requirement |
|---|---|
| Type and origin each carry a glyph beside their word: `◆ ▲ ● ■` for the four types, `★` for App Dev For All and `☆` for a community addon. Filled geometric shapes are types, stars are origins, and a filled star is first-party. The glyph is `aria-hidden` — the word beside it is what a screen reader announces, so the glyph is a second cue rather than a replacement. | R20 |
| Tags, type, **and origin** filter the list, and combine. Origin is a second `<select>` beside the type one, with the same known-values guard, so an unknown `?origin=` falls back to no filter instead of hiding every card. | R18 |
| Addon icons on cards, in both light and dark form. Every addon already ships `icon_day.png` and `icon_night.png`; the catalog carries both and a `<picture>` lets the browser choose. | R17 |
| Links take an explicit colour token. The browser default `#0000EE` sits at roughly 1.4:1 against the dark card, well under the 4.5:1 minimum. | R19 |
| Tags render on each card as buttons that filter, and combine with type, origin, and free text. | R18 |
| Sort by name. There is no sort at all today; cards render in file order. | — |
| `plugins.json` and `templates.json` dropped. Generated, committed, and served today, but consumed by nothing. | — |
| Unused `icon` field dropped from the schema in favour of the derived `iconUrl`. | — |

Mobile-first layout is already the prototype's design and is retained (R19).

### 11.2 Description pages

The 31 existing pages are self-contained HTML with an inline `<style>`. They stay that way in git — an author writes plain HTML and nothing else.

At publish time, `addons page` extracts the `<body>` content and wraps it in the gallery's header, footer, and shared stylesheet link, producing the object served at `p/<slug>.html`. The result is coherent with the gallery and always links back to it.

Two consequences worth stating. The published page differs from the file in git, so anyone diffing them will see the chrome; this is intended. And no chrome is duplicated across 31 files in the repository, which is precisely the maintenance cost the prototype incurred.

The in-IDE documentation at `src/main/assets/docs/index.html` is a different artifact for a different purpose and is not touched, not published, and not related (R32, R35).

---

## 12. Cloudflare R2

### 12.1 Object layout and headers

| Key | `Content-Type` | `Cache-Control` | Extra | Edge-cached? |
|---|---|---|---|---|
| `index.html` | `text/html` | `public, max-age=60` | | No (V04) |
| `v1/catalog.json` | `application/json` | `public, max-age=60` | | No (V04) |
| `v1/catalog.schema.json` | `application/json` | `public, max-age=60` | | No (V04) |
| `assets/app.<hash>.js` | `text/javascript` | `public, max-age=31536000, immutable` | | Yes |
| `assets/styles.<hash>.css` | `text/css` | `public, max-age=31536000, immutable` | | Yes |
| `p/<slug>.html` | `text/html` | `public, max-age=60` | | No (V04) |
| `p/<slug>.png` | `image/png` | `public, max-age=60` | | Yes |
| `dl/<slug>.cgp` | `application/octet-stream` | `public, max-age=60` | `Content-Disposition: attachment; filename="<slug>.cgp"` | No |
| `src/<slug>-src.tar.gz` | `application/gzip` | `public, max-age=60` | `Content-Disposition: attachment; filename="<slug>-src.tar.gz"` | Yes |
| `staging/<run-id>/…` | as above | `public, max-age=60` | as above | as above |

`Content-Type` is set explicitly on every object and never left to inference (C05). `Content-Disposition: attachment` on the two binary classes is what makes them download while pages render (R05).

### 12.2 Root routing

A zone Transform Rule: when `http.request.uri.path eq "/"`, rewrite path to the static value `/index.html`. Configuration only, Free plan, no regex. **Already in place and verified live** (V01).

### 12.3 Staging (R09)

`publish-addons.yml` with `staging: true` writes under `staging/<run-id>/` and prints the URLs to the run summary. A maintainer gets one addon on demand, at a real URL, with no artifact behind it. Staging keys are never referenced by the catalog.

Two properties of a staging publish, both confirmed against the live bucket and both consequences of decisions made elsewhere:

- **A staging URL needs the explicit `index.html`.** The Transform Rule rewrites only `/`, so `…/staging/<id>/` returns 404 while `…/staging/<id>/index.html` serves the gallery. The rule exists for the site root, not for every directory.
- **The catalog's base must match where it is published.** Catalog URLs are absolute (§10.4), so a staging build cannot simply reuse the live base — every icon and download would point at the root, where a staging build has published nothing. `addons catalog` therefore takes a `--base`, and the workflow derives it from the same prefix it publishes under. A staging catalog carries staging URLs; the live catalog carries live ones. Absolute URLs stay absolute for the app consumer, and a staging publish is genuinely browsable.

The site's own files use relative paths, so the gallery and the description pages work correctly under any prefix.

### 12.4 Freshness

Because Cloudflare does not edge-cache HTML or JSON by default (V04), the catalog, every description page, and every `.cgp` are current the instant a publish completes. Only icons, tarballs, and the hashed assets are cached, and the first two are bounded at 60 seconds by their `Cache-Control`. The hashed assets are never stale because their key changes with their content.

**The zone overrides our header for exactly the cached types.** Measured against the live bucket: `.html`, `.json`, and `.cgp` are served with our `max-age=60`, but `.js`, `.css`, and `.png` come back as `max-age=14400` — the zone's Browser Cache TTL, which replaces the origin header for the file types Cloudflare caches by default. A browser can hold a stale script, stylesheet, or icon for four hours.

This is why the content hash in the asset filenames is load-bearing rather than a nicety: new bytes mean a new URL, so the long TTL becomes correct instead of dangerous. Icons and tarballs stay inside that four-hour window, which is acceptable — an icon change is cosmetic, and a tarball's checksum is published in the catalog.

**This requires amending R04.** Its current text — "Stale content must never be served after a publish completes" — describes a purge-based design. New text:

> **R04** Published content must become current within 60 seconds of a publish completing, without manual intervention.

C04's corollary also drops. It anticipated that "publishing and cache invalidation need separate credentials"; with no invalidation step, the design needs exactly one bucket-scoped token (R08).

### 12.5 CORS (R07)

One bucket-wide rule. S3-style CORS has no key-prefix condition, so per-path policies are neither possible nor needed.

```json
[
  {
    "AllowedOrigins": ["*"],
    "AllowedMethods": ["GET", "HEAD"],
    "AllowedHeaders": ["Range"],
    "ExposeHeaders": ["ETag", "Content-Disposition", "Accept-Ranges", "Content-Range"],
    "MaxAgeSeconds": 86400
  }
]
```

`AllowedOrigins: ["*"]` because the content is public, unauthenticated, and read-only — there is no cookie or credential for an allowlist to protect — and because an Android WebView loading local content sends `Origin: null`, which an explicit allowlist would reject. `Access-Control-Allow-Credentials` is deliberately absent: it is invalid alongside `*`, and nothing here is authenticated.

`Range` is listed because it is not a CORS-safelisted request header, so a resumable download would otherwise fail its preflight. Only four response headers need exposing; `Content-Type`, `Content-Length`, `Cache-Control`, and `Last-Modified` are safelisted already.

Applied once, out of band, via the dashboard or `wrangler r2 bucket cors set addons --file addons-cors.json` — **not** `aws s3api put-bucket-cors`, which R2 rejects (V09). Followed by one cache purge for the hostname (V10).

CORS governs browsers only. A native Android download ignores it entirely; this policy exists for a page on `appdevforall.org` and for a future in-app browser if that browser is a WebView.

### 12.6 Credentials

One R2 API token, scoped to **Object Read & Write on the `addons` bucket only**, with no account-level permission (R08). Stored as three repository secrets: account id, access key id, secret access key. The token cannot list other buckets, cannot alter bucket configuration, and cannot purge cache — it does not need to.

No `AWS_REQUEST_CHECKSUM_CALCULATION` workaround is used. That incompatibility was resolved upstream and Cloudflare removed its own guidance about it (V08); carrying it forward would be cargo cult.

---

## 13. Workflows

### 13.1 `update-libs.yml`

Returns to its name: refresh `libs/`, commit, cut a release. The scp block, the SSH key setup and teardown, the remote MD5 check, the website-filename staging loop, and the `GREENGEEKS_SSH_*` secret and variables are all deleted (G02, R10, P04). The GitHub Release step is untouched.

**One bug is made visible here, not fixed.** `scripts/update-libs.sh` rebuilds two jars, but `libs/` holds five. `common.jar`, `eventbus-events.jar`, and `idetooltips.jar` are never refreshed and can silently drift. R41 makes those jars part of a published deliverable, so the drift stops being cosmetic.

Refreshing them automatically was attempted and **rejected as unsafe**. The two Gradle tasks this script runs do not produce them, and their real source is not known. A search by name finds `composite-builds/build-logic/common/build/libs/common.jar` — a different 21 KB artifact with 17 files, where `libs/common.jar` is 355 KB with 224 files. Copying it would silently replace the IDE's jar with an unrelated one and break every plugin that depends on it.

The script therefore prints `NOT REFRESHED` with each jar's date, so the staleness is loud rather than silent. Finding the true source is tracked as O9.

### 13.2 `build-plugins.yml`

The `publish` job and the bundle upload are deleted. No `upload-artifact` or `download-artifact` step remains anywhere in the repository (R01), which is what makes G01 measurable as zero.

The capability that job provided — a maintainer downloading one built addon from the run summary — is not lost. It moves to `publish-addons.yml` with `staging: true`, which is strictly better: a URL anyone can click, rather than a zip only a logged-in maintainer can retrieve.

### 13.3 `publish-addons.yml` (new)

`workflow_dispatch`. Inputs: `addon` (a slug, or `all`) and `staging` (boolean).

```
checkout → set up JDK 17 + Gradle → uv sync
  → addons check
  → build (downloadAssets when present, then assemblePlugin)
  → addons tarball
  → addons catalog
  → addons page
  → addons publish
  → print URLs to the run summary
```

The build step reuses the loop already in `scripts/update-libs.sh`, including its two hard-won rules: fall back to the repo-root wrapper when an addon has no local `gradlew`, and run `downloadAssets` before `assemblePlugin` whenever the build file references it. A bare `assemblePlugin` silently packages a broken artifact for those addons.

`addons check` runs **first**. A naming or metadata error should cost seconds, not a full build.

### 13.4 `check-toolchain.yml`

Gains `uv sync`, `addons check`, and the tool's `pytest` suite. This is the only workflow that runs automatically on pull requests, which makes it the only place naming and metadata rules can actually be enforced (R28).

---

## 14. Migration sequence

Ordered so that every step is reversible until the last one.

1. **Land the tool and the checks.** `tools/addons/`, `site/`, the schema, the tests, and the `check-toolchain.yml` wiring. Nothing publishes yet. `addons check` is advisory on this pass.
2. **Land metadata.** 31 reviewed `addon.json` files. `addons check` becomes blocking.
3. **Rename and move.** Directories renamed to the convention, description pages renamed to `<slug>.html`, addons moved under `plugins/`. `plugin.id`, `namespace`, and `applicationId` are untouched (R29). Discovery already handles both locations (R31), so this can proceed in batches. **Rename the Favorite Snippets addon before creating `snippets/`** — the names collide until it moves (§5).
4. **Publish to staging and verify by hand.** One addon, one page, one tarball: the page renders, the artifact downloads, the tarball extracts and builds, the catalog validates, the gallery loads. This is K03's mitigation and it is not optional.
5. **First full publish**, and apply the CORS policy plus its one-time purge.
6. **Repoint the website's links** at `addons.appdevforall.org`.
7. **Delete the scp step** and the GreenGeeks secrets from CI.
8. **Revoke the GreenGeeks credentials.** Last, not first.

Steps 1–3 are independent of Cloudflare entirely and can land while step 4 is still being arranged.

---

## 15. Risks

Carried from the PRD where still live, plus what the design introduces.

| ID | Risk | Handling |
|---|---|---|
| **K01** | Root routing not achievable by configuration. | **Closed.** Verified live (V01). |
| **K02** | A "not found" response cached before first publish persists. | HTML is not edge-cached (V04), so this cannot happen for pages or the catalog. Verify before announcing the address regardless. |
| **K03** | Switching hosts in one step leaves no rollback. | Migration step 4 — hand verification of one complete addon before any link is repointed. GreenGeeks stays live until step 8. |
| **K04** | A partial migration becomes permanent. | Discovery spans both locations by design (R31), which is what makes stalling possible. Remaining addons are tracked as explicit follow-up work. |
| **K05** | A renamed addon's in-app help breaks silently with a green build. | R29 prevents the known cause and `addons check` enforces it. Device verification is still required — a green build is not verification for anything that mutates IDE state. |
| **K06** | Crediting a community contribution incorrectly. | R36–R38 and the `author` requirement in §7. PRD Q1 stays open and blocks `cotg-ndk`, which remains on the skip list. |
| **K07** | Structural verification proves well-formed, not compilable. | Accepted knowingly (§9.6). A periodic build-one-tarball job is the later mitigation. |
| **K08** | Two addons' tarballs depend on fetched assets remaining available. | Those assets move to the `addons` bucket under this change, so the dependency is on infrastructure we control. |
| **K09** | Python is a third toolchain in a bash-and-Gradle repository. | Confined to `tools/addons/`, pinned by `uv.lock`, and exercised on every PR by its own test suite. The alternative — untested shell — was rejected in D01. |
| **K10** | Bot Fight Mode could challenge non-browser clients. | JavaScript Detections is enabled on the zone and currently detects without blocking. If Super Bot Fight Mode's block action is ever turned on, an in-app addon browser or an in-IDE download — neither of which runs JavaScript — would receive a challenge instead of the file. This hostname must stay exempt from bot challenges. |
| **K12** | Version ordering has to be defined by us. Neither existing convention is usable — the app never parses `plugin.version`, and the IDE's own version is not semver. | §10.7 defines both orderings explicitly, and `addons check` enforces the shapes. An addon whose version does not parse fails generation rather than shipping an unorderable entry. |
| **K13** | The contract is being frozen before its main consumer exists. | Keep v1 minimal and strictly additive (§10.5), and publish the schema so the app can be built against something checkable. The `v1/` path means a wrong guess costs a second file, not a broken fleet. |
| **K11** | `addons check` becomes a merge blocker on unrelated work. | It only inspects addon directories and only fails on identity or metadata disagreement. A change touching neither cannot trip it. |

---

## 16. Requirements traceability

| Req | Satisfied by |
|---|---|
| R01 | §13.2 — every artifact step deleted; no upload/download-artifact remains |
| R02 | §12.1 — all five object classes live in the `addons` bucket |
| R03 | §6 — key derived from slug, stable across releases |
| R04 | §12.4 — amended; HTML/JSON uncached, others bounded at 60 s |
| R05 | §12.1 — explicit `Content-Type` plus `Content-Disposition: attachment` on binaries |
| R06 | §12.2 — Transform Rule, verified live |
| R07 | §12.5 — bucket CORS policy |
| R08 | §12.6 — one bucket-scoped token, no account access |
| R09 | §12.3, §13.2 — staging prefix with URLs in the run summary |
| R10 | §13.1 — GreenGeeks removed from the delivery path |
| R11 | §10 — catalog generated, never hand-maintained |
| R12 | §7 — per-addon `addon.json` |
| R13 | §10 — `type` on every entry from release one |
| R14 | §10 — checksum and size computed at publish |
| R15 | §7, §8.2 — `author` required for community origin, enforced by `check` |
| R16 | §8.2, §10 — schema-validated, fails loudly, `additionalProperties: false` |
| R17 | §11.1 — card fields |
| R18 | §11.1 — combining type, origin, tag, and free-text filters |
| R19 | §11.1 — mobile-first layout retained |
| R20 | §11.1 — a glyph beside the word on both the type and the origin |
| R21 | §11.1 — filter state synchronised to the URL |
| R22 | §11.1 — explicit error state |
| R23 | §6 — directory is the sole source of identity |
| R24 | §6 — MixedCase with lowercase small words |
| R25 | §6, §8.2 — one display name, agreement enforced |
| R26 | §6 — slug used unchanged for artifact and page |
| R27 | §6 — no override mechanism exists |
| R28 | §8.2, §13.4 — `addons check` on every pull request |
| R29 | §6, §8.2 — runtime identity never derived and never changed |
| R30 | §5 — `plugins/` plus three reserved siblings |
| R31 | §8.1 — discovery spans both locations |
| R32 | §11.2 — two pages, separate purposes, only one published |
| R33 | §14 — documentation updated during migration; CLAUDE.md's stale `MAP` instruction corrected |
| R34 | §8.2 — both pages required, existence checked |
| R35 | §11.2 — only the gallery page is published |
| R36 | §7 — `author` credits the originating contributor |
| R37 | §7 — name and URL required, email only by consent |
| R38 | §6 — a community addon's original naming is preserved |
| R39 | §9 — a tarball per published addon |
| R40 | §9.1 — the flat archive root builds standalone, and opens in Code on the Go |
| R41 | §9.2 — jar set derived per addon by parsing build files |
| R42 | §9.1 — root wrapper copied in |
| R43 | §9.1 — one bounded rewrite, `(../)+libs/` → `libs/`, enforced by check 6 in §9.6 |
| R44 | §9.3 — file list from `git ls-files` |
| R45 | §9.5 — fetch step retained, assets move to our host |
| R46 | §9.4 — prerequisite stated at the top of `BUILDING.md` |
| R47 | §9.2, §9.6, §10 — every failure aborts the run |
| R48 | §9.6 — five structural checks on every tarball |
| R49 | §10 — tarball checksum and size in the catalog |

---

## 17. Open items

Nothing below blocks implementation of §14 steps 1–3.

| ID | Item | Effect |
|---|---|---|
| **O1** | PRD Q1 — how Aman Khan wishes to be credited, and whether the addon is CotGX NDK. | Blocks publishing `cotg-ndk`. It stays on the skip list until answered. Everything else proceeds. |
| **O2** | PRD Q2 — a per-addon version in the catalog. | Deferred (§10.2). Additive later. |
| **O3** | ~~PRD Q4 — where the other addon types live.~~ | **Closed.** They live here. `snippets/` is blocked only by the rename in §5. |
| **O4** | The 24×24 icon on `sketch-to-ui-plugin`, and the two at 96×96. | Cosmetic. Follow-up issue, not a blocker (D07). |
| **O5** | `pebble-custom-function-template-installer` ships stale copies of both shared jars and is the sole skip-list entry with no recorded reason. | Cleanup during migration step 3. |
| **O6** | A periodic job that actually builds one published tarball. | The real mitigation for K07. Out of scope here. |
| **O7** | The app's "Discover plugins" button opens the contribute page. Repointing it at the gallery is a one-string change. | The cheapest possible integration, and app-side work. Worth filing as soon as the gallery is live. |
| **O10** | ~~The six AI addons are skipped, so they do not publish.~~ | **Closed by decision.** They are not touched in any way and are ignored by this work; they will be migrated into the gallery later. PRD acceptance criterion 13 is superseded for this change. A visible consequence: three pages tell the reader to install AI Core, which is not in the catalog until that migration happens. |
| **O9** | The real source of `common.jar`, `eventbus-events.jar`, and `idetooltips.jar` in CodeOnTheGo is unknown, so `update-libs.sh` cannot refresh them. | It reports them as `NOT REFRESHED` on every run. Someone who knows the CodeOnTheGo build must identify the producing tasks. Until then these three jars ship inside every source tarball at whatever age they already have. |
| **O8** | **No addon sets its own version.** All but one report `1.0.0`, so the catalog cannot yet tell a user that an update exists. | Not caused by this change and not fixed by it. The field and its ordering ship now, so the capability works the day someone sets real versions. Setting them is separate work. |
