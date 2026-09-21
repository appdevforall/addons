# PRD — Addon Distribution & Repository Normalization

| | |
|---|---|
| **Status** | Draft for approval |
| **Owner** | Hal Eisen, App Dev For All |
| **Date** | 2026-08-27 |
| **Product** | Code on the Go — addon ecosystem |
| **Repo** | `appdevforall/plugin-examples` |

> **Scope of this document.** Requirements and behaviors only — what the system must do and why. Mechanism, file layouts, tooling choices, and work sequencing are deliberately absent; they belong in the design document that follows approval of this PRD.

---

## 1. Summary

Code on the Go's addons are built in `plugin-examples` and delivered to users through two pieces of infrastructure that are both failing us:

1. **GitHub Actions artifact storage**, which the build consumes so fast that the quota is exhausted within a couple of release runs.
2. **A GreenGeeks WordPress host**, which serves every published `.cgp` and is unstable enough that downloads cannot be relied on.

This PRD defines the requirements for moving addon delivery to a Cloudflare R2 bucket (`addons`) that App Dev For All controls, eliminating the artifact-storage dependency entirely, and normalizing addon naming and repository organization so the ecosystem can grow beyond plugins to templates, snippets, and code actions.

---

## 2. Background

### 2.1 What exists today

`plugin-examples` holds 31 independent Gradle projects, each producing a `.cgp` plugin installable through Code on the Go's Plugin Manager. A manually-triggered workflow rebuilds the shared libraries, builds every plugin, and copies the results to a directory on the GreenGeeks host. Users find and download plugins from a page on appdevforall.org.

### 2.2 Measured problems

| ID | Observation |
|---|---|
| **P01** | A full release run uploads roughly 1.25 GB to GitHub Actions artifact storage. Nothing outside that run ever consumes it — the artifacts exist only to pass files between jobs of the same workflow. |
| **P02** | The per-plugin publish step re-downloads the complete bundle once for every plugin, to extract a single file from it — roughly **15 GB of transfer per run** to produce files that already existed moments earlier. Detailed below. |
| **P03** | Three plugins account for about two-thirds of every payload, because they bundle large assets fetched at build time. |
| **P04** | Published downloads depend on a WordPress host outside our control, reached over SSH with long-lived credentials. |
| **P05** | Addon names have drifted. The same plugin can carry one name in its directory, another in the Plugin Manager, a third in its `.cgp` filename, and a fourth in its documentation page. Directory names mix conventions; one plugin hardcodes a version the build is supposed to inject. |
| **P06** | All 31 plugins sit at the repository root, leaving no room for the other addon types that are coming. |
| **P07** | **No addon's source is independently obtainable or buildable.** Every addon depends on a shared `libs/` directory one level up, so a single addon directory is not a standalone project — a user who wants to read, build, or modify one must clone the entire repository and infer the layout. Code on the Go is AGPL v3; publishing usable source alongside each binary is the point, not a bonus. |

### 2.3 P02 in detail — the publish fan-out

**Where:** `.github/workflows/build-plugins.yml`, the `publish` job, lines 116–147.

The `build` job stages every `.cgp` into a directory and uploads all of them as a **single** artifact named `all-plugins-cgp` (lines 116–122). It then emits the plugin names as a JSON array (line 109) for the sole purpose of letting the next job fan out over them.

The `publish` job declares a matrix with **one leg per plugin** (lines 130–133). Each leg runs exactly two steps: download `all-plugins-cgp` (lines 135–139), then upload one file out of it under that plugin's own name (lines 141–147).

**Why roughly thirty times over.** The matrix width equals the number of plugins built — 29 in the most recent release. `actions/download-artifact` cannot retrieve a single file from a multi-file artifact; it fetches the whole thing. So all 29 legs each pull the complete ~530 MB bundle in order to extract one file from it:

| | |
|---|---|
| Matrix legs | 29 (one per built plugin) |
| Downloaded per leg | ~530 MB (the entire bundle) |
| **Total transfer** | **~15 GB per run** |
| New output produced | none |

