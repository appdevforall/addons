# libGDX Android Game

A simple 3D space game for Android built with [libGDX 1.13.1](https://libgdx.com/) (ported from jMonkeyEngine 3.6).

## Features

- **Touch drag** to rotate the Earth on any axis
- **Inertia** — flick and it coasts to a stop
- **Tap** to earn points
- **Orbiting rocket** — a procedural rocket ship (red nose, white body, 4 fins) circles the Earth on a tilted plane
- **Orange exhaust trail** behind the rocket (pooled billboard decals)
- **Bloom glow** on the engine nozzle (`BloomEffect` — glow pass + separable gaussian blur, additively composited)
- **Starfield** — 250 stars scattered on a sphere shell, batched to one draw call
- **Ambient + directional lighting** for shaded, non-harsh geometry

## Requirements

| Item | Version |
|------|---------|
| Native libs | unpacked from the gdx-platform natives jars by `copyAndroidNatives` |
| SDK levels | set by Code on the Go when the project is created |
| OpenGL ES | 2.0+ |

## Getting Started

1. Tap **Run** in Code on the Go.

libGDX 1.13.1 is already in `app/libs`, so the build does not fetch it from Maven Central.

## Project Structure

```
app/
├── libs/                   # libGDX 1.13.1 jars and .aar
└── src/main/
    ├── java/<your package>/
    │   ├── MainActivity.java   # AndroidApplication entry point
    │   ├── CubeGame.java       # ApplicationAdapter — all game logic
    │   └── BloomEffect.java    # glow/bloom post-process
    ├── assets/Textures/        # earth.png, Particle.png
    ├── AndroidManifest.xml
    └── res/
        ├── mipmap-*/ic_launcher.png
        └── values/strings.xml
```

## Key Dependencies

```
app/libs/gdx-1.13.1.jar                                  # core: math, 3D models, 2D batching
app/libs/gdx-jnigen-loader-2.5.2.jar                     # loads the native library
app/libs/gdx-backend-android-1.13.1.aar                  # Android backend + AndroidApplication
app/libs/gdx-platform-1.13.1-natives-armeabi-v7a.jar     # libgdx.so for armeabi-v7a
app/libs/gdx-platform-1.13.1-natives-arm64-v8a.jar       # libgdx.so for arm64-v8a
```

`androidx.core` comes from Code on the Go's offline repository.

libGDX is distributed under the Apache License 2.0.

## Controls

| Gesture | Action |
|---------|--------|
| Drag | Rotate the Earth |
| Flick | Spin with inertia |
| Tap | +10 points |
