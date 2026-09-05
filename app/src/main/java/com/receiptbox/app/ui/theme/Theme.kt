package com.receiptbox.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = TealPrimary,
    onPrimary = Color.White,
    primaryContainer = TealLight,
    onPrimaryContainer = Ink,
    secondary = AmberAccent,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = CardSurface,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Muted
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF2DD4BF),
    onPrimary = Color(0xFF003732),
    primaryContainer = TealDark,
    onPrimaryContainer = TealLight,
    secondary = AmberAccent,
    onSecondary = Color(0xFF3B2F00),
    background = Color(0xFF0F172A),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF1E293B),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFF94A3B8)
)

@Composable
fun ReceiptBoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
