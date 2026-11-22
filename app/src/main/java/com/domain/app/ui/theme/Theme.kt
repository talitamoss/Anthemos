package com.domain.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// One look, as on anthemos.org: black, white text, glaucous for what matters
// now. No wallpaper colours and no light mode, so it stays calm and the same.
private val Anthemos = darkColorScheme(
    primary = Glaucous,
    onPrimary = Ink,
    primaryContainer = Raised,
    onPrimaryContainer = Glaucous,
    secondary = Glaucous,
    onSecondary = Ink,
    secondaryContainer = Raised,
    onSecondaryContainer = Paper,
    tertiary = Glaucous,
    onTertiary = Ink,
    background = Ink,
    onBackground = Paper,
    surface = Ink,
    onSurface = Paper,
    surfaceVariant = Raised,
    onSurfaceVariant = Quiet,
    surfaceContainer = Raised,
    surfaceContainerLow = Ink,
    surfaceContainerHigh = Raised,
    surfaceContainerHighest = Raised,
    outline = Line,
    outlineVariant = Line,
    error = Alarm,
    onError = Ink
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Anthemos, typography = Typography, content = content)
}
