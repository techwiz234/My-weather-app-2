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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.techwiz.myweather.domain.Level
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
    modifier: Modifier = Modifier,
) {
    val fc = state.forecast
    if (fc == null) {
        Box(modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
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
                "Forecast data: Open-Meteo (ECMWF, NOAA GFS, DWD ICON). Wind shown is at 10 m.",
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
