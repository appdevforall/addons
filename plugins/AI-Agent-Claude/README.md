# AI Agent Claude plugin for CodeOnTheGo

Claude inference for CodeOnTheGo's AI plugins. Registers itself as the `claude`
backend with [`ai-core`](../AI-Core/)'s `LlmInferenceService`, which is what
`ai-core`'s Agent chat, `Code-Suggestions` and `Speech-to-Text` talk to.

It speaks Anthropic's Messages API, `POST https://api.anthropic.com/v1/messages`,
with the agent's tools declared natively (`tools[]` out, `tool_use` blocks in).
The endpoint is fixed: unlike the OpenAI backend there is no family of
compatible servers to point it at.

Calls the API directly over `HttpURLConnection` rather than Anthropic's Java
SDK: plugins run in the host IDE's classloader, which resolves `okhttp3` to the
host's older OkHttp first, and an SDK bundling its own copy crashes generation
with a `NoSuchMethodError`.

**No embeddings.** Anthropic has no embeddings endpoint, so this backend does not
implement `EmbeddingBackend` and `Vector-Search` needs `ai-agent-openai` or
`ai-agent-gemini` for indexing.

## Building

Prerequisites: Android SDK (API 33+), JDK 17. Create `local.properties` with
`sdk.dir=...`. This plugin uses the shared wrapper at the repo root:

```bash
cd plugins/AI-Agent-Claude
../../gradlew assemblePlugin          # release  -> build/plugin/ai-agent-claude.cgp
../../gradlew assemblePluginDebug     # debug variant
../../gradlew testDebugUnitTest       # the JVM unit tests
```

## Configuration

Everything is configured in **Preferences → Configuration → Agent**, on the pane
this plugin contributes: API key, model, and one **Test Connection & List Models** button.
Nothing outside this plugin handles the key. Listing models and testing the key
are the same `GET /v1/models`, so they are one control, and the model is a
single editable dropdown: type any id, or pick one the key can use.

## Request shape

`ClaudeRequestBuilder` owns it. Most rules below exist because the alternative is
a 400:

- **History is text.** AI Core hands tool results back as user text and never the
  assistant `tool_use` block they answer, so they are sent as user text rather
  than `tool_result` blocks, which the API rejects without a matching call.
  Consecutive same-role turns are merged and blank turns dropped; `SYSTEM` turns
  join the top-level `system`. Because no thinking block is ever replayed, the
  preserved-thinking history check never applies.
- **Two kinds of turn.** A streamed turn is the agent's: it asks for
  `thinking: {type: "adaptive"}` where the model takes it (on Opus 4.x and
  Sonnet 4.6 an omitted `thinking` means none), effort `high`, and a 64K
  budget, since thinking counts against `max_tokens`. A turn that is not
  streamed is a small job (a chat title, an inline suggestion): no `thinking`,
  effort `low`, and the caller's own budget, raised to 4K only on the 5.x models,
  which think whether asked to or not.
- **Never `temperature`:** it is a 400 on current Opus and Sonnet models.
- **Per-model fields** come from `ClaudeModelTraits`, which reads what the live
  catalog said each model accepts: output cap, adaptive thinking, effort. The
  settings pane stores that with the model list. For a model the catalog has not
  described, allow-lists decide, so an unknown model gets a bare request rather
  than a field it may reject. `fallbacks: "default"` with the
  `server-side-fallback-2026-07-01` beta goes only to the models whose safety
  classifiers can decline a request (Fable 5.1, Opus 5.5, Opus 5, Sonnet 5.5).
- **Aliases and dated ids are the same model.** The catalog lists some models
  only by dated id (`claude-haiku-4-5-20251001`), so a saved alias is matched to
  it rather than retired.
- **`required_tool` is ignored.** Forced `tool_choice` is a 400 on current
  models, and the contract lets a backend that cannot force a call ignore it.
- **Tools are not `strict`.** Strict mode needs closed schemas, which contributed
  MCP schemas rarely are.
- **Top-level `cache_control`** caches the system prompt and tool list an agent
  run re-sends every turn.

## Failures

- **`stop_reason: "refusal"`** is checked before anything else: the turn is
  reported as declined, and a tool call inside it is not run.
- **A server-side fallback** drops any tool call the declined model began, since
  the model that takes over never saw it. A tool call cut off by `max_tokens`
  is dropped too, even when its input happens to parse.
- **Stop** cancels the turn and closes its socket at once, so the API stops
  generating rather than running on until its next line.
- **A stream that goes silent** past the 120 s read timeout is reported as
  Claude stopping mid-reply, not as a lost connection.
- **Overload and rate limits** (408, 409, 429, 500, 502, 503, 504, 529) are
  retried twice with backoff,
  honouring `retry-after` up to 10 s, but only before a stream has delivered
  anything, so a retry never repeats text on screen (`TransientRetry`).
- An `error` event inside a 200 stream is raised as the HTTP status its type
  stands for, so it classifies and retries like the same failure on the status
  line.
- `ClaudeErrorFormatter` turns every failure into one translated sentence; a raw
  JSON body never reaches the transcript. An empty credit balance arrives as a
  400 whose message names it, and is reported as billing, not as a bad request.

## API key handling

Stored encrypted with the IDE's `KeystoreSecretStore` and sent as an `x-api-key`
**header**, never in a URL. `security/SecureApiKeyStore.kt` holds only this
plugin's Keystore alias (`cotg_ai_claude_key_v1`), unique so that another plugin's
invalidated-key recovery cannot delete this one's key. A key is checked against
`/v1/models` before it is saved; one that cannot be checked because the device is
offline can be saved anyway and is marked unverified.

