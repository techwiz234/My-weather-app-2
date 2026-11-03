package com.techwiz.myweather.domain

import com.techwiz.myweather.data.Forecast
import com.techwiz.myweather.data.HourPoint
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val HOUR = DateTimeFormatter.ofPattern("h a", Locale.US)
private val DAY = DateTimeFormatter.ofPattern("EEE", Locale.US)

/** Big value plus one short explanatory line, for the at-a-glance tiles. */
data class Glance(val value: String, val sub: String)

fun weatherEmoji(code: Int?, isDay: Boolean): String = when (code) {
    0 -> if (isDay) "☀️" else "🌙"
    1 -> if (isDay) "🌤️" else "🌙"
    2 -> if (isDay) "⛅" else "☁️"
    3 -> "☁️"
    45, 48 -> "🌫️"
    51, 53, 55, 56, 57 -> "🌦️"
    61, 63, 65, 66, 67 -> "🌧️"
    71, 73, 75, 77, 85, 86 -> "🌨️"
    80, 81, 82 -> "🌦️"
    95, 96, 99 -> "⛈️"
    else -> if (isDay) "🌡️" else "🌙"
}

private val COMPASS = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

/** Direction the wind comes FROM, as a compass point ("SSE"). */
fun compass(deg: Double): String = COMPASS[(((deg % 360 + 360) % 360) / 22.5 + 0.5).toInt() % 16]

private fun dayWord(t: LocalDateTime, today: LocalDate): String = when (t.toLocalDate()) {
    today -> "today"
    today.plusDays(1) -> "tomorrow"
    else -> t.format(DAY)
}

/** Rain expected in the next few hours from the 15-minute nowcast. */
private class SoonRain(val first: java.time.LocalDateTime, val totalIn: Double) {
    /** Under 0.02 in over four hours is a sprinkle, not worth planning around. */
    val isTrace get() = totalIn < 0.02
}

private fun soonRain(o: RainOutlook, now: LocalDateTime): SoonRain? {
    val ahead = o.next.filter { !it.time.isBefore(now) }
    val first = ahead.firstOrNull { it.precipIn >= 0.004 } ?: return null
    return SoonRain(first.time, ahead.sumOf { it.precipIn })
}

private fun clock(t: LocalDateTime) = t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))

/** One line for the Rain header: what is happening, then what comes next. */
fun rainHeadline(o: RainOutlook, fc: Forecast): String {
    val now = fc.localNow()
    val soon = soonRain(o, now)
    val ev = o.events.firstOrNull()
    return when {
        o.rainingNow -> "Raining now: ${o.nowIntensity.label.lowercase(Locale.US)}"
        soon != null && soon.isTrace -> "Dry now. A few sprinkles possible around ${clock(soon.first)}"
        soon != null -> "Dry now, rain ${relativeTime(soon.first, now)}"
        ev != null -> "Dry now. Next rain ${dayWord(ev.start, now.toLocalDate())} ${ev.start.format(HOUR)}"
        else -> "No rain expected for 2 days"
    }
}

fun rainGlance(o: RainOutlook, fc: Forecast): Glance {
    val now = fc.localNow()
    val soon = soonRain(o, now)
    val ev = o.events.firstOrNull()
    val total = if (o.total24Mean >= 0.01) "%.2f in over the next 24 h".format(o.total24Mean) else "Nothing measurable in the next 24 h"
    return when {
        o.rainingNow -> Glance("Raining", "${o.nowIntensity.label}. $total")
        soon != null && soon.isTrace -> Glance("Dry", "A few sprinkles possible around ${clock(soon.first)} (under 0.02 in)")
        soon != null -> Glance("Rain ${relativeTime(soon.first, now)}", "About ${"%.2f".format(soon.totalIn)} in over the next 4 h")
        ev != null -> Glance(
            "${dayWord(ev.start, now.toLocalDate()).replaceFirstChar { it.uppercase() }} ${ev.start.format(HOUR)}",
            "Next rain, about ${"%.2f".format(ev.totalIn)} in",
        )
        else -> Glance("Dry", "No rain for 2 days")
    }
}

