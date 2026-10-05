package com.icecreampost.pos.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val IceCreamColorScheme = lightColorScheme(
    primary = Color(0xFF5B1E96),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF0E3FA),
    onPrimaryContainer = Color(0xFF301047),
    secondary = Color(0xFF00796B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB9F2E8),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF9B5D00),
    tertiaryContainer = Color(0xFFFFDDB5),
    background = Color(0xFFF9F7FA),
    onBackground = Color(0xFF1D1A20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D1A20),
    surfaceVariant = Color(0xFFF1EDF3),
    onSurfaceVariant = Color(0xFF666068),
    outline = Color(0xFF79717E),
    outlineVariant = Color(0xFFE8E1EA),
    error = Color(0xFFB3261E),
)

private val CoolerzTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
)

private val CoolerzShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

@Composable
fun IceCreamPosTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = IceCreamColorScheme,
    typography = CoolerzTypography,
    shapes = CoolerzShapes,
    content = content,
)
