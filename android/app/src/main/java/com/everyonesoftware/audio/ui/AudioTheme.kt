package com.everyonesoftware.audio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val AudioColors = darkColorScheme(
    primary = Color(0xFFC0D7AE), onPrimary = Color(0xFF24341F),
    primaryContainer = Color(0xFF323E35), onPrimaryContainer = Color(0xFFEDF0E9),
    secondary = Color(0xFFAAB4A9), onSecondary = Color(0xFF1C211F),
    background = Color(0xFF1C211F), onBackground = Color(0xFFEDF0E9),
    surface = Color(0xFF262E29), onSurface = Color(0xFFEDF0E9),
    surfaceContainerHigh = Color(0xFF354239), outlineVariant = Color(0xFF4B5A4E),
    surfaceVariant = Color(0xFF323E35), onSurfaceVariant = Color(0xFFAAB4A9),
    outline = Color(0xFF3A443D), error = Color(0xFFEFB5A3), onError = Color(0xFF352019),
)

private val AudioTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 40.sp, lineHeight = 45.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 27.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
)

/** Native system font and scalable type, with the approved warm-charcoal palette. */
@Composable
fun AudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AudioColors, typography = AudioTypography, content = content)
}
