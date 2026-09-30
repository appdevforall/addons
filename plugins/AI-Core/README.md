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

## System prompt config

The agent's **behaviour** and ai-core's **integration** are kept apart. Everything
the model reads — who the agent is, its rules, their priorities, every heading and
the order it all appears in — is in `src/main/assets/prompts/`, one YAML file per
concern. The Kotlin code only loads them, supplies the run's values and sends the
result. To change the tone, add a rule or translate the prompt, edit those files alone.

| Layer | Owns | Where |
|---|---|---|
| Config | wording, rules, layout | `assets/prompts/*.yml` |
| Loading | `agent.yml` and its includes, merged into one document | `prompt/config/PromptConfigLoader.kt`, `PromptConfigDocument.kt` |
| Schema | the keys and types, validated strictly | `prompt/config/AgentPromptConfig.kt`, `AgentPromptConfigParser.kt` |
| Cache | read once on activation, held in memory | `prompt/config/PromptConfigStore.kt` |
| Rendering | values into the layout, one pass | `prompt/PromptVariables.kt`, `SystemPromptRenderer.kt`, `ToolResultsPrompt.kt`, `ApprovalPrompt.kt`, `ContextFilesPrompt.kt`, `template/PromptTemplateEngine.kt` |
| Integration | which prompt a run gets, the tool loop, and sending it | `prompt/SystemPromptFactory.kt`, `tool/AgentLoop.kt`, `viewmodel/ChatViewModel.kt` |

The files are loaded once, when the plugin is activated, and cached in memory, so
no chat turn reads the disk. For each user message the layout is rendered into one
string, the run's system prompt. The model never sees or fetches the files themselves.

### The files

`agent.yml` is the entry point. It holds `schema_version`, the agent's `identity`
and an `include` list; the listed files are read in that order and merged with it
into one document:

| File | Keys | What it is |
|---|---|---|
| `agent.yml` | `schema_version`, `identity`, `include` | The version (`2`; another is refused rather than misread), who the agent is and what it will answer, and the files below. |
| `rules.yml` | `rules` | Priority groups, highest first; each has a `heading` (`CRITICAL`, `IMPORTANT`, `MANDATORY`, `OPTIONAL`) and its `items`. **Adding a rule is adding an item.** |
| `tools.yml` | `tools`, `tool_call_format` | What introduces the tool list, and how to write a call as text (sent only under the text protocol). The list itself is the tools the run offers (`PromptToolCatalog`). |
| `ide_context.yml` | `ide_context`, `session` | One line per fact the IDE can state: open files, module paths. `session` states the device's date and time on every prompt, and that the web tools are there. |
| `agent_loop.yml` | `agent_loop`, `approval` | What the agent is told after each tool batch: the `FAILED:` marker, the truncation notice, what to do next after a success or a failure, the `unfinished` turn sent once when a run that has used tools replies without `respond`, and the `required_tool` turn sent once when a run that had to search first answers without searching. `approval` is what it is told when the user denies a call, asks for a revision, or leaves the dialog unanswered. |
| `context_files.yml` | `context_files` | The heading over the files the user attached to a message. |
| `chat_title.yml` | `chat_title` | The system prompt of the one-off request that names a chat after its first reply. |
| `web_search.yml` | `web_search` | The system prompt of the one-off request the `web_search` tool makes through the active backend. It may use `CURRENT_TIME`, so "latest" is read as of the device's date. |
| `answer_review.yml` | `answer_review` | The system prompt of the second pass over an answer holding code, and what stands in for the evidence when no tool ran. `layout.answer_review` arranges its user turn from `REQUEST`, `EVIDENCE`, `HAS_EVIDENCE` and `DRAFT`; the instruction may use `CURRENT_TIME` and must end on `END_MARKER`, the line the code checks to know the reply was not cut off. |
| `tool_descriptions.yml` | `terminal_tool`, `built_in_tools` | What each of ai-core's own tools is for and what each argument means, keyed by tool name, plus the tool the agent answers with. The model reads it in the tool list and native definitions; the approval dialog shows the same description. |
| `layout.yml` | `layout.system_prompt`, `layout.ide_context`, `layout.tool_results`, `layout.context_files`, `layout.chat_title` | Where each text goes. The IDE CONTEXT layout is also appended to a backend's own prompt; `tool_results` is the user turn after each tool batch; `context_files` frames the attached files appended to the user's message; `chat_title` is the exchange the title request sends. |

**Connecting a new file** is two edits, and no code: create it, then add it to
`include`. Which file holds a key is up to the files: a top-level key may move to
any included file (or `agent.yml` itself; with no `include`, one file can hold
everything). The rules that keep this safe:

- **A key belongs to one file.** Defining it in two fails and names both
  (`tools.yml: identity is also defined in agent.yml`), so no copy wins silently.
- **Only `agent.yml` includes.** One level, so the whole prompt is always listed in one place.
- **Nothing is loaded by accident.** A `.yml` not listed is not read, and a listed
  one that is missing fails the load; `ShippedPromptFilesTest` also fails if a
  shipped file is never included. Entries are `.yml` paths under `prompts/`, each listed once.
- **Names cross files; anchors do not.** `layout.yml` places `{{IDENTITY}}` from
  `agent.yml` by name. YAML anchors (`&x`/`*x`) work only inside one file.

