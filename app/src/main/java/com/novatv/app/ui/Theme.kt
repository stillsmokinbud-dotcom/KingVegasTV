package com.novatv.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.novatv.app.settings.AppSettings

/** Appearance › Color theme › Accent color (TiviMate's palette). */
fun accentColor(name: String): Color = when (name) {
    "red" -> Color(0xFFF44336)
    "pink" -> Color(0xFFE91E63)
    "purple" -> Color(0xFF9C27B0)
    "indigo" -> Color(0xFF3F51B5)
    "cyan" -> Color(0xFF00BCD4)
    "teal" -> Color(0xFF009688)
    "green" -> Color(0xFF4CAF50)
    "lime" -> Color(0xFFCDDC39)
    "yellow" -> Color(0xFFFFEB3B)
    "amber" -> Color(0xFFFFC107)
    "orange" -> Color(0xFFFF9800)
    "brown" -> Color(0xFF795548)
    "grey" -> Color(0xFF9E9E9E)
    "blue_grey" -> Color(0xFF607D8B)
    "black" -> Color(0xFF212121)
    else -> Color(0xFF2196F3) // blue
}

/** Applies Settings › Appearance (background color, accent, selection color, font size). */
@Composable
fun AppTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val accent = accentColor(settings.effective("appearance.accent"))
    val scheme = when (settings.effective("appearance.theme")) {
        "black" -> darkColorScheme(primary = accent, background = Color.Black, surface = Color(0xFF101010),
            surfaceVariant = Color(0xFF1E1E1E))
        "dark_blue" -> darkColorScheme(primary = accent, background = Color(0xFF0A1628), surface = Color(0xFF10213A),
            surfaceVariant = Color(0xFF1A3050))
        "dark_grey" -> darkColorScheme(primary = accent, background = Color(0xFF26282C), surface = Color(0xFF303338),
            surfaceVariant = Color(0xFF3B3F45))
        else -> darkColorScheme(primary = accent, background = Color(0xFF15171B), surface = Color(0xFF212429),
            surfaceVariant = Color(0xFF2C3036))
    }
    val scale = when (settings.str("appearance.font")) {
        "small" -> 0.9f
        "large" -> 1.12f
        "xlarge" -> 1.25f
        else -> 1f
    }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * scale),
        LocalSelectionWhite provides (settings.effective("appearance.selection") != "accent"),
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
