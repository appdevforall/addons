# Fun and Games JME

A [Code on the Go](https://github.com/appdevforall/CodeOnTheGo) template bundle that adds
**three small Android games** built with [jMonkeyEngine](https://jmonkeyengine.org/) to the New
Project screen. Each one creates a complete, playable game project that builds and runs on the
device.

| Template | Directory | Language | Engine | Game |
|---|---|---|---|---|
| Demo (jME) | `demo/` | Java | jME 3.6.1 | A 3D Earth you drag to spin, an orbiting rocket with an exhaust trail and bloom glow, a starfield; tap for points |
| Block Game (jME) | `blockgame/` | Kotlin | jME 3.9.0 | Falling-block puzzle: drag to move, drag down to drop faster, tap to rotate, long-press to hard-drop |
| Bubble Wand (jME) | `bubblewand/` | Kotlin | jME 3.9.0 | First-person shooter: catch floating balloon animals in bubbles with an on-screen D-pad and **FIRE** button |

A libGDX version of the same three games lives in
[FunandGames-libGDX](https://github.com/appdevforall/FunandGames-libGDX).

## How it works

There is no code. The bundle is a `.cgt` file: a zip of the three directories plus
`templates.json`, which Code on the Go reads directly. Install it under **Preferences →
Extensions Manager → Templates** with the **+** button, and the three templates appear in
**New Project**. Nothing to enable, no permissions.

Files ending in `.peb` are rendered by Pebble at project-creation time and lose the suffix;
everything else is copied byte for byte. Besides `${{APP_NAME}}` and `${{PACKAGE_NAME}}`, these
templates take the toolchain from the IDE: `${{AGP_VERSION}}`, `${{KOTLIN_VERSION}}`,
`${{GRADLE_VERSION}}`, `${{COMPILE_SDK}}`, `${{TARGET_SDK}}`, `${{MIN_SDK}}` and the Java
compatibility tokens. The projects follow whatever toolchain the installed Code on the Go ships.

jMonkeyEngine ships inside the bundle. `templates.json` lists each game's jME jars under
`libraries`, the build downloads them into `app/libs`, and each game's `app/build.gradle` uses
them with `implementation(files("libs/…"))`. A generated project does not fetch jME from Maven
Central.

## Controls

| Game | Controls |
|---|---|
| Demo | Drag to rotate the Earth · flick to spin with inertia · tap for +10 points |
| Block Game | Drag left/right to move · drag down to soft-drop · tap to rotate · long-press to hard-drop |
| Bubble Wand | Left half: drag to walk · right half: drag to look, tap to shoot · or use the D-pad and **FIRE** · **HIDE UI** toggles the buttons |

A hardware keyboard also works (arrow keys, space, WASD).

## Notes for template authors

- **AGP 9 built-in Kotlin:** the Kotlin templates do not apply `org.jetbrains.kotlin.android` in
  the app module (AGP 9 rejects it); `jvmTarget` is set through `kotlin { compilerOptions { } }`.
- **Pebble drops the newline right after `}}`.** A token at the end of a line needs a trailing
  space (`compileSdk = ${{COMPILE_SDK}} `), or the next line is joined onto it. Do not strip the
  trailing whitespace in these files.
- **ABIs:** builds include `armeabi-v7a` and `arm64-v8a` only. That covers every phone and tablet
  that runs Code on the Go, but not x86 emulators.

## Building

The `.cgt` is **never committed**. The publish workflow generates it. To build one locally:

```sh
./scripts/build-templates.sh Fun-and-Games-JME
unzip -l dist/fun-and-games-jme.cgt
```

See [`../README.md`](../README.md) for authoring guidance and the format reference.

## Credit

Written by **James Turner**. Converted from the standalone
[FunandGames-jme](https://github.com/appdevforall/FunandGames-jme) repository to a template
bundle in this repository. Two changes to the skeletons, which a sync from upstream must keep:
each `app/build.gradle.peb` takes jME from the jars in `app/libs` instead of from Maven Central,
and Demo's `settings.gradle.peb` does not list the jitpack.io repository (ADFA-6865).
