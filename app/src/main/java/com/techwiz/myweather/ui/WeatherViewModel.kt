package com.techwiz.myweather.ui

import android.app.Application
import android.content.Context
import android.location.Geocoder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.techwiz.myweather.data.AlertsRepository
import com.techwiz.myweather.data.AlertsStatus
import com.techwiz.myweather.data.Forecast
import com.techwiz.myweather.data.Grid
import com.techwiz.myweather.data.GridRepository
import com.techwiz.myweather.data.Place
import com.techwiz.myweather.data.RadarRepository
import com.techwiz.myweather.data.RasterFrames
import com.techwiz.myweather.data.ServerTest
import com.techwiz.myweather.data.WeatherServer
import com.techwiz.myweather.data.WeatherRepository
import com.techwiz.myweather.domain.Profile
import com.techwiz.myweather.domain.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale
import kotlin.math.abs

val DEFAULT_PLACE = Place("Fayetteville", 36.0626, -94.1574, "Arkansas, United States")

enum class MapLayer(val label: String) {
    NONE("Map"),
    WIND("Wind"),
    CLOUDS("Clouds"),
    RAIN("Rain forecast"),
    RADAR("Radar"),
    SATELLITE("Satellite"),
}

val MapLayer.usesGrid get() = this == MapLayer.WIND || this == MapLayer.CLOUDS || this == MapLayer.RAIN
val MapLayer.usesRaster get() = this == MapLayer.RADAR || this == MapLayer.SATELLITE

/** What part of the world the map is showing. */
data class Viewport(
    val south: Double, val north: Double, val west: Double, val east: Double,
    val zoom: Double, val widthPx: Int, val heightPx: Int,
    val centerLat: Double, val centerLon: Double,
)

data class UiState(
    val place: Place = DEFAULT_PLACE,
    val profile: Profile = Profile.KITE,
    val settings: Settings = Settings(),
    val forecast: Forecast? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val lastUpdated: Instant? = null,

    val searchResults: List<Place> = emptyList(),
    val searching: Boolean = false,
    val searchError: String? = null,

    val locating: Boolean = true,
    val locationDenied: Boolean = false,
    val hasGps: Boolean = false,

    val alerts: AlertsStatus = AlertsStatus.Unknown,

    // Where weather data comes from
    val serverUrl: String = "",
    val useFallback: Boolean = true,
    val testing: Boolean = false,
    val serverTest: ServerTest? = null,

    // Map
    val camera: Viewport? = null,
    val layer: MapLayer = MapLayer.NONE,
    val grid: Grid? = null,
    val gridLoading: Boolean = false,
    val gridError: String? = null,
    val gridHour: Int = 0,
    val frames: RasterFrames? = null,
    val framesLoading: Boolean = false,
    val framesError: String? = null,
    val frameIndex: Int = 0,
    val playing: Boolean = true,
)

class WeatherViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = WeatherRepository()
    private val alertsRepo = AlertsRepository()
    private val gridRepo = GridRepository()
    private val radarRepo = RadarRepository()
    private val prefs = app.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var alertsJob: Job? = null
    private var gridJob: Job? = null
    private var framesJob: Job? = null
    private var animJob: Job? = null
    private var viewport: Viewport? = null

    init {
        loadSaved()
        refresh()
        // Keep alerts and "right now" conditions fresh while the app is open.
        viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                delay(60_000)
                ticks++
                refreshAlerts()
                if (ticks % 5 == 0) refresh(quiet = true)
            }
        }
    }

    // ---- Persistence ------------------------------------------------------------------------

    private fun loadSaved() {
        val lat = prefs.getFloat("lat", Float.NaN)
        val lon = prefs.getFloat("lon", Float.NaN)
        val profile = runCatching { Profile.valueOf(prefs.getString("profile", "") ?: "") }.getOrNull()
        _state.update {
            it.copy(
                place = if (!lat.isNaN() && !lon.isNaN())
                    Place(prefs.getString("name", "Saved place") ?: "Saved place", lat.toDouble(), lon.toDouble(), prefs.getString("sub", "") ?: "")
                else it.place,
                profile = profile ?: it.profile,
                serverUrl = prefs.getString("server_url", "") ?: "",
                useFallback = prefs.getBoolean("use_fallback", true),
            )
        }
    }

    private fun savePlace(p: Place) {
        prefs.edit().putFloat("lat", p.lat.toFloat()).putFloat("lon", p.lon.toFloat())
            .putString("name", p.name).putString("sub", p.subtitle).apply()
    }

    // ---- Data source ------------------------------------------------------------------------

    /** Home server first (if set), then optionally the public API as a safety net. */
    private fun servers(): List<WeatherServer> {
        val st = _state.value
        val url = normalizeServerUrl(st.serverUrl)
        return when {
            url.isEmpty() -> listOf(WeatherServer.PUBLIC)
            st.useFallback -> listOf(
                WeatherServer("Your server", url),
                WeatherServer("Public Open-Meteo (fallback; has daily limits)", WeatherServer.PUBLIC_URL),
            )
            else -> listOf(WeatherServer("Your server", url))
        }
    }

    fun setServerUrl(raw: String) {
        prefs.edit().putString("server_url", raw.trim()).apply()
        _state.update { it.copy(serverUrl = raw.trim(), serverTest = null, grid = null) }
        refresh()
    }

    fun setUseFallback(enabled: Boolean) {
        prefs.edit().putBoolean("use_fallback", enabled).apply()
        _state.update { it.copy(useFallback = enabled) }
    }

    fun testServer(raw: String) {
        viewModelScope.launch {
            _state.update { it.copy(testing = true, serverTest = null) }
            val base = normalizeServerUrl(raw).ifEmpty { WeatherServer.PUBLIC_URL }
            val result = repo.test(base)
            _state.update { it.copy(testing = false, serverTest = result) }
        }
    }

    // ---- Forecast ---------------------------------------------------------------------------

    /** [quiet] keeps the current forecast on screen while a fresh one loads. */
    fun refresh(quiet: Boolean = false) {
        val place = _state.value.place
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (!quiet) _state.update { it.copy(loading = true, error = null) }
            try {
                val fc = repo.forecast(place, servers())
                _state.update { it.copy(forecast = fc, loading = false, error = null, lastUpdated = Instant.now()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    // A failed background refresh must not wipe good data off the screen.
                    if (quiet && it.forecast != null) it
                    else it.copy(loading = false, error = e.message ?: "Couldn't load the forecast")
                }
            }
        }
        refreshAlerts()
    }

    fun refreshAlerts() {
        val p = _state.value.place
        alertsJob?.cancel()
        alertsJob = viewModelScope.launch {
            val status = alertsRepo.active(p.lat, p.lon)
            _state.update { it.copy(alerts = status) }
        }
    }

    fun selectPlace(place: Place) {
        savePlace(place)
        _state.update {
            it.copy(
                place = place, forecast = null, alerts = AlertsStatus.Unknown,
                searchResults = emptyList(), searchError = null,
            )
        }
        refresh()
    }

    /** Called when the user taps the map. The place is named in the background. */
    fun selectCoordinates(lat: Double, lon: Double) {
        selectPlace(Place(formatCoordinates(lat, lon), lat, lon))
        nameInBackground(lat, lon)
    }

    // ---- Location ---------------------------------------------------------------------------

    fun onLocationPermissionResult(granted: Boolean) {
        _state.update { it.copy(locationDenied = !granted, locating = granted) }
    }

    fun onLocationFound(lat: Double, lon: Double) {
        _state.update { it.copy(locating = false, hasGps = true, locationDenied = false) }
        selectPlace(Place("My location", lat, lon))
        nameInBackground(lat, lon)
    }

    fun onLocationUnavailable() = _state.update { it.copy(locating = false) }

    private fun nameInBackground(lat: Double, lon: Double) {
        viewModelScope.launch {
            val named = reverseGeocode(lat, lon) ?: return@launch
            // Only apply if the user hasn't moved on to a different place meanwhile.
            val cur = _state.value.place
            if (abs(cur.lat - lat) < 1e-6 && abs(cur.lon - lon) < 1e-6) {
                val p = named.copy(lat = lat, lon = lon)
                savePlace(p)
                _state.update { s -> s.copy(place = p, forecast = s.forecast?.copy(place = p)) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun reverseGeocode(lat: Double, lon: Double): Place? = withContext(Dispatchers.IO) {
        try {
            val app = getApplication<Application>()
            if (!Geocoder.isPresent()) return@withContext null
            val a = Geocoder(app, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
                ?: return@withContext null
            val name = a.locality ?: a.subAdminArea ?: a.adminArea ?: return@withContext null
            Place(name, lat, lon, listOfNotNull(a.adminArea?.takeIf { it != name }, a.countryName).joinToString(", "))
        } catch (e: Exception) {
            null
        }
    }

    // ---- Profile / settings -----------------------------------------------------------------

    fun setProfile(profile: Profile) {
        prefs.edit().putString("profile", profile.name).apply()
        _state.update { it.copy(profile = profile) }
    }

    fun setGroundFactor(value: Double) =
        _state.update { it.copy(settings = it.settings.copy(groundFactor = value)) }

    // ---- Search -----------------------------------------------------------------------------

    fun search(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        viewModelScope.launch {
            _state.update { it.copy(searching = true, searchError = null) }
            try {
                val results = deviceSearch(q).ifEmpty { repo.search(q) }
                _state.update {
                    it.copy(
                        searching = false,
                        searchResults = results,
                        searchError = if (results.isEmpty()) "No places found" else null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, searchError = e.message ?: "Search failed") }
            }
        }
    }

    /** Place search through Android's built-in geocoder: no key and no quota of ours. */
    @Suppress("DEPRECATION")
    private suspend fun deviceSearch(query: String): List<Place> = withContext(Dispatchers.IO) {
        try {
            if (!Geocoder.isPresent()) return@withContext emptyList()
            Geocoder(getApplication<Application>(), Locale.getDefault())
                .getFromLocationName(query, 8).orEmpty().mapNotNull { a ->
                    if (!a.hasLatitude() || !a.hasLongitude()) return@mapNotNull null
                    val name = a.featureName ?: a.locality ?: return@mapNotNull null
                    val sub = listOfNotNull(a.locality?.takeIf { it != name }, a.adminArea, a.countryName).joinToString(", ")
                    Place(name, a.latitude, a.longitude, sub)
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearSearch() = _state.update { it.copy(searchResults = emptyList(), searchError = null) }

    // ---- Map layers -------------------------------------------------------------------------

    fun setLayer(layer: MapLayer) {
        _state.update { it.copy(layer = layer, gridError = null, framesError = null) }
        animJob?.cancel()
        when {
            layer.usesGrid -> ensureGrid(force = false)
            layer == MapLayer.RADAR -> loadFrames { radarRepo.radar() }
            layer == MapLayer.SATELLITE -> loadFrames { radarRepo.satellite() }
            else -> Unit
        }
    }

    private fun loadFrames(source: suspend () -> RasterFrames) {
        framesJob?.cancel()
        framesJob = viewModelScope.launch {
            _state.update { it.copy(framesLoading = true, framesError = null, frames = null) }
            try {
                val f = source()
                _state.update { it.copy(frames = f, framesLoading = false, frameIndex = f.nowIndex, playing = f.urls.size > 1) }
                startAnimation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(framesLoading = false, framesError = e.message ?: "Couldn't load imagery") }
            }
        }
    }

    private fun startAnimation() {
        animJob?.cancel()
        animJob = viewModelScope.launch {
            while (isActive) {
                delay(700)
                val s = _state.value
                val n = s.frames?.urls?.size ?: 0
                if (s.playing && n > 1) {
                    _state.update { it.copy(frameIndex = (it.frameIndex + 1) % n) }
                }
            }
        }
    }

    fun togglePlaying() = _state.update { it.copy(playing = !it.playing) }

    fun setFrame(i: Int) = _state.update { it.copy(frameIndex = i, playing = false) }

    fun setGridHour(i: Int) = _state.update { it.copy(gridHour = i) }

    /** Called by the map whenever it stops moving. */
    fun onViewport(v: Viewport) {
        viewport = v
        _state.update { it.copy(camera = v) }
        if (_state.value.layer.usesGrid) ensureGrid(force = false)
    }

    private fun ensureGrid(force: Boolean) {
        val v = viewport ?: return
        val g = _state.value.grid
        val covered = g != null &&
            v.south >= g.south && v.north <= g.north && v.west >= g.west && v.east <= g.east
        val spanRatio = if (g == null) 1.0 else (v.north - v.south) / (g.north - g.south)
        if (!force && covered && spanRatio > 0.45) return

        // Fetch a bit beyond the screen so small pans don't trigger another request.
        val padLat = (v.north - v.south) * 0.15
        val padLon = (v.east - v.west) * 0.15
        gridJob?.cancel()
        gridJob = viewModelScope.launch {
            delay(500) // debounce while the user is still moving
            _state.update { it.copy(gridLoading = true, gridError = null) }
            try {
                // Aim for ~60 points, laid out to match the screen's shape so spacing is even.
                val aspect = v.widthPx.toDouble() / v.heightPx.coerceAtLeast(1)
                val cols = Math.round(Math.sqrt(60 * aspect)).toInt().coerceIn(4, 12)
                val rows = Math.round(60.0 / cols).toInt().coerceIn(4, 12)
                val fetched = gridRepo.fetch(
                    v.south - padLat, v.north + padLat, v.west - padLon, v.east + padLon, cols, rows,
                    servers = servers(),
                )
                val cellPx = minOf(
                    v.widthPx * 1.3f / cols,
                    v.heightPx * 1.3f / rows,
                )
                val grid = fetched.copy(refZoom = v.zoom, cellPx = cellPx)
                _state.update {
                    it.copy(grid = grid, gridLoading = false, gridHour = if (it.grid == null) grid.nowIndex() else it.gridHour.coerceIn(0, grid.times.lastIndex))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(gridLoading = false, gridError = e.message ?: "Couldn't load map data") }
            }
        }
    }
}

fun formatCoordinates(lat: Double, lon: Double): String {
    val ns = if (lat >= 0) "N" else "S"
    val ew = if (lon >= 0) "E" else "W"
    return "%.2f°%s, %.2f°%s".format(abs(lat), ns, abs(lon), ew)
}

/** "192.168.1.20:8080" -> "http://192.168.1.20:8080"; blank stays blank. */
fun normalizeServerUrl(raw: String): String {
    val t = raw.trim().trimEnd('/')
    if (t.isEmpty()) return ""
    return if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
}
