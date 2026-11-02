package com.techwiz.myweather.ui

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import kotlin.math.roundToInt

// Free vector basemap (OpenStreetMap data, CARTO styling). Attribution is shown by MapLibre.
private const val BASE_STYLE = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"
private const val SEL_SOURCE = "selected-place-src"
private const val SEL_LAYER = "selected-place-layer"

@Composable
fun MapScreen(
    state: UiState,
    onTap: (Double, Double) -> Unit,
    onSearch: (String) -> Unit,
    onPickResult: (Place) -> Unit,
    onClearSearch: () -> Unit,
    onOpenForecast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        WeatherMap(place = state.place, onTap = onTap, modifier = Modifier.fillMaxSize())

        Column(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .align(Alignment.TopCenter),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchBar(
                searching = state.searching,
                onSearch = onSearch,
                onClear = onClearSearch,
            )
            if (state.searchResults.isNotEmpty() || state.searchError != null) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    if (state.searchError != null) {
                        Text(
                            state.searchError,
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(state.searchResults) { p ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPickResult(p) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                if (p.subtitle.isNotEmpty()) {
                                    Text(
                                        p.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        PlaceSummaryCard(
            state = state,
            onOpenForecast = onOpenForecast,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
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
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PlaceSummaryCard(state: UiState, onOpenForecast: () -> Unit, modifier: Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
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
                val now = state.forecast?.upcoming()?.firstOrNull()
                val line = when {
                    state.loading -> "Loading…"
                    state.error != null -> "Couldn't load forecast"
                    now != null -> buildString {
                        now.tempF?.let { append("${it.roundToInt()}°F") }
                        now.windMph?.let { append("  ·  wind ${it.roundToInt()} mph") }
                        now.gustMph?.let { append(" (gusts ${it.roundToInt()})") }
                    }
                    else -> state.place.subtitle
                }
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (now == null && !state.loading && state.error == null) {
                    Text(
                        "Tap the map to pick another spot",
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
private fun WeatherMap(place: Place, onTap: (Double, Double) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnTap by rememberUpdatedState(onTap)

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }

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
            m.cameraPosition = CameraPosition.Builder()
                .target(LatLng(place.lat, place.lon))
                .zoom(5.0)
                .build()
            m.addOnMapClickListener { latLng ->
                currentOnTap(latLng.latitude, latLng.longitude)
                true
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
                style = s
            }
            map = m
        }
    }

    // Move the marker and camera whenever the selected place changes.
    LaunchedEffect(place, map, style) {
        val m = map ?: return@LaunchedEffect
        style?.getSourceAs<GeoJsonSource>(SEL_SOURCE)
            ?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(place.lon, place.lat)))
        val zoom = maxOf(m.cameraPosition.zoom, 5.0)
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(place.lat, place.lon), zoom))
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
