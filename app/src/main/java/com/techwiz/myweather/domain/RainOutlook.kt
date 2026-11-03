package com.techwiz.myweather.domain

import com.techwiz.myweather.data.Forecast
import com.techwiz.myweather.data.HourPoint
import com.techwiz.myweather.data.RainSlot
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Standard rain-rate bands, in inches per hour. */
enum class Intensity(val label: String) {
    NONE("No rain"),
    TRACE("Trace / drizzle"),
    LIGHT("Light rain"),
    MODERATE("Moderate rain"),
    HEAVY("Heavy rain"),
}

fun intensityOf(inPerHour: Double): Intensity = when {
    inPerHour < 0.004 -> Intensity.NONE
    inPerHour < 0.02 -> Intensity.TRACE
    inPerHour < 0.098 -> Intensity.LIGHT
    inPerHour < 0.31 -> Intensity.MODERATE
    else -> Intensity.HEAVY
}

/** One spell of wet weather, with how much the models disagree about it. */
data class RainEvent(
    val start: LocalDateTime,
    /** First dry hour after the spell. */
    val end: LocalDateTime,
    val totalIn: Double,
    val peakInPerHour: Double,
    /** Total over the spell for each model that provided hourly rain. */
    val modelTotals: Map<String, Double>,
    /** Earliest and latest start time among models (within a few hours of [start]). */
    val earliestModelStart: LocalDateTime?,
    val latestModelStart: LocalDateTime?,
)

data class RainOutlook(
    val rainingNow: Boolean,
    val nowIntensity: Intensity,
    /** Inches that fell in the last hour according to the 15-minute nowcast. */
    val lastHourIn: Double?,
    /** Next ~4 hours in 15-minute slots. */
    val next: List<RainSlot>,
    val events: List<RainEvent>,
    /** Next-24h total: average of the models, and the lowest / highest single model. */
    val total24Mean: Double,
    val total24Min: Double,
    val total24Max: Double,
    val total24ByModel: Map<String, Double>,
    val thunderFirst: LocalDateTime?,
    val capeMax24: Double?,
)

private val THUNDER_CODES = setOf(95, 96, 99)
private val WET_CODES = setOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99)

/** Best estimate of an hour's rainfall: the average of the models, else the blended forecast. */
fun HourPoint.rainConsensus(): Double =
    if (modelPrecip.isNotEmpty()) modelPrecip.values.average() else (precipIn ?: 0.0)

