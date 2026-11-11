# android

An Android client for [Hiker Atlas](https://www.hikeratlas.com): the same
basemap and curated hikes as the website, as a native Kotlin app.

## Status

The app matches more of the website with each version:

- **Map**: the basemap style from `public.hikeratlas.com`, rendered by
  [MapLibre Native](https://github.com/maplibre/maplibre-native), with the
  curated routes layer and "where am I?". It uses the device's own location
  provider rather than Google Play Services, so the app could go on F-Droid.
- **Search**: the website's search API, with the same result lines and recent
  picks.
- **Sheets**: tap a route, a park's name, or "hikes in view". A park lists its
  hikes by section. A hike shows its stats, an elevation chart (drag it to see
  each point on the map), campsites, via, passes and terrain, plus directions
  to the trailhead and a share link to its page on hikeratlas.com.
- **Tabs**: Map and You. Saved joins them with offline areas and tracks.
- **Layers**: aerial imagery (the website's services, clipped to where each
  has imagery), contour lines and hillshade, each switchable; and a link to
  the same view on OpenStreetMap.

- **Trails**: an OSM trail picked in search shows its ways on the map, its
  elevation (end to end, or along its longest stretch if it branches), and
  its park's curated hikes that walk it.

Still to come for parity with the website: the route planner.

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

## Roadmap

1. **Website parity** (in progress): map layers, the route planner, and OSM
   trail pages.
2. **Offline**: download an area, i.e. a bbox extract of the basemap and routes
   archives written as a local PMTiles file and served by a `FileRangeSource`,
   plus glyphs, sprites and terrain tiles.
3. **GPS track recording**: a foreground service that records while the screen
   is off, with the track on the map, stats, and GPX export.
4. **Photos**: take geotagged photos on a hike and share them back to the
   route. This needs an upload API and moderation on the server side.
5. **OSM edits**: fix trail tags and add missing features, via OSM's OAuth 2
   and changeset API.
6. **Explore and deep links**: browse by country, state and park; open
   hikeratlas.com links in the app.
