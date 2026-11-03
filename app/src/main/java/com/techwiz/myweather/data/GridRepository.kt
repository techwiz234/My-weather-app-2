package com.techwiz.myweather.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Forecast values for one map grid point, one entry per hour in [Grid.times]. */
data class GridPoint(
    val lat: Double,
    val lon: Double,
    val cloudPct: List<Double?>,
    val windMph: List<Double?>,
    val windDirDeg: List<Double?>,
    val precipIn: List<Double?>,
)

/**
 * A regular lattice of forecast points covering the visible map. Point (row r, column c) is at
 * index r * cols + c; rows run south to north, columns west to east, and points sit at cell centres.
 */
data class Grid(
    val south: Double,
    val north: Double,
    val west: Double,
    val east: Double,
    val cols: Int,
    val rows: Int,
    val times: List<Instant>,
    val points: List<GridPoint>,
) {
    val latStep get() = (north - south) / rows
    val lonStep get() = (east - west) / cols

    /** Index into [times] of the hour closest to now. */
    fun nowIndex(now: Instant = Instant.now()): Int =
        times.indices.minByOrNull { kotlin.math.abs(times[it].epochSecond - now.epochSecond) } ?: 0
}

/**
 * Fetches the same forecast the point view uses, but for a lattice of points covering the map, so
 * wind / cloud / rain can be drawn as smooth map layers.
 *
 * Your own server has no usage limits, so it gets a dense lattice (about 1,100 points, sent in
 * parallel batches). The public API gets a small one (about 60 points) to stay under its caps.
 */
class GridRepository(client: OkHttpClient = OkHttpClient()) {

    private val client = client.newBuilder()
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(
        south: Double, north: Double, west: Double, east: Double,
        /** Screen width / height, so cells come out roughly square on screen. */
        aspect: Double,
        servers: List<WeatherServer> = listOf(WeatherServer.PUBLIC),
    ): Grid {
        var last: Exception? = null
        for (server in servers) {
            try {
                val dense = server.baseUrl != WeatherServer.PUBLIC_URL
                return fetchFrom(server.baseUrl, south, north, west, east, aspect, if (dense) DENSE_POINTS else COARSE_POINTS)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("No weather server configured")
    }

    private suspend fun fetchFrom(
        base: String, south: Double, north: Double, west: Double, east: Double,
        aspect: Double, targetPoints: Int,
    ): Grid {
        val s = south.coerceIn(-85.0, 85.0)
        val n = north.coerceIn(-85.0, 85.0)
        val w = west.coerceIn(-180.0, 180.0)
        val e = east.coerceIn(-180.0, 180.0)

        // Lattice shape: match the screen, but never finer than the models can resolve (~0.2°).
        var cols = sqrt(targetPoints * aspect).roundToInt().coerceIn(4, 60)
        var rows = (targetPoints.toDouble() / cols).roundToInt().coerceIn(4, 60)
        cols = minOf(cols, ceil((e - w) / MIN_SPACING_DEG).toInt().coerceAtLeast(4))
        rows = minOf(rows, ceil((n - s) / MIN_SPACING_DEG).toInt().coerceAtLeast(4))

        val latStep = (n - s) / rows
        val lonStep = (e - w) / cols
        val lats = ArrayList<Double>(rows * cols)
        val lons = ArrayList<Double>(rows * cols)
        for (r in 0 until rows) for (c in 0 until cols) {
            lats += s + (r + 0.5) * latStep
            lons += w + (c + 0.5) * lonStep
        }

        val gate = Semaphore(4)
        val batches = (0 until lats.size step BATCH).map { start ->
            val end = minOf(start + BATCH, lats.size)
            start to end
        }
        val parsed: List<List<JsonObject>> = coroutineScope {
            batches.map { (start, end) ->
                async {
                    gate.withPermit { fetchBatch(base, lats.subList(start, end), lons.subList(start, end)) }
                }
            }.awaitAll()
        }
        val items = parsed.flatten()
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

    private suspend fun fetchBatch(base: String, lats: List<Double>, lons: List<Double>): List<JsonObject> {
        val url = "$base/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", lats.joinToString(",") { "%.3f".format(it) })
            .addQueryParameter("longitude", lons.joinToString(",") { "%.3f".format(it) })
            .addQueryParameter("hourly", "cloud_cover,wind_speed_10m,wind_direction_10m,precipitation")
            .addQueryParameter("forecast_days", "2")
            .addQueryParameter("timezone", "GMT")
            .addQueryParameter("wind_speed_unit", "mph")
            .addQueryParameter("precipitation_unit", "inch")
            .build().toString()

        val body = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).header("User-Agent", "MyWeatherApp/0.3").build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.request.url.host != req.url.host) {
                    error("Blocked by the server's login. Check the Access token on the Data tab.")
                }
                if (!resp.isSuccessful) {
                    val reason = Regex("\"reason\":\"([^\"]+)").find(text)?.groupValues?.get(1)
                    error("Map data: ${reason ?: "HTTP ${resp.code}"}")
                }
                text
            }
        }
        return when (val root = Json.parseToJsonElement(body)) {
            is JsonArray -> root.map { it.jsonObject }
            else -> listOf(root.jsonObject)
        }
    }

    private companion object {
        const val DENSE_POINTS = 1100
        const val COARSE_POINTS = 60
        const val BATCH = 100
        const val MIN_SPACING_DEG = 0.2
    }
}
