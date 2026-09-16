# Vector Search plugin for Code on the Go

Semantic (meaning-based) code search. Files are chunked and embedded into
vectors; a query is embedded the same way and ranked by cosine similarity. The
plugin contributes a **"Semantic Results"** section to the project search screen
via `ProjectSearchExtension`.

> Embeddings come from the backend you selected in **AI settings**, resolved at
> runtime through `ai-core` (no compile-time dependency). That backend has to be
> one that embeds, which today means `ai-agent-openai` or `ai-agent-gemini`.
> With none selected, this plugin contributes **no results at all** — it has no
> lexical fallback, because word matches presented as semantic ones look like
> the feature working badly rather than not running.

## Architecture

```
┌──────────────────────────┐
│  vector-search (this)    │  ← chunk, batch, cosine-similarity ranking, project search
└────────────┬─────────────┘
             │ SharedServices (runtime) → LlmInferenceService
             ▼
┌──────────────────────────┐
│  ai-core                 │  ← resolves the backend the user selected
└────────────┬─────────────┘
             │ LlmInferenceService.EmbeddingBackend (a host type, from plugin-api)
             ▼
┌──────────────────────────┐
│  ai-agent-openai         │  ← POST /v1/embeddings
│  ai-agent-gemini         │  ← models/{model}:batchEmbedContents
└──────────────────────────┘
```

## Features

- Semantic search over the current project via `ProjectSearchExtension`
- Embeds through whichever backend the user selected; names no provider itself
- Batched indexing, so a project costs a handful of calls rather than one per chunk
- Provenance per vector (backend, model, width); a search only ranks vectors of
  the same origin, and changing either builds the index again
- Chunk-level results with file, line range, and a preview snippet
- Local SQLite embedding store; on-demand indexing per searched root

## Permissions

Declared in `plugin.permissions`:

| Permission | Why |
|---|---|
| `filesystem.read` | read project files to chunk and embed |
| `project.structure` | enumerate the project's source roots |

No `network.access`: this plugin opens no sockets. The HTTP call belongs to the
backend plugin, which declares it. Indexing reads files in the current project
only and the index is a local database, but the chunk text **is** sent to the
selected backend to be embedded — which is why nothing is indexed until a
backend has been chosen.

## Building

Prerequisites: Android SDK (API 33+), JDK 17. Create `local.properties` with
`sdk.dir=...`. No NDK or native toolchain.

```bash
cd Vector-Search
../gradlew assemblePlugin          # release  -> build/plugin/vector-search.cgp
../gradlew assemblePluginDebug     # debug variant
../gradlew testDebugUnitTest       # ranking maths and the reindex decision
```

The build resolves `plugin-api.jar` from the repo-root `../libs/`.

## Installation

1. Install **`ai-core`** and an agent plugin whose backend embeds
   (`ai-agent-openai` or `ai-agent-gemini`), select it in AI settings and give
   it a key.
2. Build this plugin, install `build/plugin/vector-search.cgp` via
   Code on the Go's Plugin Manager, and restart the IDE.
3. Run a query from the project search screen; look for the **Semantic
   Results** section. The first query on a project builds the index.

## Key classes

- `VectorSearchPlugin.kt` — lifecycle, `ProjectSearchExtension`, search flow
- `EmbedderResolver.kt` — which embedder may be used, and why not when not
- `EmbedderIdentity.kt` — the provenance stamped onto every stored vector
- `ReindexDecision.kt` — whether the existing index can answer the query
- `EmbeddingBatches.kt` — how many chunks go into one call
- `EmbeddingIndexingService.kt` — file collection, schema, embedding storage (SQLite)
- `CodeChunker.kt` — splits files into embeddable chunks
- `VectorSearchService.kt` / `VectorMath.kt` — similarity ranking

## License

GPL-3.0 — same as AndroidIDE / Code on the Go.
