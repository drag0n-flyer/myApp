package org.example.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Baseline scheme from a blue seed (#1A73E8), used on Android 10 and 11 where dynamic colour does not exist. */
internal val BaselineLight = lightColorScheme(
    primary = Color(0xFF1A5FB4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF555F71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E3F8),
    onSecondaryContainer = Color(0xFF121C2B),
    tertiary = Color(0xFF6F5675),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF8D8FD),
    onTertiaryContainer = Color(0xFF28132F),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAF9FD),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFFAF9FD),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    outline = Color(0xFF74777F),
)

internal val BaselineDark = darkColorScheme(
    primary = Color(0xFFABC7FF),
    onPrimary = Color(0xFF002F66),
    primaryContainer = Color(0xFF00458F),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBDC7DC),
    onSecondary = Color(0xFF273141),
    secondaryContainer = Color(0xFF3D4758),
    onSecondaryContainer = Color(0xFFD9E3F8),
    tertiary = Color(0xFFDBBCE1),
    onTertiary = Color(0xFF3F2846),
    tertiaryContainer = Color(0xFF573E5D),
    onTertiaryContainer = Color(0xFFF8D8FD),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121317),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF121317),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
)
