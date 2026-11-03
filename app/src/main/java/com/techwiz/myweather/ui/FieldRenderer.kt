package com.techwiz.myweather.ui

import android.graphics.Bitmap
import com.techwiz.myweather.data.Grid
import com.techwiz.myweather.domain.FieldKind
import com.techwiz.myweather.domain.FieldMath

/** Android wrapper: turns the pure [FieldMath] picture into a [Bitmap] for the map. */
object FieldRenderer {
    fun render(layer: MapLayer, grid: Grid, hour: Int, darkMap: Boolean): Bitmap? {
        val kind = when (layer) {
            MapLayer.CLOUDS -> FieldKind.CLOUDS
            MapLayer.RAIN -> FieldKind.RAIN
            MapLayer.WIND -> FieldKind.WIND
            else -> return null
        }
        val p = FieldMath.renderPixels(kind, grid, hour, darkMap) ?: return null
        val bmp = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(p.argb, 0, p.width, 0, 0, p.width, p.height)
        return bmp
    }
}
