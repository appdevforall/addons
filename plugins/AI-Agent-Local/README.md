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
- `prompt/LocalSystemPrompt.kt` — the system prompt small on-device models need
- `feedback/UserFeedback.kt` — throttled Toasts, and the actionable exceptions
- `format/ByteSize.kt` — binary-unit rendering of RAM figures
- `logging/` — `LOG_PREFIX` (`AiAgentLocal`), prefixing every logcat tag this plugin writes
- `settings/` — the settings pane this backend contributes to the selector

## License

GPL-3.0 — same as AndroidIDE / CodeOnTheGo.
