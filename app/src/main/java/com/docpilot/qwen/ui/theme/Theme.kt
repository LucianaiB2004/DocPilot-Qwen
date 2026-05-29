package com.docpilot.qwen.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BrandBlue = Color(0xFF0F62FE)
val BrandCyan = Color(0xFF15A5FF)
val SuccessGreen = Color(0xFF0FA968)
val WarningOrange = Color(0xFFF97316)
val DangerRed = Color(0xFFEF4444)
val Ink = Color(0xFF111827)
val Muted = Color(0xFF667085)
val SoftBlue = Color(0xFFEFF6FF)
val SurfaceWash = Color(0xFFF6F9FF)

private val DocPilotLight = lightColorScheme(
    primary = BrandBlue,
    secondary = BrandCyan,
    tertiary = SuccessGreen,
    background = SurfaceWash,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Ink,
    onSurface = Ink
)

@Composable
fun DocPilotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DocPilotLight,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}

