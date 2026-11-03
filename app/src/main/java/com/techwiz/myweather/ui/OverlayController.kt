package com.techwiz.myweather.ui

import com.techwiz.myweather.data.Grid
import com.techwiz.myweather.data.RasterFrames
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleBlur
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.rasterFadeDuration
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Owns every weather overlay drawn on the map style (wind arrows, cloud / rain blobs, radar and
 * satellite tiles). New layers are always inserted below [belowLayerId] so the selected-place
 * marker stays on top.
 */
class OverlayController(private val style: Style, private val belowLayerId: String) {

    private var kind: String = ""
    private var rasterCount = 0

    fun clear() {
        listOf(GRID_LYR, WIND_LINES_LYR, WIND_TIPS_LYR).forEach { style.removeLayer(it) }
        listOf(GRID_SRC, WIND_LINES_SRC, WIND_TIPS_SRC).forEach { style.removeSource(it) }
        for (i in 0 until rasterCount) {
            style.removeLayer("$RAS_LYR$i")
            style.removeSource("$RAS_SRC$i")
        }
        rasterCount = 0
        kind = ""
    }

    // ---- Forecast grid layers -----------------------------------------------------------

    fun showGrid(layer: MapLayer, grid: Grid, hour: Int) {
        if (kind != layer.name) {
            clear()
            build(layer, grid)
            kind = layer.name
        }
        val h = hour.coerceIn(0, grid.times.lastIndex.coerceAtLeast(0))
        when (layer) {
            MapLayer.CLOUDS -> source(GRID_SRC)?.setGeoJson(FeatureCollection.fromFeatures(
                grid.points.mapNotNull { p ->
                    val v = p.cloudPct.getOrNull(h) ?: return@mapNotNull null
                    point(p.lat, p.lon, v)
                },
            ))
            MapLayer.RAIN -> source(GRID_SRC)?.setGeoJson(FeatureCollection.fromFeatures(
                grid.points.mapNotNull { p ->
                    val v = p.precipIn.getOrNull(h) ?: return@mapNotNull null
                    if (v < 0.004) null else point(p.lat, p.lon, v)
                },
            ))
            MapLayer.WIND -> {
                val lines = ArrayList<Feature>()
                val tips = ArrayList<Feature>()
                val baseLen = min(grid.latStep, grid.lonStep) * 0.42
                for (p in grid.points) {
                    val speed = p.windMph.getOrNull(h) ?: continue
                    val from = p.windDirDeg.getOrNull(h) ?: continue
                    if (speed < 0.5) continue
                    // Wind direction is where it blows FROM; arrows point where it is going.
                    val heading = Math.toRadians((from + 180.0) % 360.0)
                    val len = baseLen * (0.35 + 0.65 * min(speed, 30.0) / 30.0)
                    val cosLat = cos(Math.toRadians(p.lat)).coerceAtLeast(0.1)
                    val dLat = len * cos(heading) / 2
                    val dLon = len * sin(heading) / cosLat / 2
                    val tail = Point.fromLngLat(p.lon - dLon, p.lat - dLat)
                    val head = Point.fromLngLat(p.lon + dLon, p.lat + dLat)
                    lines += Feature.fromGeometry(LineString.fromLngLats(listOf(tail, head)))
                        .also { it.addNumberProperty("v", speed) }
                    tips += Feature.fromGeometry(head).also { it.addNumberProperty("v", speed) }
                }
                source(WIND_LINES_SRC)?.setGeoJson(FeatureCollection.fromFeatures(lines))
                source(WIND_TIPS_SRC)?.setGeoJson(FeatureCollection.fromFeatures(tips))
            }
            else -> Unit
        }
    }