**Why it exists.** Every one of those files was already sitting in the `build` job's workspace moments earlier. The fan-out's only purpose is convenience: it lets a maintainer download one plugin directly from the run summary instead of the whole bundle. The workflow's own comment at lines 124–125 says exactly that.

**What it costs.** Beyond the transfer, it stores a **second full copy** of every artifact — the bundle plus 29 individual extracts — both retained for seven days. That is the larger half of P01's 1.25 GB.

> This is why the fan-out cannot simply be deleted: it serves a real need. R09 preserves the capability — a maintainer must still be able to obtain one addon on demand — without an artifact behind it.

### 2.4 Why now

The quota problem blocks releases outright, and the naming drift compounds with every addon added. Templates, snippets, and code actions are all planned; adding them to a flat root with no naming discipline would multiply both problems. Fixing distribution and organization together — while there are only plugins to migrate — is materially cheaper than fixing them later.

### 2.5 Insight

**The artifacts are plumbing, not a deliverable.** Nothing consumes them beyond the run that creates them. Any solution that delivers built addons to their destination without an intermediate handoff eliminates the quota problem permanently, rather than managing it — and makes addon size irrelevant to it forever. This is why P03 needs no fix here.

---

## 3. Goals

| ID | Goal | Measure of success |
|---|---|---|
| **G01** | Eliminate GitHub Actions artifact-storage consumption. | Zero bytes of artifact storage consumed by a full release run. |
| **G02** | End the dependency on GreenGeeks for addon delivery. | No published addon, page, or catalog is served from that host; its credentials are revoked. |
| **G03** | Make every addon's identity consistent everywhere it appears. | For each addon, directory, display name, artifact filename, and documentation filename all agree, and a check enforces it. |
| **G04** | Make room for templates, snippets, and code actions. | Plugins occupy a dedicated area; sibling areas exist for the other types; the published catalog distinguishes addon types. |
| **G05** | Give users one place to discover addons. | A searchable, filterable catalog page lists every published addon with its description, source, and download. |
| **G06** | Make every addon's source independently buildable. | A user downloads one addon's source tarball, and builds it without cloning this repository. |

---

## 4. Non-goals

- Reducing the size of any addon.
- Reducing CI build time or dependency-download cost.
- Changing how GitHub Releases work.
- Changing any addon's runtime identity or its behavior inside Code on the Go.
- Building templates, snippets, or code actions. This makes room for them; it does not create them.
- User accounts, submissions, ratings, reviews, or download telemetry.
- Installing addons directly from the catalog. The catalog links to a file; the Plugin Manager installs it.

---

## 5. Users

| User | Need |
|---|---|
| **Addon consumer** — a Code on the Go user, usually on an Android phone or tablet. | Find an addon, understand what it does well enough to decide, and download it reliably. |
| **App Dev For All maintainer.** | Cut a release without hitting a quota wall, and add an addon without touching a central registry file. |
| **Community contributor.** | Have their addon published and credited under their own name. |

### 5.1 Use cases

1. A user browses the catalog on a phone, searches for a capability, reads the description page, and downloads the addon.
2. A user filters to a single addon type, or to community-contributed addons only.
3. A maintainer triggers a release; every addon and its page publish and are immediately downloadable.
4. A maintainer builds one addon against a development branch of Code on the Go and installs the result on a device to test it.
5. A contributor's addon appears in the catalog credited to them, linking to their source.

---

## 6. Requirements

### 6.1 Distribution

