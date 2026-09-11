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
    surfaceVariant = SusuPinkLight,
    onSurfaceVariant = SusuPurpleDark,
    outline = SusuPurple,
    error = StatusCritical,
    onError = androidx.compose.ui.graphics.Color.White,
)

private val DarkColorScheme = darkColorScheme(
    // 暗色下 primary 直接用白色：按钮/链接/图标文字统一为高对比白
    primary = androidx.compose.ui.graphics.Color.White,
    onPrimary = SusuPurpleDark,
    primaryContainer = SusuPurpleDark,
    onPrimaryContainer = SusuPurpleLight,
    secondary = SusuPinkLight,
    onSecondary = SusuPurpleDark,
    // 暗色语义：容器深色底 + 亮色文字（此前 secondaryContainer 误用亮粉，暗色下反色）
    secondaryContainer = SusuPurpleDark,
    onSecondaryContainer = SusuPinkLight,
    background = SusuDarkBg,
    onBackground = androidx.compose.ui.graphics.Color.White,
    // surface 略亮于 background：卡片在暗色下有 tonal 层次（M3 惯例），不再与页面背景融为一体
    surface = androidx.compose.ui.graphics.Color(0xFF241F33),
    onSurface = androidx.compose.ui.graphics.Color.White,
    surfaceVariant = SusuPurpleDark,
    onSurfaceVariant = SusuPinkLight,
    outline = SusuPurpleLight,
    error = SusuCriticalLight,
    onError = SusuPurpleDark,
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