    private fun build(layer: MapLayer, grid: Grid) {
        // Blob radius = grid spacing in pixels at the zoom the grid was fetched, scaled with the map.
        fun scaled(px: Float): Expression = Expression.interpolate(
            Expression.exponential(2f), Expression.zoom(),
            Expression.stop(grid.refZoom, px),
            Expression.stop(grid.refZoom + 8.0, px * 256f),
        )
        when (layer) {
            MapLayer.CLOUDS -> {
                style.addSource(GeoJsonSource(GRID_SRC))
                style.addLayerBelow(
                    CircleLayer(GRID_LYR, GRID_SRC).withProperties(
                        circleRadius(scaled(grid.cellPx * 0.8f)),
                        circleBlur(1f),
                        circleColor(Expression.rgb(232, 238, 250)),
                        circleOpacity(
                            Expression.interpolate(
                                Expression.linear(), Expression.get("v"),
                                Expression.stop(0, 0.0), Expression.stop(30, 0.1),
                                Expression.stop(70, 0.45), Expression.stop(100, 0.8),
                            ),
                        ),
                    ),
                    belowLayerId,
                )
            }
            MapLayer.RAIN -> {
                style.addSource(GeoJsonSource(GRID_SRC))
                style.addLayerBelow(
                    CircleLayer(GRID_LYR, GRID_SRC).withProperties(
                        circleRadius(scaled(grid.cellPx * 0.75f)),
                        circleBlur(0.9f),
                        circleOpacity(0.8f),
                        circleColor(
                            Expression.interpolate(
                                Expression.linear(), Expression.get("v"),
                                Expression.stop(0.004, Expression.rgb(130, 205, 255)),
                                Expression.stop(0.05, Expression.rgb(60, 140, 255)),
                                Expression.stop(0.15, Expression.rgb(60, 200, 90)),
                                Expression.stop(0.3, Expression.rgb(255, 220, 60)),
                                Expression.stop(0.6, Expression.rgb(255, 80, 70)),
                            ),
                        ),
                    ),
                    belowLayerId,
                )
            }
            MapLayer.WIND -> {
                val color = Expression.interpolate(
                    Expression.linear(), Expression.get("v"),
                    Expression.stop(0, Expression.rgb(130, 205, 255)),
                    Expression.stop(10, Expression.rgb(80, 225, 150)),
                    Expression.stop(20, Expression.rgb(255, 210, 80)),
                    Expression.stop(30, Expression.rgb(255, 110, 90)),
                    Expression.stop(45, Expression.rgb(225, 70, 210)),
                )
                style.addSource(GeoJsonSource(WIND_LINES_SRC))
                style.addSource(GeoJsonSource(WIND_TIPS_SRC))
                style.addLayerBelow(
                    LineLayer(WIND_LINES_LYR, WIND_LINES_SRC).withProperties(
                        lineColor(color), lineWidth(3f), lineCap(Property.LINE_CAP_ROUND),
                    ),
                    belowLayerId,
                )
                style.addLayerBelow(
                    CircleLayer(WIND_TIPS_LYR, WIND_TIPS_SRC).withProperties(
                        circleRadius(4.5f), circleColor(color),
                    ),
                    belowLayerId,
                )
            }
            else -> Unit
        }
    }

    // ---- Radar / satellite tiles ----------------------------------------------------------

    fun showRaster(frames: RasterFrames, active: Int) {
        val key = "raster:" + frames.urls.hashCode()
        if (kind != key) {
            clear()
            frames.urls.forEachIndexed { i, url ->
                val tiles = TileSet("2.2.0", url)
                tiles.maxZoom = frames.maxZoom
                style.addSource(RasterSource("$RAS_SRC$i", tiles, 256))
                style.addLayerBelow(
                    RasterLayer("$RAS_LYR$i", "$RAS_SRC$i").withProperties(
                        rasterOpacity(HIDDEN), rasterFadeDuration(0f),
                    ),
                    belowLayerId,
                )
            }
            rasterCount = frames.urls.size
            kind = key
        }
        for (i in 0 until rasterCount) {
            style.getLayerAs<RasterLayer>("$RAS_LYR$i")
                ?.setProperties(rasterOpacity(if (i == active) SHOWN else HIDDEN))
        }
    }

    private fun source(id: String): GeoJsonSource? = style.getSourceAs<GeoJsonSource>(id)

    private fun point(lat: Double, lon: Double, v: Double): Feature =
        Feature.fromGeometry(Point.fromLngLat(lon, lat)).also { it.addNumberProperty("v", v) }

    private companion object {
        const val GRID_SRC = "ov-grid-src"
        const val GRID_LYR = "ov-grid-lyr"
        const val WIND_LINES_SRC = "ov-wind-lines-src"
        const val WIND_LINES_LYR = "ov-wind-lines-lyr"
        const val WIND_TIPS_SRC = "ov-wind-tips-src"
        const val WIND_TIPS_LYR = "ov-wind-tips-lyr"
        const val RAS_SRC = "ov-ras-src-"
        const val RAS_LYR = "ov-ras-lyr-"

        // Inactive frames stay barely visible so their tiles keep loading and the loop is smooth.
        const val HIDDEN = 0.01f
        const val SHOWN = 0.8f
    }
}
