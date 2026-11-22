package com.turrit.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val AppColorScheme = lightColorScheme(
    primary = BrandBlue,
    onPrimary = SurfaceWhite,
    secondary = BrandBlueDark,
    background = ScreenBackground,
    onBackground = TextPrimary,
    surface = SurfaceWhite,
    onSurface = TextPrimary,
    outline = BorderSoft
)

@Composable
fun TurritAssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
