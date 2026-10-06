# Python Tools

A Python + Flask plugin for Code on the Go

This plugin shows how to add languages and projects to Code on the Go. It contains a version of Python and Flask.

## What it adds

Two Python project templates:

- **Python Flask App** — a Flask web app with routes, HTML templates, static files, and error handling.
- **Python Starter** — a minimal Python project with a single entry point.

It also installs Python on-device the first time it is needed, and provides built-in run, install-dependencies, and test actions for Python projects.

`.py` files get syntax highlighting from a bundled tree-sitter Python grammar, and code completion, diagnostics (pyflakes), and formatting (yapf) from `pylsp`, which runs on the device. Both are contributed through the plugin API's `LanguageExtension`, so the plugin needs Code on the Go 26.41 or later.

## Offline bundle

Python 3.12 and pip ship in Code on the Go's Termux bootstrap, so the plugin only bundles what used to need a network connection:

- **Wheelhouse** - python-lsp-server with pyflakes and yapf, Flask, gunicorn, and everything they depend on. On first use the plugin installs them with `pip install --no-index` from the bundled files. `ujson` and `markupsafe` have no Android wheels, so they ship as source and are compiled on the device by the bootstrap's `clang` (with `setuptools`, `setuptools-scm`, and `wheel` bundled for the build).
- **Project requirements** are installed automatically when a Python project opens, looking in the wheelhouse first, so the Flask template (Flask and gunicorn) needs no network. Other packages in a project's `requirements.txt` still download from PyPI.

`python-bundle.lock` pins every file by URL and SHA-256. `downloadPythonBundle` fetches them at build time (resuming interrupted transfers) and refuses any file whose checksum does not match. To update, regenerate the lock from a `pip install --dry-run --report` run on a device.

## In-app help

Long-pressing a Python command in the editor toolbar shows its documentation, shipped with the
plugin and written into the IDE's `documentation.db` at install time. The plugin binds the
long-press on its own toolbar buttons, so no host change is needed:

- **Tier 1** - one-line summary of what the command runs.
- **Tier 2** - "See more" detail: entry-point order, dependency auto-install, timeouts.
- **Tier 3** - `src/main/assets/docs/index.html`, served offline in the IDE's help viewer.

Tooltip tags are `<plugin.id>.<action id>` (for example
`com.appdevforall.python.plugin.python.run.app`) under the category
`plugin_com.appdevforall.python.plugin`, which is what the IDE derives when it resolves a tooltip
for a plugin-contributed action.

## Disclaimer

Only light testing has been done; use at your own risk. Customer support cannot provide help with this plugin.

