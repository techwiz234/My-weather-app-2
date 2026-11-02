package com.techwiz.myweather.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.techwiz.myweather.domain.Level

val GoColor = Color(0xFF4CD08A)
val CautionColor = Color(0xFFFFC857)
val NoGoColor = Color(0xFFFF6B6B)

val ModelColors = mapOf(
    "ECMWF" to Color(0xFFFFB74D),
    "GFS" to Color(0xFF81C784),
    "ICON" to Color(0xFFBA68C8),
)

fun Level.color(): Color = when (this) {
    Level.GO -> GoColor
    Level.CAUTION -> CautionColor
    Level.NO_GO -> NoGoColor
}

fun Level.label(): String = when (this) {
    Level.GO -> "Go"
    Level.CAUTION -> "Caution"
    Level.NO_GO -> "No-go"
}

private val AppColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00334D),
    secondary = Color(0xFF80CBC4),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF111A2B),
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF1B2740),
    onSurfaceVariant = Color(0xFF9FB0C8),
    surfaceContainer = Color(0xFF111A2B),
    surfaceContainerHigh = Color(0xFF1B2740),
    error = NoGoColor,
)

@Composable
fun MyWeatherTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, content = content)
}