**Keys outside a workspace.** Such a key is refused with a 400 on every request
until it carries an `anthropic-workspace-id` header. The plugin cannot look the
id up (listing workspaces needs an admin key), so when the key check reports
this, the pane shows a **Workspace ID** field and says where to find the id
(the Claude Console's **Workspaces** page; ids start with `wrkspc_`); the id is saved with the key,
sent on every request, and removed with the key. A key in a workspace never sees
the field. Typed ids are checked by `WorkspaceIds` before they become a header:
one printable token, no whitespace or line breaks.

## Installation

Install **`ai-core` as well**; without the router this plugin has nothing to
register with. Order does not matter: this plugin re-registers when it sees
ai-core activate. Copy `build/plugin/ai-agent-claude.cgp` to the device, install
via CodeOnTheGo's Plugin Manager, then restart the IDE.

## System prompt config

The prompt Claude asks ai-core to send lives in `src/main/assets/prompts/`, one YAML
file per concern, apart from the code that sends it. Changing the tone, adding a
rule or translating the prompt is an edit to those files alone. ai-core appends its
own IDE CONTEXT block after the rendered prompt.

The files are loaded, validated and cached once, when the plugin is activated.
`getSystemPrompt` renders `layout.yml` from that cache for each request, since the
tool list, the protocol and the example path vary per run; it never waits. Until the
config has loaded, or if it cannot render, it returns null and ai-core sends its own
default prompt.

| File | Keys | What it is |
|---|---|---|
| `agent.yml` | `schema_version`, `identity`, `include` | The entry point: the version (`1`; another is refused rather than misread), who the agent is, and the files below. |
| `scope.yml` | `scope` | What the agent will answer: anything, with the project's tools only when the request is about the open project. |
| `rules.yml` | `rules` | Priority groups, highest first; each has a `heading` (`CRITICAL`, `IMPORTANT`, `MANDATORY`, `OPTIONAL`) and its `items`. **Adding a rule is adding an item.** |
| `workflow.yml` | `behavior`, `workflow` | How to go about building or changing something; the workflow's `steps` are numbered when rendered. |
| `tools.yml` | `tools`, `tool_call_format` | What introduces the tool list, and how to call a tool: `native` under the function-calling API, `text` (with its examples) when calls travel in the reply. Exactly one is sent. |
| `layout.yml` | `layout.system_prompt` | Where each text goes. |

Loading and checking follow ai-core's rules (see ai-core's README): a key belongs to
one file, only `agent.yml` includes, and a missing, unknown, misspelled or duplicate
key, an empty list or an unquoted number is refused naming the file and path, e.g.
`rules.yml: rules[1].items is empty`. Texts are named by their YAML path in upper
case (`scope.heading` is `SCOPE_HEADING`); each rule group has `HEADING` and `ITEMS`,
each item and step has `TEXT`, each step has `NUMBER`, and each example has `PURPOSE`
and `CALL`. The request's values are `TOOLS` (each with `NAME`, `DESCRIPTION`,
inserted verbatim), `TOOL_CALL_SYNTAX` (null under native calling),
`NATIVE_TOOL_CALLS`, `EXAMPLE_FILE_PATH` and `EXAMPLE_FILE_STEM`.

Rendering is strict: an unknown name throws, naming the text it was in. Activation
renders the prompt for requests that open and close every section and logs any
failure, and `ClaudeSystemPromptTest` fails on one in the shipped files. A new key
needs `ClaudePromptConfig` and its parser; a new name needs `ClaudePromptVariables`.

The engine and the YAML plumbing (`PromptTemplateEngine`, `PromptConfigLoader`,
`PromptConfigStore`, `PromptConfigObject`, ...) are the IDE's, in `plugin-api.jar`'s
`com.itsaky.androidide.plugins.ai.prompt`, shared with ai-core and the other backends.
Only `ClaudePromptConfig`, its mapping in `ClaudePromptConfigParser`, and `sharedPromptConfig` are this plugin's own.

## Key classes

- `plugin/ClaudePlugin.kt` — entry point; registers the backend with ai-core
- `backend/ClaudeBackend.kt` — the conversation: streaming, retries, empty-reply diagnosis
- `backend/ClaudeRequestBuilder.kt` — `messages[]` mapping and request JSON (pure)
- `backend/ClaudeModelTraits.kt` — which optional fields each model accepts (pure)
- `backend/ClaudeStreamEvent.kt` — one line of the event stream (pure)
- `backend/TurnAssembler.kt` — folds a stream into a turn and decides how it ends (pure)
- `backend/ClaudeToolProtocol.kt` — `tools[]` declaration and `tool_use` accumulation (pure)
- `backend/TransientRetry.kt` — the retry schedule (pure)
- `backend/ClaudeModelCatalog.kt` — reads `GET /v1/models` (pure)
- `backend/ClaudeHttpClient.kt` — sockets, headers and timeouts
- `errors/ClaudeErrorFormatter.kt` — turns a failure into one translated sentence
- `prompt/ClaudeSystemPrompt.kt` — renders `layout.yml` from `ClaudePromptVariables`;
  `prompt/config/` maps `assets/prompts/` onto this plugin's config type, which the
  IDE's `ai.prompt` package loads, validates, caches and renders
- `settings/` — the pane this backend contributes to the selector
- `logging/` — `LOG_PREFIX` (`AiAgentClaude`), prefixing every logcat tag

## License

GPL-3.0 — same as AndroidIDE / CodeOnTheGo.
