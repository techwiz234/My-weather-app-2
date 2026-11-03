package com.techwiz.myweather.ui

import android.graphics.Bitmap
import com.techwiz.myweather.data.Grid
import com.techwiz.myweather.data.RasterFrames
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory.rasterFadeDuration
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.PropertyFactory.rasterResampling
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/**
 * Owns every weather overlay drawn on the map style: the smooth forecast picture (wind speed,
 * clouds, rain) and the radar / satellite tiles. New layers are inserted below [belowLayerId] so
 * the selected-place marker stays on top.
 */
class OverlayController(private val style: Style, private val belowLayerId: String) {

    private var kind: String = ""
    private var rasterCount = 0

    fun clear() {
        style.removeLayer(IMG_LYR)
        style.removeSource(IMG_SRC)
        for (i in 0 until rasterCount) {
            style.removeLayer("$RAS_LYR$i")
            style.removeSource("$RAS_SRC$i")
        }
        rasterCount = 0
        kind = ""
    }

    // ---- Forecast picture -----------------------------------------------------------------

    /** Places [bitmap] (rendered by [FieldRenderer]) over the area the [grid] covers. */
    fun showPicture(bitmap: Bitmap, grid: Grid, opacity: Float) {
        val quad = LatLngQuad(
            LatLng(grid.north, grid.west), LatLng(grid.north, grid.east),
            LatLng(grid.south, grid.east), LatLng(grid.south, grid.west),
        )
        val existing = style.getSourceAs<ImageSource>(IMG_SRC)
        if (kind != "picture" || existing == null) {
            clear()
            style.addSource(ImageSource(IMG_SRC, quad, bitmap))
            style.addLayerBelow(
                RasterLayer(IMG_LYR, IMG_SRC).withProperties(
                    rasterOpacity(opacity),
                    rasterFadeDuration(0f),
                    rasterResampling(Property.RASTER_RESAMPLING_LINEAR),
                ),
                belowLayerId,
            )
            kind = "picture"
        } else {
            existing.setCoordinates(quad)
            existing.setImage(bitmap)
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
                style.addSource(RasterSource("$RAS_SRC$i", tiles, frames.tileSize))
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

    private companion object {
        const val IMG_SRC = "ov-img-src"
        const val IMG_LYR = "ov-img-lyr"
        const val RAS_SRC = "ov-ras-src-"
        const val RAS_LYR = "ov-ras-lyr-"

        // Inactive frames stay barely visible so their tiles keep loading and the loop is smooth.
        const val HIDDEN = 0.01f
        const val SHOWN = 0.85f
    }
}
