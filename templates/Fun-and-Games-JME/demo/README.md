# JME Android Game

![Screenshot](screenshot.png)

A simple 3D space game for Android built with [jMonkeyEngine 3.6](https://jmonkeyengine.org/).

## Features

- **Touch drag** to rotate the Earth on any axis
- **Inertia** — flick and it coasts to a stop
- **Tap** to earn points and cycle the Earth's colour
- **Orbiting rocket** — a procedural rocket ship (red nose, white body, 4 fins) circles the Earth on a tilted plane
- **Orange exhaust trail** behind the rocket (particle emitter)
- **Bloom glow** on the engine nozzle (`BloomFilter`, `GlowMode.Objects`)
- **Starfield** — 250 stars scattered on a sphere shell, batched to one draw call
- **Ambient + directional lighting** for shaded, non-harsh geometry

## Requirements

| Tool | Version |
|------|---------|
| Android Studio | Hedgehog or later |
| Android NDK | bundled via jme3-android-native |
| Min SDK | 21 (Android 5.0) |
| Target SDK | 34 |
| OpenGL ES | 2.0+ |

## Getting Started

1. Clone the repo:
   ```bash
   git clone https://github.com/yaturner/jme-android-game.git
   ```
2. Open the `jme-android-game` folder in Android Studio (**File → Open**).
3. Let Gradle sync complete — it will download jME 3.6.1 from Maven Central.
4. Connect an Android device (API 21+) or start an emulator.
5. Run **app**.

## Project Structure

```
app/src/main/
├── java/com/example/jmegame/
│   ├── MainActivity.java   # AndroidHarness entry point
│   └── CubeGame.java       # SimpleApplication — all game logic
├── AndroidManifest.xml
└── res/
    ├── mipmap-*/ic_launcher.png
    └── values/strings.xml
gradle/
└── libs.versions.toml      # jME 3.6.1-stable dependency versions
```

## Key Dependencies

```toml
jme3-core          = "3.6.1-stable"   # engine core, math, scene graph
jme3-android       = "3.6.1-stable"   # Android backend + AndroidHarness
jme3-android-native= "3.6.1-stable"   # native .so libs for OpenGL ES
jme3-effects       = "3.6.1-stable"   # BloomFilter, post-processing
```

## Controls

| Gesture | Action |
|---------|--------|
| Drag | Rotate the Earth |
| Flick | Spin with inertia |
| Tap | +10 points, cycle Earth colour |

## License

MIT
