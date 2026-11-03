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
    /** Rain/snow-water amount for this hour from the blended "best match" forecast. */
    val precipIn: Double?,
    /** Chance (0-100) that at least a trace of precipitation falls at this spot this hour. */
    val precipProb: Double?,
    val snowIn: Double?,
    val cloudPct: Double?,
    val uv: Double?,
    val visibilityMi: Double?,
    val weatherCode: Int?,
    /** Convective energy: higher means more fuel for thunderstorms (J/kg). */
    val cape: Double?,
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
    /** Hourly precipitation amount (inches) per model, keyed by model display name. */
    val modelPrecip: Map<String, Double>,
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

/** What the weather system says is happening right now (model analysis + nowcast, not a gauge). */
data class CurrentConditions(
    val time: LocalDateTime,
    val tempF: Double?,
    val feelsF: Double?,
    /** Precipitation over the last 15 minutes, inches. */
    val precip15In: Double?,
    val weatherCode: Int?,
    val windMph: Double?,
    val gustMph: Double?,
    val cape: Double?,
)

/** Precipitation in one 15-minute slot, inches. Includes the past hour and the next few hours. */
data class RainSlot(val time: LocalDateTime, val precipIn: Double)

data class Forecast(
    val place: Place,
    val hours: List<HourPoint>,
    val days: List<DayInfo>,
    val utcOffsetSeconds: Int,
    /** False when the multi-model request failed and only the blended forecast is shown. */
    val hasModelComparison: Boolean,
    val current: CurrentConditions? = null,
    val minutely: List<RainSlot> = emptyList(),
    /** Which weather server produced this forecast (shown in the UI). */
    val source: String = "",
) {
    /** Current time at the place, in the place's own time zone. */
    fun localNow(): LocalDateTime = LocalDateTime.now(ZoneOffset.ofTotalSeconds(utcOffsetSeconds))

    /** Hours from the current hour (in the place's own time zone) onward. */
    fun upcoming(): List<HourPoint> {
        val now = localNow().truncatedTo(ChronoUnit.HOURS)
        return hours.filter { !it.time.isBefore(now) }
    }
}
