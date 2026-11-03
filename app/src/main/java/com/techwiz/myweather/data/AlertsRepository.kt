package com.techwiz.myweather.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.OffsetDateTime

data class WeatherAlert(
    val event: String,
    val severity: String,
    val headline: String,
    val description: String,
    val instruction: String?,
    val expires: Instant?,
    val area: String,
) {
    val isWarning get() = event.endsWith("Warning", ignoreCase = true) || event.contains("Emergency", ignoreCase = true)
    val isWatch get() = event.endsWith("Watch", ignoreCase = true)

    /** Lower sorts first: the most dangerous alerts on top. */
    val rank: Int
        get() = when {
            event.contains("Tornado Warning", true) || event.contains("Emergency", true) -> 0
            event.contains("Severe Thunderstorm Warning", true) -> 1
            event.contains("Flash Flood Warning", true) -> 2
            isWarning -> 3
            isWatch -> 4
            else -> 5
        }
}

sealed interface AlertsStatus {
    /** Not checked yet. */
    data object Unknown : AlertsStatus

    /** Official alert feed used here (US National Weather Service) doesn't cover this point. */
    data object Unsupported : AlertsStatus

    data class Ok(val alerts: List<WeatherAlert>, val checkedAt: Instant) : AlertsStatus

    /** The check itself failed, so "no alerts" must NOT be assumed. */
    data class Failed(val message: String) : AlertsStatus
}

/** Active official alerts from the US National Weather Service (api.weather.gov). */
class AlertsRepository(private val client: OkHttpClient = OkHttpClient()) {

    suspend fun active(lat: Double, lon: Double): AlertsStatus = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.weather.gov/alerts/active".toHttpUrl().newBuilder()
                .addQueryParameter("point", "%.4f,%.4f".format(lat, lon))
                .build()
            val req = Request.Builder().url(url)
                .header("User-Agent", "MyWeatherApp (personal Android app)")
                .header("Accept", "application/geo+json")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                // NWS answers 404 (or 400) for points outside its coverage area.
                if (resp.code == 404 || resp.code == 400) return@withContext AlertsStatus.Unsupported
                if (!resp.isSuccessful) return@withContext AlertsStatus.Failed("Alert service returned HTTP ${resp.code}")
                parse(body)
            }
        } catch (e: Exception) {
            AlertsStatus.Failed(e.message ?: "Couldn't reach the alert service")
        }
    }

    internal fun parse(body: String): AlertsStatus {
        val root = Json.parseToJsonElement(body).jsonObject
        val features = root["features"] as? JsonArray ?: return AlertsStatus.Failed("Unexpected alert response")
        val alerts = features.mapNotNull { f ->
            val p = (f as? JsonObject)?.get("properties") as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (p[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val event = str("event") ?: return@mapNotNull null
            WeatherAlert(
                event = event,
                severity = str("severity") ?: "Unknown",
                headline = str("headline") ?: event,
                description = str("description").orEmpty(),
                instruction = str("instruction"),
                expires = (str("ends") ?: str("expires"))?.let {
                    runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
                },
                area = str("areaDesc").orEmpty(),
            )
        }.sortedBy { it.rank }
        return AlertsStatus.Ok(alerts, Instant.now())
    }
}
