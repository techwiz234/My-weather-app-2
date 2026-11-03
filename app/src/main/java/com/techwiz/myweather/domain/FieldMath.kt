package com.techwiz.myweather.domain

import com.techwiz.myweather.data.Grid
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.tan

/** One scalar field on a [Grid] (for one hour) that can be sampled at any latitude/longitude. */
class GridField(
    private val grid: Grid,
    private val values: FloatArray,
    /** Rain and cloud amounts can't be negative; smooth interpolation may overshoot, so clamp. */
    private val nonNegative: Boolean = false,
) {
    private val lat0 = grid.south + 0.5 * grid.latStep
    private val lon0 = grid.west + 0.5 * grid.lonStep

    private fun at(r: Int, c: Int): Float =
        values[r.coerceIn(0, grid.rows - 1) * grid.cols + c.coerceIn(0, grid.cols - 1)]

    /** Catmull-Rom spline through four neighbouring samples; t in [0, 1] between p1 and p2. */
    private fun cubic(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float =
        0.5f * (2f * p1 + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t * t +
            (-p0 + 3f * p1 - 3f * p2 + p3) * t * t * t)

    /** Bicubic interpolation (smooth, no visible grid), clamped at the grid's edge. */
    fun sample(lat: Double, lon: Double): Float {
        val fr = ((lat - lat0) / grid.latStep).coerceIn(0.0, (grid.rows - 1).toDouble())
        val fc = ((lon - lon0) / grid.lonStep).coerceIn(0.0, (grid.cols - 1).toDouble())
        val r1 = floor(fr).toInt()
        val c1 = floor(fc).toInt()
        val tr = (fr - r1).toFloat()
        val tc = (fc - c1).toFloat()
        val rowVals = FloatArray(4)
        for (i in 0..3) {
            val r = r1 - 1 + i
            rowVals[i] = cubic(at(r, c1 - 1), at(r, c1), at(r, c1 + 1), at(r, c1 + 2), tc)
        }
        val v = cubic(rowVals[0], rowVals[1], rowVals[2], rowVals[3], tr)
        return if (nonNegative) max(0f, v) else v
    }
}

enum class FieldKind { CLOUDS, RAIN, WIND }

/** An ARGB picture (non-premultiplied) plus its size. */
class Pixels(val argb: IntArray, val width: Int, val height: Int)

object FieldMath {

    fun cloudField(grid: Grid, hour: Int) = GridField(grid, FloatArray(grid.points.size) {
        (grid.points[it].cloudPct.getOrNull(hour) ?: 0.0).toFloat()
    }, nonNegative = true)

    fun rainField(grid: Grid, hour: Int) = GridField(grid, FloatArray(grid.points.size) {
        (grid.points[it].precipIn.getOrNull(hour) ?: 0.0).toFloat()
    }, nonNegative = true)

    /** East-west (u) and north-south (v) wind components in mph; positive = toward east / north. */
    fun windFields(grid: Grid, hour: Int): Pair<GridField, GridField> {
        val u = FloatArray(grid.points.size)
        val v = FloatArray(grid.points.size)
        grid.points.forEachIndexed { i, p ->
            val s = p.windMph.getOrNull(hour) ?: 0.0
            val from = Math.toRadians(p.windDirDeg.getOrNull(hour) ?: 0.0)
            // Meteorological direction is where the wind comes FROM.
            u[i] = (-s * sin(from)).toFloat()
            v[i] = (-s * cos(from)).toFloat()
        }
        return GridField(grid, u) to GridField(grid, v)
    }

    /**
     * Renders [layer] for one hour as a smooth picture covering the grid, laid out linearly in
     * Web-Mercator space so it lines up with the map when placed on its four corners.
     */
    fun renderPixels(layer: FieldKind, grid: Grid, hour: Int, darkMap: Boolean, width: Int = 340): Pixels? {
        if (grid.times.isEmpty() || grid.rows < 2 || grid.cols < 2) return null
        val h = hour.coerceIn(0, grid.times.lastIndex)

        val value: (Double, Double) -> Float
        val lut: IntArray
        val maxV: Float
        val sqrtScale: Boolean
        when (layer) {
            FieldKind.CLOUDS -> {
                val f = cloudField(grid, h)
                value = f::sample
                lut = buildLut(CLOUD_STOPS(darkMap), 100f, false)
                maxV = 100f; sqrtScale = false
            }
            FieldKind.RAIN -> {
                val f = rainField(grid, h)
                value = f::sample
                lut = buildLut(RAIN_STOPS, 1f, true)
                maxV = 1f; sqrtScale = true
            }
            FieldKind.WIND -> {
                val (u, v) = windFields(grid, h)
                value = { lat, lon -> hypot(u.sample(lat, lon), v.sample(lat, lon)) }
                lut = buildLut(WIND_STOPS, 70f, false)
                maxV = 70f; sqrtScale = false
            }
        }

        val ymN = mercY(grid.north)
        val ymS = mercY(grid.south)
        val dLon = Math.toRadians(grid.east - grid.west)
        val height = ((ymN - ymS) / dLon * width).roundToInt().coerceIn(48, 720)

        val lons = DoubleArray(width) { grid.west + (it + 0.5) / width * (grid.east - grid.west) }
        val vals = FloatArray(width * height)
        for (py in 0 until height) {
            val lat = invMercY(ymN - (py + 0.5) / height * (ymN - ymS))
            val row = py * width
            for (px in 0 until width) vals[row + px] = max(0f, value(lat, lons[px]))
        }
        // Cloud cover in a model jumps between clear and overcast from one point to the next, so
        // feather it a little (two box-blur passes) for the soft look of real cloud.
        if (layer == FieldKind.CLOUDS) boxBlur(vals, width, height, 4, 2)

        val pixels = IntArray(width * height)
        for (i in vals.indices) {
            val t = if (sqrtScale) sqrt(vals[i] / maxV) else vals[i] / maxV
            pixels[i] = lut[(t * 255f).roundToInt().coerceIn(0, 255)]
        }
        return Pixels(pixels, width, height)
    }

    /** In-place separable box blur, [passes] times, with edge clamping. */
    private fun boxBlur(a: FloatArray, w: Int, h: Int, radius: Int, passes: Int) {
        val tmp = FloatArray(a.size)
        val size = (2 * radius + 1).toFloat()
        repeat(passes) {
            for (y in 0 until h) {                      // horizontal
                var sum = 0f
                for (k in -radius..radius) sum += a[y * w + k.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    tmp[y * w + x] = sum / size
                    sum += a[y * w + (x + radius + 1).coerceAtMost(w - 1)] - a[y * w + (x - radius).coerceAtLeast(0)]
                }
            }
            for (x in 0 until w) {                      // vertical
                var sum = 0f
                for (k in -radius..radius) sum += tmp[k.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    a[y * w + x] = sum / size
                    sum += tmp[(y + radius + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - radius).coerceAtLeast(0) * w + x]
                }
            }
        }
    }

    // ---- Colour ramps (value, r, g, b, alpha 0-255) -------------------------------------------

    private class Stop(val v: Float, val r: Int, val g: Int, val b: Int, val a: Int)

    // Cloud cover %: transparent when clear, thickening toward overcast.
    private fun CLOUD_STOPS(dark: Boolean): List<Stop> =
        if (dark) listOf(
            Stop(0f, 240, 245, 255, 0), Stop(20f, 240, 245, 255, 0), Stop(45f, 240, 245, 255, 60),
            Stop(75f, 240, 245, 255, 140), Stop(100f, 245, 248, 255, 205),
        ) else listOf(
            Stop(0f, 80, 92, 115, 0), Stop(20f, 80, 92, 115, 0), Stop(45f, 80, 92, 115, 55),
            Stop(75f, 80, 92, 115, 120), Stop(100f, 70, 80, 105, 170),
        )

    // Rain in inches/hour: light blue drizzle up to magenta downpours.
    private val RAIN_STOPS = listOf(
        Stop(0f, 0, 0, 0, 0), Stop(0.003f, 0, 0, 0, 0), Stop(0.006f, 130, 205, 255, 150),
        Stop(0.02f, 70, 150, 255, 190), Stop(0.06f, 40, 200, 110, 205), Stop(0.12f, 250, 235, 70, 215),
        Stop(0.25f, 255, 150, 40, 225), Stop(0.5f, 240, 50, 50, 235), Stop(1f, 200, 40, 200, 240),
    )

    // Wind speed in mph.
    private val WIND_STOPS = listOf(
        Stop(0f, 70, 100, 210, 70), Stop(4f, 60, 150, 225, 120), Stop(8f, 60, 200, 200, 140),
        Stop(12f, 90, 215, 120, 150), Stop(17f, 160, 225, 80, 160), Stop(23f, 245, 220, 60, 170),
        Stop(30f, 250, 150, 50, 180), Stop(40f, 235, 70, 60, 190), Stop(55f, 190, 50, 170, 200),
        Stop(70f, 160, 40, 200, 210),
    )

    /** 256-entry colour lookup: entry i is the colour for value (i/255 of the range, optionally squared). */
    private fun buildLut(stops: List<Stop>, maxV: Float, sqrtScale: Boolean): IntArray =
        IntArray(256) { i ->
            val t = i / 255f
            val v = if (sqrtScale) t * t * maxV else t * maxV
            val hi = stops.indexOfFirst { it.v >= v }.let { if (it < 0) stops.lastIndex else it }
            val lo = max(0, hi - 1)
            val s0 = stops[lo]
            val s1 = stops[hi]
            val span = s1.v - s0.v
            val k = if (span <= 0f) 0f else ((v - s0.v) / span).coerceIn(0f, 1f)
            fun mix(a: Int, b: Int) = (a + (b - a) * k).roundToInt().coerceIn(0, 255)
            (mix(s0.a, s1.a) shl 24) or (mix(s0.r, s1.r) shl 16) or (mix(s0.g, s1.g) shl 8) or mix(s0.b, s1.b)
        }

    // ---- Web-Mercator helpers -----------------------------------------------------------------

    fun mercY(latDeg: Double): Double = ln(tan(Math.PI / 4 + Math.toRadians(latDeg) / 2))

    fun invMercY(y: Double): Double = Math.toDegrees(2 * atan(exp(y)) - Math.PI / 2)
}
