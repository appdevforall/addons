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

Everything is configured in **AI Core → Agent settings**, on the pane this plugin
contributes: API key, model, and one **Test Connection & List Models** button.
Nothing outside this plugin handles the key. Listing models and testing the key
are the same `GET /v1/models`, so they are one control, and the model is a
single editable dropdown: type any id, or pick one the key can use.

## Request shape

`ClaudeRequestBuilder` owns it, and each rule below is a 400 when got wrong:

- **History is text.** AI Core hands tool results back as user text and never the
  assistant `tool_use` block they answer, so they are sent as user text rather
  than `tool_result` blocks, which the API rejects without a matching call.
  Consecutive same-role turns are merged and blank turns dropped; `SYSTEM` turns
  join the top-level `system`. Because no thinking block is ever replayed, the
  preserved-thinking history check never applies.
- **No `thinking`, no `temperature`.** Omitting `thinking` runs each model's own
  default (adaptive on Claude Opus 5.5, which rejects any other setting);
  sampling parameters are removed on current Opus and Sonnet models.
- **`max_tokens` is raised to 64K for a stream** (16K otherwise), because
  thinking counts against it and `LlmConfig`'s default 2048 cuts turns off.
- **Per-model fields** come from `ClaudeModelTraits`: `output_config.effort:
  "high"` where the model takes it (not Haiku 4.5 or older lines), and
  `fallbacks: "default"` with the `server-side-fallback-2026-07-01` beta on the
  models whose safety classifiers can decline a request (Fable 5.1, Opus 5.5,
  Opus 5, Sonnet 5.5).
- **`required_tool` is ignored.** Forced `tool_choice` is a 400 on current
  models, and the contract lets a backend that cannot force a call ignore it.
- **Tools are not `strict`.** Strict mode needs closed schemas, which contributed
  MCP schemas rarely are.
- **Top-level `cache_control`** caches the system prompt and tool list an agent
  run re-sends every turn.

## Failures

- **`stop_reason: "refusal"`** is checked before anything else: the turn is
  reported as declined, and a tool call inside it is not run.
- **Overload and rate limits** (529, 429, 5xx) are retried twice with backoff,
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

## Installation

Install **`ai-core` as well**; without the router this plugin has nothing to
register with. Order does not matter: this plugin re-registers when it sees
ai-core activate. Copy `build/plugin/ai-agent-claude.cgp` to the device, install
via CodeOnTheGo's Plugin Manager, then restart the IDE.

## Key classes

- `plugin/ClaudePlugin.kt` — entry point; registers the backend with ai-core
- `backend/ClaudeBackend.kt` — the conversation: streaming, retries, empty-reply diagnosis
- `backend/ClaudeRequestBuilder.kt` — `messages[]` mapping and request JSON (pure)
- `backend/ClaudeModelTraits.kt` — which optional fields each model accepts (pure)
- `backend/ClaudeStreamEvent.kt` — one line of the event stream (pure)
- `backend/ClaudeToolProtocol.kt` — `tools[]` declaration and `tool_use` accumulation (pure)
- `backend/TransientRetry.kt` — the retry schedule (pure)
- `backend/ClaudeModelCatalog.kt` — reads `GET /v1/models` (pure)
- `backend/ClaudeHttpClient.kt` — sockets, headers and timeouts
- `errors/ClaudeErrorFormatter.kt` — turns a failure into one translated sentence
- `prompt/ClaudeSystemPrompt.kt` — the system prompt this cloud model is given
- `settings/` — the pane this backend contributes to the selector
- `logging/` — `LOG_PREFIX` (`AiAgentClaude`), prefixing every logcat tag

## License

GPL-3.0 — same as AndroidIDE / CodeOnTheGo.
