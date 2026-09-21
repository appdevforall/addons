# AI Core plugin for CodeOnTheGo

Two things in one plugin, and **mandatory for every AI feature**:

1. The **Agent** — a tool-calling chat assistant that reads, searches and edits
   the open project behind an approval gate, contributed as an editor tab plus a
   settings screen.
2. The **LLM inference router** — publishes `LlmInferenceService` through
   `SharedServices`, which [`Code-Suggestions`](../AI-Code-Suggestions/),
   [`Speech-to-Text`](../Speech-to-Text/) and
   [`Vector-Search`](../Vector-Search/) consume at runtime.

The Agent and the router shipped as separate `ai-assistant` and `ai-core` plugins
until they were merged here; an existing install's settings and chat history are
adopted on first activation.

**AI Core ships no backend of its own.** Backends are separate plugins that
register themselves with it on activation:

- [`ai-agent-local`](../AI-Agent-Local/) — on-device GGUF inference through a
  bundled, prebuilt **llama.cpp** AAR. Registers as `local`.
- [`ai-agent-gemini`](../AI-Agent-Gemini/) — the Gemini REST API over
  `HttpURLConnection` (no third-party SDK), so it is unaffected by the host IDE's
  OkHttp version. Registers as `gemini`.

Install AI Core **plus at least one backend**, or every request fails with
`Backend '…' not found`.

**Other plugins can add agent tools.** AI Core also publishes `ToolSourceRegistry`
through `SharedServices`; any plugin may register a tool source and its tools join
the agent's tool list. [`ai-agent-mcp`](../AI-Agent-MCP/) uses it to offer the
tools of remote Model Context Protocol servers. Unlike a backend, a tool provider
is entirely optional — with none installed the agent has exactly its own tools.

## Building

Prerequisites: Android SDK (API 33+), JDK 17. Create `local.properties` with
`sdk.dir=...`. No NDK, submodule or CMake — those moved to `ai-agent-local`
with the native code. This plugin uses the shared wrapper at the repo root:

```bash
cd plugins/AI-Core
../../gradlew assemblePlugin          # release  -> build/plugin/ai-core.cgp
../../gradlew assemblePluginDebug     # debug variant
```

The build resolves `plugin-api.jar` from the repo-root `../../libs/`.

## Backend registration and load order

Plugins load in parallel with no guaranteed order, so a backend plugin may
activate *before* AI Core has published its service. Each backend plugin handles
that by registering through a `PluginLifecycleListener` on AI Core's plugin id,
so it re-registers as soon as AI Core activates. Installation order does not
matter; only "at least one backend is installed" does.

## Optional backend capabilities

`LlmBackend` carries only what every backend can answer. Anything optional is a
separate interface extending it — `HistoryCapableBackend`, `ToolCallingBackend`,
`CancellableBackend`, `ConfigurableBackend` — and AI Core asks by type before it
calls. It holds no per-backend branching.

**A backend that wants multi-turn chat must implement `HistoryCapableBackend`.**
`generateStreamingWithTools` routes to `generateStreamingWithHistory` for one,
and to single-turn `generateStreaming` for a backend that declares neither
capability — so a missing declaration silently drops the conversation and
produces a plausible one-shot reply with no error. That is why both shipped
backends declare it, and why `LocalLlmBackendTest` asserts the declaration
rather than trusting behaviour to catch it.

## Plugin-contributed tools

A provider implements `ToolSourceRegistry.ToolSource` and registers it on
activation. Because plugins load in parallel with no guaranteed order, a provider
needs the same `PluginLifecycleListener` pattern the backends use, and AI Core
clears the store on deactivation so a provider re-registers when it comes back.

Only plain JDK types cross the boundary: each plugin has its own class loader, so
the host's contract is the one type both sides can name. `ToolSourceRegistryImpl`
is the single file that names it, and everything past it works in this plugin's
own `ContributedToolSource` / `ContributedTool`, which is what lets the tool set
be built and tested without the host.

Four rules the store applies, each of which was a bug before it was a rule:

- **Built-ins are reserved first**, so a contributed tool can never take over
  `edit_file`. A tool colliding with a *reserved* name is dropped outright, never
  qualified: published as `<alias>_respond` it stays reachable through the
  router's suffix pass, which would hand a model's final answer to a remote
  server. Reserved means the built-ins and the terminal tool. A name no longer
  buys anything at the approval gate either — `ToolApprovalManager` reads the
  handler's own `requiresApproval`, and `ContributedToolHandler` hard-codes it to
  `true`, so a contributed tool cannot skip the dialog whatever it is called. A
  tool colliding with another *contributed* tool is qualified rather than dropped
  — prefixing *everything* unconditionally cost the model the one name a tool's
  own description talks about.
- **Router, executor, grammar and the prompt's tool list are rebuilt together**,
  behind one `@Volatile` reference. Replacing the router alone leaves the local
  backend's token mask forbidding every newly contributed tool — a green build
  whose only symptom is "the model ignores the tools". The grammar is built from
  the *budgeted* list for the same reason: a name the mask permits but the prompt
  never mentioned is a name the model cannot use. A run in flight keeps the
  snapshot it started with.
- **`PromptToolBudget` caps what reaches the prompt** — 12 contributed tools, 200
  characters of description each, flattened to one line. One MCP server can
  advertise ninety tools; the cap lives here because every backend renders the
  tool list itself, including backends written elsewhere. Drops are logged once
  per rebuild.
- **A provider's failure costs one tool call, never the run.** A source that
  throws while listing is skipped whole; one that throws, hangs or completes with
  nothing while invoking yields a failed `ToolResult`, and stopping the run
  cancels through to the provider.

Contributed tools run inside the contributing plugin, under *its* permissions, and
outside the `PathGuard` containment that covers this plugin's own handlers — so the
approval dialog names the source plugin, and `allowsSessionApproval` is false for
every contributed tool: "Always Allow" is downgraded to a single approval.

For the same reason a source's own `requiresApproval = false` is ignored:
`ensureApproved` returns early on it, before `allowsSessionApproval` is ever
consulted, so honouring it would let a provider decline the only control there is
by asking. The dialog's title is the *registered* name, and all three
provider-supplied strings on it — the tool's own name, its description and the
source's label — are flattened and capped first, at the `ContributedToolHandler`
boundary rather than in each provider. A remote `displayName` carrying a newline
and a copy of the dialog's own header could otherwise forge structure the user
then trusts. Contributing plugins therefore ship no sanitising of their own and
depend on the installed `ai-core` for it; the two version independently.

## Key classes

Every source file sits in a package named for its layer; nothing is loose at the
root of `com/itsaky/androidide/plugins/aicore/`.

- `plugin/AiCorePlugin.kt` — plugin entry point; publishes the router, contributes
  the Agent tab and settings screen, and adopts a pre-merge install's data
- `services/LlmInferenceServiceImpl.kt` — the SharedServices-exposed router
- `services/ToolSourceRegistryImpl.kt` — the SharedServices-exposed tool registry;
  the only file naming that host contract
- `tool/sources/` — the contributed-tool layer: the store, the namespacing rules,
  the prompt budget and the handler that isolates a provider's failures
- `tool/AgentTools.kt` — router, executor and grammar as one swappable snapshot
- `backends/AiBackend.kt` — maps a stored backend setting onto a backend id
- `backends/BackendRegistry.kt` — the installed backends, as the settings
  selector sees them
- `backends/BackendFragmentFactory.kt` — loads a backend's settings pane with
  that backend plugin's own classloader
- `managers/ChatStorageManager.kt` — chat history persisted as JSON
- `logging/` — `LOG_PREFIX` (`AiCore`), prefixing every logcat tag this plugin
  writes, and `AgentTrace`, the one-stream trace of an agent run
- `fragments/`, `viewmodel/`, `tool/` — the Agent chat, its tool loop and handlers

## License

GPL-3.0 — same as AndroidIDE / CodeOnTheGo.