fun buildRainOutlook(fc: Forecast): RainOutlook {
    val hours = fc.upcoming()
    val h24 = hours.take(24)

    // ---- Right now: observed-style nowcast first, never the hourly forecast. -------------
    val cur = fc.current
    val rate15 = cur?.precip15In?.let { it * 4 } ?: 0.0
    val codeWet = cur?.weatherCode in WET_CODES
    val nowIntensity = intensityOf(rate15).let { if (it == Intensity.NONE && codeWet) Intensity.TRACE else it }
    val rainingNow = nowIntensity != Intensity.NONE

    val now = fc.localNow()
    val lastHour = fc.minutely.filter { !it.time.isAfter(now) && it.time.isAfter(now.minusMinutes(60)) }
        .takeIf { it.isNotEmpty() }?.sumOf { it.precipIn }
    val next = fc.minutely.filter { it.time.isAfter(now.minusMinutes(15)) }

    // ---- Wet spells over the next 48 h, merged across one dry hour. -----------------------
    val h48 = hours.take(48)
    val wet = h48.map { it.rainConsensus() >= 0.01 }
    val events = mutableListOf<RainEvent>()
    var i = 0
    while (i < h48.size) {
        if (!wet[i]) { i++; continue }
        var j = i
        while (j + 1 < h48.size && (wet[j + 1] || (j + 2 < h48.size && wet[j + 2]))) j++
        val span = h48.subList(i, j + 1)
        val start = span.first().time
        val end = span.last().time.plusHours(1)

        val totals = linkedMapOf<String, Double>()
        val starts = mutableListOf<LocalDateTime>()
        val labels = span.flatMap { it.modelPrecip.keys }.toSet()
        for (label in labels) {
            totals[label] = span.sumOf { it.modelPrecip[label] ?: 0.0 }
            // Each model's own first wet hour, looking a few hours either side of the spell.
            val window = h48.filter { !it.time.isBefore(start.minusHours(4)) && it.time.isBefore(end) }
            window.firstOrNull { (it.modelPrecip[label] ?: 0.0) >= 0.01 }?.let { starts += it.time }
        }
        events += RainEvent(
            start = start,
            end = end,
            totalIn = span.sumOf { it.rainConsensus() },
            peakInPerHour = span.maxOf { it.rainConsensus() },
            modelTotals = totals,
            earliestModelStart = starts.minOrNull(),
            latestModelStart = starts.maxOrNull(),
        )
        i = j + 1
    }

    // ---- 24 h totals. --------------------------------------------------------------------
    val labels = h24.flatMap { it.modelPrecip.keys }.toSet()
    val byModel = labels.associateWith { l -> h24.sumOf { it.modelPrecip[l] ?: 0.0 } }
    val mean = if (byModel.isNotEmpty()) byModel.values.average() else h24.sumOf { it.precipIn ?: 0.0 }
    val min = byModel.values.minOrNull() ?: mean
    val max = byModel.values.maxOrNull() ?: mean

    val thunder = h24.firstOrNull { it.weatherCode in THUNDER_CODES }?.time
        ?: if (cur?.weatherCode in THUNDER_CODES) cur?.time else null

    return RainOutlook(
        rainingNow = rainingNow,
        nowIntensity = nowIntensity,
        lastHourIn = lastHour,
        next = next,
        events = events,
        total24Mean = mean,
        total24Min = min,
        total24Max = max,
        total24ByModel = byModel,
        thunderFirst = thunder,
        capeMax24 = h24.mapNotNull { it.cape }.maxOrNull(),
    )
}

/** "Starts in 40 min", "Started 20 min ago", etc. relative to [now]. */
fun relativeTime(target: LocalDateTime, now: LocalDateTime): String {
    val mins = Duration.between(now.truncatedTo(ChronoUnit.MINUTES), target).toMinutes()
    val abs = kotlin.math.abs(mins)
    val span = if (abs < 90) "$abs min" else "%.0f h".format(abs / 60.0)
    return if (mins >= 0) "in $span" else "$span ago"
}

// ---- UV --------------------------------------------------------------------------------

fun uvLabel(uv: Double): String = when {
    uv < 3 -> "Low"
    uv < 6 -> "Moderate"
    uv < 8 -> "High"
    uv < 11 -> "Very high"
    else -> "Extreme"
}

fun uvAdvice(uv: Double): String = when {
    uv < 3 -> "No protection needed for most people."
    uv < 6 -> "Sunscreen and sunglasses if outside for long."
    uv < 8 -> "Sunscreen, hat and shade at midday."
    uv < 11 -> "Skin can burn in well under 30 minutes. Cover up."
    else -> "Burns in minutes. Avoid midday sun."
}

// ---- Weather codes (WMO) --------------------------------------------------------------

fun weatherCodeText(code: Int?): String = when (code) {
    null -> "–"
    0 -> "Clear"
    1 -> "Mostly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    51, 53, 55 -> "Drizzle"
    56, 57 -> "Freezing drizzle"
    61 -> "Light rain"
    63 -> "Rain"
    65 -> "Heavy rain"
    66, 67 -> "Freezing rain"
    71 -> "Light snow"
    73 -> "Snow"
    75 -> "Heavy snow"
    77 -> "Snow grains"
    80 -> "Light showers"
    81 -> "Showers"
    82 -> "Violent showers"
    85, 86 -> "Snow showers"
    95 -> "Thunderstorm"
    96, 99 -> "Thunderstorm with hail"
    else -> "Code $code"
}
