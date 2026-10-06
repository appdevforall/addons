# JVM Tools

Runs Java and Kotlin programs in Code on the Go, the way a desktop IDE does: choose the main class,
give it program arguments, and tap Run.

## What it adds

In a JVM project, a folder of `.java` and `.kt` files with no Gradle build, the editor toolbar gets:

- **Run** - compiles every Java and Kotlin file in the project on the device and runs the chosen main
  class. Compiler errors and the program's output stream into Build Output, and the button becomes
  **Cancel Run** while the program runs.
- **Run configuration** - lists every class with a `main` method and takes the program arguments.
  The choice is saved for each project.

The Gradle actions (Quick Run, Quick Build, Sync, Debug, Run Tasks, Launch App) are hidden in a JVM
project. Android projects are left alone.

The **Java/Kotlin Program** template on the New Project screen creates a two-class program in
`src/`, in the language you choose.

## How it runs code

Nothing is downloaded. Java is compiled with the JDK that ships with Code on the Go, and Kotlin with
the `kotlin-compiler-embeddable` already in the IDE's local Maven repository. Classes go to a folder
under `$PREFIX/tmp/jvm-tools`, not into the project.

## Limits

- Programs cannot read keyboard input; pass values as program arguments.
- Only the Java class library and the Kotlin standard library are on the classpath.
- Gradle projects are not handled.

## Build

```sh
cd plugins/JVM-Tools
../../gradlew assemblePlugin        # build/plugin/jvm-tools.cgp
../../gradlew assemblePluginDebug   # build/plugin/jvm-tools-debug.cgp
../../gradlew testDebugUnitTest
```

Supports Code on the Go 26.37 through 26.99.
