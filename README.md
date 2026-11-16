> **[Download the latest APK](https://github.com/hikeratlas/android/releases/latest/download/hiker-atlas.apk)**
> (built from `main` on every push; [release page](https://github.com/hikeratlas/android/releases/latest)).
> Open it on your phone to install, allowing installs from your browser if asked.

## Building

You need JDK 17 or later and the Android SDK (platform 37):

```bash
./gradlew testDebugUnitTest   # HIKERATLAS_LIVE=1 also reads a live tile
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

`SCREENSHOTS=1 ./gradlew testDebugUnitTest` renders the sheets to
`app/build/screenshots/` with Robolectric, to check layouts without a device.

CI builds a debug APK for every push and PR (see the workflow run's
artifacts). Install it with `adb install app-debug.apk`, or by opening it on
the phone.

## How the map works

The basemap's PMTiles archives are zstd-compressed, and merged tiles are
several zstd frames joined together. MapLibre Native's own `pmtiles://`
support can't read them, so the app has its own reader:

- `pmtiles/Pmtiles.kt` reads PMTiles v3 archives through a `RangeSource`: HTTP
  range requests, or a file on disk for offline areas. It caches
  leaf directories, and re-reads the archive if its ETag changes (the basemap is
  rebuilt in place).
- `pmtiles/TileServer.kt` is an OkHttp interceptor on MapLibre's HTTP client.
  It answers `https://tiles.hikeratlas.invalid/<archive>/{z}/{x}/{y}` with the
  decompressed tile.
- `style/StyleRewriter.kt` points the style's `pmtiles://` sources at those
  URLs, and its contour and terrain sources at the app's own servers:
  - `contours/ContourServer.kt` makes contour tiles on the device from the
    terrain tiles (marching squares, written as vector tiles), with the
    options, layer and properties the website's maplibre-contour uses, so
    the style's contour layers work unchanged.
  - `elevation/DemServer.kt` serves terrain tiles: nl-dem's over
    Newfoundland, else Mapterhorn's, cut from an ancestor where a tile is
    missing.
- `imagery/ImageryServer.kt` serves aerial imagery as the website's
  `imagery://` does: each service only where its outline says it has
  imagery (`assets/imagery/`, copied from the website's `public/imagery/`).
