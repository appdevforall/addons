# AI Agent OpenAI plugin for CodeOnTheGo

OpenAI-compatible inference for CodeOnTheGo's AI plugins. Registers itself as the
`openai` backend with [`ai-core`](../AI-Core/)'s `LlmInferenceService`, which is
what `ai-core`'s Agent chat, `Code-Suggestions`, `Speech-to-Text`
and `Vector-Search` actually talk to.

**One backend, many servers.** It speaks `POST {baseUrl}/chat/completions`, and
the base URL is a setting. Across OpenAI, Ollama, LM Studio, OpenRouter and
llama.cpp's `llama-server` the auth header, request JSON, SSE framing and error
shape are identical — only the host changes. So this is one backend with a URL
field rather than one plugin per provider:

| Base URL | What it is |
|---|---|
| `https://api.openai.com/v1` | **Default.** OpenAI itself. |
| `http://localhost:11434/v1` | Ollama on the device (e.g. in the bundled Termux). |
| `http://192.168.1.50:11434/v1` | Ollama on the user's PC, over Wi-Fi. |
| `http://192.168.1.50:1234/v1` | LM Studio's server. |
| `http://localhost:8080/v1` | `llama-server` from llama.cpp. |
| `https://openrouter.ai/api/v1` | OpenRouter — many models behind one key, some free. |

Calls the API directly over `HttpURLConnection` rather than an SDK: plugins run
in the host IDE's classloader, where `okhttp3` resolves to the host's older
OkHttp, and an SDK bundling its own copy crashes generation with a
`NoSuchMethodError`.

## Building

Prerequisites: Android SDK (API 33+), JDK 17. Create `local.properties` with
`sdk.dir=...`. This plugin uses the shared wrapper at the repo root:

```bash
cd plugins/AI-Agent-OpenAI
../../gradlew assemblePlugin          # release  -> build/plugin/ai-agent-openai.cgp
../../gradlew assemblePluginDebug     # debug variant
../../gradlew testDebugUnitTest       # the JVM unit tests
```

## Configuration

Everything is configured in **AI Core → Agent settings**, on the pane this plugin
contributes: server URL (with presets), API key, model, and one **Test Connection
& List Models** button. Nothing outside this plugin handles the key.

The pane adapts to the chosen server as it is picked, via
`BaseUrlPolicy.keyRequirement()`: `REQUIRED` for OpenAI's own host, `EXPECTED` for
another cloud provider, `NOT_NEEDED` for loopback or a private address — where the
key entry collapses to one muted line rather than showing an empty,
mandatory-looking field for a server that wants no credential. That happens whether
or not a key is already stored; a stored one leaves only **Remove**, so a key saved
for another server can still be cleared from here. Listing models and testing the connection
are the same `GET {baseUrl}/models`, so they are one control, and the model is a
single editable dropdown rather than a field beside a spinner.

Three rules that each break a real user if got wrong, and are covered by tests:

- **The API key is optional.** `isAvailable()` requires a key only when the base
  URL is OpenAI's own host. For any other server a non-blank URL is enough —
  local Ollama and LM Studio need no credential, and demanding one would leave
  the backend permanently "not available" for exactly the users who wanted a
  custom server.
- **The model is a field, not a constant.** Pointed at a local server the model
  is whatever the user pulled (`qwen2.5-coder`, `llama3.2`), so free-text entry
  always works and `GET /v1/models` is treated as optional — plenty of compatible
  servers do not implement it, which is why a 404 there reports "check the URL"
  rather than rejecting the key.
- **No auto-discovery.** There is no probing of `localhost:11434`; a background
  port scan is not something the user asked for. The URL field already reaches
  any server, on-device or on the LAN.

### Reasoning models

`gpt-5.x` and the `o` series reject `max_tokens` in favour of
`max_completion_tokens`, and several reject `temperature`. `RequestTuning` picks
the parameters from the model id and the server, and `UnsupportedParameter` reads
the offending name out of a 400 so the request is retried once without it —
compatible servers vary too much to hardcode a matrix.

