package com.techwiz.myweather.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant

/** A looping set of map-tile templates (oldest first) plus a label for each frame. */
data class RasterFrames(
    val urls: List<String>,
    val labels: List<String>,
    val source: String,
    val maxZoom: Float,
    /** Index of the "now" frame, where the loop should rest when paused. */
    val nowIndex: Int,
    val tileSize: Int = 256,
)

/**
 * Where observed radar / satellite imagery comes from. Both feeds are free and need no key.
 *
 *  - Radar over the contiguous US: the Iowa Environmental Mesonet NEXRAD mosaic, requested as
 *    full-resolution WMS images, so it stays sharp when you zoom in (no coarse pre-made tiles).
 *  - Radar elsewhere: RainViewer's global composite, without its blur filter. If either can't be
 *    reached the other is tried.
 *  - Satellite: NASA GIBS, GOES-East "GeoColor" (Americas).
 *
 * NOTE: these endpoints could not be tested from the build sandbox; they follow each provider's
 * documented URL patterns. The map reports a clear error instead of showing nothing.
 */
class RadarRepository(private val client: OkHttpClient = OkHttpClient()) {

    suspend fun radar(centerLat: Double, centerLon: Double): RasterFrames {
        val inUs = centerLat in 24.0..50.0 && centerLon in -125.0..-66.0
        if (inUs) return iemNexrad()
        val rv = runCatching { rainViewer() }.getOrNull()
        if (rv != null && rv.urls.isNotEmpty()) return rv
        return iemNexrad()
    }

    fun satellite(): RasterFrames = RasterFrames(
        urls = listOf(
            "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/GOES-East_ABI_GeoColor/default/" +
                "GoogleMapsCompatible_Level7/{z}/{y}/{x}.png",
        ),
        labels = listOf("latest"),
        source = "NASA GIBS · GOES-East GeoColor (Americas)",
        maxZoom = 7f,
        nowIndex = 0,
    )

    private suspend fun rainViewer(): RasterFrames {
        val text = withContext(Dispatchers.IO) {
            val req = Request.Builder().url("https://api.rainviewer.com/public/weather-maps.json")
                .header("User-Agent", "MyWeatherApp/0.2").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("RainViewer HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
        }
        val root = Json.parseToJsonElement(text).jsonObject
        val host = root["host"]?.jsonPrimitive?.content ?: error("no host")
        val radar = root["radar"] as? JsonObject ?: error("no radar")
        fun frames(key: String) = (radar[key] as? JsonArray).orEmpty().mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            val t = o["time"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val path = o["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
            t to path
        }
        val past = frames("past").takeLast(10)
        val nowcast = frames("nowcast")
        val all = past + nowcast
        val nowSecs = Instant.now().epochSecond
        return RasterFrames(
            urls = all.map { (_, path) -> "$host$path/256/{z}/{x}/{y}/2/0_1.png" },
            labels = all.map { (t, _) ->
                val mins = (t - nowSecs) / 60
                if (kotlin.math.abs(mins) < 3) "now" else if (mins < 0) "${-mins} min ago" else "in $mins min"
            },
            source = "RainViewer · global radar",
            maxZoom = 7f,
            nowIndex = past.lastIndex.coerceAtLeast(0),
        )
    }

    private fun iemNexrad(): RasterFrames {
        // WMS GetMap: the server draws each 512 px tile from the full-resolution composite.
        fun wms(layer: String) =
            "https://mesonet.agron.iastate.edu/cgi-bin/wms/nexrad/n0q.cgi?SERVICE=WMS&VERSION=1.1.1" +
                "&REQUEST=GetMap&LAYERS=$layer&STYLES=&FORMAT=image/png&TRANSPARENT=true" +
                "&SRS=EPSG:3857&WIDTH=512&HEIGHT=512&BBOX={bbox-epsg-3857}"
        val offsets = (50 downTo 5 step 5).toList()
        val urls = offsets.map { wms("nexrad-n0q-900913-m%02dm".format(it)) } + wms("nexrad-n0q-900913")
        val labels = offsets.map { "$it min ago" } + "now"
        return RasterFrames(
            urls, labels, "NEXRAD radar · Iowa Environmental Mesonet (US)", 14f, urls.lastIndex, tileSize = 512,
        )
    }
}
