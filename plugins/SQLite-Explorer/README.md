# SQLite Explorer

Browses and queries the SQLite databases of the open project's app while its debug build runs. Read-only.
Part of the database debugger, ADFA-7131; this plugin is ADFA-7342.

## How it works

- The app's debug build carries Code on the Go's database debugger (`cogo.sqliteDebugger=true` in the project's
  `gradle.properties`), an MCP server on `127.0.0.1` with a random port and token.
- Code on the Go stores each app's port and token (ADFA-7137), and plugins read them through
  `DebuggerEndpointService` (ADFA-7139), which needs the `debugger.endpoints` permission.
- The plugin speaks MCP over HTTP with a small client of its own (`core/McpClient.kt`, `HttpURLConnection` and
  `org.json`): a bundled OkHttp would clash with the host's copy.
- It uses the debugger's read-only tools: `list_databases`, `get_schema`, `query`, `explain_query_plan`, `get_stats`
  and `integrity_check`. `query`'s output must be ADFA-7297's (BLOBs as `{hex, bytes, truncated}`, with `column_types`
  and `storage_classes`); an older server's is refused with a message.

## Layout

| Path | What |
|---|---|
| `core/` | Pure Kotlin, JVM-tested: transport, MCP client, result parsing, SQL quoting, error messages |
| `SqliteExplorerPlugin.kt` | Menu entry, tooltips, help |
| `Connection.kt` | The chosen app and its client; dropped when the plugin is disabled |
| `ExplorerFragment.kt` | The screen, built in code |

## Building and testing

```sh
./gradlew assemblePlugin        # builds sqlite-explorer.cgp
./gradlew testDebugUnitTest     # core tests
```

## Not in scope

The debugger's other half (statement events, breakpoints) and writes (`execute`, read-write mode).
