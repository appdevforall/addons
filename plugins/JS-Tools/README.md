# JS Tools

A JavaScript and TypeScript plugin for Code on the Go

This plugin adds JavaScript and TypeScript projects to Code on the Go, with everything they need
bundled inside it: Node.js, npm, TypeScript, and a language server. Nothing is downloaded on the
device.

## What it adds

Three project templates, each using only Node.js's built-in modules and shipping the Node.js type
declarations in `node_modules`:

- **JavaScript App** - an ES module program with a module, tests, and `jsconfig.json` type checking.
- **TypeScript App** - the same program in TypeScript with strict type checking.
- **Node.js Web Server** - a TypeScript server on `node:http` with JSON routes, tests, and graceful
  shutdown.

Five editor toolbar commands for JavaScript and TypeScript projects: **Run app**, **Run current
file**, **Run tests** (`npm test` or `node --test`), **Type check** (`tsc --noEmit`), and **Install
packages** (`npm install`, the only one that needs a network connection, to reach the npm
registry). TypeScript runs without a build step: Node.js 24 strips the types as it loads each file.

`.js`, `.mjs`, `.cjs`, `.jsx`, `.ts`, `.mts`, `.cts`, and `.tsx` files get syntax highlighting from
bundled tree-sitter grammars (JavaScript 0.23.1, TypeScript and TSX 0.23.2), and completion,
diagnostics, hover, and navigation from `typescript-language-server` on TypeScript's `tsserver`.
Both are contributed through the plugin API's `LanguageExtension`, so the plugin needs Code on the
Go 26.41 or later.

## Offline bundle

`node-bundle.lock` pins every bundled file by URL and SHA-256, one tab-separated line each:
`abi`, `kind`, `name`, `version`, `url`, `sha256`.

- **`deb`, per ABI** - Termux's `nodejs-lts` with the libraries it loads (`libc++`, `openssl`,
  `c-ares`, `libicu`, `libsqlite`, `zlib`) and `npm`, for `arm64-v8a` and `armeabi-v7a`. Code on
  the Go's own package repository has no Node.js, so these come from Termux.
- **`npm`, all ABIs** - `typescript` and `typescript-language-server`, which are plain JavaScript.
- **`types`, all ABIs** - `@types/node` and its `undici-types`, unpacked at build time into the
  templates' `node_modules`.

`downloadNodeBundle` fetches every entry at build time (resuming interrupted transfers), refuses any
file whose checksum does not match, and packs all of them into the plugin's assets. On the device
the plugin copies the archives for its ABI out of its assets, checks them against the lock again,
unpacks Node.js into its private directory, and writes small launchers for `node`, `npm`, `npx`,
`tsc`, and `typescript-language-server`. The `node` launcher puts the bundled libraries first on
`LD_LIBRARY_PATH`, so Node.js loads the ICU and OpenSSL it was built against rather than the
terminal's own. A missing archive stops the install with an error; the plugin never downloads.

### Choosing versions

`updateNodeBundleLock` rewrites the lock from Termux's package index and the npm registry:

```bash
./gradlew updateNodeBundleLock                       # Node.js LTS, newest TypeScript 6.x
./gradlew updateNodeBundleLock -PnodeRelease=current # Node.js Current instead of LTS
./gradlew updateNodeBundleLock -PtypescriptVersion=6.0.3 -PtypescriptLanguageServerVersion=6.0.1
```

It follows the Termux dependency graph for the chosen Node.js package, picks the newest
`@types/node` for that Node.js major version, and hashes the npm tarballs. Termux keeps only current
builds in its pool, so build from a fresh lock when a pinned `.deb` disappears.

### Why TypeScript 6, not TypeScript 7

TypeScript 7's native compiler is a Go binary built for Linux. Inside an Android app, its
`fswatch` package calls `fanotify_init` at startup, which the app sandbox's seccomp filter answers
by killing the process. typescript-go added an Android build (microsoft/typescript-go#4734) after
the 7.0.2 release; once `@typescript/typescript-android-arm64` is published, the bundle can switch
to it. Until then the language server is `typescript-language-server` on TypeScript 6, the last
release built in JavaScript, running on the bundled Node.js.

## In-app help

Long-pressing a JS Tools command in the editor toolbar shows its documentation, shipped with the
plugin. Tooltip tags are `<plugin.id>.<action id>` (for example `com.appdevforall.js.plugin.js.run.app`)
under the category `plugin_com.appdevforall.js.plugin`. The full page is
`src/main/assets/docs/index.html`.

## Disclaimer

Only light testing has been done; use at your own risk. Customer support cannot provide help with this plugin.
