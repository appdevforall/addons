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
`network.access`, plus `debugger.endpoints` to read the SQLite debugger endpoints
Code on the Go stores, and nothing else. Keeping them apart is what lets a user install
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
3. Switch on the tools you want. **They start off** (except five read-only tools
   of the automatic SQLite debugger entry, below): one popular GitHub server
   advertises around ninety tools, which would fill a phone-sized context window
   on its own.
4. **Clear stored credential** — removes the stored token and headers. The token
   field never shows what is stored and an empty field keeps it, so this is the
   only way back to a server that needs no credential.

Every remote tool asks for approval on every call, with one exception: a tool
of the automatic SQLite debugger entry that declares itself read-only may be
given "Always Allow" for the rest of the chat (details under "The SQLite
debugger entry"). Remote tools run outside the Agent's own path containment, so
the dialog is the only gate.

### The SQLite debugger entry

`read_sql_template` (ADFA-7302) is this plugin's own tool on each entry whose server has `get_template_variables`:
- **What it does.** It fetches those variables from the app, adds the project's Room schemas (`SqlTemplateService.getRoomSchemas`) and per database `schema_matches` and `room_schema`, and has Code on the Go render the template (`SqlTemplateService.render`). It returns the SQL without running it.
- **Template functions.** `row_count`, `distinct_values`, `sample_rows`, `integrity`, `suggest_indexes` and `lint_foreign_keys` are tool calls to the same app, made on the call's session and handle, so a cancel reaches them. `distinct_values` and `sample_rows` ask the server for their whole limit (at most 1000 rows) and fail rather than give the template fewer rows than exist or a value the server cut. A function's call that fails, as opposed to one the server answers with an error, fails the read as `get_template_variables` would, so it is retried on the app's new address and reported the same way.
- **Start state and budget.** It starts switched on, last of the entry's five, so in compact mode it is the one AI-Core drops.
- **Requirements.** It needs Code on the Go 26.48 or later.
- **Limits.**
  - The render's time limit (110 s) interrupts the render thread, but a function's HTTP read is not interruptible: it ends when the server answers, within its 50 s.
  - Without a `template` argument it lists the project's `*.sql.peb` files.

For an app built in Code on the Go with `cogo.sqliteDebugger=true`, the plugin
adds a server entry on its own, named "SQLite debugger (<application ID>)", with
no URL or token to type. Code on the Go stores the port and token the running
debug app reports; the plugin reads them through the `DebuggerEndpointService`
(permission `debugger.endpoints`, so it needs Code on the Go 26.48 or later) and
points the entry at `http://127.0.0.1:<port>/mcp`. A relaunched app gets a new
port and token, and the plugin reads them again after a refused connection. An
app that has not reported yet has no entry, and no error is shown. An app that
stops reporting loses its entry, and the entry's tools, health and session go
with it in the same step (the session ended with the entry's token, which the
app's server requires); if the app comes back before that step has finished
its cleanup, the new entry keeps its own. A call or a listing still running
when the entry goes leaves no session or health behind for it.

When the entry cannot reach its app, the reason the Agent tab shows says what to
do, told apart by exception type (`SocketTimeoutException` or `ConnectException`,
anywhere in the cause chain):

- **No answer** (a socket timeout): "The app <application ID> did not answer. If
  the app is in the background, Android may have paused it. Bring the app to the
  front and try again." Some phones freeze an app in the background: a Galaxy
  Note 20 froze one about 13 minutes after it left the screen.
- **Refused** (nothing listens on the port): "The app <application ID> is not
  running with the SQLite debugger. Open the app again from Code on the Go." The
  app's process has ended, or the app was rebuilt without the SQLite debugger.

Any other failure of the entry, and every failure of a server you add yourself,
keeps the generic reason, such as "Could not reach <name>: <detail>".

Each app's tools are told apart for the model. Their names start with `db_` and
the first seven characters of the application ID's last segment
(`db_notes_query` for `com.example.notes`, `db_myappli_query` for
`com.example.myapplication`); when two apps would share that prefix, both use the first
five characters of the segment and four hex digits derived from the full
application ID (`notes_3fa1_query`). If that prefix is still another app's, as
when two apps' digits agree or the prefix equals another app's `db_` prefix, the
app uses the segment's first character and eight hex digits (`n_3fa1c2d4_query`),
and past that `db_` and a number in application-ID order (`db_1_query`). An app
keeps the first prefix that no other app shares, so the names never depend on
which app was read first. Each description starts with the application ID, as
"com.example.notes (SQLite debugger): ...", and the approval dialog shows it.
Servers you add yourself keep the name prefix taken from their label.

- It is held in memory only and is **not shown in the settings screen in this
  version**, so there is no switch to turn it off there.
- Five tools start switched **on**: `list_databases`, `get_schema`, `query` and
  `get_events`, each only when the server declares MCP's `readOnlyHint: true` for
  it, and this plugin's own `read_sql_template`, added when the server has
  `get_template_variables`. Every other tool of that entry (today `list_connections`,
  `explain_query_plan`, `get_stats` and `integrity_check`) starts off, and so
  does any tool without that hint. The hint is trusted for this entry only, never
  for a server you add yourself. Five, not all nine, because AI-Core describes
  at most 12 contributed tools per message (4 for a small on-device model), in
  order, and drops the rest; every app has its own entry. Switching the others
  on will come with showing this entry in the settings screen (phase 2).
- Writes (ADFA-7300). The entry's `execute` tool, which runs one statement that
  changes the app's database, is offered only while **Let the Agent change app
  databases** is on in this plugin's settings (one switch for every app, off by
  default, `McpServerStore.allowDatabaseWrites`). It is never declared
  read-only, whatever hint the server sends (`ReadOnlyTrust.WRITE_TOOLS`,
  `ReadOnlyTrust.isReadOnly`), so AI-Core asks before every call and never
  offers "Always Allow" for it. A change is committed at once and cannot be
  undone. On a Room database the server refuses the first write until the Agent
  repeats it with `acknowledge_room: true` (ADFA-7299), so that call asks too.
  While the switch is off, the entry's `query` description adds that the user
  can switch writes on there and that the databases must not be changed any
  other way (`ReadOnlyTrust.needsWriteSwitchHint`). Told only "use execute", a
  tool it did not have, the model asked to run `sqlite3` in the shell instead
  (a Note 20, 2026-11-25). The shell would not reach another app's private
  files, but the user was never told about the switch. When SQLite refuses a
  write sent through `query` ("not authorized", or a read-only database), the
  same sentence follows the failure's text (`ReadOnlyTrust.isWriteRefusal`),
  which AI-Core shows under the chat: after a failed call, AI-Core replaces the
  Agent's own answer with "I couldn't complete that, the last action failed".
  With the switch on, each app offers five tools, in the order the server lists
  them (today `list_databases`, `get_schema`, `query`, `execute`, `get_events`),
  so two apps take 10 of AI-Core's 12 slots, and in compact mode (4) AI-Core
  drops the first app's `get_events` and every later tool.
- Approval. This plugin declares each of that entry's tools read-only when the
  server sends `readOnlyHint: true` for it (`McpToolSource.Spec.isReadOnly`).
  AI-Core 3.6.0 or later then offers "Always Allow" in the approval dialog for
  those tools (ADFA-7147). One tap covers that tool for the rest of the chat,
  and the Agent asks again in a new chat. Each tool is its own grant, and so is
  each app: AI-Core keys a grant by this plugin, the tool's name and a digest of
  its description and schema, and the description names the app. The grant
  ignores the arguments, so after one tap the Agent can call that tool with any
  arguments. It rests on the plugin's own read-only claim, which Code on the Go
  cannot check. AI-Core still asks on every call for the tools that are not
  read-only, for every tool of a server you add yourself, and, on an AI-Core
  older than 3.6.0, for every tool: this plugin does not check AI-Core's
  version. AI-Core ignores the tool's own `requiresApproval` and always asks at
  least once.

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
  `McpConnections` (one session per server, shared by every call and listing),
  `McpCallHandle` (one tool call's cancel: its flag and its own connection)
- `tools/` — `McpToolSource` (the `ToolSourceRegistry.ToolSource`),
  `McpToolCatalog` (what each server last advertised; a SQLite debugger entry's
  list is kept beside its endpoint in `debugger/AutomaticServers`, under the lock
  that checks the endpoint, so a listing of a replaced endpoint cannot overwrite
  the new one's), `McpToolText` (sanitising)
- `settings/` — server CRUD, per-tool toggles, the settings pane
- `errors/` — HTTP and JSON-RPC failures reduced to one translated sentence
- `security/SecureTokenStore.kt` — this plugin's Keystore alias, over the IDE's `KeystoreSecretStore`

## Known limits

Three rare session races, accepted for now:

- A Save or Clear that changes only the credentials can leave one session on a
  third-party server that needs the old token to end it; the session expires on
  the server.
- A SQLite debugger entry's health follows the entry, not the port, so after the
  app moves to a new port a result from the old one can show for up to one
  20-second probe cycle.
- Two requests that both get a 404 for the same expired session can start an
  extra handshake and leave one session to expire; tracked in ADFA-7250.

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
  **26.36**), so there is one implementation in the process rather than a copy per plugin. The alias stays per plugin: they all
  share the host's Keystore, so a shared alias would let one plugin's invalidated-key
  recovery delete another's secret. A token that can no longer be decrypted — a
  restored backup, an OEM Keystore reset — is reported as exactly that, never sent as
  an absent one, which would surface as the server refusing a token that is still
  stored and still correct; a Keystore that merely would not answer is told apart
  again, since that token is intact and the call is only worth retrying.
- `http://` is accepted only for a server on this device (`localhost`, `127.0.0.0/8`,
  `[::1]`), with or without credentials; any other host needs `https://`, since its
  traffic would cross a network in the clear. The check is `McpUrlPolicy`, built on
  the host's `ServerUrl` (plugin-api 26.42), and judges the URL as typed with no DNS lookup. Save refuses
  such a URL, and the transport refuses it again, so a server saved before the rule
  is listed with a warning and never contacted until it is switched to `https://`.
- The plugin declares the `debugger.endpoints` permission, to offer the SQLite debugger of the
  open project's app without typing a port or token. Code on the Go 26.47 and older reject a
  manifest that names a permission they do not know (`PluginSecurityManager.validatePlugin`), so this
  version needs **26.48 or later**, the first release that ships `DebuggerEndpointService`
  (`plugin.min_ide_version`); users on 26.47 or older cannot install it. 26.48 assumes the
  release after 26.47 ships it; recheck it against the real release number before merging.
- A server's token and headers go only to the URL it is saved with. A save
  writes the URL, token and headers in one step that a request's credential
  read waits for, so no request pairs the new URL with the old token, or the
  old URL with the new. The old session is taken out before that step and
  ended after it, its DELETE carrying the credentials it was opened with, so a
  server that wants its token to end a session gets it; any of its requests
  still running after the save finish with neither. Clearing the credential
  ends the session the same way. If the Keystore refuses to store a new token
  or headers while the URL changes, the old ones are forgotten with the
  change, so the new URL never gets them; the pane reports the failure and the
  credential has to be entered again. If forgetting them fails too, the URL
  stays as it was.
  Accepted as rare: when the URL is put back, a token or headers that the
  Keystore did store for the new URL in that save stay stored, and requests to
  the old URL carry them. That case needs two refused Keystore writes and one
  accepted write in the same save; the pane reports the failure, and **Clear
  stored credential** removes them.
  Limit, on purpose: a blank token field means "keep the stored token", so
  changing only the URL and leaving the token blank sends the stored token to
  the new URL. Headers behave the same only when the stored ones could not be
  read, which the pane cannot draw; rows it did draw replace the stored set,
  so none left in the pane forgets them. To send the new URL no token, use
  **Clear stored credential**.
  Also accepted: the credentials lock is one lock for every server, held across
  the Keystore writes of a save and the two Keystore decrypts of the settings
  dialog's load, so a slow Keystore stalls the credential reads of other servers
  for that long.
- Redirects are never followed automatically. A 3xx is repeated only when it
  resolves to the same origin, so the bearer token and the user's own headers
  cannot be replayed to another host in one hop.
- Error bodies stay in logcat. The transcript gets one sentence.
- The connection log masks every credential the request carried wherever the
  server echoes it, escaped or not (`McpRedactor`), and the session id a reply
  assigns wherever that reply echoes it. The body of a `202 Accepted`
  is kept only in part, cut at a fixed length; when the cut falls inside a
  credential, what was kept of it is masked too, so the log never shows the
  start of one.
- A server's JSON is parsed only when it nests arrays and objects at most 100
  levels deep, brackets inside strings not counted (`JsonDepth`): parsing
  recurses once per level, and a body of nothing but `[` would run Code on the
  Go out of stack. The scan reads strict JSON and refuses any text that is not:
  Android's org.json also reads `;` and `=` separators, comments,
  single-quoted strings and unquoted text, each a way to make it nest through
  brackets a strict scan took as text. A refused reply fails its call or
  listing with the rule it broke, "nests arrays and objects more than 100
  levels deep" or "is not strict JSON" with the character found, so the
  server's author knows which to look for; a refused body in the connection log
  is shown as it came.
- A `202 Accepted` body is read and thrown away, for at most 1 MiB of characters
  and 10 seconds; one that runs past either is abandoned and its socket
  disconnected, not reused. The worst case is the 10 seconds plus one read
  timeout, since a read already blocked ends only at its own timeout.
