package com.techwiz.myweather.domain

import com.techwiz.myweather.data.HourPoint

enum class Profile(val label: String) {
    KITE("Kites"),
    DRONE("Drone / RC"),
    HIKING("Hiking"),
    BIKING("Biking"),
    OFFROAD("Offroad"),
    EVERYDAY("Everyday"),
}

enum class Level { GO, CAUTION, NO_GO }

data class Verdict(val level: Level, val reasons: List<String>)

/**
 * A kite and the wind range (mph, at flying height near the ground) it flies well in.
 * These defaults are rough starting estimates, not manufacturer data; they will become
 * editable so you can tune them to your own kites.
 */
data class KiteSpec(val name: String, val minMph: Double, val maxMph: Double)

val DEFAULT_KITES = listOf(
    KiteSpec("1 m mini kite", 3.0, 12.0),
    KiteSpec("1.5 m small delta", 4.0, 14.0),
    KiteSpec("2 m delta / diamond", 5.0, 18.0),
    KiteSpec("Dual-line stunt kite", 5.0, 20.0),
    KiteSpec("3 m+ parafoil / large delta", 7.0, 22.0),
    KiteSpec("Power kite (large)", 12.0, 30.0),
)

data class Settings(
    /**
     * Forecast models give wind at 10 m. Small kites fly lower where wind is usually lighter,
     * so the 10 m value is scaled by this factor before comparing against a kite's range.
     */
    val groundFactor: Double = 0.8,
    val droneMaxWindMph: Double = 20.0,
    val droneMaxGustMph: Double = 25.0,
)

private fun fmt(v: Double) = "%.0f".format(v)

fun evaluateKite(h: HourPoint, kite: KiteSpec, s: Settings): Verdict {
    val raw = h.windMph ?: return Verdict(Level.NO_GO, listOf("No wind data"))
    val wind = raw * s.groundFactor
    val gust = (h.gustMph ?: raw) * s.groundFactor
    val reasons = mutableListOf<String>()
    var level = Level.GO

    fun worsen(to: Level, reason: String) {
        reasons += reason
        if (to.ordinal > level.ordinal) level = to
    }

    when {
        wind < kite.minMph ->
            worsen(Level.NO_GO, "Too light: ~${fmt(wind)} mph (needs ${fmt(kite.minMph)}+)")
        wind > kite.maxMph * 1.15 ->
            worsen(Level.NO_GO, "Too strong: ~${fmt(wind)} mph (max ${fmt(kite.maxMph)})")
        wind > kite.maxMph ->
            worsen(Level.CAUTION, "At the upper limit: ~${fmt(wind)} mph")
        wind < kite.minMph * 1.25 ->
            worsen(Level.CAUTION, "Marginal lift: ~${fmt(wind)} mph")
    }
    if (level != Level.NO_GO && gust > kite.maxMph * 1.25) {
        worsen(Level.CAUTION, "Gusts to ~${fmt(gust)} mph")
    }
    if ((h.precipIn ?: 0.0) > 0.02) worsen(Level.CAUTION, "Rain")
    if (!h.isDay) worsen(Level.NO_GO, "After dark")
    if (reasons.isEmpty()) reasons += "Good: ~${fmt(wind)} mph, gusts ~${fmt(gust)}"
    return Verdict(level, reasons)
}

