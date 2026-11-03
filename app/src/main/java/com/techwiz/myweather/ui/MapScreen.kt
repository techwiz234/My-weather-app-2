package com.techwiz.myweather.ui

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.techwiz.myweather.data.AlertsStatus
import com.techwiz.myweather.data.Place
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// Free vector basemap (OpenStreetMap data, CARTO styling). Attribution is shown by MapLibre.
private const val BASE_STYLE = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"
private const val SEL_SOURCE = "selected-place-src"
private const val SEL_LAYER = "selected-place-layer"

private val SLIDER_TIME = DateTimeFormatter.ofPattern("EEE h a", Locale.US)

class MapActions(
    val onTap: (Double, Double) -> Unit,
    val onSearch: (String) -> Unit,
    val onPickResult: (Place) -> Unit,
    val onClearSearch: () -> Unit,
    val onOpenForecast: () -> Unit,
    val onViewport: (Viewport) -> Unit,
    val onLayer: (MapLayer) -> Unit,
    val onGridHour: (Int) -> Unit,
    val onTogglePlay: () -> Unit,
    val onFrame: (Int) -> Unit,
    val onMyLocation: () -> Unit,
    val onAlertClick: () -> Unit,
)

@Composable
fun MapScreen(state: UiState, actions: MapActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        WeatherMap(state = state, actions = actions, modifier = Modifier.fillMaxSize())

        Column(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .align(Alignment.TopCenter),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AlertsBanner(state.alerts, actions.onAlertClick, showAllClear = false)
            SearchBar(state.searching, actions.onSearch, actions.onClearSearch)
            if (state.searchResults.isNotEmpty() || state.searchError != null) {
                SearchResults(state, actions.onPickResult)
            } else {
                LayerChips(state.layer, actions.onLayer)
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SmallFloatingActionButton(
                    onClick = actions.onMyLocation,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) { Text("◎", style = MaterialTheme.typography.titleLarge) }
            }
            TimeControl(state, actions)
            PlaceSummaryCard(state, actions.onOpenForecast)
        }
    }
}

