# PHP Tools

A PHP plugin for Code on the Go

This plugin adds PHP projects to Code on the Go, with everything they need bundled inside it: PHP,
Composer, and a language server. Nothing is downloaded on the device.

## What it adds

Two project templates, each using only PHP's built-in functions:

- **PHP Script** - a command-line program with a class and assert-based tests.
- **PHP Web App** - a website and JSON API on PHP's built-in web server, with routes, a static
  stylesheet, and tests.

Five editor toolbar commands for PHP projects: **Run app** (the `composer.json` start script, the
built-in web server for `public/`, or the entry file), **Run current file**, **Run tests** (PHPUnit,
the `test` script, or every `tests/*Test.php` with assertions on), **Check syntax** (`php -l`), and
**Install dependencies** (`composer install`, the only one that needs a network connection, to reach
Packagist).

`.php` and `.phtml` files get syntax highlighting from a bundled tree-sitter grammar
(tree-sitter-php 0.23.12), and completion, hover, navigation, and code actions from phpactor. Both
are contributed through the plugin API's `LanguageExtension`, so the plugin needs Code on the Go
26.41 or later.

## Offline bundle

`php-bundle.lock` pins every bundled file by URL and SHA-256, one tab-separated line each: `abi`,
`kind`, `name`, `version`, `url`, `sha256`.

- **`deb`, per ABI** - Termux's `php` with the libraries it loads, for `arm64-v8a` and
  `armeabi-v7a`. Code on the Go's own package repository has no PHP, so these come from Termux.
  PHP's extensions are compiled into the binary, so there are no extension modules to load.
- **`deb`, all ABIs** - Termux's `composer`, which is a PHP archive.
- **`phar`, all ABIs** - phpactor's `phpactor.phar` from its GitHub release.

`downloadPhpBundle` fetches every entry at build time (resuming interrupted transfers), refuses any
file whose checksum does not match, and packs all of them into the plugin's assets. On the device the
plugin copies the archives for its ABI out of its assets, checks them against the lock again, unpacks
PHP into its private directory (leaving out `php-cgi`, `phpdbg`, headers, and documentation), and
writes launchers for `php`, `composer`, and `phpactor`. A missing archive stops the install with an
error; the plugin never downloads.

The `php` launcher puts the bundled libraries first on `LD_LIBRARY_PATH` and sets `PHPRC` to the
plugin's own `php.ini`. Termux's PHP has paths under `/data/data/com.termux` compiled in; without the
ini, OPcache cannot create its lock file there and `php -S` dies at startup. The ini moves OPcache's
lock file, the temporary and session directories, and the certificate bundle to paths an app can
use.

One compiled-in path cannot be moved by configuration: the shell `proc_open()` runs a string command
with, `/data/data/com.termux/files/usr/bin/sh`. Without it, `proc_open()` fails with "Exec failed",
which breaks phpactor's indexer and diagnostics (they run subprocesses through amphp/process),
Composer's subprocesses, and any project code that calls `proc_open()`. After unpacking, the plugin
rewrites that one NUL-terminated string in its copy of the `php` binary to `/system/bin/sh`, padded
with NULs to the same length, the same prefix relocation conda uses. The install fails if the string
is missing, so a PHP build that stops embedding it is noticed rather than silently left broken.

### Choosing versions

`updatePhpBundleLock` rewrites the lock from Termux's package index and phpactor's GitHub releases:

```bash
./gradlew updatePhpBundleLock                                # latest phpactor release
./gradlew updatePhpBundleLock -PphpactorVersion=2026.10.07.0 # a specific phpactor release
```

It follows the Termux dependency graph of `php` and `composer` for both ABIs, pins packages built for
any architecture once, and hashes the phar. Termux keeps only current builds in its pool, so build
from a fresh lock when a pinned `.deb` disappears.

## In-app help

Long-pressing a PHP Tools command in the editor toolbar shows its documentation, shipped with the
plugin. Tooltip tags are `<plugin.id>.<action id>` (for example `com.appdevforall.php.plugin.php.run.app`)
under the category `plugin_com.appdevforall.php.plugin`. The full page is
`src/main/assets/docs/index.html`.

## Disclaimer

Only light testing has been done; use at your own risk. Customer support cannot provide help with this plugin.