### Cleartext URLs

`https` is required except for loopback and private ranges (RFC 1918, link-local,
IPv6 ULA, and bare LAN hostnames), where plain `http` is accepted and warned about
once on save. That is the "Ollama on my PC" case, and the host IDE's
`network_security_config` permits cleartext, so it works at runtime.

## API key handling

Stored encrypted (AES/GCM under a hardware-backed Android Keystore secret) and
sent as an `Authorization: Bearer` **header**, never in a URL query string. With
no key configured, no header is sent at all.

**A key is bound to the server it was saved for.** The base URL is recorded next to
the key (`KEY_API_KEY_URL`) and `readApiKeyOrBlank()` sends nothing when it does not
match the configured server's origin, so pointing the URL at a local or LAN address
after configuring OpenAI cannot put that bearer token on the network in the clear.
A key stored before the origin was recorded is still sent, since it cannot be shown
to belong elsewhere. The connection test applies the same rule.

`security/SecureApiKeyStore.kt` holds only this plugin's Keystore alias
(`cotg_ai_openai_key_v1`); the AES/GCM itself is the IDE's `KeystoreSecretStore`
(`plugin-api`, since **26.36** — hence this plugin's `min_ide_version`), so there
is one implementation in the process rather than a copy per plugin. The **alias**
is deliberately not shared: every plugin runs in the host app's process and UID
and therefore shares one Keystore, so a shared alias would let one plugin's
invalidated-key recovery (`deleteEntry`) destroy the other backend's stored key.
The plugins never read each other's ciphertext, so they have no reason to share
one.

## Installation

Install **`ai-core` as well** — without the router this plugin has nothing to
register with. Order does not matter: this plugin re-registers when it sees
ai-core activate. Copy `build/plugin/ai-agent-openai.cgp` to the device, install
via CodeOnTheGo's Plugin Manager, then restart the IDE.

## Native function calling

Not implemented, deliberately. This backend declares `HistoryCapableBackend` but
not `ToolCallingBackend`, so ai-core streams it the whole conversation and the
agent loop drives tools through a text envelope in the system prompt, which is
provider-agnostic. Declaring `ToolCallingBackend` without native function calling
would leave the caller waiting on a call this backend never makes. The system
prompt also tells the model not to use its own function-calling channel, since
nothing reads it.

## Key classes

Every source file sits in a package named for its layer; nothing is loose at the
root of `com/itsaky/androidide/plugins/aiagentopenai/`.

- `plugin/OpenAiPlugin.kt` — plugin entry point; registers the backend with ai-core
- `backend/OpenAiBackend.kt` — the HTTP transport, SSE streaming and model catalog
- `backend/OpenAiRequestBuilder.kt` — `messages[]` mapping and request JSON (pure)
- `backend/RequestTuning.kt` — reasoning-model parameters and the 400-retry rule (pure)
- `backend/SseChunk.kt` — one line of the token stream (pure)
- `backend/ChatModelFilter.kt` — keeps non-chat models out of the picker (pure)
- `errors/OpenAiErrorFormatter.kt` — turns a failure into one translated sentence
- `security/SecureApiKeyStore.kt` — this plugin's Keystore alias, over the IDE's `KeystoreSecretStore`
- `preferences/OpenAiPreferences.kt` — this plugin's settings store
- `prompt/OpenAiSystemPrompt.kt` — the system prompt this cloud model is given
- `settings/BaseUrlPolicy.kt` — URL normalization and the cleartext rule (pure)
- `settings/ServerPreset.kt` — the one-tap server list
- `settings/ConnectionVerification.kt` — what a live check established (pure)
- `settings/` — the pane this backend contributes to the selector
- `logging/` — `LOG_PREFIX` (`AiAgentOpenAi`), prefixing every logcat tag

The pure units carry the logic that would otherwise only fail on a device; they
are covered by 118 JVM tests.

## License

GPL-3.0 — same as AndroidIDE / CodeOnTheGo.
