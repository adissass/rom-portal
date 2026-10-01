package com.romportal.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val HostControlMinHeight = 56.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF24272B),
    onPrimary = Color(0xFFF6F5F2),
    background = Color(0xFFF6F5F2),
    onBackground = Color(0xFF24272B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF24272B),
    outline = Color(0xFF797E84),
    error = Color(0xFFA3343C)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF6F5F2),
    onPrimary = Color(0xFF24272B),
    background = Color(0xFF24272B),
    onBackground = Color(0xFFF6F5F2),
    surface = Color(0xFF2E3237),
    onSurface = Color(0xFFF6F5F2),
    outline = Color(0xFF92979D),
    error = Color(0xFFF1ADB3)
)

private val RomPortalTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
)

@Composable
internal fun RomPortalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = RomPortalTypography,
        content = content
    )
}
