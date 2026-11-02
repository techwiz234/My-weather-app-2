package com.techwiz.myweather.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class Place(
    val name: String,
    val lat: Double,
    val lon: Double,
    val subtitle: String = "",
)

/** Weather models we compare. First = Open-Meteo model id, second = display name. */
val WEATHER_MODELS: List<Pair<String, String>> = listOf(
    "ecmwf_ifs025" to "ECMWF",
    "gfs_seamless" to "GFS",
    "icon_seamless" to "ICON",
)

/**
 * One forecast hour. All units are imperial (°F, mph, inches, miles) because that is what the
 * app requests from the API.
 */
data class HourPoint(
    val time: LocalDateTime,
    val isDay: Boolean,
    val tempF: Double?,
    val feelsF: Double?,
    val precipIn: Double?,
    val precipProb: Double?,
    val snowIn: Double?,
    val cloudPct: Double?,
    val uv: Double?,
    val visibilityMi: Double?,
    /** Average 10 m wind across all models that returned data. */
    val windMph: Double?,
    val windMinMph: Double?,
    val windMaxMph: Double?,
    val gustMph: Double?,
    val windDirDeg: Double?,
    val wind80: Double?,
    val wind120: Double?,
    val wind180: Double?,
    /** 10 m wind per model, keyed by model display name. */
    val modelWind: Map<String, Double>,
)

data class DayInfo(
    val date: LocalDate,
    val sunrise: LocalDateTime?,
    val sunset: LocalDateTime?,
    val tempMaxF: Double?,
    val tempMinF: Double?,
    val uvMax: Double?,
    val precipSumIn: Double?,
)

data class Forecast(
    val place: Place,
    val hours: List<HourPoint>,
    val days: List<DayInfo>,
    val utcOffsetSeconds: Int,
    /** False when the multi-model request failed and only the blended forecast is shown. */
    val hasModelComparison: Boolean,
) {
    /** Hours from the current hour (in the place's own time zone) onward. */
    fun upcoming(): List<HourPoint> {
        val now = LocalDateTime.now(ZoneOffset.ofTotalSeconds(utcOffsetSeconds))
            .truncatedTo(ChronoUnit.HOURS)
        return hours.filter { !it.time.isBefore(now) }
    }
}
