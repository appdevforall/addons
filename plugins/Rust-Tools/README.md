# Rust Tools

A Rust plugin for Code on the Go.

Adds Rust as a first-class project type: the toolbar swaps its Gradle actions for Rust ones whenever
a Rust project is open, and leaves every other project untouched. The Rust toolchain ships inside
the plugin, so it works with no network at all. **64-bit ARM (`arm64-v8a`) only**: the plugin needs
the 64-bit build of Code on the Go.

## What it adds

Two project templates, both standard-library only:

- **Rust Starter** - a minimal crate with a `greet` function and a unit test.
- **Rust HTTP Server** - a `std::net` server with an HTML page, a JSON route, one thread per
  connection, and read and write timeouts.

Four toolbar commands, shown only in Rust projects:

| Command | Runs | Timeout |
|---|---|---|
| Run app | `cargo run` | 30 min |
| Run current file | `rustc` on the open `.rs` file, then the binary | 30 min |
| Format code | `cargo fmt` | 5 min |
| Run tests | `cargo test` | 15 min |

## Language support

`.rs` files get tree-sitter highlighting and rust-analyzer (completion, diagnostics, go to
definition, references, and formatting). Requires Code on the Go 26.41 or later.

- **Highlighting** - [tree-sitter-rust](https://github.com/tree-sitter/tree-sitter-rust) 0.23.3,
  compiled into `libtree-sitter-rust.so` at build time. The query in `assets/treesitter/rust/` is
  upstream's, reordered for the editor's first-match-wins highlighter (doc comments before comments,
  calls and paths before the identifier conventions), with upstream's all-caps constant pattern fixed
  (it ended in a stray `'` and never matched) and capture names mapped to the editor's theme.
- **rust-analyzer** - Termux's build, started through `/system/bin/sh` so it gets the toolchain's
  `PATH` and `LD_LIBRARY_PATH` ahead of the IDE's. It checks code into its own target directory, so
  it never blocks a build you start from the toolbar.

## The toolchain is bundled, trimmed, and arm64 only

The plugin carries Termux's Rust packages for aarch64 and unpacks them into its own plugin data
directory on first activation. It installs nothing into the shared Termux prefix and needs no
network on the device. This adds ~108 MB to the `.cgp` and ~600 MB on device.

`rustc` loads LLVM as a shared library and links through clang, and Code on the Go's bootstrap has
neither, so the bundle is the whole compiler stack: `rust`, `rust-std-aarch64-linux-android`,
`rust-src`, `rust-analyzer`, `libllvm`, `clang`, `lld`, `libcompiler-rt`, `ndk-sysroot`, `libffi`,
and `libxml2`. Everything else those binaries load (`libc++_shared`, `libssl`, `libcrypto`, `libz`,
`libzstd`, `libiconv`, `libandroid-execinfo`) is already in the bootstrap.

Those packages unpack to ~1.2 GB. `downloadRustBundle` keeps only what building, running, testing,
formatting, and rust-analyzer use, as listed in `rustBundleSelection` in `build.gradle.kts`:

| Dropped | Size | Why |
|---|---|---|
| Standard library for x86_64, i686, armv7 | ~300 MB | An arm64 device only builds for aarch64 |
| `libclang.so`, clangd, clang-tidy, other clang tools, C++ dev headers | ~110 MB | The `clang` driver loads only `libclang-cpp.so` and `libLLVM.so` |
| LLVM dev headers and CMake files | ~37 MB | Only needed to build against LLVM |
| compiler-rt sanitizers and profilers | ~32 MB | Linking needs only `libclang_rt.builtins-aarch64-android.a` |
| clippy, rustc-internal libraries, docs, man pages, completions | ~40 MB | Not used to build or run programs |

The `.rmeta` files in the `rust` package stay: Termux keeps the standard library's full metadata
there, and the `.rlib`s in `rust-std` carry only a stub.

`downloadRustBundle` fetches the packages pinned in `rust-bundle.lock`, verifies each SHA-256,
unpacks them, copies the selected files into one tree, and packs it as
`toolchain/rust-aarch64.tar.xz` under `build/generated/rustBundle/`, an extra assets source, so
nothing lands in `src/` or in git. It fails the build when a selection pattern matches nothing, so a
package whose layout changes cannot ship a broken toolchain. `preBuild` depends on it; the first
build needs a network connection.

**Termux deletes old versions from its pool**, so a pinned URL eventually returns 404 and the build
fails. Run `../../gradlew updateRustBundleLock` to re-pin every package to what Termux publishes
today, then build and test on a device. Termux's index is one consistent snapshot, so `rust`,
`libllvm`, and `clang` move together.

On device, the plugin compares `toolchain/rust-aarch64.id` (a hash of the lock and the selection)
with the copy it wrote after the last install, and unpacks again whenever they differ.

## Linking and relocation

Termux builds every package for `/data/data/com.termux/files/usr`. Nothing here needs patching:

- `rustc` finds its standard library relative to its own binary, so it works from the plugin
  directory.
- `rustc` has no linker configured for `aarch64-linux-android` and would call `cc`. The plugin
  writes `bin/rust-cc`, which runs the bundled `clang` with `--sysroot` pointing at the plugin's
  toolchain directory, and sets `CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER` to it. clang finds `lld`
  next to itself.

## Environment

| Variable | Why |
|---|---|
| `PATH`, `LD_LIBRARY_PATH` | Exported inside each command, ahead of the IDE's Termux entries, so a clang or LLVM installed with `apt` in the terminal can never replace the bundled ones |
| `CARGO`, `RUSTC`, `RUSTDOC`, `RUSTFMT` | Point cargo and rust-analyzer at the bundled binaries |
| `CARGO_TARGET_DIR` | `target/build` for commands and `target/analyzer` for rust-analyzer, in the plugin data directory. Android does not let programs start from shared storage, where projects live |
| `CARGO_HOME` | The plugin data directory, so nothing touches shared Termux state |
| `TMPDIR` | The IDE's temporary directory; Android apps cannot write `/tmp` |

## Offline

Everything works offline. Adding a crate under `[dependencies]` is the one thing that needs a
network, while cargo downloads it from crates.io - which is why both bundled templates use only the
standard library.

## Build

```sh
cd plugins/Rust-Tools
../../gradlew assemblePlugin        # release .cgp -> build/plugin/rust-tools.cgp
../../gradlew assemblePluginDebug   # debug variant
../../gradlew updateRustBundleLock  # re-pin the toolchain packages
```

The build runs `tar` and `xz`, so both must be on the build machine's `PATH`.

## In-app help

Long-pressing a Rust command in the editor toolbar shows its documentation, written into the IDE's
`documentation.db` at install time. Tooltip tags are `<plugin.id>.<action id>` (for example
`com.appdevforall.rust.plugin.rust.run.app`) under the category
`plugin_com.appdevforall.rust.plugin`. Tier 3 is `src/main/assets/docs/index.html`, served offline
in the IDE's help viewer.

## Disclaimer

Only light testing has been done; use at your own risk. Customer support cannot provide help with
this plugin.