| ID | Requirement |
|---|---|
| **R01** | Built addons must reach their published destination **without being stored in GitHub Actions artifact storage**. No part of the repository may consume that storage. |
| **R02** | The `addons` Cloudflare R2 bucket must be the single published home for every addon artifact, its description page, the catalog, and the catalog web application. |
| **R03** | Each addon must be downloadable at a **stable, predictable URL derived from its slug**, unchanged from release to release. |
| **R04** | Published content must become **current within 60 seconds** of a publish completing, **without manual intervention**. |
| **R05** | Description pages must **render** in a browser; artifacts must **download**. Neither may be served in a way that produces the other behavior. |
| **R06** | The catalog must be reachable at the site's root address, not only at a fully-qualified file path. |
| **R07** | The catalog data must be readable by cross-origin consumers, so a future in-app addon browser can use it. |
| **R08** | Publishing must authenticate with credentials scoped to the `addons` bucket alone, with no broader account access. |
| **R09** | A maintainer must be able to build a single addon on demand and obtain a downloadable URL for it, kept separate from published releases. |
| **R10** | Delivery of addons must not depend on any host outside App Dev For All's control. |

### 6.2 Catalog

| ID | Requirement |
|---|---|
| **R11** | The catalog must be **generated**, never hand-maintained. Adding an addon to the repository is the only action required to get it into the catalog. |
| **R12** | Each addon's curated metadata — description, tags, origin, license, author — must live **with that addon in the repository**, not in a central registry file. |
| **R13** | The catalog must record an **addon type** for every entry, so plugins, templates, snippets, and code actions coexist without a breaking change. |
| **R14** | Each entry must carry an integrity checksum and a size, both computed at publish time, never hand-entered. |
| **R15** | Each entry must distinguish **first-party** from **community** origin, and community entries must credit their author. |
| **R16** | Generation must **fail loudly** on an addon with missing or invalid metadata. Silent omission from the catalog is not acceptable. |

### 6.3 Gallery

| ID | Requirement |
|---|---|
| **R17** | The gallery must present every published addon with its name, description, type, origin, source link, description-page link, and download link. |
| **R18** | Users must be able to filter by addon type, filter by **origin** — App Dev For All or community — filter by tag, and search free text; filters must combine. |
| **R19** | The gallery must be **mobile-first** — designed for a phone viewport, usable in Android browsers and in-app WebViews. |
| **R20** | Type and origin must be distinguishable **without relying on color alone** — each carries a shape or glyph beside its word. |
| **R21** | A filtered view must be shareable as a URL that reproduces it. |
| **R22** | A failed catalog load must show an error state, not a blank page. |

### 6.4 Naming

| ID | Requirement |
|---|---|
| **R23** | The **directory name is the single source of truth** for an addon's identity. Display name, artifact filename, and documentation filename derive from it mechanically. |
| **R24** | Directory names use MixedCase words separated by hyphens. **Small words stay lowercase**, so `Rainbow-on-the-Go` yields "Rainbow on the Go". |
| **R25** | The display name is the directory with hyphens replaced by spaces, and must appear **identically** in the Plugin Manager, the catalog, and the documentation page title. |
| **R26** | The slug is the lowercased directory, and is used **unchanged** for both the artifact filename and the documentation filename. |
| **R27** | There is **no override mechanism**. An addon whose desired display name cannot be derived must have its directory renamed. |
| **R28** | A check must **fail the build** when any derived value disagrees with the directory. |
| **R29** | An addon's runtime identity — its plugin id, namespace, and application id — **must not change**. |

> **Why R29.** The plugin id is both the installed-addon identity on a user's device and the key under which in-app help is registered. Changing it orphans existing installations and silently degrades every tooltip in that addon to the literal text `n/a`, with no build failure to catch it. Renaming buys tidiness and costs users. The naming standard does not govern it.

### 6.5 Repository organization

| ID | Requirement |
|---|---|
| **R30** | Plugins must live in a dedicated area, with sibling areas reserved for templates, snippets, and code actions. |
| **R31** | Addon discovery must work **during a partial migration**, so addons can move in batches rather than all at once. |
| **R32** | Each addon keeps **two distinct documentation pages** (§6.6). |
| **R33** | Repository documentation that enumerates addons must be updated to match, including the guidance file that still documents a mechanism deleted months ago. |

### 6.6 Documentation — two pages, two purposes

Every addon has two HTML pages. They are **not duplicates** and must not be merged.

