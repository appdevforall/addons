# AI Chat

A Code on the Go plugin that adds a chat tab. The chat screen is a React web app adapted
from [Jan](https://github.com/janhq/jan)'s web UI (Apache-2.0; see [`web/NOTICE`](web/NOTICE)),
shown in a WebView. It runs no model: every message goes through AI Core's
`LlmInferenceService` router to the backend picked in the composer.

Requires **AI Core** and at least one backend (for example **AI Agent Local**).

## Layout

| Path | What |
|---|---|
| `web/` | The web app: Vite + React 19 + Tailwind 4 + zustand, Jan's Streamdown renderer |
| `web/src/lib/bridge.ts` | `window.CoGoChat`, the page's only way into the IDE; a mock echo backend when there is none |
| `web/src/stores/chat.ts` | Threads, streaming, stop and regenerate |
| `src/main/kotlin/.../AiChatPlugin.kt` | Lifecycle, the editor tab, the side-menu entry, tooltips and Tier-3 help |
| `src/main/kotlin/.../ChatFragment.kt` | WebView served by `WebViewAssetLoader` from `assets/web/`; navigation locked to the bundle; network loads blocked |
| `src/main/kotlin/.../ChatBridge.kt` | `@JavascriptInterface` methods: backends, send/cancel (streamed back through `window.__cogoChatEvent`), chats saved to `threads.json` in the plugin's files dir |

## Building

```
../../gradlew assemblePlugin
```

`preBuild` runs `npm ci` and `npm run build` in `web/` (Node 20+ needed), writing
`src/main/assets/web/`, which is not committed. The `.cgp` lands in `build/plugin/`.

To work on the UI alone, `cd web && npm run dev` serves it in a desktop browser against the
mock backend.

## Security notes

- The WebView allows JavaScript but no file or content access and no network loads. Only
  `https://appassets.androidplatform.net/web/` is served; any other navigation is refused.
- Markdown from the model is rendered without remote images; links go to the browser
  through the bridge, which accepts only `http` and `https`.
- No tools are offered to the model, so it cannot act on the project.