/** Returns null for [Profile.EVERYDAY], which has no go/no-go idea. */
fun evaluate(profile: Profile, h: HourPoint, s: Settings): Verdict? {
    val reasons = mutableListOf<String>()
    var level = Level.GO
    fun worsen(to: Level, reason: String) {
        reasons += reason
        if (to.ordinal > level.ordinal) level = to
    }

    val wind = h.windMph ?: 0.0
    val gust = h.gustMph ?: wind
    val rain = h.precipIn ?: 0.0
    val feels = h.feelsF ?: h.tempF

    when (profile) {
        Profile.KITE -> {
            // Overall kite verdict = best result across the kite list.
            return DEFAULT_KITES.map { evaluateKite(h, it, s) }.minByOrNull { it.level.ordinal }
        }

        Profile.DRONE -> {
            if (!h.isDay) worsen(Level.CAUTION, "Dark: needs lights / night rules")
            if (rain > 0.01) worsen(Level.NO_GO, "Rain")
            else if ((h.precipProb ?: 0.0) >= 50) worsen(Level.CAUTION, "${fmt(h.precipProb!!)}% chance of rain")
            if (wind > s.droneMaxWindMph || gust > s.droneMaxGustMph)
                worsen(Level.NO_GO, "Wind ${fmt(wind)} mph, gusts ${fmt(gust)}")
            else if (wind > s.droneMaxWindMph * 0.75 || gust > s.droneMaxGustMph * 0.75)
                worsen(Level.CAUTION, "Breezy: ${fmt(wind)} mph, gusts ${fmt(gust)}")
            h.wind120?.let {
                if (it > s.droneMaxWindMph) worsen(Level.CAUTION, "Strong wind aloft at 120 m: ${fmt(it)} mph")
            }
            h.visibilityMi?.let {
                if (it < 1.0) worsen(Level.NO_GO, "Visibility under 1 mi")
                else if (it < 3.0) worsen(Level.CAUTION, "Low visibility: ${"%.1f".format(it)} mi")
            }
            h.tempF?.let {
                if (it < 14) worsen(Level.NO_GO, "Very cold: batteries will sag")
                else if (it < 32) worsen(Level.CAUTION, "Freezing: reduced battery life")
                else if (it > 105) worsen(Level.CAUTION, "Very hot: motor/battery heat")
            }
        }

        Profile.HIKING -> {
            if (rain > 0.1) worsen(Level.NO_GO, "Heavy rain")
            else if (rain > 0.02) worsen(Level.CAUTION, "Rain")
            if (wind > 30 || gust > 40) worsen(Level.NO_GO, "Dangerous wind")
            else if (wind > 20) worsen(Level.CAUTION, "Windy: ${fmt(wind)} mph")
            feels?.let {
                if (it < 20 || it > 100) worsen(Level.NO_GO, "Extreme feels-like: ${fmt(it)}°F")
                else if (it < 35 || it > 90) worsen(Level.CAUTION, "Feels like ${fmt(it)}°F")
            }
            h.uv?.let { if (it >= 8) worsen(Level.CAUTION, "UV ${"%.0f".format(it)}: very high") }
            if (!h.isDay) worsen(Level.CAUTION, "After dark")
        }

        Profile.BIKING -> {
            if (rain > 0.05) worsen(Level.NO_GO, "Rain")
            else if (rain > 0.01) worsen(Level.CAUTION, "Light rain")
            if (wind > 25 || gust > 35) worsen(Level.NO_GO, "Strong wind")
            else if (wind > 15) worsen(Level.CAUTION, "Windy: ${fmt(wind)} mph")
            feels?.let {
                if (it < 25 || it > 100) worsen(Level.NO_GO, "Extreme feels-like: ${fmt(it)}°F")
                else if (it < 40 || it > 90) worsen(Level.CAUTION, "Feels like ${fmt(it)}°F")
            }
            if (!h.isDay) worsen(Level.CAUTION, "After dark")
        }

        Profile.OFFROAD -> {
            if (rain > 0.1) worsen(Level.CAUTION, "Heavy rain: expect mud / washouts")
            else if (rain > 0.02) worsen(Level.CAUTION, "Rain: trails may be slick")
            if ((h.snowIn ?: 0.0) > 0.0) worsen(Level.CAUTION, "Snowfall")
            h.tempF?.let { if (it < 28) worsen(Level.CAUTION, "Icy conditions possible") }
            if (gust > 40) worsen(Level.CAUTION, "Gusts ${fmt(gust)} mph")
            if (!h.isDay) worsen(Level.CAUTION, "After dark")
        }

        Profile.EVERYDAY -> return null
    }

    if (reasons.isEmpty()) reasons += "Conditions look good"
    return Verdict(level, reasons)
}

/** Longest run of GO hours in [verdicts], as an index range, or null if there is none. */
fun bestWindow(verdicts: List<Verdict?>): IntRange? {
    var best: IntRange? = null
    var start = -1
    for (i in 0..verdicts.size) {
        val go = i < verdicts.size && verdicts[i]?.level == Level.GO
        if (go && start < 0) start = i
        if (!go && start >= 0) {
            val run = start until i
            if (best == null || run.count() > best.count()) best = run
            start = -1
        }
    }
    return best
}

enum class Agreement(val label: String) {
    HIGH("Models agree"),
    MEDIUM("Some disagreement"),
    LOW("Models disagree"),
}

/**
 * How closely the weather models agree on wind over the given hours, from the average
 * gap between the highest and lowest model. This is a simple spread heuristic: it says how
 * much the models disagree, not which one will be right.
 */
fun windAgreement(hours: List<HourPoint>): Agreement? {
    val spreads = hours.mapNotNull {
        val lo = it.windMinMph
        val hi = it.windMaxMph
        if (lo != null && hi != null && it.modelWind.size > 1) hi - lo else null
    }
    if (spreads.isEmpty()) return null
    val avg = spreads.average()
    return when {
        avg <= 2.5 -> Agreement.HIGH
        avg <= 5.0 -> Agreement.MEDIUM
        else -> Agreement.LOW
    }
}
