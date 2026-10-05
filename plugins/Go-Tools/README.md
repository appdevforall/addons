# Go Tools

A Go plugin for Code on the Go.

Adds Go as a first-class project type: the toolbar swaps its Gradle actions for Go ones whenever a
Go project is open, and leaves every other project untouched. The Go toolchain ships inside the
plugin, so it works with no network at all.

## What it adds

Two project templates, both standard-library only:

- **Go Starter** - a minimal module with a `greet` function and a table-driven test.
- **Go HTTP Server** - a `net/http` server with an HTML page, a JSON route, request timeouts, and
  graceful shutdown.

Four toolbar commands, shown only in Go projects:

| Command | Runs | Timeout |
|---|---|---|
| Run app | `go build` on the root, then the binary | 30 min |
| Run current file | `go build` on the open `.go` file, then the binary | 30 min |
| Tidy modules | `go mod tidy` | 10 min |
| Run tests | `go test ./...` | 15 min |

## Language support

`.go` files get tree-sitter highlighting and `gopls` (completion, diagnostics, go to definition, references, signature help, and formatting). Requires Code on the Go 26.41 or later.

- **Highlighting** - [tree-sitter-go](https://github.com/tree-sitter/tree-sitter-go) 0.23.4, compiled into `libtree-sitter-go.so` at build time the same way as the toolchain: fetched, checksum-verified, then built by CMake for `arm64-v8a` and `armeabi-v7a`. The query in `assets/treesitter/go/` is upstream's with builtin calls ahead of plain calls and the identifier catch-alls last.
- **gopls** - Termux's `gopls` 0.23.0 build, bundled for arm64 (downloaded and checksum-verified on armv7), unpacked to the plugin's `bin/gopls` after Go is installed. It runs with the same `GOROOT`, `GOPATH`, and `GOCACHE` as the toolbar commands.

## The toolchain is bundled, and it has to be Termux's build

The plugin carries Termux's `golang` package (aarch64) as an asset and unpacks it into its own
plugin data directory on first activation. It installs nothing into the shared Termux prefix and
needs no network on the device. This adds ~38 MB to the `.cgp` and ~253 MB on device.

The 38 MB payload is **not committed**. `downloadGoToolchain` fetches it at build time into
`build/generated/toolchainAssets/`, verifies its SHA-256, and that directory is registered as an
extra assets source, so nothing lands in `src/` or in git. `preBuild` depends on the task, and the
task is `mustRunAfter(clean)` so `gradlew clean assemblePlugin` cannot delete what it just wrote.
The download is skipped on rebuilds and the first build needs a network connection.

Three findings forced this, each verified on device:

1. **`pkg install golang` cannot work.** CoGo's Termux bootstrap ships no
   `$PREFIX/etc/termux/mirrors/` directory, so `pkg` fails with
   `find: .../mirrors/asia/: No such file or directory` before it reaches the network. This affects
   every plugin that shells out to `pkg`, not just this one.
2. **`apt-get install golang` cannot work either.** CoGo pins one curated repo
   (`packages.appdevforall.org`, 347 packages). `apt-get` works fine against it, but it carries no
   `golang`.
3. **The official go.dev toolchain cannot build a CoGo project at all.** Projects live on
   `/storage/emulated/0/...`, and Go takes a POSIX file lock on `go.mod` during module load.
   Android's FUSE-backed `/sdcard` does not implement those locks, so every command dies instantly
   with `go: RLock <project>/go.mod: function not implemented`. Termux's patched build handles it.
   A stock tarball tests fine under `/data` and then fails on every real project, so this is worth
   knowing before anyone "simplifies" the install back to go.dev.

If `golang` is ever published to the ADFA mirror, all of this collapses to
`apt-get install -y golang` and the bundled asset can be dropped.

## Build and run, not `go run`

`go run` compiles your program and launches it as a *child* process, so the process the IDE tracks
is the compiler. Cancelling it kills the compiler and leaves your program running as an orphan -
exactly wrong for the HTTP server template. Building to the IDE's temporary directory and `exec`ing
the result makes the tracked process *be* your program.

Verified on device: with the server running, tapping **Cancel Run app** leaves zero `cogo-go-app`
processes and the port stops answering.

## Environment

| Variable | Why |
|---|---|
| `CGO_ENABLED=0` | The toolchain is not wired to Android's NDK layout, so its cgo path finds no usable compiler even though clang ships with the IDE. Nothing in the templates needs cgo. |
| `GOFLAGS=-buildvcs=false` | Go stamps VCS metadata and fails the build when it finds `.git` without a `git` executable - the normal state on a device, since CoGo's git is JGit. |
| `TMPDIR`, `GOTMPDIR` | Go otherwise creates its work directory under `/tmp`, which no Android app can write. Without these every build fails with `mkdir /tmp/go-build...: permission denied`. |
| `GOROOT`, `GOPATH`, `GOCACHE` | Point into the plugin's own data directory, so nothing touches shared Termux state. |

## Offline

Everything works offline. **Tidy modules** is the one command that needs a network, because it
downloads from the Go module proxy - which is why both bundled templates import only the standard
library.

## Build

```sh
cd plugins/Go-Tools
../../gradlew assemblePlugin        # release .cgp -> build/plugin/go-tools.cgp
../../gradlew assemblePluginDebug   # debug variant
```

## In-app help

Long-pressing a Go command in the editor toolbar shows its documentation, written into the IDE's
`documentation.db` at install time. Tooltip tags are `<plugin.id>.<action id>` (for example
`com.appdevforall.go.plugin.go.run.app`) under the category `plugin_com.appdevforall.go.plugin`.
Tier 3 is `src/main/assets/docs/index.html`, served offline in the IDE's help viewer.

## Disclaimer

Only light testing has been done; use at your own risk. Customer support cannot provide help with
this plugin.
