package com.example.myvpn.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF7FF8E2),
    secondary = Color(0xFF4A635C),
    secondaryContainer = Color(0xFFCCE9DF),
    surface = Color(0xFFF4FBF7),
    surfaceVariant = Color(0xFFDBE5E0),
    background = Color(0xFFF4FBF7),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF60DBBF),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005046),
    secondary = Color(0xFFB1CCC4),
    secondaryContainer = Color(0xFF334B44),
    surface = Color(0xFF0B1F1A),
    surfaceVariant = Color(0xFF3F4945),
    background = Color(0xFF0B1F1A),
    error = Color(0xFFFFB4AB),
)

@Composable
fun MyVpnTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
