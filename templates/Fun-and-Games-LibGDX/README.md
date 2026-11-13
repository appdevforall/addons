# Fun and Games LibGDX

A [Code on the Go](https://github.com/appdevforall/CodeOnTheGo) template bundle that adds
**three small Android games** built with [libGDX](https://libgdx.com/) 1.13.1 to the New Project
screen. Each one creates a complete, playable game project that builds and runs on the device.
They are ports of the jMonkeyEngine games in [`../Fun-and-Games-JME`](../Fun-and-Games-JME).

| Template | Directory | Language | Game |
|---|---|---|---|
| Demo (libGDX) | `demo/` | Java | A 3D Earth you drag to spin, an orbiting rocket with an exhaust trail and bloom glow, a starfield; tap for points |
| Block Game (libGDX) | `blockgame/` | Kotlin | Falling-block puzzle: drag to move, drag down to drop faster, tap to rotate, long-press to hard-drop |
| Bubble Wand (libGDX) | `bubblewand/` | Kotlin | First-person shooter: catch floating balloon animals in bubbles with an on-screen D-pad and **FIRE** button |

## How it works

There is no code. The bundle is a `.cgt` file: a zip of the three directories plus
`templates.json`, which Code on the Go reads directly. Install it under **Preferences →
Extensions Manager → Templates** with the **+** button, and the three templates appear in
**New Project**. Nothing to enable, no permissions.

Files ending in `.peb` are rendered by Pebble at project-creation time and lose the suffix;
everything else is copied byte for byte. Besides `${{APP_NAME}}` and `${{PACKAGE_NAME}}`, these
templates take the toolchain from the IDE: `${{AGP_VERSION}}`, `${{KOTLIN_VERSION}}`,
`${{GRADLE_VERSION}}`, `${{COMPILE_SDK}}`, `${{TARGET_SDK}}`, `${{MIN_SDK}}` and the Java
compatibility tokens.

## How the build works

- **libGDX ships in the bundle.** `templates.json` lists the libGDX files under `libraries`, and
  the bundle build downloads them from Maven Central and places them in each game's `app/libs/`:
  `gdx-1.13.1.jar`, `gdx-jnigen-loader-2.5.2.jar` (which `gdx` needs),
  `gdx-backend-android-1.13.1.aar`, and the `gdx-platform` natives jars for `armeabi-v7a` and
  `arm64-v8a`. Each `app/build.gradle` uses those files, so a generated project does not fetch
  libGDX.
- **Native libraries:** `libgdx.so` sits inside the per-ABI natives jars. A `copyAndroidNatives`
  task in `app/build.gradle` unpacks them into `build/gdx-natives/<abi>/` before AGP merges
  native libraries. Builds include `armeabi-v7a` and `arm64-v8a` only. The unpacked files must not
  go into `app/libs/`: a Copy task owns its whole destination, so Gradle would count the jars
  there as its output and fail the compile for using them without depending on it.
- **AndroidX:** libGDX needs `androidx.core` at runtime. A local `.aar` does not carry its
  dependencies, so `app/build.gradle` declares `androidx.core:core` directly. Code on the Go
  resolves `androidx.*` only from its offline Maven repository, which lacks some of what newer
  `androidx.core` releases pull in, so the templates force versions the repository has:

  | Module | Forced version |
  |---|---|
  | `androidx.core:core` | 1.9.0 |
  | `androidx.collection:collection` | 1.4.2 |
  | `androidx.annotation:annotation` | 1.8.1 |
  | `androidx.lifecycle:lifecycle-runtime`, `lifecycle-common` | 2.5.1 |

  These match the versions Code on the Go's own AndroidX templates use.

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
- **Don't drop to libGDX 1.12.x.** It avoids AndroidX, but its `libgdx.so` isn't 16 KB-page
  aligned, and Google Play Protect blocked the builds as harmful. libGDX 1.13.0 also uses
  `androidx.core` at runtime without declaring it, so it crashes on launch without the pins above.
- **Changing the libGDX version** means changing three places together: the file names in each
  `app/build.gradle.peb`, the `src` URLs and `sha256` values in `templates.json`, and
  `gradle/libs.versions.toml.peb`.

## Building

The `.cgt` is **never committed**. The publish workflow generates it. To build one locally:

```sh
./scripts/build-templates.sh Fun-and-Games-LibGDX
unzip -l dist/fun-and-games-libgdx.cgt
```

See [`../README.md`](../README.md) for authoring guidance and the format reference.

## Credit

Written by **James Turner**. Converted from the standalone
[FunandGames-libGDX](https://github.com/appdevforall/FunandGames-libGDX) repository to a template
bundle in this repository. The game source is unchanged; the build files now take libGDX from
`app/libs`.