| Page | Audience | Answers |
|---|---|---|
| **Gallery page** — published to the catalog site. | Someone browsing who has never seen this addon. | *"Should I download this at all?"* Information scent: enough to decide, short enough to skim on a phone. |
| **In-app page** — bundled inside the addon, shown in Code on the Go. | Someone who has already installed it. | *"How do I use every feature of this?"* Complete reference documentation. |

| ID | Requirement |
|---|---|
| **R34** | Both pages must exist for every addon and must be maintained separately. |
| **R35** | Only the gallery page is published to the catalog site. |

> Collapsing these makes the gallery page too long to skim and the in-app page too thin to use. This is a deliberate decision, recorded here so it is not revisited as an optimization.

### 6.7 Attribution

| ID | Requirement |
|---|---|
| **R36** | Community-originated addons must be credited to the **originating community member**, not to the App Dev For All employee who implemented or submitted the work on their behalf. |
| **R37** | Attribution must include a name and a link to the contributor's own source, where one exists. Publishing a personal email address requires that person's consent. |
| **R38** | A community addon's original naming may be **preserved** even where it conflicts with first-party naming conventions. The convention binds what App Dev For All creates, not what it adopts. |

### 6.8 Source distribution

Each published addon ships a **source tarball** alongside its artifact and gallery page, so anyone can read, build, or modify one addon without cloning this repository.

The obstacle is that **no addon directory is standalone today.** Each one references a shared `libs/` directory one level up, and the shape of that dependency varies more than it appears:

| ID | Obstacle | Detail |
|---|---|---|
| **X1** | The shared `libs/` holds **five** jars, not two. | `layout-editor` references all five; `client-time-tracker` and `pair-programming-plugin` also need `eventbus-events`. A fixed two-jar bundle yields three tarballs that cannot build. |
| **X2** | Two addons **already have their own `libs/`** — `ai-agent-local` (`llama-api.jar`) and `pair-programming-plugin` (`shared.jar`) — referenced without the `../` prefix. | Copying the shared `libs/` into a tarball collides with an existing directory, and rewriting paths must not disturb references that are already correct. |
| **X3** | **Eight addons have no Gradle wrapper**, relying on the repository-root one. | Their source is unbuildable by anyone lacking the exact Gradle version. |
| **X4** | `local.properties` is **not tracked but present on disk** in most addon directories, carrying an SDK path and a Sentry DSN. | Any tarball built from the working tree rather than from tracked content publishes developer-local and secret material. |
| **X5** | One addon's source lives partly in a **git submodule**. | Tracked content alone yields a tarball that is present, plausible, and incomplete. |

#### Requirements

| ID | Requirement |
|---|---|
| **R39** | Every published addon must have a **source tarball** published alongside its artifact and gallery page. |
| **R40** | A tarball must build **without cloning this repository** and without reference to anything outside itself, except where R46 applies. |
| **R41** | A tarball must contain **every shared jar that addon actually references**. The set is derived per addon; a fixed list is not acceptable (X1). |
| **R42** | A tarball must contain a **Gradle wrapper** (X3). |
| **R43** | Every build path inside a tarball must resolve **within the tarball**. No path may escape its root, and paths already correct must be left alone (X2). |
| **R44** | A tarball must contain **no developer-local or secret material** — no SDK paths, credentials, DSNs, or local configuration (X4). |
| **R45** | Assets fetched at build time are **not bundled**. The tarball retains the fetch step, and what it fetches must come from a host App Dev For All controls. |
| **R46** | An addon whose source **cannot** be made fully self-contained must state its prerequisite prominently within the tarball, so the gap is visible before a build is attempted, never after (X5). |
| **R47** | Tarball generation must **fail loudly** rather than emit an incomplete or unbuildable tarball. |
| **R48** | Every tarball must be **structurally verified** on every run: each referenced jar present, no path escaping the root, a wrapper present, and no local configuration included. |
| **R49** | The catalog must expose each tarball with a **checksum and size**, on the same terms as the artifact itself. |

