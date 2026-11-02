# My Weather

A personal, ad-free Android weather app (Android 15 target, minSdk 26) with hobby-specific views:
kites (down to 1 m), drone/RC, hiking, biking, offroad, and everyday weather.

- **Data:** [Open-Meteo](https://open-meteo.com) (ECMWF, NOAA GFS, DWD ICON), free for non-commercial use, no API key.
- **Map:** MapLibre with a free OpenStreetMap-based basemap. Search or tap anywhere on Earth.
- **No server needed:** the app talks to the weather APIs directly.

## Build

Open in Android Studio, or let GitHub Actions build it: every push runs `.github/workflows/build.yml`
and uploads `app-debug.apk` as a build artifact you can sideload.

## Status

Phase 1: map, search, tap-for-forecast, profile switcher, kite/drone/etc. go-no-go, multi-model wind chart.
Planned: weather/radar layers, animated wind, ensemble confidence, editable kite list, area reports (PDF), offline cache.
