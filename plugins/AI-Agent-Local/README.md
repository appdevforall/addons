# AI Agent Local plugin for CodeOnTheGo

On-device GGUF inference for CodeOnTheGo's AI plugins. Registers itself as the
`local` backend with [`ai-core`](../AI-Core/)'s `LlmInferenceService`, which is
what `ai-core`'s Agent chat, `Code-Suggestions`, `Speech-to-Text` and
`Vector-Search` actually talk to.

Runs `.gguf` models through a bundled, prebuilt **llama.cpp** AAR. Declares no
INTERNET permission — prompts and code never leave the device. Requires a 64-bit
ARM device (`arm64-v8a`).

## Building

Prerequisites: Android SDK (API 33+), JDK 17. Create `local.properties` with
`sdk.dir=...`. A normal build needs **no NDK, submodule, or CMake** — the native
library is committed prebuilt. This plugin uses the shared wrapper at the repo
root:

```bash
cd plugins/AI-Agent-Local
../../gradlew assemblePlugin          # release  -> build/plugin/ai-agent-local.cgp
../../gradlew assemblePluginDebug     # debug variant
```

The build resolves `plugin-api.jar` from the repo-root `../../libs/` and the native
library from `libs/v8/llama-v8-release.aar` + `libs/llama-api.jar`.

## Native llama.cpp: prebuilt by default

The plugin consumes the committed AAR, so the `llama.cpp` git submodule and the
`llama-api` / `llama-impl` source modules are **not** part of a normal build.
`settings.gradle.kts` includes those modules **only** when the submodule is
checked out (`subprojects/llama.cpp/CMakeLists.txt` exists) — which is exactly
the setup a CI checkout does *not* have, so CI builds against the prebuilt AAR.

To regenerate the AAR after bumping the llama.cpp fork, see
**[BUILDING.md](BUILDING.md)** and `scripts/rebuild-llama-aar.sh` (requires the
submodule + NDK/CMake). Notes on the fork live in
[SUBMODULE_NOTES.md](SUBMODULE_NOTES.md).

## Installation

Install **`ai-core` as well** — without the router this plugin has nothing to
register with. Order does not matter: this plugin re-registers when it sees
ai-core activate. Copy `build/plugin/ai-agent-local.cgp` to the device, install
via CodeOnTheGo's Plugin Manager, then restart the IDE.

The model file itself is chosen in **AI Core → Agent settings**; this backend
reads that setting at request time.

## System prompt config

With **Use simple local prompt** on, this backend asks ai-core to send a short prompt
written for 1–3B on-device models; it lives in `src/main/assets/prompts/`, one YAML
file per concern, apart from the code that sends it. Changing the tone, adding a
rule or translating the prompt is an edit to those files alone. ai-core appends its
own IDE CONTEXT block after the rendered prompt. With the setting off,
`getSystemPrompt` returns null and ai-core's own prompt is sent.

The files are loaded, validated and cached once, when the plugin is activated.
`getSystemPrompt` renders `layout.yml` from that cache for each request, since the
tool list and the example path vary per run; it never waits. Until the config has
loaded, or if it cannot render, it returns null and ai-core sends its default prompt.

| File | Keys | What it is |
|---|---|---|
| `agent.yml` | `schema_version`, `identity`, `include` | The entry point: the version (`1`; another is refused rather than misread), who the agent is, and the files below. |
| `rules.yml` | `rules` | Rule groups, each a `heading` and its `items`; today one `Rules` group. **Adding a rule is adding an item.** |
| `tools.yml` | `tools`, `tool_call_format` | What introduces the tool list, and `tool_call_format.text`: the envelope and its examples, each a `purpose` and a `call`. A small model calls through the text protocol only, so there is no native format; when ai-core parses no text calls, none of it is sent. |
| `layout.yml` | `layout.system_prompt` | Where each text goes. |

The structure, the names texts are rendered under and the checks are AI-Agent-Gemini's
and AI-Agent-OpenAI's (see Gemini's README). The request's values are `TOOLS` (each
with `NAME`, `DESCRIPTION`, inserted verbatim), `TOOL_CALL_SYNTAX`,
`EXAMPLE_FILE_PATH`, `EXAMPLE_FILE_NAME` (the bare file name, which `read_file` and
`open_file` accept) and `EXAMPLE_FILE_STEM`.

Rendering is strict: an unknown name throws, naming the text it was in, where the
file-per-section design this replaced dropped the file silently. Activation renders
the prompt for requests that open and close every section and logs any failure, and
`LocalSystemPromptTest` fails on one in the shipped files. A new key needs
`LocalPromptConfig` and its parser; a new name needs `LocalPromptVariables`.

The engine and the YAML plumbing (`PromptTemplateEngine`, `PromptConfigLoader`,
`PromptConfigStore`, `PromptConfigObject`, ...) are the IDE's, in `plugin-api.jar`'s
`com.itsaky.androidide.plugins.ai.prompt`, shared with ai-core and the other backends.
Only `LocalPromptConfig`, its mapping in `LocalPromptConfigParser`, and `sharedPromptConfig` are this plugin's own.

## Key classes

Every source file sits in a package named for its layer; nothing is loose at the
root of `com/itsaky/androidide/plugins/aiagentlocal/`.

- `plugin/LocalLlmPlugin.kt` — plugin entry point; registers the backend with ai-core
- `backend/LocalLlmBackend.kt` — on-device GGUF inference over the llama.cpp AAR
- `model/GgufModelInspector.kt` — refuses embedding-only models before they SIGABRT
- `model/ModelLoadDiagnostics.kt` / `model/ModelLoadMessages.kt` — load-failure
  classification and its user-facing wording
- `preferences/LocalLlmPreferences.kt` — this plugin's settings store, plus the
  one-time adoption of settings written under earlier plugin ids
- `prompt/LocalSystemPrompt.kt` — renders `layout.yml` from `LocalPromptVariables`;
  `prompt/config/` maps `assets/prompts/` onto this plugin's config type, which the
  IDE's `ai.prompt` package loads, validates, caches and renders
- `feedback/UserFeedback.kt` — throttled Toasts, and the actionable exceptions
- `format/ByteSize.kt` — binary-unit rendering of RAM figures
- `logging/` — `LOG_PREFIX` (`AiAgentLocal`), prefixing every logcat tag this plugin writes
- `settings/` — the settings pane this backend contributes to the selector

## License

GPL-3.0 — same as AndroidIDE / CodeOnTheGo.
