# android

An Android client for [Hiker Atlas](https://www.hikeratlas.com): the same
basemap and curated hikes as the website, as a native Kotlin app.

## Status

Version 0.1 shows the map:

- The basemap style from `public.hikeratlas.com`, rendered by
  [MapLibre Native](https://github.com/maplibre/maplibre-native).
- The curated routes layer. Tap a route to see its type, distance and climb.
- "Where am I?" with the device's own location provider (no Google Play
  Services, so the app could go on F-Droid too).
- It reopens where you left the map.

## How the map works

The basemap's PMTiles archives are zstd-compressed, and merged tiles are
several zstd frames joined together. MapLibre Native's own `pmtiles://`
support can't read them, so the app has its own reader:

- `pmtiles/Pmtiles.kt` reads PMTiles v3 archives through a `RangeSource`: HTTP
  range requests now, and a file on disk for offline areas later. It caches
  leaf directories, and re-reads the archive if its ETag changes (the basemap is
  rebuilt in place).
- `pmtiles/TileServer.kt` is an OkHttp interceptor on MapLibre's HTTP client.
  It answers `https://tiles.hikeratlas.invalid/<archive>/{z}/{x}/{y}` with the
  decompressed tile.
- `style/StyleRewriter.kt` points the style's `pmtiles://` sources at those
  URLs. It also drops sources that only the website's JavaScript provides:
  contours come from maplibre-contour's `dem-contour://`.

## Building

You need JDK 17 or later and the Android SDK (platform 37):

```bash
./gradlew testDebugUnitTest   # HIKERATLAS_LIVE=1 also reads a live tile
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

CI builds a debug APK for every push and PR (see the workflow run's
artifacts). Install it with `adb install app-debug.apk`, or by opening it on
the phone.

## Roadmap

1. **Find hikes**: search parks and hikes, using the website's search index,
   and park and hike pages built from routes' per-park JSON, with elevation
   profiles.
2. **Offline**: download an area, i.e. a bbox extract of the basemap and routes
   archives written as a local PMTiles file and served by a `FileRangeSource`,
   plus glyphs, sprites and terrain tiles. Also cache the style.
3. **Contours**: generate contour lines on the device from the Mapterhorn DEM,
   as maplibre-contour does on the website.
4. **GPS track recording**: a foreground service that records while the screen
   is off, with the track on the map, stats, and GPX export.
5. **Photos**: take geotagged photos on a hike and share them back to the
   route. This needs an upload API and moderation on the server side.
6. **OSM edits**: fix trail tags and add missing features, via OSM's OAuth 2
   and changeset API.
