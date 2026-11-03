# My Weather

A personal, ad-free Android weather app (Android 15 target, minSdk 26) with hobby-specific views:
kites (down to 1 m), drone/RC, hiking, biking, offroad, and everyday weather.

- **Data:** [Open-Meteo](https://open-meteo.com) (ECMWF, NOAA GFS, DWD ICON), free for non-commercial use, no API key.
- **Map:** MapLibre with a free OpenStreetMap-based basemap. Search or tap anywhere on Earth.
- **No server needed:** the app talks to the weather APIs directly.

## Build

Open in Android Studio, or let GitHub Actions build it: every push runs `.github/workflows/build.yml`
and uploads `app-debug.apk` as a build artifact you can sideload.

## Data sources

| What | Source |
|---|---|
| Forecasts, current conditions, 15-min rain nowcast, map grids | Open-Meteo (ECMWF, GFS, ICON) |
| Official alerts (US) | api.weather.gov (NWS) |
| Radar | RainViewer (global), fallback Iowa Environmental Mesonet NEXRAD (US) |
| Satellite | NASA GIBS, GOES-East GeoColor (Americas) |
| Basemap | OpenStreetMap data, CARTO style |

## Status

Done: GPS location, map (wind / clouds / rain-forecast / radar / satellite layers), tap-for-forecast, hobby profiles with go/no-go, multi-model wind & rain, UV, official US alerts.
Planned: animated wind particles, ensemble confidence, editable kite list, area reports (PDF), offline cache, non-US alerts.