Parsing is strict: a missing key, an unknown or misspelled key, a duplicate, an
empty list or an unquoted number fails naming the file that holds it and the path,
for example `rules.yml: rules[1].items is empty`. The parser is the IDE's
`snakeyaml-engine`, which reads plain maps and lists only, with no class binding, so no reflection.

### Template syntax

Every text in the config is a template, written in a small in-house Mustache subset:

- `{{NAME}}` — a value. Config text placed this way is rendered where it lands, with
  the values in scope there; run data (a tool's description) is inserted verbatim.
- `{{#NAME}}…{{/NAME}}` — a section: repeated per item of a list (the item's
  keys shadow outer ones), rendered once for `true` or non-empty text, dropped for
  `false`, null, empty.
- `{{^NAME}}…{{/NAME}}` — an inverted section: rendered only when `{{#NAME}}` would not be.
- Inside a list, `FIRST` and `LAST` say where the item sits, e.g. `{{^FIRST}}` for a separator.

A line holding only a section tag vanishes, so tags can sit on their own lines.
Names are upper case, so JSON's `}}` in the examples is never read as a tag.

Every text is named by its YAML path in upper case, whichever file holds it: `identity` is `IDENTITY`,
`tools.heading` is `TOOLS_HEADING`, `ide_context.current_file` is
`IDE_CONTEXT_CURRENT_FILE`, `layout.ide_context` is `LAYOUT_IDE_CONTEXT`. Each
`RULES` item has `HEADING` and `ITEMS`, and each of those has `TEXT`. The run's values:

| Name | Value |
|---|---|
| `TERMINAL_TOOL` | the tool that answers the user (`respond`) |
| `TOOLS` | list; each has `NAME`, `DESCRIPTION` |
| `TOOL_CALL_SYNTAX` | the tool-call envelope; **null under native tool calling** |
| `EXAMPLE_FILE_PATH` | a real open file, for examples |
| `CURRENT_TIME` | the device's date, time and time zone, e.g. `Friday, 25 September 2026, 14:03 (America/Mexico_City, UTC-06:00)` |
| `HAS_IDE_CONTEXT` | whether anything is open or any module is known |
| `CURRENT_FILE` | the focused file, or null |
| `OTHER_FILES` | the other open tabs, comma separated; empty when none |
| `MODULES` | list; each has `NAME`, `SOURCE_DIR`, `LAYOUT_DIR`, `MANIFEST` (each may be null) |
| `HAS_MODULES` | whether `MODULES` has any |
| `TOOL_RESPONSES` | in `layout.tool_results`: the batch's results, already in `<tool_response>` envelopes |
| `ALL_SUCCEEDED` | in `layout.tool_results`: whether every tool in the batch succeeded |
| `MESSAGE` | in `agent_loop.failed`: what the failed tool reported |
| `KEPT`, `COUNT` | in `agent_loop.truncated`: the part kept, and how many characters were cut |
| `TOOL` | in `approval`: the tool the user did not approve; in `agent_loop.required_tool`: the tool the run had to call first |
| `INSTRUCTION` | in `approval.corrected_with_instruction`: what the user typed, verbatim |
| `MINUTES` | in `approval.timed_out`: how long the dialog waited |
| `USER_TEXT`, `REPLY_TEXT` | in `layout.chat_title`: the chat's first message and the reply to it, each cut to its start, verbatim |
| `FILES` | in `layout.context_files`: list of the attachments that could be read; each has `NAME`, `CONTENT` (verbatim) |

A built-in tool's name and the shape of its arguments (names, types, which are
required) stay in its handler, since the code runs them; what the tool and each
argument are *for* is `tool_descriptions.yml`'s. Every built-in and every argument
it declares must be described there, and an entry naming no built-in or no real
argument is refused, so a new built-in cannot ship unworded and a typo cannot
describe nothing. A tool contributed by another plugin brings its own description
and is never rewritten.

The `<tool_response>` envelope and the transcript's `Assistant:` label stay in code:
chat-tuned models are trained on the tag (handed bare prose, a small model re-issues
the call it already ran), `ToolCallExtractor` spots a model imitating it, and the
backend appends its own matching `Assistant:` cue. Tool output is inserted verbatim,
never rendered as a template.

Rendering is strict: an unknown name throws, naming the text it was in
(`rules.yml: rules[2].items[0]: unknown name {{TERMINAL_TOLL}}`). Activation renders
every layout against runs and tool batches that open and close every section, and
logs any failure, and checks `tool_descriptions.yml` against the built-in tools;
`SystemPromptConfigTest`, `ToolResultsPromptTest`, `ApprovalPromptTest`,
`ContextFilesPromptTest`, `ChatTitleTest` and `ToolDescriptionsTest` fail on one in the shipped files. Two changes need
code: a new key needs `AgentPromptConfig` and its parser, and a new name needs
`PromptVariables`.

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
- `prompt/` — system-prompt assembly: `SystemPromptFactory` picks the backend's
  prompt or the general one, and `SystemPromptRenderer` renders it from
  `PromptVariables`. `prompt/config/` maps `assets/prompts/` onto `AgentPromptConfig`; the
  IDE's `ai.prompt` package (in `plugin-api.jar`) loads, validates, caches and renders it.
- `fragments/`, `viewmodel/`, `tool/` — the Agent chat, its tool loop and handlers

## License

GPL-3.0 — same as AndroidIDE / CodeOnTheGo.
