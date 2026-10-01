# AI Agent Gemini plugin for CodeOnTheGo

Google Gemini API inference for CodeOnTheGo's AI plugins. Registers itself as the
`gemini` backend with [`ai-core`](../AI-Core/)'s `LlmInferenceService`, which is
what `ai-core`'s Agent chat, `Code-Suggestions`, `Speech-to-Text` and
`Vector-Search` actually talk to.

Calls the Generative Language REST API directly over `HttpURLConnection` rather
than the google-genai SDK: the SDK bundles OkHttp 4.x, but plugins run in the
host IDE's classloader where `okhttp3` resolves to the host's older OkHttp, and
that mismatch crashed generation with a `NoSuchMethodError`.

## Building

Prerequisites: Android SDK (API 33+), JDK 17. Create `local.properties` with
`sdk.dir=...`. This plugin uses the shared wrapper at the repo root:

```bash
cd plugins/AI-Agent-Gemini
../../gradlew assemblePlugin          # release  -> build/plugin/ai-agent-gemini.cgp
../../gradlew assemblePluginDebug     # debug variant
```

## API key handling

The key is entered in **AI Core → Agent settings**, not here. It is stored
encrypted (AES/GCM under a hardware-backed Android Keystore secret) and sent as
an `x-goog-api-key` **header**, never in a URL query string.

`security/SecureApiKeyStore.kt` holds only this plugin's Keystore alias
(`cotg_ai_gemini_key_v1`); the AES/GCM itself is the IDE's `KeystoreSecretStore`
(`plugin-api`, since **26.36** — hence this plugin's `min_ide_version`), so there
is one implementation in the process rather than a copy per plugin. The alias
stays per plugin: they all share the host's Keystore, so a shared alias would let
one plugin's invalidated-key recovery delete another's secret. A key written under
an earlier plugin id is adopted once by `preferences/GeminiPreferences.kt` and
re-encrypted here.

## Installation

Install **`ai-core` as well** — without the router this plugin has nothing to
register with. Order does not matter: this plugin re-registers when it sees
ai-core activate. Copy `build/plugin/ai-agent-gemini.cgp` to the device, install
via CodeOnTheGo's Plugin Manager, then restart the IDE.

## Cross-plugin contract

This plugin's own settings pane calls `GeminiBackend.listModels()` and
`listModels(String)` directly (see `BackendGeminiCatalogGateway` in
`settings/GeminiCatalogGateway.kt`) to populate the model picker and to verify a
key before it is saved. Those two signatures, and the `ListModels HTTP <code>`
message shape thrown by `fetchAvailableModels`, are a contract — the pane is
mounted by ai-core across the plugin classloader boundary, so
`proguard-rules.pro` pins the class and its public methods.

## System prompt config

The prompt Gemini asks ai-core to send lives in `src/main/assets/prompts/`, one YAML
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
failure, and `GeminiSystemPromptTest` fails on one in the shipped files. A new key
needs `GeminiPromptConfig` and its parser; a new name needs `GeminiPromptVariables`.

The engine and the YAML plumbing (`PromptTemplateEngine`, `PromptConfigLoader`,
`PromptConfigStore`, `PromptConfigObject`, ...) are the IDE's, in `plugin-api.jar`'s
`com.itsaky.androidide.plugins.ai.prompt`, shared with ai-core and the other backends.
Only `GeminiPromptConfig`, its mapping in `GeminiPromptConfigParser`, and `sharedPromptConfig` are this plugin's own.

## Key classes

Every source file sits in a package named for its layer; nothing is loose at the
root of `com/itsaky/androidide/plugins/aiagentgemini/`.

- `plugin/GeminiPlugin.kt` — plugin entry point; registers the backend with ai-core
- `backend/GeminiBackend.kt` — the REST transport, streaming (SSE), embeddings and model catalog
- `backend/GeminiEmbeddingProtocol.kt` — the `batchEmbedContents` body, per-call cap and positional reply (pure)
- `errors/GeminiErrorFormatter.kt` — turns an API failure into one translated sentence
- `security/SecureApiKeyStore.kt` — this plugin's Keystore alias, over the IDE's `KeystoreSecretStore`
- `preferences/GeminiPreferences.kt` — this plugin's settings store, plus the
  one-time adoption of settings written under earlier plugin ids
- `prompt/GeminiSystemPrompt.kt` — renders `layout.yml` from `GeminiPromptVariables`;
  `prompt/config/` maps `assets/prompts/` onto this plugin's config type, which the
  IDE's `ai.prompt` package loads, validates, caches and renders
- `logging/` — `LOG_PREFIX` (`AiAgentGemini`), prefixing every logcat tag this plugin writes
- `settings/` — the settings pane this backend contributes to the selector

## License

GPL-3.0 — same as AndroidIDE / CodeOnTheGo.
