# plugin-examples

Reference add-ons for [Code on the Go](https://github.com/appdevforall/CodeOnTheGo).

There are four kinds, and they are built in different ways. Read the area's own README before adding one.

| Area | What it is | Artifact | How to add one |
|---|---|---|---|
| [`plugins/`](plugins/) | Code that runs inside the IDE. A self-contained Gradle project | `.cgp` | [`plugins/README.md`](plugins/README.md) |
| [`templates/`](templates/) | A project skeleton on the New Project screen. Plain text, no build | `.cgt` | [`templates/README.md`](templates/README.md) |
| [`snippets/`](snippets/) | Reusable editor snippets. Placeholder — nothing here yet | `.cgs` | [`snippets/README.md`](snippets/README.md) |
| [`code-actions/`](code-actions/) | Editor quick-fixes. Placeholder — nothing here yet | — | [`code-actions/README.md`](code-actions/README.md) |

See the official [plugin documentation](https://www.appdevforall.org/codeonthego/help/exp-plugins-top.html) for concepts, the plugin API surface, and install workflow.

## Plugins

| Plugin                                             | Purpose                                                           |
| -------------------------------------------------- | ----------------------------------------------------------------- |
| [`plugins/AI-Agent-Gemini/`](plugins/AI-Agent-Gemini/) | Adds Google Gemini as an inference backend for AI Core. |
| [`plugins/AI-Agent-Local/`](plugins/AI-Agent-Local/) | Runs GGUF models on the device itself, with no network access. |
| [`plugins/AI-Agent-MCP/`](plugins/AI-Agent-MCP/) | Gives the AI Core agent the tools of Model Context Protocol servers. |
| [`plugins/AI-Agent-OpenAI/`](plugins/AI-Agent-OpenAI/) | Adds OpenAI-compatible servers as an inference backend for AI Core. |
| [`plugins/APK-Analyzer/`](plugins/APK-Analyzer/) | Inspects the structure of an APK file inside the editor. |
| [`plugins/Bookshelf/`](plugins/Bookshelf/) | Offline reference textbooks inside the in-app help. |
| [`plugins/Client-Time-Tracker/`](plugins/Client-Time-Tracker/) | Tracks billable time for each project and creates invoices. |
| [`plugins/AI-Code-Suggestions/`](plugins/AI-Code-Suggestions/) | Shows inline code completions as you type. |
| [`plugins/AI-Literacy-Course/`](plugins/AI-Literacy-Course/) | An offline "Introduction to AI" course with lesson videos and activities. |
| [`plugins/Code-Together/`](plugins/Code-Together/) | Pair programming between two devices on the same network. |
| [`plugins/AI-Core/`](plugins/AI-Core/) | The agent chat and the inference router every other AI addon needs. |
| [`plugins/Favorite-Snippets/`](plugins/Favorite-Snippets/) | Saves your own code snippets and inserts them in the editor. |
| [`plugins/Get-AI-Models/`](plugins/Get-AI-Models/) | Downloads small language models for on-device AI addons. |
| [`plugins/Icons-Repository/`](plugins/Icons-Repository/) | Adds vector icons to a project from inside the editor. |
| [`plugins/Jetpack-Compose-Preview/`](plugins/Jetpack-Compose-Preview/) | Renders Compose preview functions on the device. |
| [`plugins/Keystore-Generator/`](plugins/Keystore-Generator/) | Creates and manages app signing keystores on the device. |
| [`plugins/Layout-Editor/`](plugins/Layout-Editor/) | Edits Android XML layouts by dragging views. |
| [`plugins/Markdown-Previewer/`](plugins/Markdown-Previewer/) | Shows a live preview of Markdown and HTML files. |
| [`plugins/NDK-Installer/`](plugins/NDK-Installer/) | Installs the NDK and CMake, and adds a native project template. |
| [`plugins/Project-to-Template/`](plugins/Project-to-Template/) | Turns the open project into a reusable template. |
| [`plugins/Python-Tools/`](plugins/Python-Tools/) | Adds Python and Flask project templates, and runs them. |
| [`plugins/Rainbow-Brackets/`](plugins/Rainbow-Brackets/) | Colours brackets by depth so pairs are easy to see. |
| [`plugins/Random-XKCD/`](plugins/Random-XKCD/) | Shows xkcd comics in the editor's bottom sheet. |
| [`plugins/Sketch-to-UI/`](plugins/Sketch-to-UI/) | Turns a drawing or screenshot into an Android layout. |
| [`plugins/Speech-to-Text/`](plugins/Speech-to-Text/) | Dictates code and text into the editor with your voice. |
| [`plugins/Vector-Search/`](plugins/Vector-Search/) | Searches the project by meaning, not only by exact text. |
| [`plugins/Voice-Alerts/`](plugins/Voice-Alerts/) | Plays a sound when a build finishes or fails. |

> `cotg-ndk` and `pebble-custom-function-template-installer` are not listed. They are still at the repository root, beside `libs/` and `tools/`, until their own work lands.
> The `AI-*` addons above build from `plugins/` like every other example but are still held out of the published gallery; `tools/addons/skip.txt` states the reason for each.

## Templates

| Template bundle | Purpose |
| --- | --- |
| [`templates/Flutter-Templates/`](templates/Flutter-Templates/) | Five Flutter starter projects, one per state-management approach. |

A bundle holds one or more templates. It runs no code, so there is nothing to compile: the publish workflow zips the directory into a `.cgt` that Code on the Go reads directly. Start from `Flutter-Templates/`, which is the reference example.


## Building a plugin

Every plugin is a standalone Gradle project that shares two jars from this repo's root `libs/` folder.

```sh
cd plugins/Voice-Alerts
./gradlew assemblePlugin
```

The resulting `.cgp` file lands under the plugin's `build/plugin/` directory. Install it from inside Code on the Go via the Plugin Manager.

## Git hooks

This repo ships a `pre-push` nudge in `.githooks/` that reminds you to run the
plugin-review skill (`/plugin-review` in Claude Code) whenever you're pushing
changes to a plugin folder. Running the skill before opening a PR keeps peer
review focused on substance instead of issues the skill catches automatically
(resource leaks, missing manifest entries, missing in-IDE help).

Git honors only a single `core.hooksPath`, so the committed hook does nothing
until you enable it once after cloning:

```sh
./scripts/setup-hooks.sh   # runs: git config core.hooksPath .githooks
```

The hook is a **reminder only** — it never blocks a push, and it stays quiet
when your push doesn't touch any plugin folder.

## The `libs/` folder

Every plugin depends on two jars produced by the Code on the Go source tree:

- **`plugin-api.jar`** — the interface surface a plugin implements (`IPlugin`, `BuildStatusListener`, etc.). Used as `compileOnly` at build time; provided by the IDE at runtime.
- **`gradle-plugin.jar`** — the custom Gradle plugin (`com.itsaky.androidide.plugins.build`) that packages a compiled Android library into a `.cgp` file. Applied via `classpath` in each plugin's `settings.gradle.kts`.

Both jars live in `libs/` at the repo root; each plugin references them via `../../libs/*.jar`. This means a plugin folder is **not standalone in isolation** — copying just `plugins/Voice-Alerts/` elsewhere will break its build until you also bring `libs/` along. The expected workflow is: clone the whole repo, work inside one of the example folders.

## Refreshing `libs/`

Whenever Code on the Go changes the plugin API or the build plugin, the jars need to be rebuilt. Two ways to do that:

### GitHub Action (normal path)

Go to [Actions → **Update libs from CodeOnTheGo**](../../actions/workflows/update-libs.yml) and click **Run workflow**. It will clone Code on the Go at the branch or tag you specify (default: `stage`), build both jars, and commit them directly to the default branch.

### Locally

```sh
./scripts/update-libs.sh                          # builds from github.com/appdevforall/CodeOnTheGo@stage
./scripts/update-libs.sh --ref v1.2.0             # pin to a tag or branch
./scripts/update-libs.sh --local ../CodeOnTheGo   # use an existing local checkout instead of cloning
```

First local run clones Code on the Go into `.cache/CodeOnTheGo/` (gitignored); subsequent runs `git pull` in place. Review the diff in `libs/` and commit if you're happy with it.

## Adding a new plugin example

1. Copy `plugins/Random-XKCD/` to a new folder under `plugins/`. Name it in MixedCase with single hyphens between words (`My-Plugin`), ASCII letters and digits only. Every other name, filename, and URL is derived from this one — see [`docs/addon-naming-standards.md`](docs/addon-naming-standards.md).

2. Update **every** copied file that still names the template. Two values come from the folder name: the **slug** is it lowercased (`my-plugin`), and the **display name** is it with hyphens replaced by spaces (`My Plugin`).

   | File | Change |
   |---|---|
   | `settings.gradle.kts` | `rootProject.name` — Gradle's own name for the build. Nothing is derived from it; keep it in step with the slug anyway. |
   | `build.gradle.kts` | `pluginBuilder { pluginName }` → the slug; `android { namespace, applicationId }` |
   | `src/main/AndroidManifest.xml` | `plugin.id`, `plugin.name` → the display name, `plugin.main_class` |
   | `random-xkcd.html` | rename to `<slug>.html`; set `<title>` to the display name exactly, and make the `<h1>` contain it |
   | `addon.json` | `summary`, `description`, `tags`, `origin`, `license`, `author`. This text goes on the gallery card. A copied file passes every check while describing the wrong plugin, so rewrite it. |
   | `src/main/assets/icon_day.png`, `icon_night.png` | replace both; both must be present |
   | `src/main/kotlin/...` | your implementation |

3. Run the checks from the repository root before you push:

   ```sh
   uv run --directory tools/addons addons --root "$PWD" check
   ```

   `--root` must be an absolute path: `--directory` moves uv into `tools/addons`, so `--root .` would resolve there and find no plugins. This is the same check that runs on every pull request. It names the exact file and value it expects, so it is faster to run it here than to read it from a failed run.

4. Add a row to the **Examples** table above.