@Composable
private fun SearchResults(state: UiState, onPick: (Place) -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        if (state.searchError != null) {
            Text(state.searchError, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(state.searchResults) { p ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(p) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    if (p.subtitle.isNotEmpty()) {
                        Text(p.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun LayerChips(selected: MapLayer, onLayer: (MapLayer) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(MapLayer.entries.toList()) { l ->
            FilterChip(
                selected = l == selected,
                onClick = { onLayer(l) },
                label = { Text(l.label) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}

/** Time slider for forecast layers, play/scrub for radar & satellite, status messages. */
@Composable
private fun TimeControl(state: UiState, actions: MapActions) {
    val layer = state.layer
    if (layer == MapLayer.NONE) return
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            when {
                layer.usesGrid -> {
                    val g = state.grid
                    when {
                        state.gridError != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Couldn't load map data: ${state.gridError}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { actions.onLayer(layer) }) { Text("Retry") }
                        }
                        g == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(end = 12.dp), strokeWidth = 2.dp)
                            Text("Loading forecast layer…", style = MaterialTheme.typography.bodyMedium)
                        }
                        else -> {
                            val idx = state.gridHour.coerceIn(0, g.times.lastIndex)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    SLIDER_TIME.format(g.times[idx].atZone(ZoneId.systemDefault())),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    "  ·  " + layerLegend(layer) + if (state.gridLoading) "  ·  updating…" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Slider(
                                value = idx.toFloat(),
                                onValueChange = { actions.onGridHour(it.roundToInt()) },
                                valueRange = 0f..g.times.lastIndex.toFloat(),
                            )
                            Text(
                                "Model forecast (not observed). Slide to see the next 48 hours.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                else -> {
                    val f = state.frames
                    when {
                        state.framesError != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Couldn't load ${layer.label.lowercase(Locale.US)}: ${state.framesError}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { actions.onLayer(layer) }) { Text("Retry") }
                        }
                        f == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(end = 12.dp), strokeWidth = 2.dp)
                            Text("Loading ${layer.label.lowercase(Locale.US)}…", style = MaterialTheme.typography.bodyMedium)
                        }
                        else -> {
                            val idx = state.frameIndex.coerceIn(0, f.urls.lastIndex)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (f.urls.size > 1) {
                                    TextButton(onClick = actions.onTogglePlay) { Text(if (state.playing) "Pause" else "Play") }
                                }
                                Text(f.labels[idx], style = MaterialTheme.typography.titleSmall)
                            }
                            if (f.urls.size > 1) {
                                Slider(
                                    value = idx.toFloat(),
                                    onValueChange = { actions.onFrame(it.roundToInt()) },
                                    valueRange = 0f..f.urls.lastIndex.toFloat(),
                                )
                            }
                            Text(
                                f.source,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun layerLegend(layer: MapLayer) = when (layer) {
    MapLayer.WIND -> "arrows point downwind · green ≈10 mph, yellow 20, red 30+"
    MapLayer.CLOUDS -> "cloud cover (white = overcast)"
    MapLayer.RAIN -> "rain rate: blue light → green → yellow → red heavy"
    else -> ""
}

@Composable
private fun SearchBar(searching: Boolean, onSearch: (String) -> Unit, onClear: () -> Unit) {
    var text by remember { mutableStateOf("") }
    TextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        placeholder = { Text("Search any place on Earth") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            when {
                searching -> CircularProgressIndicator(Modifier.padding(12.dp), strokeWidth = 2.dp)
                text.isNotEmpty() -> IconButton(onClick = { text = ""; onClear() }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch(text) }),
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PlaceSummaryCard(state: UiState, onOpenForecast: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(state.place.name, style = MaterialTheme.typography.titleMedium)
                val cur = state.forecast?.current
                val line = when {
                    state.locating && !state.hasGps -> "Finding your location…"
                    state.loading -> "Loading…"
                    state.error != null -> "Couldn't load forecast"
                    cur != null -> buildString {
                        cur.tempF?.let { append("${it.roundToInt()}°F") }
                        append("  ·  ")
                        append(com.techwiz.myweather.domain.weatherCodeText(cur.weatherCode))
                        cur.windMph?.let { append("  ·  ${it.roundToInt()} mph") }
                        cur.gustMph?.let { append(" (g${it.roundToInt()})") }
                    }
                    else -> state.place.subtitle
                }
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.locationDenied) {
                    Text(
                        "Location permission is off. Tap ◎ to enable, or search a place.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(onClick = onOpenForecast) { Text("Forecast") }
        }
    }
}

@Composable
private fun WeatherMap(state: UiState, actions: MapActions, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentActions by rememberUpdatedState(actions)
    val place = state.place

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var overlay by remember { mutableStateOf<OverlayController?>(null) }

    DisposableEffect(lifecycle, mapView) {
        var destroyed = false
        // Adding the observer replays lifecycle events up to the current state, so the
        // MapView is created/started even if this composable enters late.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(Bundle())
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    destroyed = true
                    mapView.onDestroy()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (!destroyed) {
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
            }
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            // Restore where the user was looking if they come back from the forecast screen.
            val cam = state.camera
            m.cameraPosition = CameraPosition.Builder()
                .target(if (cam != null) LatLng(cam.centerLat, cam.centerLon) else LatLng(place.lat, place.lon))
                .zoom(cam?.zoom ?: 6.0)
                .build()
            m.addOnMapClickListener { latLng ->
                currentActions.onTap(latLng.latitude, latLng.longitude)
                true
            }
            m.addOnCameraIdleListener {
                val b = m.projection.visibleRegion.latLngBounds
                val c = m.cameraPosition
                val target = c.target ?: return@addOnCameraIdleListener
                if (mapView.width > 0 && mapView.height > 0) {
                    currentActions.onViewport(
                        Viewport(
                            south = b.latitudeSouth, north = b.latitudeNorth,
                            west = b.longitudeWest, east = b.longitudeEast,
                            zoom = c.zoom, widthPx = mapView.width, heightPx = mapView.height,
                            centerLat = target.latitude, centerLon = target.longitude,
                        ),
                    )
                }
            }
            m.setStyle(BASE_STYLE) { s ->
                s.addSource(GeoJsonSource(SEL_SOURCE))
                s.addLayer(
                    CircleLayer(SEL_LAYER, SEL_SOURCE).withProperties(
                        circleRadius(9f),
                        circleColor("#4FC3F7"),
                        circleStrokeColor("#FFFFFF"),
                        circleStrokeWidth(2.5f),
                    ),
                )
                overlay = OverlayController(s, SEL_LAYER)
                style = s
            }
            map = m
        }
    }

    // Marker follows the selected place; the camera only moves if that place is off-screen.
    LaunchedEffect(place.lat, place.lon, map, style) {
        val m = map ?: return@LaunchedEffect
        style?.getSourceAs<GeoJsonSource>(SEL_SOURCE)
            ?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(place.lon, place.lat)))
        val target = LatLng(place.lat, place.lon)
        if (!m.projection.visibleRegion.latLngBounds.contains(target)) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(target, maxOf(m.cameraPosition.zoom, 8.0)))
        }
    }

    // Weather overlays.
    LaunchedEffect(overlay, state.layer, state.grid, state.gridHour, state.frames, state.frameIndex) {
        val o = overlay ?: return@LaunchedEffect
        when {
            state.layer.usesGrid -> {
                val g = state.grid
                if (g != null) o.showGrid(state.layer, g, state.gridHour) else o.clear()
            }
            state.layer.usesRaster -> {
                val f = state.frames
                if (f != null) o.showRaster(f, state.frameIndex) else o.clear()
            }
            else -> o.clear()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

internal fun AlertsStatus.activeAlerts() = (this as? AlertsStatus.Ok)?.alerts.orEmpty()
