# AI Literacy Course

A Code On The Go plugin that bundles Learn AI Anywhere's offline
**"Introduction to AI"** course and plays it full-screen, fully offline.
Twenty-six short lesson videos plus interactive activities, the NeuroPocket
machine-learning app, and teacher resources — all on-device, no internet
required.

Open it from the side menu (**AI Literacy Course**). The first launch unpacks
the bundle once into the plugin's private storage; every launch after that is
instant.

## How it works

The course ships as a ~110 MB ZIP that is **not committed to git**. The
`downloadAssets` Gradle task fetches it from Learn AI Anywhere's Google Drive at
build time, verifies a pinned MD5, and packs it into the `.cgp`. At runtime the
plugin:

1. Extracts the bundle once into `getPluginDirectory()/course` and unpacks the
   nested activity zips ([`CourseInstaller`](src/main/kotlin/org/appdevforall/ailiteracycourse/CourseInstaller.kt)).
2. Generates a video-forward navigation `index.html` over the extracted tree
   ([`CourseShell`](src/main/kotlin/org/appdevforall/ailiteracycourse/CourseShell.kt)) — the bundle ships no web shell of its own.
3. Serves it over a virtual `https://appassets.androidplatform.net/` origin via
   `WebViewAssetLoader` and loads it in a full-screen WebView
   ([`CourseFragment`](src/main/kotlin/org/appdevforall/ailiteracycourse/fragments/CourseFragment.kt)). The https origin is what makes video byte-range
   seeking and NeuroPocket's service worker work.

`CourseFragment` handles the host requirements explicitly: **Back** exits a
fullscreen video, then steps back through course history, then closes the
screen; **rotation** restores page + scroll via `WebView.saveState`; HTML5
**fullscreen video** is routed through `WebChromeClient`.

No permissions are declared — the bundle is in the `.cgp`, extraction targets
the plugin's own data dir, and everything renders offline.

## Build

```bash
../../gradlew downloadAssets      # fetches + verifies the ~110 MB course ZIP
../../gradlew assemblePlugin      # -> build/plugin/ai-literacy-course.cgp (~119 MB)
```

`scripts/update-libs.sh` runs `downloadAssets` automatically before
`assemblePlugin`. Sideload the `.cgp` via **Preferences → Plugin Manager → +**.

## Source layout

```
plugins/AI-Literacy-Course/
├── build.gradle.kts                  ← deps + downloadAssets (Drive fetch + MD5)
└── src/main/
    ├── AndroidManifest.xml
    ├── assets/
    │   ├── ai-literacy-course.zip     ← fetched at build time (gitignored)
    │   ├── docs/                      ← Tier 3 in-IDE help
    │   ├── icon_day.png / icon_night.png
    ├── kotlin/org/appdevforall/ailiteracycourse/
    │   ├── AiLiteracyCoursePlugin.kt  ← lifecycle + side-menu launcher + tooltip
    │   ├── CourseInstaller.kt         ← one-time extraction
    │   ├── CourseShell.kt             ← generates the navigation page
    │   └── fragments/CourseFragment.kt← full-screen WebView host
    └── res/
        ├── layout/fragment_course.xml
        ├── drawable/ic_course.xml
        └── values/, values-night/
```

## Course attribution + license

The course content is © **Learn AI Anywhere** (learnaianywhere.org) and is
redistributed here offline with the project's explicit encouragement (they
invite implementers to import the pack into any learning platform). The course
keeps all self-check activity scores on-device; no learner data leaves the
device.

The plugin's own source code is licensed per the surrounding `plugin-examples`
repository (see `LICENSE` at the repo root). Learn AI Anywhere's terms apply to
the course content the plugin displays.
