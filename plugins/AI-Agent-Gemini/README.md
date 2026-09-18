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

## Key classes

Every source file sits in a package named for its layer; nothing is loose at the
root of `com/itsaky/androidide/plugins/aiagentgemini/`.

- `plugin/GeminiPlugin.kt` — plugin entry point; registers the backend with ai-core
- `backend/GeminiBackend.kt` — the REST transport, streaming (SSE) and model catalog
- `errors/GeminiErrorFormatter.kt` — turns an API failure into one translated sentence
- `security/SecureApiKeyStore.kt` — this plugin's Keystore alias, over the IDE's `KeystoreSecretStore`
- `preferences/GeminiPreferences.kt` — this plugin's settings store, plus the
  one-time adoption of settings written under earlier plugin ids
- `prompt/GeminiSystemPrompt.kt` — the system prompt this cloud model is given
- `logging/` — `LOG_PREFIX` (`AiAgentGemini`), prefixing every logcat tag this plugin writes
- `settings/` — the settings pane this backend contributes to the selector

## License

GPL-3.0 — same as AndroidIDE / CodeOnTheGo.
