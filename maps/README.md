# Maps: Offline OpenStreetMap for Code on the Go

`Maps` scaffolds an OpenStreetMap app in minutes. It downloads a map region and wires it into a
MapLibre renderer, so the generated app shows a real, pannable map with no network connection —
built for offline-first, low-bandwidth use.

You start by creating a project from the **Offline OSM Map** template in the New Project wizard
— a basic MapLibre map app. The plugin then adds a **Maps** tab to the editor bottom sheet,
where you download a map region and bundle it into that app.

## Features

- **Start from a template**: create an **Offline OSM Map** project (Kotlin or Java) — a basic
  MapLibre map app, ready to drop a region into.
- **Download a region**: from the **Maps** tab, draw a bounding box on a world map and the
  plugin slices out just that region's OpenStreetMap vector tiles (PMTiles) from an
  Internet-in-a-Box source — with a live size estimate and an automatic zoom cap to keep
  downloads small.
- **Bundle it into your app**: apply a downloaded region to your **Offline OSM Map** project and
  its tiles are copied into the app's assets, ready to render offline.
- **Builds and runs offline**: the generated app renders tiles — place and street-name labels
  included — through MapLibre and an in-process loopback HTTP server, with no network calls. It
  even builds on-device without internet, since MapLibre is vendored.

## Architecture

```
maps/
├── build.gradle.kts            # Build configuration (plugin API via ../libs)
├── settings.gradle.kts         # Buildscript classpath (shared ../libs)
├── maps.html                   # Full plugin documentation
├── src/main/
│   ├── AndroidManifest.xml     # Plugin metadata, permissions, icons
│   ├── kotlin/org/appdevforall/maps/
│   │   ├── MapsPlugin.kt       # Main plugin class (registers the tab + template)
│   │   ├── domain/             # Pure types: bbox, zoom cap, region models
│   │   ├── data/               # Region cache, downloader, installer, stores
│   │   ├── slicer/             # PMTiles region slicer (Hilbert range decomposition)
│   │   ├── templates/          # Project template + MapLibre app emitter
│   │   ├── ui/                 # Maps tab, bbox picker, download wizard
│   │   └── util/               # Atomic file I/O, byte-size formatting
│   └── assets/
│       ├── templates/region-map/      # The emitted MapLibre app (+ offline Maven repo)
│       ├── docs/osm-tutorial.html     # In-IDE OSM + MapLibre tutorial
│       ├── maps/natural-earth-*.pmtiles  # Bundled world basemap
│       ├── fonts/                     # Noto Sans label glyphs (OFL)
│       └── icon_day.png / icon_night.png
└── README.md
```

### Plugin metadata (AndroidManifest.xml)
- **plugin.id**: `org.appdevforall.maps`
- **plugin.name**: `Maps`
- **plugin.author**: App Dev For All
- **plugin.main_class**: `org.appdevforall.maps.MapsPlugin`
- **plugin.permissions**: `filesystem.read`, `filesystem.write`, `network.access`, `native.code`

## Building the plugin

```bash
./gradlew assemblePlugin        # release -> build/plugin/maps-plugin.cgp
./gradlew assemblePluginDebug   # debug
```

### Installation
1. Import the `.cgp` through Code on the Go's Plugin Manager.
2. Open the **Maps** tab in the editor bottom sheet, or create a new project from the
   **Offline OSM Map** template.

## Usage

1. **Create a new project** from the **Offline OSM Map** template (New Project wizard) — this
   scaffolds a basic MapLibre map app (Kotlin or Java).
2. Open the **Maps** tab in the editor bottom sheet and tap **Download new region**: draw a
   bounding box, name it, and **Save**.
3. **Apply** the downloaded region — its tiles are bundled into your app's assets.
4. Build and run. The app renders your region offline.

## Dependencies
- `plugin-api`: Code on the Go Plugin API (`compileOnly`, shared `../libs/plugin-api.jar`)
- `org.maplibre.gl:android-sdk-opengl`: offline vector map rendering
- `com.github.davidmoten:hilbert-curve`: PMTiles tile-range decomposition
- `okhttp`, `androidx.*`, `material`, `kotlinx-coroutines`: networking and UI

### Requirements
- Android API 28+ (Android 9)
- Minimum IDE version: 1.0.0
- Network once, to download a region's tiles (the generated app then runs and builds offline)

## License

Provided as-is for educational and development purposes. OpenStreetMap data is © OpenStreetMap
contributors (ODbL); bundled fonts are Noto Sans (OFL). See
`src/main/assets/THIRD_PARTY_LICENSES.txt` for full attribution.
