# AI Agent MCP plugin for CodeOnTheGo

Connects CodeOnTheGo's Agent to **Model Context Protocol** servers. Tools a
configured server advertises are contributed to
[`ai-core`](../AI-Core/)'s agent through the host's `ToolSourceRegistry`, so they
appear beside the Agent's own tools with no change to `ai-core`.

This is a *tool* plugin, not a model backend: it adds no inference. Install
`ai-core` and at least one backend (`ai-agent-local`, `ai-agent-gemini`) as well.

## Why a separate plugin

`ai-core` declares the filesystem, shell and project permissions its own tools
need, and **no network access**. MCP is the mirror image: it declares
`network.access` and nothing else. Keeping them apart is what lets a user install
the Agent without granting it the network, or add remote tools without widening
what the Agent itself may touch.

## Building

Prerequisites: Android SDK (API 33+), JDK 17. Create `local.properties` with
`sdk.dir=...`. This plugin uses the shared wrapper at the repo root:

```bash
cd plugins/AI-Agent-MCP
../../gradlew assemblePlugin          # release -> build/plugin/ai-agent-mcp.cgp
../../gradlew assemblePluginDebug     # debug variant
../../gradlew testDebugUnitTest       # JVM tests: framing, error classification, sanitising
```

## Using it

Install the `.cgp` through the Plugin Manager, then open **Preferences →
Configuration → MCP servers**:

1. **Add server** — name, endpoint URL, optional token.
2. **Connect** — saves the server, performs the MCP handshake and lists the tools
   it offers, all in one step.
3. Switch on the tools you want. **They start off**: one popular GitHub server
   advertises around ninety tools, which would fill a phone-sized context window
   on its own.
4. **Clear stored credential** — removes the stored token and headers. The token
   field never shows what is stored and an empty field keeps it, so this is the
   only way back to a server that needs no credential.

Every remote tool asks for approval on every call, and there is no "always
allow" for contributed tools — they run outside the Agent's own path
containment, so the dialog is the only gate.

## Transport

MCP's **Streamable HTTP** transport over `HttpURLConnection`:

- one POST per JSON-RPC call, reading either a JSON body or the SSE stream
  carrying the same document;
- `Mcp-Session-Id` echoed when the server is stateful, dropped when it is not
  (the 2026-07-28 revision is stateless), with one clean re-handshake when a
  session expires;
- the bearer token as an `Authorization` header, never a query string.

Two dependencies were deliberately not taken:

- **No OkHttp.** Plugins run in the host IDE's classloader, where `okhttp3`
  resolves to the host's older copy; a bundled SDK crashes with
  `NoSuchMethodError`.
- **No official MCP Kotlin SDK.** It is KMP with no stated Android target, ships
  no HTTP engine, and required pinning Kotlin 2.4.10 when it was tried
  (ADFA-5083) against the 2.3.0 these plugins standardise on.

## Layout

- `plugin/McpPlugin.kt` — entry point; registers the tool source with `ai-core`
  and re-registers from a `PluginLifecycleListener`, since plugins load in
  parallel with no ordering
- `transport/` — `JsonRpc` framing, `SseChunk` line parsing, `McpHttpClient`
- `client/` — `McpSession` (handshake, `tools/list`, `tools/call`),
  `McpConnections` (one session per server)
- `tools/` — `McpToolSource` (the `ToolSourceRegistry.ToolSource`),
  `McpToolCatalog` (what each server last advertised), `McpToolText` (sanitising)
- `settings/` — server CRUD, per-tool toggles, the settings pane
- `errors/` — HTTP and JSON-RPC failures reduced to one translated sentence
- `security/SecureTokenStore.kt` — this plugin's Keystore alias, over the IDE's `KeystoreSecretStore`

## Security notes

- Tool names and descriptions from a server are **untrusted remote text**. Names
  are reduced to `[a-z0-9_]` here; descriptions are flattened to one capped line
  by `ai-core`, which owns that cap because it is the side that assembles the
  prompt. Capping in both places only gave the two constants room to diverge, so
  this plugin ships no description sanitising of its own. Nothing enforces the
  pairing — the two plugins version independently — so an `ai-core` older than the
  release that flattens at its `ContributedToolHandler` boundary would take raw
  multi-line server text into the prompt.
- Tokens and custom headers are encrypted with an AES/GCM key held in the Android
  Keystore under this plugin's own alias; only ciphertext is written to disk.
  `security/SecureTokenStore.kt` holds nothing but that alias (`cotg_ai_mcp_token_v1`);
  the AES/GCM itself is the IDE's `KeystoreSecretStore` (`plugin-api`, since
  **26.36** — hence this plugin's `min_ide_version`), so there is one implementation
  in the process rather than a copy per plugin. The alias stays per plugin: they all
  share the host's Keystore, so a shared alias would let one plugin's invalidated-key
  recovery delete another's secret. A token that can no longer be decrypted — a
  restored backup, an OEM Keystore reset — is reported as exactly that, never sent as
  an absent one, which would surface as the server refusing a token that is still
  stored and still correct; a Keystore that merely would not answer is told apart
  again, since that token is intact and the call is only worth retrying.
- A token or a custom header is refused on an `http://` URL: encryption at rest
  buys nothing for a credential sent in the clear.
- Redirects are never followed automatically. A 3xx is repeated only when it
  resolves to the same origin, so the bearer token and the user's own headers
  cannot be replayed to another host in one hop.
- Error bodies stay in logcat. The transcript gets one sentence.