> **A broken tarball is worse than no tarball.** Every obstacle above fails in the same way — the tarball is produced, looks right, and only fails when someone tries to build it. R47 and R48 exist to make those failures loud and early. Note the limit accepted in K07: structural verification proves a tarball is well-formed, not that it compiles.

---

## 7. Platform constraints

Verified against Cloudflare documentation on 2026-08-27. These bound any design and are stated here because they change what is achievable, not merely how.

| ID | Constraint | Consequence for requirements |
|---|---|---|
| **C01** | **R2 provides no static-website hosting.** There is no index-document setting; buckets are flat; a request for the site root does not resolve to an uploaded index page. | R06 is not satisfied by uploading an index page. It needs an explicit routing decision in the design. |
| **C02** | **Overwriting an object does not clear the edge cache.** Previous bytes continue to be served until the cache expires or is purged. Cached "not found" responses persist identically. | Bounded by an explicit `Cache-Control` set at upload. Cloudflare does not edge-cache HTML or JSON by default, so pages, the catalog, and artifacts are unaffected; only icons and tarballs are cached, at 60 seconds. |
| **C03** | Caching is keyed on **file extension, not content type**, and the addon extension is not cached by default. Very large files are not cached at all on standard plans. | Accepted. Egress is unmetered, so this affects latency only. |
| **C04** | **No federated or keyless authentication exists** for R2. Access is by API token only. Bucket-scoped tokens cannot perform account-level operations. | R08 is satisfiable with a single bucket-scoped token. There is no invalidation step, so no second credential is needed. |
| **C05** | Whether content type is inferred on upload is **undocumented**. | R05 must be met by setting content type explicitly, never by relying on inference. |
| **C06** | **Egress is unmetered and free**; expected storage and request volume fall inside the free tier. | Cost is not a constraint on this migration. |

**Verified environment state:** the `addons` bucket exists and is empty; bucket-scoped credentials authenticate against it successfully; `appdevforall.org` is served by Cloudflare nameservers, satisfying the custom-domain prerequisite; no custom domain is yet connected.

---

## 8. Addon inventory and naming

31 plugin directories exist. **23 are in scope** for renaming and relocation.

**Frozen in place (6):** the AI plugins — `ai-agent-gemini`, `ai-agent-local`, `ai-agent-mcp`, `ai-agent-openai`, `ai-core`, `ai-literacy-course` — because other work is in flight against them. They must continue to build and publish unchanged.

> Superseded by ADFA-6167: five of those six moved to `plugins/AI-Agent-Gemini`, `plugins/AI-Agent-Local`, `plugins/AI-Agent-MCP`, `plugins/AI-Agent-OpenAI` and `plugins/AI-Core` and took the MixedCase directory names. Only `ai-literacy-course` is still frozen at the root. All six remain out of the gallery — see `tools/addons/skip.txt`.

**Held out of the build (2):** `pebble-custom-function-template-installer` is excluded permanently — never ships, no real name or description. `cotg-ndk` is held **temporarily** pending Q1 (§8.3). Both are skipped by the build and neither appears in the catalog.

### 8.1 Names

