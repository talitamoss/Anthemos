package com.domain.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.domain.app.R

// JetBrains Mono, as on anthemos.org (SIL Open Font License, assets/jetbrains-mono-OFL.txt).
val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold)
)

private fun mono(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, spacing: Double = 0.0) =
    TextStyle(fontFamily = Mono, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.sp)

// Few sizes, one face. Large only for the numbers worth reading at a glance.
val Typography = Typography(
    displayLarge = mono(44, 52),
    displayMedium = mono(36, 44),
    displaySmall = mono(30, 38),
    headlineLarge = mono(28, 36),
    headlineMedium = mono(24, 32),
    headlineSmall = mono(22, 30),
    titleLarge = mono(18, 26),
    titleMedium = mono(16, 24),
    titleSmall = mono(14, 20),
    bodyLarge = mono(15, 22),
    bodyMedium = mono(14, 20),
    bodySmall = mono(12, 18),
    labelLarge = mono(13, 18, spacing = 0.5),
    labelMedium = mono(12, 16, spacing = 0.5),
    labelSmall = mono(11, 16, spacing = 0.5)
)
