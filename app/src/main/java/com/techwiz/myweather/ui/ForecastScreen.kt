package com.techwiz.myweather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techwiz.myweather.data.DayInfo
import com.techwiz.myweather.data.HourPoint
import com.techwiz.myweather.data.WEATHER_MODELS
import com.techwiz.myweather.domain.DEFAULT_KITES
import com.techwiz.myweather.domain.Intensity
import com.techwiz.myweather.domain.Level
import com.techwiz.myweather.domain.RainOutlook
import com.techwiz.myweather.domain.buildRainOutlook
import com.techwiz.myweather.domain.intensityOf
import com.techwiz.myweather.domain.rainConsensus
import com.techwiz.myweather.domain.relativeTime
import com.techwiz.myweather.domain.uvAdvice
import com.techwiz.myweather.domain.uvLabel
import com.techwiz.myweather.domain.weatherCodeText
import com.techwiz.myweather.data.Forecast
import com.techwiz.myweather.domain.Profile
import com.techwiz.myweather.domain.Verdict
import com.techwiz.myweather.domain.bestWindow
import com.techwiz.myweather.domain.evaluate
import com.techwiz.myweather.domain.evaluateKite
import com.techwiz.myweather.domain.windAgreement
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private val HOUR_FMT = DateTimeFormatter.ofPattern("h a", Locale.US)
private val TIME_FMT = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val DAY_FMT = DateTimeFormatter.ofPattern("EEE", Locale.US)