| Current directory | Directory | Display name | Slug |
|---|---|---|---|
| `Beepy` | `Voice-Alerts` | Voice Alerts | `voice-alerts` |
| `apk-viewer` | `APK-Analyzer` | APK Analyzer | `apk-analyzer` |
| `bookshelf` | `Bookshelf` | Bookshelf | `bookshelf` |
| `client-time-tracker` | `Client-Time-Tracker` | Client Time Tracker | `client-time-tracker` |
| `code-suggestions-plugin` | `Code-Suggestions` | Code Suggestions | `code-suggestions` |
| `compose-preview` | `Jetpack-Compose-Preview` | Jetpack Compose Preview | `jetpack-compose-preview` |
| `cotg-ndk` | *held — see §8.3* | — | — |
| `flutter-template` | `Flutter-Templates` | Flutter Templates | `flutter-templates` |
| `get-ai-models` | `Get-AI-Models` | Get AI Models | `get-ai-models` |
| `icons-repository` | `Icons-Repository` | Icons Repository | `icons-repository` |
| `keystore-generator` | `Keystore-Generator` | Keystore Generator | `keystore-generator` |
| `layout-editor` | `Layout-Editor` | Layout Editor | `layout-editor` |
| `markdown-preview` | `Markdown-Previewer` | Markdown Previewer | `markdown-previewer` |
| `ndk-installer-plugin` | `NDK-Installer` | NDK Installer | `ndk-installer` |
| `pair-programming-plugin` | `Code-Together` | Code Together | `code-together` |
| `project-to-template` | `Project-to-Template` | Project to Template | `project-to-template` |
| `python-tools` | `Python-Tools` | Python Tools | `python-tools` |
| `rainbow-on-the-go` | `Rainbow-Brackets` | Rainbow Brackets | `rainbow-brackets` |
| `random-xkcd` | `Random-XKCD` | Random XKCD | `random-xkcd` |
| `sketch-to-ui-plugin` | `Sketch-to-UI` | Sketch to UI | `sketch-to-ui` |
| `snippets` | `Favorite-Snippets` | Favorite Snippets | `favorite-snippets` |
| `speech-to-text-plugin` | `Speech-to-Text` | Speech to Text | `speech-to-text` |
| `template-manager` | `Template-Manager` | Template Manager | `template-manager` |
| `vector-search-plugin` | `Vector-Search` | Vector Search | `vector-search` |

### 8.2 Consequences

- Thirteen addons get a new artifact filename. Existing published download links for those break. **Accepted; no mitigation required.**
- `Icons-Repository` stops hardcoding its version and adopts the build-injected one, like every other addon.
- `NDK-Installer` drops the "(64-bit only)" qualifier from its display name; that detail belongs in its description.
- `APK-Analyzer` carries an application id left over from a copy-paste (`com.example.sampleplugin`). Frozen under R29 and noted for future work.

### 8.3 The two NDK addons

They are different products with a shared purpose. One ships now; one is held.

| Addon | Origin | Disposition |
|---|---|---|
| `ndk-installer-plugin` | First-party. Introduced 2026-04-23 by Joel Menchavez. | Ours and first. Becomes `NDK-Installer`, follows the naming standard. Ships. |
| `cotg-ndk` | **Community — Aman Khan** (`github.com/aman-khan-786/cotgx-ndk`). | **Held out of the build** pending Q1. Not renamed, not relocated, not published, not in the catalog. |

**Provenance.** Aman Khan built and released a custom NDK engine for the Code on the Go community, announced in the project's Telegram discussion group. App Dev For All could not accept an outside pull request at the time, so a staff engineer implemented it on his behalf. The work is his; the commit authorship is an artifact of that process and must not be mistaken for origin.

**Why it is held.** Publishing a contributor's work under a name and attribution we have not confirmed with them is the wrong order of operations. Holding it costs nothing — the first-party `NDK-Installer` still ships, so users are not left without the capability. The hold is released by answering Q1 with Aman, not by a technical change; the naming and attribution requirements (R36–R38) are already in place to receive the answer.

Both this addon and `pebble-custom-function-template-installer` are excluded from the build by the same mechanism, but for different reasons and with different lifespans: one is a permanent exclusion, the other a temporary hold. That distinction should survive into the design.

---

## 9. Acceptance criteria

