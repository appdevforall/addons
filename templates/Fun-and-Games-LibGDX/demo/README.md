# libGDX Android Game

![Screenshot](screenshot.png)

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

| Tool | Version |
|------|---------|
| Android Studio | Hedgehog or later |
| Native libs | unpacked from gdx-platform natives jars by `copyAndroidNatives` |
| Min SDK | 21 (Android 5.0) |
| Target SDK | 34 |
| OpenGL ES | 2.0+ |

## Getting Started

1. Clone the repo:
   ```bash
   git clone https://github.com/yaturner/jme-android-game.git
   ```
2. Open the `jme-android-game` folder in Android Studio (**File → Open**).
3. Let Gradle sync complete — it will download libGDX 1.13.1 from Maven Central.
4. Connect an Android device (API 21+) or start an emulator.
5. Run **app**.

## Project Structure

```
app/src/main/
├── java/com/example/jmegame/
│   ├── MainActivity.java   # AndroidApplication entry point
│   ├── CubeGame.java       # ApplicationAdapter — all game logic
│   └── BloomEffect.java    # glow/bloom post-process
├── AndroidManifest.xml
└── res/
    ├── mipmap-*/ic_launcher.png
    └── values/strings.xml
gradle/
└── libs.versions.toml      # libGDX 1.13.1 dependency versions
```

## Key Dependencies

```toml
gdx                 = "1.13.1"   # core: math, 3D models, 2D batching
gdx-backend-android = "1.13.1"   # Android backend + AndroidApplication
gdx-platform        = "1.13.1"   # natives-<abi> classifier jars with libgdx.so
```

## Controls

| Gesture | Action |
|---------|--------|
| Drag | Rotate the Earth |
| Flick | Spin with inertia |
| Tap | +10 points |

## License

MIT
