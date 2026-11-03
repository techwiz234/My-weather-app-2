package com.techwiz.myweather.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.withFrameNanos
import com.techwiz.myweather.data.Grid
import com.techwiz.myweather.domain.FieldMath
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Windy-style wind: thousands of tiny particles drifting with the wind, each leaving a short
 * fading trail. Particles live in screen space and are steered by the wind field sampled at the
 * place they are over, so the map must not be rotated or tilted (the map screen disables both).
 *
 * The overlay is not drawn while the camera is moving (positions would lag the map) and starts
 * again once it settles on the viewport the field was built for.
 */
@Composable
fun WindParticles(
    grid: Grid,
    hour: Int,
    viewport: Viewport,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableLongStateOf(0L) }

    // Screen-space wind field: one (dx, dy) in pixels/second per coarse screen cell.
    val sim = remember(grid, hour, viewport, canvasSize) {
        if (canvasSize.width == 0 || canvasSize.height == 0) null
        else ParticleSim(grid, hour, viewport, canvasSize, density)
    }

    LaunchedEffect(sim) {
        if (sim == null) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                last = now
                sim.step(dt)
                frame++
            }
        }
    }

    Canvas(modifier.onSizeChanged { canvasSize = it }) {
        val tick = frame // read so every simulation step redraws
        val s = sim ?: return@Canvas
        if (tick < 0) return@Canvas
        drawIntoCanvas { c -> s.draw(c.nativeCanvas) }
    }
}

private class ParticleSim(
    grid: Grid,
    hour: Int,
    private val vp: Viewport,
    private val size: IntSize,
    density: Float,
) {
    private val w = size.width.toFloat()
    private val h = size.height.toFloat()

    // Wind field on a coarse screen lattice (cell ~ 28 dp), in pixels per second.
    private val cell = 28f * density
    private val gx = (w / cell).toInt().coerceAtLeast(2) + 1
    private val gy = (h / cell).toInt().coerceAtLeast(2) + 1
    private val fx = FloatArray(gx * gy)
    private val fy = FloatArray(gx * gy)
    private val fs = FloatArray(gx * gy) // wind speed mph (for fade-in of calm areas)

    private val n = (1400 * (w * h) / (1080f * 2000f)).toInt().coerceIn(600, 2200)
    private val px = FloatArray(n)
    private val py = FloatArray(n)
    private val age = IntArray(n)
    private val life = IntArray(n)
    private val rnd = Random(7)

    // Trail ring buffers.
    private val trail = 7
    private val tx = FloatArray(n * trail)
    private val ty = FloatArray(n * trail)

    private val paints = Array(trail - 1) { i ->
        Paint().apply {
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            color = android.graphics.Color.argb((40 + 215 * (trail - 2 - i) / (trail - 2)).coerceIn(0, 255), 255, 255, 255)
            strokeWidth = (1.1f + 0.9f * (trail - 2 - i) / (trail - 2)) * density
        }
    }
    private val lines = Array(trail - 1) { FloatArray(n * 4) }

    init {
        val (u, v) = FieldMath.windFields(grid, hour)
        val ymN = FieldMath.mercY(vp.north)
        val ymS = FieldMath.mercY(vp.south)
        // Pixels of drift per second for one mph. Gentle: 20 mph crosses about a quarter screen in 5 s.
        val k = 3.2f * density
        for (j in 0 until gy) {
            val lat = FieldMath.invMercY(ymN - (j * cell / h).coerceIn(0f, 1f) * (ymN - ymS))
            for (i in 0 until gx) {
                val lon = vp.west + (i * cell / w).coerceIn(0f, 1f) * (vp.east - vp.west)
                val uu = u.sample(lat, lon)
                val vv = v.sample(lat, lon)
                fx[j * gx + i] = uu * k
                fy[j * gx + i] = -vv * k // screen y grows downward
                fs[j * gx + i] = hypot(uu, vv)
            }
        }
        for (i in 0 until n) respawn(i, randomAge = true)
    }

    private fun respawn(i: Int, randomAge: Boolean) {
        px[i] = rnd.nextFloat() * w
        py[i] = rnd.nextFloat() * h
        life[i] = 70 + rnd.nextInt(90)
        age[i] = if (randomAge) rnd.nextInt(life[i]) else 0
        for (t in 0 until trail) {
            tx[i * trail + t] = px[i]
            ty[i * trail + t] = py[i]
        }
    }

    private fun fieldAt(x: Float, y: Float, out: FloatArray) {
        val fxp = (x / cell).coerceIn(0f, (gx - 1).toFloat())
        val fyp = (y / cell).coerceIn(0f, (gy - 1).toFloat())
        val x0 = fxp.toInt().coerceAtMost(gx - 2)
        val y0 = fyp.toInt().coerceAtMost(gy - 2)
        val tx0 = fxp - x0
        val ty0 = fyp - y0
        fun at(a: FloatArray, i: Int, j: Int) = a[j * gx + i]
        fun bil(a: FloatArray) =
            (at(a, x0, y0) * (1 - tx0) + at(a, x0 + 1, y0) * tx0) * (1 - ty0) +
                (at(a, x0, y0 + 1) * (1 - tx0) + at(a, x0 + 1, y0 + 1) * tx0) * ty0
        out[0] = bil(fx)
        out[1] = bil(fy)
        out[2] = bil(fs)
    }

    private val tmp = FloatArray(3)

    fun step(dt: Float) {
        for (i in 0 until n) {
            fieldAt(px[i], py[i], tmp)
            px[i] += tmp[0] * dt
            py[i] += tmp[1] * dt
            age[i]++
            // Shift the trail and record the new head.
            val base = i * trail
            for (t in trail - 1 downTo 1) {
                tx[base + t] = tx[base + t - 1]
                ty[base + t] = ty[base + t - 1]
            }
            tx[base] = px[i]
            ty[base] = py[i]
            if (age[i] > life[i] || px[i] < -10 || px[i] > w + 10 || py[i] < -10 || py[i] > h + 10) {
                respawn(i, randomAge = false)
            }
        }
    }

    fun draw(canvas: android.graphics.Canvas) {
        for (seg in 0 until trail - 1) {
            val arr = lines[seg]
            var m = 0
            for (i in 0 until n) {
                // Fade particles in and out over their life so they don't pop.
                val a = age[i]
                if (a < 6 || life[i] - a < 8) continue
                val base = i * trail + seg
                arr[m++] = tx[base]
                arr[m++] = ty[base]
                arr[m++] = tx[base + 1]
                arr[m++] = ty[base + 1]
            }
            canvas.drawLines(arr, 0, m, paints[seg])
        }
    }
}
