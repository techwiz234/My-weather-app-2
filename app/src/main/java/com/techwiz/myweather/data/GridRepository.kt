package com.techwiz.myweather.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Forecast values for one map grid point, one entry per hour in [Grid.times]. */
data class GridPoint(
    val lat: Double,
    val lon: Double,
    val cloudPct: List<Double?>,
    val windMph: List<Double?>,
    val windDirDeg: List<Double?>,
    val precipIn: List<Double?>,
)

/** A coarse lattice of forecast points covering the visible map. */
data class Grid(
    val south: Double,
    val north: Double,
    val west: Double,
    val east: Double,
    val cols: Int,
    val rows: Int,
    val times: List<Instant>,
    val points: List<GridPoint>,
    /** Map zoom the grid was fetched at, and the spacing between points in pixels at that zoom. */
    val refZoom: Double = 0.0,
    val cellPx: Float = 100f,
) {
    val latStep get() = (north - south) / rows
    val lonStep get() = (east - west) / cols

    /** Index into [times] of the hour closest to now. */
    fun nowIndex(now: Instant = Instant.now()): Int =
        times.indices.minByOrNull { kotlin.math.abs(times[it].epochSecond - now.epochSecond) } ?: 0
}

/**
 * Fetches the same forecast the point view uses, but for a lattice of points covering the map,
 * in a single request, so wind / cloud / rain can be drawn as map layers.
 */
class GridRepository(private val client: OkHttpClient = OkHttpClient()) {

    suspend fun fetch(
        south: Double, north: Double, west: Double, east: Double,
        cols: Int = 9, rows: Int = 6,
    ): Grid {
        val s = south.coerceIn(-85.0, 85.0)
        val n = north.coerceIn(-85.0, 85.0)
        val w = west.coerceIn(-180.0, 180.0)
        val e = east.coerceIn(-180.0, 180.0)
        val latStep = (n - s) / rows
        val lonStep = (e - w) / cols
        val lats = ArrayList<Double>()
        val lons = ArrayList<Double>()
        for (r in 0 until rows) for (c in 0 until cols) {
            lats += s + (r + 0.5) * latStep
            lons += w + (c + 0.5) * lonStep
        }
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", lats.joinToString(",") { "%.3f".format(it) })
            .addQueryParameter("longitude", lons.joinToString(",") { "%.3f".format(it) })
            .addQueryParameter("hourly", "cloud_cover,wind_speed_10m,wind_direction_10m,precipitation")
            .addQueryParameter("forecast_days", "2")
            .addQueryParameter("timezone", "GMT")
            .addQueryParameter("wind_speed_unit", "mph")
            .addQueryParameter("precipitation_unit", "inch")
            .build().toString()

        val body = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).header("User-Agent", "MyWeatherApp/0.2").build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("Map data returned HTTP ${resp.code}")
                text
            }
        }
        val root = Json.parseToJsonElement(body)
        val items: List<JsonObject> = when (root) {
            is JsonArray -> root.map { it.jsonObject }
            else -> listOf(root.jsonObject)
        }
        require(items.size == lats.size) { "Map data had ${items.size} points, expected ${lats.size}" }

        fun JsonObject.arr(key: String): List<Double?> =
            (this[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull } ?: emptyList()

        val times = items[0]["hourly"]!!.jsonObject["time"]!!.jsonArray.map {
            LocalDateTime.parse(it.jsonPrimitive.content).toInstant(ZoneOffset.UTC)
        }
        val points = items.mapIndexed { i, o ->
            val h = o["hourly"]!!.jsonObject
            // Use the requested (not API-snapped) coordinates so the lattice stays regular.
            GridPoint(
                lat = lats[i], lon = lons[i],
                cloudPct = h.arr("cloud_cover"),
                windMph = h.arr("wind_speed_10m"),
                windDirDeg = h.arr("wind_direction_10m"),
                precipIn = h.arr("precipitation"),
            )
        }
        return Grid(s, n, w, e, cols, rows, times, points)
    }
}