fun windGlance(fc: Forecast, hours: List<HourPoint>): Glance {
    val h0 = hours.firstOrNull()
    val wind = fc.current?.windMph ?: h0?.windMph
    val gust = fc.current?.gustMph ?: h0?.gustMph
    val dir = h0?.windDirDeg?.let { " from ${compass(it)}" } ?: ""
    if (wind == null) return Glance("–", "No wind data")
    val peak = hours.take(12).maxByOrNull { it.gustMph ?: 0.0 }
    val peakNote = peak?.gustMph?.takeIf { g -> g >= (gust ?: 0.0) + 6 && g >= 25 }
        ?.let { ". Gusts to ${it.roundToInt()} around ${peak.time.format(HOUR)}" } ?: ""
    return Glance(
        "${wind.roundToInt()} mph",
        (gust?.let { "gusting ${it.roundToInt()}" } ?: "steady") + dir + peakNote,
    )
}

fun uvGlance(hours: List<HourPoint>): Glance {
    val now = hours.firstOrNull()?.uv ?: 0.0
    val peak = hours.take(24).filter { it.uv != null }.maxByOrNull { it.uv ?: 0.0 }
    val peakUv = peak?.uv ?: 0.0
    return when {
        peakUv < 0.5 -> Glance("UV 0", "No UV in the next 24 h")
        now < 0.5 -> Glance("UV 0", "Next peak ${peakUv.roundToInt()} (${uvLabel(peakUv)}) at ${peak!!.time.format(HOUR)}")
        else -> Glance("UV ${now.roundToInt()}", "${uvLabel(now)}. Peak ${peakUv.roundToInt()} at ${peak!!.time.format(HOUR)}")
    }
}

/** A few plain sentences about the next day: rain, wind, UV, and any storm. */
fun plainSummary(fc: Forecast, o: RainOutlook): String {
    val now = fc.localNow()
    val hours = fc.upcoming()
    val parts = mutableListOf<String>()

    // Rain
    val current = o.events.firstOrNull { !it.start.isAfter(now) && it.end.isAfter(now) }
    val soon = soonRain(o, now)
    parts += when {
        o.rainingNow && current != null -> "Raining now, easing around ${current.end.format(HOUR)}."
        o.rainingNow -> "Raining now."
        soon != null && soon.isTrace -> "Mostly dry, with a few sprinkles possible around ${clock(soon.first)}."
        soon != null -> "Rain ${relativeTime(soon.first, now)}."
        o.events.isNotEmpty() -> {
            val e = o.events.first()
            "Rain ${dayWord(e.start, now.toLocalDate())} around ${e.start.format(HOUR)} (about ${"%.1f".format(e.totalIn)} in)."
        }
        else -> "No rain expected for the next 2 days."
    }

    // Storms
    o.thunderFirst?.let { parts += "Thunderstorms possible around ${it.format(HOUR)}." }

    // Wind
    val peak = hours.take(18).maxByOrNull { it.gustMph ?: it.windMph ?: 0.0 }
    val gust = peak?.gustMph ?: peak?.windMph ?: 0.0
    parts += when {
        gust >= 40 -> "Very windy: gusts up to ${gust.roundToInt()} mph around ${peak!!.time.format(HOUR)}."
        gust >= 28 -> "Windy: gusts up to ${gust.roundToInt()} mph around ${peak!!.time.format(HOUR)}."
        gust >= 18 -> "Breezy, gusts to ${gust.roundToInt()} mph."
        else -> "Light winds."
    }

    // UV
    val uvPeak = hours.take(24).maxByOrNull { it.uv ?: 0.0 }
    uvPeak?.uv?.takeIf { it >= 6 }?.let {
        parts += "UV gets ${uvLabel(it).lowercase(Locale.US)} (${it.roundToInt()}) around ${uvPeak.time.format(HOUR)}."
    }
    return parts.joinToString(" ")
}