@Composable
fun ForecastScreen(
    state: UiState,
    onProfile: (Profile) -> Unit,
    onGroundFactor: (Double) -> Unit,
    onRefresh: () -> Unit,
    onAlertClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fc = state.forecast
    if (fc == null) {
        Box(modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
            Box(Modifier.align(Alignment.TopCenter).padding(16.dp)) {
                AlertsBanner(state.alerts, onAlertClick, showAllClear = false)
            }
            when {
                state.loading -> CircularProgressIndicator()
                state.error != null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(32.dp),
                ) {
                    Text("Couldn't load the forecast", style = MaterialTheme.typography.titleMedium)
                    Text(state.error, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onRefresh) { Text("Try again") }
                }
                else -> Text("Pick a place on the map")
            }
        }
        return
    }

    val hours = remember(fc) { fc.upcoming() }
    val settings = state.settings
    val profile = state.profile
    val verdicts: List<Verdict?> = remember(hours, profile, settings) {
        hours.map { evaluate(profile, it, settings) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { AlertsBanner(state.alerts, onAlertClick, showAllClear = true) }
        item {
            Column {
                Text(fc.place.name, style = MaterialTheme.typography.headlineMedium)
                if (fc.place.subtitle.isNotEmpty()) {
                    Text(
                        fc.place.subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Profile.entries) { p ->
                    FilterChip(
                        selected = p == profile,
                        onClick = { onProfile(p) },
                        label = { Text(p.label) },
                    )
                }
            }
        }
        item { NowCard(fc, hours) }
        item { RainCard(fc) }
        item { SummaryCard(profile, hours, verdicts) }

        if (profile == Profile.KITE) {
            item {
                KiteCard(
                    hours = hours,
                    groundFactor = settings.groundFactor,
                    settings = settings,
                    onGroundFactor = onGroundFactor,
                )
            }
        }

        item { WindCard(hours, hasComparison = fc.hasModelComparison) }

        item { SunUvCard(fc, hours) }

        item {
            SectionCard("Hour by hour") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(hours.size.coerceAtMost(48)) { i ->
                        HourCell(hours[i], verdicts[i])
                    }
                }
            }
        }

        item {
            SectionCard("7-day outlook") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    fc.days.forEach { DayRow(it) }
                }
            }
        }
        item {
            Text(
                "Forecast data: Open-Meteo (ECMWF, NOAA GFS, DWD ICON). Wind shown is at 10 m." +
                    (state.lastUpdated?.let { " Updated ${java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(it.atZone(java.time.ZoneId.systemDefault()))}." } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SummaryCard(profile: Profile, hours: List<HourPoint>, verdicts: List<Verdict?>) {
    val now = hours.firstOrNull() ?: return
    val v = verdicts.firstOrNull()
    SectionCard(if (profile == Profile.EVERYDAY) "Right now" else "${profile.label}: right now") {
        if (v != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(v.level.color()))
                Text(v.level.label(), style = MaterialTheme.typography.headlineSmall, color = v.level.color())
            }
            Text(v.reasons.joinToString("\n"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val window = bestWindow(verdicts.take(24))
            Text(
                if (window == null) "No good window in the next 24 hours"
                else "Best window: ${hours[window.first].time.format(HOUR_FMT)} – " +
                    "${hours[window.last].time.plusHours(1).format(HOUR_FMT)}",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Text(
            buildString {
                now.tempF?.let { append("${it.roundToInt()}°F") }
                now.feelsF?.let { append(" (feels ${it.roundToInt()}°)") }
                now.windMph?.let { append("  ·  wind ${it.roundToInt()} mph") }
                now.gustMph?.let { append(", gusts ${it.roundToInt()}") }
                now.uv?.let { if (it >= 1) append("  ·  UV ${it.roundToInt()}") }
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        val nextRain = hours.take(24).firstOrNull { (it.precipIn ?: 0.0) > 0.01 }
        Text(
            if (nextRain == null) "No rain expected in the next 24 hours"
            else "Rain expected around ${nextRain.time.format(HOUR_FMT)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun KiteCard(
    hours: List<HourPoint>,
    groundFactor: Double,
    settings: com.techwiz.myweather.domain.Settings,
    onGroundFactor: (Double) -> Unit,
) {
    SectionCard("Which kite? (next 24 hours)") {
        DEFAULT_KITES.forEach { kite ->
            val levels = hours.take(24).map { evaluateKite(it, kite, settings).level }
            val goHours = levels.count { it == Level.GO }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(kite.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${kite.minMph.roundToInt()}–${kite.maxMph.roundToInt()} mph · $goHours h good",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                    levels.forEach { l ->
                        Box(Modifier.weight(1f).fillMaxSize().background(l.color().copy(alpha = 0.85f)))
                    }
                }
            }
        }
        Text(
            "Strip = each of the next 24 hours: green go, yellow caution, red no-go (or dark).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column {
            Text(
                "Ground wind correction: ${(groundFactor * 100).roundToInt()}% of forecast 10 m wind",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = groundFactor.toFloat(),
                onValueChange = { onGroundFactor(it.toDouble()) },
                valueRange = 0.5f..1.2f,
            )
            Text(
                "Forecasts give wind at 10 m. Near the ground it's usually lighter, so lower this " +
                    "if your kite sits dead more often than predicted, raise it if it's overpowered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WindCard(hours: List<HourPoint>, hasComparison: Boolean) {
    SectionCard("Wind by model (next 48 hours)") {
        val agreement = windAgreement(hours.take(24))
        if (agreement != null) {
            Text(
                "Next 24 h: ${agreement.label}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else if (!hasComparison) {
            Text(
                "Model comparison unavailable right now; showing the blended forecast.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        WindChart(hours)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (hasComparison) {
                WEATHER_MODELS.forEach { (_, label) -> LegendDot(ModelColors.getValue(label), label) }
            }
            LegendDot(Color.White, "Average")
            LegendDot(Color(0xFFFF8A80), "Gust")
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WindChart(hours: List<HourPoint>) {
    val pts = hours.take(48)
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = labelColor.copy(alpha = 0.2f)
    val bandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)

    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        if (pts.size < 2) return@Canvas
        val leftPad = 32.dp.toPx()
        val topPad = 8.dp.toPx()
        val bottomPad = 4.dp.toPx()
        val w = size.width - leftPad
        val h = size.height - topPad - bottomPad

        val peak = pts.maxOf { p ->
            max(p.windMaxMph ?: p.windMph ?: 0.0, p.gustMph ?: 0.0)
        }
        val top = max(10.0, ceil(peak / 5.0) * 5.0)

        fun x(i: Int) = leftPad + w * i / (pts.size - 1)
        fun y(v: Double) = (topPad + h * (1 - v / top)).toFloat()

        // Grid + y labels
        for (step in 0..4) {
            val v = top * step / 4
            val yy = y(v)
            drawLine(gridColor, Offset(leftPad, yy), Offset(size.width, yy), strokeWidth = 1.dp.toPx())
            drawText(
                measurer, "${v.roundToInt()}",
                topLeft = Offset(0f, yy - 7.dp.toPx()),
                style = TextStyle(color = labelColor, fontSize = 10.sp),
            )
        }

        // Min–max band across models
        val band = Path()
        var started = false
        pts.forEachIndexed { i, p ->
            val v = p.windMaxMph ?: p.windMph ?: return@forEachIndexed
            if (!started) { band.moveTo(x(i), y(v)); started = true } else band.lineTo(x(i), y(v))
        }
        for (i in pts.indices.reversed()) {
            val v = pts[i].windMinMph ?: pts[i].windMph ?: continue
            band.lineTo(x(i), y(v))
        }
        if (started) {
            band.close()
            drawPath(band, bandColor)
        }

        fun line(values: List<Double?>): Path {
            val path = Path()
            var pen = false
            values.forEachIndexed { i, v ->
                if (v == null) { pen = false; return@forEachIndexed }
                if (!pen) { path.moveTo(x(i), y(v)); pen = true } else path.lineTo(x(i), y(v))
            }
            return path
        }

        // One line per model
        WEATHER_MODELS.forEach { (_, label) ->
            val values = pts.map { it.modelWind[label] }
            if (values.any { it != null }) {
                drawPath(line(values), ModelColors.getValue(label), style = Stroke(width = 1.5.dp.toPx()))
            }
        }
        // Gusts (dashed), then average on top
        drawPath(
            line(pts.map { it.gustMph }),
            Color(0xFFFF8A80),
            style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
        )
        drawPath(line(pts.map { it.windMph }), Color.White, style = Stroke(width = 3.dp.toPx()))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val first = pts.firstOrNull()
        val mid = pts.getOrNull(pts.size / 2)
        val last = pts.lastOrNull()
        listOf(first, mid, last).forEach {
            Text(
                it?.let { p -> "${p.time.format(DAY_FMT)} ${p.time.format(HOUR_FMT)}" } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
            )
        }
    }
}

@Composable
private fun HourCell(h: HourPoint, v: Verdict?) {
    Column(
        Modifier
            .width(66.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(h.time.format(HOUR_FMT), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(h.tempF?.let { "${it.roundToInt()}°" } ?: "–", style = MaterialTheme.typography.titleMedium)
        // Arrow points the way the wind is blowing (direction data is where it comes FROM).
        Text("↑", Modifier.rotate(((h.windDirDeg ?: 0.0) + 180.0).toFloat()), color = MaterialTheme.colorScheme.primary)
        Text(h.windMph?.let { "${it.roundToInt()}" } ?: "–", style = MaterialTheme.typography.titleSmall)
        Text(
            h.gustMph?.let { "g${it.roundToInt()}" } ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            h.precipProb?.let { "${it.roundToInt()}%" } ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        val amt = h.rainConsensus()
        Text(
            if (amt >= 0.005) "%.2f\"".format(amt) else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .width(34.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(v?.level?.color() ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)),
        )
    }
}

@Composable
private fun DayRow(d: DayInfo) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(d.date.format(DAY_FMT), Modifier.width(48.dp), style = MaterialTheme.typography.titleSmall)
        Text(
            "${d.tempMaxF?.roundToInt() ?: "–"}° / ${d.tempMinF?.roundToInt() ?: "–"}°",
            Modifier.width(88.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                buildString {
                    append("Rain ${"%.2f".format(d.precipSumIn ?: 0.0)} in")
                    d.uvMax?.let { append("  ·  UV ${it.roundToInt()}") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (d.sunrise != null && d.sunset != null) {
                Text(
                    "☀ ${d.sunrise.format(TIME_FMT)} – ${d.sunset.format(TIME_FMT)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---- Right now ---------------------------------------------------------------------------

@Composable
private fun NowCard(fc: Forecast, hours: List<HourPoint>) {
    val cur = fc.current
    val h0 = hours.firstOrNull()
    val temp = cur?.tempF ?: h0?.tempF
    val feels = cur?.feelsF ?: h0?.feelsF
    val code = cur?.weatherCode ?: h0?.weatherCode
    val wind = cur?.windMph ?: h0?.windMph
    val gust = cur?.gustMph ?: h0?.gustMph
    SectionCard("Right now") {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(temp?.let { "${it.roundToInt()}°" } ?: "–", style = MaterialTheme.typography.displayMedium)
            Column(Modifier.padding(bottom = 8.dp)) {
                Text(weatherCodeText(code), style = MaterialTheme.typography.titleMedium)
                feels?.let {
                    Text("Feels like ${it.roundToInt()}°F", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(
            buildString {
                wind?.let { append("Wind ${it.roundToInt()} mph") }
                gust?.let { append(", gusts ${it.roundToInt()}") }
                h0?.uv?.let { if (it >= 0.5) append("  ·  UV ${it.roundToInt()} (${uvLabel(it)})") }
                h0?.cloudPct?.let { append("  ·  clouds ${it.roundToInt()}%") }
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

// ---- Rain ---------------------------------------------------------------------------------

@Composable
private fun RainCard(fc: Forecast) {
    val o: RainOutlook = remember(fc) { buildRainOutlook(fc) }
    val now = fc.localNow()
    var showInfo by remember { mutableStateOf(false) }
    val hour0 = fc.upcoming().firstOrNull()

    SectionCard("Rain") {
        // Headline: what is happening, then what comes next.
        val nowcastNext = o.next.firstOrNull { !it.time.isBefore(now) && it.precipIn >= 0.004 }
        val headline = when {
            o.rainingNow -> "Raining now: ${o.nowIntensity.label.lowercase(Locale.US)}"
            nowcastNext != null -> "Dry now, rain ${relativeTime(nowcastNext.time, now)}"
            o.events.isNotEmpty() -> "Dry now. Next rain ${relativeTime(o.events.first().start, now)}"
            else -> "No rain expected in the next 48 hours"
        }
        Text(headline, style = MaterialTheme.typography.titleLarge)
        val sub = buildList {
            o.lastHourIn?.let { add("Past hour: ${"%.2f".format(it)} in") }
            hour0?.precipProb?.let { add("Chance this hour: ${it.roundToInt()}%") }
        }.joinToString("  ·  ")
        if (sub.isNotEmpty()) {
            Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 15-minute strip: last hour + next 4 hours.
        if (fc.minutely.isNotEmpty()) {
            NowcastBars(fc, now)
        }

        if (o.thunderFirst != null) {
            Text(
                "Thunderstorms possible ${relativeTime(o.thunderFirst, now)} (${o.thunderFirst.format(HOUR_FMT)})" +
                    (o.capeMax24?.let { " · storm energy up to ${it.roundToInt()} J/kg" } ?: ""),
                color = CautionColor,
                style = MaterialTheme.typography.titleSmall,
            )
        }

        // Amount over the next 24 h with the spread between models.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Next 24 hours", style = MaterialTheme.typography.titleSmall)
            if (o.total24Mean < 0.01) {
                Text("Essentially dry (under 0.01 in)")
            } else {
                Text(
                    "About ${"%.2f".format(o.total24Mean)} in" +
                        if (o.total24ByModel.size > 1) "  (models range ${"%.2f".format(o.total24Min)} – ${"%.2f".format(o.total24Max)})" else "",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (o.total24ByModel.size > 1) {
                    Text(
                        o.total24ByModel.entries.joinToString("  ·  ") { "${it.key} ${"%.2f".format(it.value)}\"" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val spread = o.total24Max - o.total24Min
                    if (spread > 0.25 * o.total24Mean && spread > 0.1) {
                        Text(
                            "Models disagree on the amount; treat it as a range, not a number.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CautionColor,
                        )
                    }
                }
            }
        }

        // Wet spells.
        o.events.take(4).forEach { e ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "${e.start.format(DAY_FMT)} ${e.start.format(HOUR_FMT)} – ${e.end.format(HOUR_FMT)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                val lo = e.modelTotals.values.minOrNull()
                val hi = e.modelTotals.values.maxOrNull()
                Text(
                    "${intensityOf(e.peakInPerHour).label}, about ${"%.2f".format(e.totalIn)} in" +
                        if (lo != null && hi != null && e.modelTotals.size > 1) " (${"%.2f".format(lo)} – ${"%.2f".format(hi)})" else "",
                )
                val early = e.earliestModelStart
                val late = e.latestModelStart
                if (early != null && late != null && early != late) {
                    Text(
                        "Models disagree on timing: start between ${early.format(HOUR_FMT)} and ${late.format(HOUR_FMT)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        TextButton(onClick = { showInfo = true }) { Text("What does the rain % mean?") }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Got it") } },
            title = { Text("Rain: chance vs amount vs timing") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Chance (%): of many slightly different model runs, the share that put at least a " +
                            "trace of rain (about 0.004 in) on this exact spot during that hour. 30% means " +
                            "3 in 10 runs did. It is not \"30% of the area gets wet\", and it says nothing " +
                            "about how much.",
                    )
                    Text(
                        "Amount (inches): shown separately, from three models (ECMWF, GFS, ICON). When they " +
                            "disagree you see the range instead of a made-up single number.",
                    )
                    Text(
                        "Timing: the first few hours come from a 15-minute nowcast, which is the most " +
                            "trustworthy for \"is it raining / about to rain\". Beyond about 12 hours, being " +
                            "off by a few hours is normal; the app shows when models disagree about the start.",
                    )
                    Text(
                        "Some other apps quote the US NWS \"PoP\", which blends confidence with how much of " +
                            "the area gets rain, so the same percentage can mean something different.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
        )
    }
}

@Composable
private fun NowcastBars(fc: Forecast, now: java.time.LocalDateTime) {
    val slots = fc.minutely
    val peak = max(0.03, slots.maxOf { it.precipIn })
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            slots.forEach { slot ->
                val past = slot.time.isBefore(now.minusMinutes(14))
                val frac = (slot.precipIn / peak).coerceIn(0.0, 1.0).toFloat()
                val color = when (intensityOf(slot.precipIn * 4)) {
                    Intensity.NONE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                    Intensity.TRACE, Intensity.LIGHT -> Color(0xFF64B5F6)
                    Intensity.MODERATE -> Color(0xFF3D7BFF)
                    Intensity.HEAVY -> Color(0xFFB04DFF)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height((4 + 40 * frac).dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (past) color.copy(alpha = 0.55f) else color),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1 h ago", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text("+4 h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- UV & sun -----------------------------------------------------------------------------

@Composable
private fun SunUvCard(fc: Forecast, hours: List<HourPoint>) {
    val today = fc.localNow().toLocalDate()
    val day = fc.days.firstOrNull { it.date == today } ?: fc.days.firstOrNull()
    val todays = fc.hours.filter { it.time.toLocalDate() == today && it.uv != null }
    val peak = todays.maxByOrNull { it.uv ?: 0.0 }
    val nowUv = hours.firstOrNull()?.uv
    SectionCard("UV & sun") {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column {
                Text("Now", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    nowUv?.let { "${"%.1f".format(it)} · ${uvLabel(it)}" } ?: "–",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (peak?.uv != null) {
                Column {
                    Text("Today's peak", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${"%.1f".format(peak.uv)} · ${uvLabel(peak.uv)} at ${peak.time.format(HOUR_FMT)}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        peak?.uv?.let {
            Text(uvAdvice(it), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "UV already accounts for forecast cloud cover, so cloudy hours read lower.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (day?.sunrise != null && day.sunset != null) {
            Text("Sunrise ${day.sunrise.format(TIME_FMT)}  ·  Sunset ${day.sunset.format(TIME_FMT)}")
        }
    }
}
