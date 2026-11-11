package com.monochrome.annas.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF0A0A0A)
val Paper = Color(0xFFFFFFFF)
val Gray900 = Color(0xFF101010)
val Gray800 = Color(0xFF1C1C1C)
val Gray700 = Color(0xFF2A2A2A)
val Gray600 = Color(0xFF3A3A3A)
val Gray500 = Color(0xFF6B6B6B)
val Gray400 = Color(0xFF9A9A9A)
val Gray300 = Color(0xFFC7C7C7)
val Gray200 = Color(0xFFDCDCDC)
val Gray100 = Color(0xFFEAEAEA)
val Gray50 = Color(0xFFF5F5F5)

val LightColors: ColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    primaryContainer = Gray50,
    onPrimaryContainer = Ink,
    secondary = Gray600,
    onSecondary = Paper,
    secondaryContainer = Gray100,
    onSecondaryContainer = Ink,
    tertiary = Gray500,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Gray50,
    onSurfaceVariant = Gray600,
    surfaceTint = Ink,
    outline = Gray200,
    outlineVariant = Gray100,
    error = Gray700,
    onError = Paper,
)

val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFF5F5F5),
    onPrimary = Color(0xFF0A0A0A),
    primaryContainer = Gray900,
    onPrimaryContainer = Color(0xFFF5F5F5),
    secondary = Gray400,
    onSecondary = Color(0xFF0A0A0A),
    secondaryContainer = Gray800,
    onSecondaryContainer = Color(0xFFF5F5F5),
    tertiary = Gray500,
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Gray900,
    onSurfaceVariant = Gray400,
    surfaceTint = Color(0xFFF5F5F5),
    outline = Gray700,
    outlineVariant = Gray800,
    error = Gray300,
    onError = Color(0xFF0A0A0A),
)

val MonoTypography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp),
    displayMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 1.4.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 1.6.sp),
)

@Composable
fun AnnasTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = MonoTypography,
        content = content,
    )
}
