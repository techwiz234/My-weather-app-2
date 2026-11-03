package com.techwiz.myweather.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.techwiz.myweather.data.AlertsStatus
import com.techwiz.myweather.data.WeatherAlert
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ALERT_TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

private fun Instant.local(): String = ALERT_TIME.format(atZone(ZoneId.systemDefault()))

private val WarningRed = Color(0xFFD32F2F)
private val WatchOrange = Color(0xFFEF6C00)
private val AdvisoryAmber = Color(0xFFB8860B)

private fun WeatherAlert.color(): Color = when {
    isWarning -> WarningRed
    isWatch -> WatchOrange
    else -> AdvisoryAmber
}

/**
 * Official alerts strip. A failed check is shown as such: "no banner" must never be mistaken
 * for "no danger".
 */
@Composable
fun AlertsBanner(
    status: AlertsStatus,
    onClick: () -> Unit,
    showAllClear: Boolean,
    modifier: Modifier = Modifier,
) {
    when (status) {
        is AlertsStatus.Ok -> {
            val top = status.alerts.firstOrNull()
            if (top != null) {
                Column(
                    modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(top.color())
                        .clickable(onClick = onClick)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        top.event.uppercase(Locale.US),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val until = top.expires?.let { "until ${it.local()}" }.orEmpty()
                    val more = if (status.alerts.size > 1) " · +${status.alerts.size - 1} more" else ""
                    Text(
                        "$until$more · tap for details".trimStart(' ', '·'),
                        color = Color.White.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else if (showAllClear) {
                Text(
                    "No active official alerts for this spot · checked ${status.checkedAt.local()}",
                    modifier = modifier,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is AlertsStatus.Failed -> Text(
            "Couldn't check official alerts. Don't assume there are none; check weather.gov.",
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(AdvisoryAmber.copy(alpha = 0.9f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
        )

        AlertsStatus.Unsupported -> if (showAllClear) Text(
            "Official alerts: US National Weather Service only for now. Not available here.",
            modifier = modifier,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AlertsStatus.Unknown -> Unit
    }
}

@Composable
fun AlertsDialog(alerts: List<WeatherAlert>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Official alerts") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                alerts.forEach { a ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                a.event,
                                fontWeight = FontWeight.Bold,
                                color = a.color(),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        a.expires?.let {
                            Text("Until ${it.local()}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (a.area.isNotEmpty()) {
                            Text(a.area, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        a.instruction?.let {
                            Text(it.trim(), fontWeight = FontWeight.SemiBold)
                        }
                        Text(a.description.trim(), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "Source: US National Weather Service. For life-threatening weather, follow your local " +
                        "emergency instructions.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
