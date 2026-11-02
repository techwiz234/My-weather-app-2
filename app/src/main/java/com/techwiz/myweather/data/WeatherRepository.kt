package com.techwiz.myweather.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Talks to Open-Meteo (free, no API key, non-commercial use). Everything is requested in
 * imperial units.
 */
class WeatherRepository(
    private val client: OkHttpClient = OkHttpClient(),
) {

    suspend fun search(query: String): List<Place> {
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", query)
            .addQueryParameter("count", "8")
            .addQueryParameter("language", "en")
            .addQueryParameter("format", "json")
            .build().toString()
        val root = Json.parseToJsonElement(get(url)).jsonObject
        val results = root["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val lat = o["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val lon = o["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val admin = o["admin1"]?.jsonPrimitive?.content
            val country = o["country"]?.jsonPrimitive?.content
            Place(name, lat, lon, listOfNotNull(admin, country).joinToString(", "))
        }
    }

    suspend fun forecast(place: Place): Forecast = coroutineScope {
        val general = async { Json.parseToJsonElement(get(generalUrl(place))).jsonObject }
        val multi = async {
            runCatching { Json.parseToJsonElement(get(multiModelUrl(place))).jsonObject }.getOrNull()
        }
        parseForecast(place, general.await(), multi.await())
    }

    // ---- URLs -------------------------------------------------------------------------------

    private fun baseUrl(place: Place) =
        "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", place.lat.toString())
            .addQueryParameter("longitude", place.lon.toString())
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", "7")
            .addQueryParameter("wind_speed_unit", "mph")
            .addQueryParameter("temperature_unit", "fahrenheit")
            .addQueryParameter("precipitation_unit", "inch")

    private fun generalUrl(place: Place): String = baseUrl(place)
        .addQueryParameter(
            "hourly",
            listOf(
                "temperature_2m", "apparent_temperature", "precipitation",
                "precipitation_probability", "snowfall", "cloud_cover", "uv_index",
                "visibility", "is_day", "wind_speed_10m", "wind_gusts_10m",
                "wind_direction_10m", "wind_speed_80m", "wind_speed_120m", "wind_speed_180m",
            ).joinToString(","),
        )
        .addQueryParameter(
            "daily",
            "sunrise,sunset,temperature_2m_max,temperature_2m_min,uv_index_max,precipitation_sum",
        )
        .build().toString()

    private fun multiModelUrl(place: Place): String = baseUrl(place)
        .addQueryParameter("hourly", "wind_speed_10m,wind_gusts_10m")
        .addQueryParameter("models", WEATHER_MODELS.joinToString(",") { it.first })
        .build().toString()

    // ---- Parsing ----------------------------------------------------------------------------

    internal fun parseForecast(
        place: Place,
        general: JsonObject,
        multi: JsonObject?,
    ): Forecast {
        val gh = general["hourly"]!!.jsonObject
        val times = gh.strings("time")

        fun g(key: String) = gh.doubles(key)

        val temp = g("temperature_2m")
        val feels = g("apparent_temperature")
        val precip = g("precipitation")
        val prob = g("precipitation_probability")
        val snow = g("snowfall")
        val cloud = g("cloud_cover")
        val uv = g("uv_index")
        val vis = g("visibility")
        val isDay = g("is_day")
        val gWind = g("wind_speed_10m")
        val gGust = g("wind_gusts_10m")
        val dir = g("wind_direction_10m")
        val w80 = g("wind_speed_80m")
        val w120 = g("wind_speed_120m")
        val w180 = g("wind_speed_180m")

        // Multi-model data is matched to the blended forecast by timestamp, not by index.
        val mh = multi?.get("hourly") as? JsonObject
        val mTimeIndex: Map<String, Int> = mh?.strings("time")
            ?.withIndex()?.associate { it.value to it.index } ?: emptyMap()
        val mWind = WEATHER_MODELS.associate { (id, label) ->
            label to mh?.doubles("wind_speed_10m_$id")
        }
        val mGust = WEATHER_MODELS.associate { (id, label) ->
            label to mh?.doubles("wind_gusts_10m_$id")
        }

        val hours = times.indices.map { i ->
            val t = times[i]
            val mi = mTimeIndex[t]
            val perModel: Map<String, Double> = if (mi == null) emptyMap() else
                mWind.mapNotNull { (label, arr) -> arr?.getOrNull(mi)?.let { label to it } }.toMap()
            val gusts = if (mi == null) emptyList() else
                mGust.values.mapNotNull { it?.getOrNull(mi) }

            val wind = if (perModel.isNotEmpty()) perModel.values.average() else gWind.getOrNull(i)
            HourPoint(
                time = LocalDateTime.parse(t),
                isDay = (isDay.getOrNull(i) ?: 1.0) >= 0.5,
                tempF = temp.getOrNull(i),
                feelsF = feels.getOrNull(i),
                precipIn = precip.getOrNull(i),
                precipProb = prob.getOrNull(i),
                snowIn = snow.getOrNull(i),
                cloudPct = cloud.getOrNull(i),
                uv = uv.getOrNull(i),
                visibilityMi = vis.getOrNull(i)?.let { it / 1609.34 },
                windMph = wind,
                windMinMph = if (perModel.size > 1) perModel.values.min() else wind,
                windMaxMph = if (perModel.size > 1) perModel.values.max() else wind,
                gustMph = if (gusts.isNotEmpty()) gusts.average() else gGust.getOrNull(i),
                windDirDeg = dir.getOrNull(i),
                wind80 = w80.getOrNull(i),
                wind120 = w120.getOrNull(i),
                wind180 = w180.getOrNull(i),
                modelWind = perModel,
            )
        }

        val gd = general["daily"] as? JsonObject
        val days = gd?.let { d ->
            val dates = d.strings("time")
            val sunrise = d.strings("sunrise")
            val sunset = d.strings("sunset")
            val tMax = d.doubles("temperature_2m_max")
            val tMin = d.doubles("temperature_2m_min")
            val uvMax = d.doubles("uv_index_max")
            val rain = d.doubles("precipitation_sum")
            dates.indices.map { i ->
                DayInfo(
                    date = LocalDate.parse(dates[i]),
                    sunrise = sunrise.getOrNull(i)?.let { LocalDateTime.parse(it) },
                    sunset = sunset.getOrNull(i)?.let { LocalDateTime.parse(it) },
                    tempMaxF = tMax.getOrNull(i),
                    tempMinF = tMin.getOrNull(i),
                    uvMax = uvMax.getOrNull(i),
                    precipSumIn = rain.getOrNull(i),
                )
            }
        } ?: emptyList()

        val offset = general["utc_offset_seconds"]?.jsonPrimitive?.intOrNull ?: 0
        return Forecast(
            place = place,
            hours = hours,
            days = days,
            utcOffsetSeconds = offset,
            hasModelComparison = hours.any { it.modelWind.size > 1 },
        )
    }

    // ---- HTTP -------------------------------------------------------------------------------

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", "MyWeatherApp/0.1").build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Weather service returned HTTP ${resp.code}")
            body
        }
    }
}

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

/** Numeric array with JSON nulls mapped to Kotlin nulls; missing key gives an empty list. */
private fun JsonObject.doubles(key: String): List<Double?> =
    (this[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull } ?: emptyList()