1. A full release run consumes **zero** GitHub Actions artifact storage.
2. No published addon, page, or catalog is served from GreenGeeks, and its credentials are revoked.
3. Every in-scope addon is downloadable from its stable URL, and downloads rather than renders.
4. Every in-scope addon's gallery page is published and renders in a browser.
5. A second release serves the new bytes, not cached ones.
6. The catalog validates against its schema; every entry has a type, checksum, and size, none hand-entered.
7. An addon with missing or invalid metadata fails the build rather than being silently omitted.
8. The gallery loads on a phone viewport, lists every published addon, and its links resolve.
9. The catalog is reachable at the site root.
10. Type, origin, tag, and text filters each work and combine; a filtered view is reproducible from its URL.
11. For every in-scope addon, directory, display name, artifact filename, and documentation filename agree, and a check fails on drift.
12. No addon's plugin id, namespace, or application id has changed.
13. All six frozen AI addons still build and publish unchanged.
14. Neither held addon builds, publishes, or appears in the catalog.
15. A single-addon on-demand build yields a downloadable URL and consumes no artifact storage.
16. Every published addon has a source tarball in the catalog, with a checksum and size.
17. Each tarball contains every shared jar that addon references, and a Gradle wrapper.
18. No tarball contains `local.properties`, an SDK path, or any credential.
19. No build path inside a tarball resolves outside the tarball.
20. An addon whose source cannot be made self-contained states its prerequisite inside the tarball.
21. **Device verification.** At least one renamed addon is downloaded from the new host onto a device, installed through the Plugin Manager, and its feature exercised — including its in-app help. A successful build is not verification.

---

## 10. Risks

| ID | Risk | Response |
|---|---|---|
| **K01** | The site root may not be routable to the catalog by configuration alone (C01). | Resolve during design; verify before cutover. |
| **K02** | A "not found" response cached before first publish persists (C02). | Publish and verify before the address is announced. |
| **K03** | Switching hosts in a single step leaves no rollback. | Validate the destination by hand — one addon, one page, served correctly — before the switch is made. |
| **K04** | The partial migration (R31) could become permanent. | Migrating the deferred addons is tracked as explicit follow-up work. |
| **K05** | A renamed addon's in-app help could break silently, with a green build. | R29 prevents the known cause; acceptance criterion 15 verifies it on a device regardless. |
| **K06** | Crediting a community contribution incorrectly is a reputational harm, not a technical one. | R36–R38; Q1 resolves the remaining detail with the contributor. |
| **K07** | **Structural verification (R48) proves a tarball is well-formed, not that it compiles.** A tarball can pass every check and still fail to build — a missing transitive dependency, an AGP or Kotlin version the wrapper cannot satisfy, a source file referencing something outside the addon. This is an accepted limit, not an oversight. | Revisit if a broken tarball is ever reported. Building one extracted tarball per run, rotating which, would close it at roughly one extra build's cost. |
| **K08** | R45 makes two addons' tarballs dependent on the fetched assets staying available. | Those assets move to R2 under this change, so the dependency is on a host we control — the same one serving the addons themselves. |

---

## 11. Dependencies

Provisioning that must happen outside the repository before this can ship: bucket-scoped publishing credentials; separate credentials for cache invalidation; a custom domain connected to the bucket; and revocation of the GreenGeeks credentials after cutover.

---

## 12. Open questions

| ID | Question |
|---|---|
| **Q1** | **Blocks releasing the hold on `cotg-ndk` (§8.3).** Aman Khan's project is **`cotgx-ndk`** ("COTGX"); our directory dropped the X. Under R38 we preserve his naming — should the addon be **CotGX NDK** rather than **CotG NDK**? Also: how does he want to be credited, and does he want a contact address published? His plugin id is frozen either way. Answering this is a conversation with him, not a decision we make for him. |
| **Q2** | ~~Should the catalog carry a version per addon?~~ **Resolved:** yes. Read from the built artifact's manifest, displayed to users, and used by the app to answer "is mine current". |
| **Q3** | ~~Do outside pull requests change what R36–R38 must support?~~ **Resolved:** accepting outside contributions is **out of scope**. R36–R38 stand — they govern how credit is given, not how a contribution arrives. |
| **Q4** | ~~Do templates, snippets, and code actions belong in this repository?~~ **Resolved:** yes, all four addon types live here. `plugins/`, `templates/`, and `code-actions/` already exist as placeholders; `snippets/` is blocked until the Favorite Snippets addon vacates that name. |

---

## 13. What follows

On approval, a design document will specify mechanism: how publishing works, how the catalog is generated and validated, how the site root is routed, how naming is enforced, and how the migration is sequenced. **Nothing in the existing prototype is binding** — it is an illustration of a possible shape, not a specification.
