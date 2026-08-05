package com.susumonitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = SusuPurple,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = SusuPurpleLight,
    onPrimaryContainer = SusuPurpleDark,
    secondary = SusuPink,
    onSecondary = SusuPurpleDark,
    secondaryContainer = SusuPinkLight,
    onSecondaryContainer = SusuPurpleDark,
    background = SusuCream,
    onBackground = SusuPurpleDark,
    surface = SusuCream,
    onSurface = SusuPurpleDark,
)

private val DarkColorScheme = darkColorScheme(
    primary = SusuPurpleLight,
    onPrimary = SusuPurpleDark,
    primaryContainer = SusuPurpleDark,
    onPrimaryContainer = SusuPurpleLight,
    secondary = SusuPinkLight,
    onSecondary = SusuPurpleDark,
    secondaryContainer = SusuPink,
    onSecondaryContainer = SusuPurpleDark,
)

/**
 * SuSuMonitor 主题：浅色为苏苏浅紫/粉，深色为紫色调暗色方案。
 */
@Composable
fun SuSuMonitorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = SuSuTypography,
        content = content,
    )
}
