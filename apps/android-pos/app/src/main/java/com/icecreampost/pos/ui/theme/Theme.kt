package com.icecreampost.pos.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val IceCreamColorScheme = lightColorScheme(
    primary = Color(0xFF5E1FA3),
    onPrimary = Color.White,
    secondary = Color(0xFF7B35C7),
    tertiary = Color(0xFFFFB52E),
    background = Color(0xFFFFFBFF),
    surface = Color(0xFFFFFBFF),
)

@Composable
fun IceCreamPosTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = IceCreamColorScheme, content = content)
}
