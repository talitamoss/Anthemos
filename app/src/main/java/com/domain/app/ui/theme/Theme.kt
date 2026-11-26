package com.domain.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

// One look, as on anthemos.org: black, white text, glaucous for what matters
// now. No wallpaper colours and no light mode, so it stays calm and the same.
// A person's own copy swaps those three for theirs (Look); the greys are
// mixed from their background and text the way Anthemos's are from black and white.
private fun scheme(look: Look): ColorScheme {
    val quiet = lerp(look.background, look.text, 0.54f)
    val line = lerp(look.background, look.text, 0.15f)
    val raised = lerp(look.background, look.text, 0.055f)
    val onAccent = if (look.accent.luminance() > 0.35f) Color.Black else Color.White
    return darkColorScheme(
        primary = look.accent,
        onPrimary = onAccent,
        primaryContainer = raised,
        onPrimaryContainer = look.accent,
        secondary = look.accent,
        onSecondary = onAccent,
        secondaryContainer = raised,
        onSecondaryContainer = look.text,
        tertiary = look.accent,
        onTertiary = onAccent,
        background = look.background,
        onBackground = look.text,
        surface = look.background,
        onSurface = look.text,
        surfaceVariant = raised,
        onSurfaceVariant = quiet,
        surfaceContainer = raised,
        surfaceContainerLow = look.background,
        surfaceContainerHigh = raised,
        surfaceContainerHighest = raised,
        outline = line,
        outlineVariant = line,
        error = Alarm,
        onError = Ink
    )
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme(Look.current), typography = Typography, content = content)
}
