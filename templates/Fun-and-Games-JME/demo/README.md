# JME Android Game

A simple 3D space game for Android built with [jMonkeyEngine 3.6](https://jmonkeyengine.org/).

## Features

- **Touch drag** to rotate the Earth on any axis
- **Inertia** — flick and it coasts to a stop
- **Tap** to earn points
- **Orbiting rocket** — a procedural rocket ship (red nose, white body, 4 fins) circles the Earth on a tilted plane
- **Orange exhaust trail** behind the rocket (particle emitter)
- **Bloom glow** on the engine nozzle (`BloomFilter`, `GlowMode.Objects`)
- **Starfield** — 250 stars scattered on a sphere shell, batched to one draw call
- **Ambient + directional lighting** for shaded, non-harsh geometry

## Requirements

| Item | Version |
|------|---------|
| Android NDK | not needed; the native libraries come in `jme3-android-native` |
| SDK levels | set by Code on the Go when the project is created |
| OpenGL ES | 2.0+ |

## Getting Started

1. Tap **Run** in Code on the Go.

jMonkeyEngine 3.6.1 is already in `app/libs`, so the build does not fetch it from Maven Central.

## Project Structure

```
app/
├── libs/                   # jMonkeyEngine 3.6.1 jars
└── src/main/
    ├── java/<your package>/
    │   ├── MainActivity.java   # AndroidHarness entry point
    │   └── CubeGame.java       # SimpleApplication — all game logic
    ├── assets/Textures/        # earth.png, Particle.png
    ├── AndroidManifest.xml
    └── res/
        ├── mipmap-*/ic_launcher.png
        └── values/strings.xml
```

## Key Dependencies

```
app/libs/jme3-core-3.6.1-stable.jar            # engine core, math, scene graph
app/libs/jme3-android-3.6.1-stable.jar         # Android backend + AndroidHarness
app/libs/jme3-android-native-3.6.1-stable.jar  # native .so libs for OpenGL ES
app/libs/jme3-effects-3.6.1-stable.jar         # BloomFilter, post-processing
```

Libraries resolve from Google and Maven Central only. For a library hosted on JitPack, add `maven { url = uri("https://jitpack.io") }` to the `repositories` block under `dependencyResolutionManagement` in `settings.gradle`.

jMonkeyEngine is distributed under the BSD 3-Clause license.

## Controls

| Gesture | Action |
|---------|--------|
| Drag | Rotate the Earth |
| Flick | Spin with inertia |
| Tap | +10 points |
