# Your own weather server

This runs a private copy of the open-source [Open-Meteo](https://github.com/open-meteo/open-meteo) API
on a computer at home. The Android app talks to it instead of a public API, so there are **no usage
limits** and no daily cut-offs. (The public API does have them: while building this I got
`Daily API request limit exceeded. Please try again tomorrow.`)

## What you need

- An always-on Linux computer at home (a mini PC, Raspberry Pi 5, old laptop, NAS).
- [Docker](https://docs.docker.com/engine/install/) with the Compose plugin.
- Disk space. Open-Meteo's guide measured **12 GB** for a smaller setup plus **3 GB** for regional
  models. This setup downloads more variables, so budget **30–50 GB** and check with
  `docker system df` after the first sync. To use less space, delete models or variables from
  `docker-compose.yml`.
- A good internet connection for the first download (hours, not minutes). After that it only
  fetches updates every 10 minutes.

## Set up

```bash
# 1. Copy this folder to the server, then from inside it:
docker compose up -d

# 2. Watch it download. Each 'sync-*' service logs its progress.
docker compose logs -f

# 3. When the sync logs go quiet, test it:
curl "http://localhost:8080/v1/forecast?latitude=36.06&longitude=-94.16&models=ecmwf_ifs025,gfs_seamless,icon_seamless&hourly=temperature_2m,precipitation,wind_speed_10m"
```

If the test returns numbers, the server works. Models finish at different times, so a model can
show up empty for a while. The app's **Data → Test** button reports which of ECMWF / GFS / ICON
already have data.

Open-Meteo's guide notes a self-hosted server can take a couple of seconds for a forecast while its
cache is cold. That is normal and speeds up with use.

## Connect the phone

**At home (same Wi-Fi):** in the app open **Data**, enter `SERVER-LAN-IP:8080`
(for example `192.168.1.20:8080`), then **Save** and **Test**.

**Away from home:** use [Tailscale](https://tailscale.com) (free for personal use). It makes a private,
encrypted link between your phone and the server, with no router changes.

1. Install Tailscale on the server and on the phone; sign in to the same account on both.
2. In the Tailscale app, note the server's address (looks like `100.x.y.z`, or its MagicDNS name).
3. In the weather app: **Data → Server address**: `100.x.y.z:8080` → **Save** → **Test**.

That single address works both at home and away.

> **Do not port-forward 8080 to the internet.** The server has no password and speaks plain HTTP.
> Keep it on your home network and Tailscale only. (Traffic over Tailscale is encrypted by Tailscale.)

## What it downloads

| Service | Models | Why |
|---|---|---|
| `sync-ecmwf` | `ecmwf_ifs025` | ECMWF, the strongest global model |
| `sync-gfs013`, `sync-gfs025` | `ncep_gfs013`, `ncep_gfs025` | NOAA GFS (temperature, rain, UV; storms, visibility, gusts) |
| `sync-icon` | `dwd_icon` | DWD ICON, including wind at 80/120/180 m for drones |
| `sync-hrrr` | `ncep_hrrr_conus` | NOAA HRRR, high-detail US forecast |
| `sync-hrrr15` | `ncep_hrrr_conus_15min` | 15-minute US rain, for "is it raining / about to" |
| `sync-ensembles` | `ncep_gefs025`, `ecmwf_ifs025_ensemble`, `dwd_icon_eps` | the chance-of-rain percentage |
| `sync-elevation` | `copernicus_dem90` | terrain, runs once |

Outside the US, the 15-minute HRRR data does not apply. Europe can add `dwd_icon_eu` and
`dwd_icon_d2`; see Open-Meteo's docs for their variable lists.

### NOAA's new models

NOAA is replacing NAM, HREF, SREF and HiresW with **RRFS** and **REFS**. The open-data bucket already
carries `ncep_rrfs_conus`, `ncep_rrfs_conus_15min` and `ncep_rrfs_north_america`. They are not synced
here yet because I have not been able to confirm the public API serves them correctly. Once the app
supports them they are one extra `sync-*` entry.

## Day to day

```bash
docker compose ps                 # is everything running?
docker compose logs --tail 50 sync-icon
docker compose pull && docker compose up -d   # update the software
docker compose down               # stop (data is kept in the 'data' volume)
```

## Honest notes

- I could **not run this server myself**: the sandbox I work in blocks both container registries, so
  this file is built from Open-Meteo's official documentation, with every model/variable name checked
  against their data bucket, but it has not been started end-to-end. If something fails, send me the
  output of `docker compose logs` and I will fix it.
- Licence: Open-Meteo's code is AGPL-3.0 (fine for personal use unmodified); the weather data is CC-BY-4.0,
  which requires attribution (the app credits the models).
- This server covers **forecast numbers only**. Radar, satellite, the base map and official alerts
  still come from other free services (listed in the app under **Data**).

## TrueNAS

Use `docker-compose.truenas.yml` (same services, TrueNAS-friendly defaults) on a Docker-based
TrueNAS (24.10 or newer, from memory; older k3s-based SCALE and TrueNAS CORE do not run Docker
compose files). In the web UI: **Apps → Discover Apps → Custom App → Install via YAML**, paste the file,
and save. The comments at the top of that file cover the port and storage choices.
