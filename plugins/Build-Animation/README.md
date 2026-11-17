# Build Animation

A Code on the Go plugin that shows an animated icon at the end of the editor toolbar while Gradle runs, then a tick or a cross for the result. When nothing is building the icon is a greyed-out wrench.

## Behaviour

| Event                      | Icon                                  |
| -------------------------- | ------------------------------------- |
| Build or sync starts       | Wrench rocks (`avd_build_running`)    |
| Build succeeds             | Tick draws (`avd_build_succeeded`)    |
| Build fails or is cancelled | Cross draws and shakes (`avd_build_failed`) |
| 5 s after a result         | Greyed-out wrench                     |

Tap the icon for the current status.

## How it works

- One `ToolbarAction` whose `iconProvider` returns the drawable for the current state and whose `isEnabledProvider` greys it out when idle. It is never hidden: the toolbar is end-aligned, so showing or hiding an icon would shift every built-in button. Each state change calls `IdeUIService.refreshToolbarActions()`; the host starts animated drawables itself.
- `BuildAnimationState` holds the state machine with an injectable `Scheduler`, so it is unit-tested without Android.
- `order = 1000` puts the icon after every built-in button.
- Needs no permissions. Supports Code on the Go **26.29** (the first release with dynamic toolbar icons) through 26.99.

## Limits

- A project sync also reports as a build start, so it animates too.
- Quick Build is not reported to plugins yet, so it does not animate.
- The plugin API carries no build progress, so the animation shows activity, not progress.

## Replacing the artwork

The animations are vector drawables in `src/main/res/drawable/`. Keep the file names and make the running one loop (`repeatCount="infinite"`). The toolbar tints the icon, so colours in the XML are ignored.

## Building

```
../../gradlew assemblePlugin
../../gradlew testDebugUnitTest
```

Output is a `.cgp` plugin package in `build/plugin/`.
