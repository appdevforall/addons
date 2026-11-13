# Native Logging Demos

A [Code on the Go](https://github.com/appdevforall/CodeOnTheGo) template bundle that adds two
native projects to the New Project screen. Each shows how to write to the IDE's **App Logs**
window from native code.

| Template | Directory | Native code | Shows |
|---|---|---|---|
| Native Log Demo (C) | `NativeLogDemoC/` | C11, `native-log.c` | `LOGx` macros over `__android_log_print`, `__android_log_write`, a `__android_log_vprint` wrapper, a checked printf-style helper that adds `__func__`/`__LINE__`, `errno` reporting, a `pthread` worker |
| Native Log Demo (C++) | `NativeLogDemoCpp/` | C++17, `native-log.cpp` | A type-safe `operator<<` logger, an RAII scope timer, structured bindings over a `std::map`, a custom type with a stream operator, an exception's `what()`, a `std::thread` worker |

Both offer Kotlin or Java for `MainActivity`, which has one button that calls the native
`runDemo()`. Every line is tagged `NativeDemoC` or `NativeDemoCpp`.

## Requirements

- **The NDK-Installer plugin.** It installs the NDK and CMake that `externalNativeBuild`
  needs. Without it the build fails at the CMake step. `app/build.gradle.kts` pins
  `ndkVersion` to the NDK that plugin installs.
- **A debug build.** Code on the Go injects LogSender, which forwards the app's log to App
  Logs, into debuggable variants only.
- **The project open in the editor**, with *Developer Options -> Enable LogSender* on (the
  default).

## How native logs reach App Logs

LogSender runs inside the app and streams the app's own `logcat` output to the IDE. Anything
written through liblog (`<android/log.h>`) reaches it. `printf`, `std::cout` and other writes to
stdout or stderr do not, because Android discards an app's standard output.

The demos skip `ANDROID_LOG_FATAL`. App Logs does not recognise the `F` level and shows such a
line as raw text.

## Design notes

- **`RegisterNatives` in `JNI_OnLoad`, not `Java_<package>_...` names.** A package containing
  `_` needs JNI name mangling (`_1`) that a template cannot apply. Registering by class path
  works for every package name, and a mismatch fails when the library loads rather than at
  the first call.
- **`arm64-v8a` only**, because that is what the NDK-Installer plugin's toolchain targets.
- **The skeleton is the built-in Empty Activity** from `core.cgt`, plus the
  `externalNativeBuild` block and `src/main/cpp/`.

## Build and test

See [`../README.md`](../README.md), sections 10 to 12. In short:

```sh
uv run --directory tools/addons addons --root "$PWD" check
./scripts/build-templates.sh Native-Logging-Demos
```
