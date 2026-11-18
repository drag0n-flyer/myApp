package org.example.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import org.example.app.core.profile.AvatarColor

/**
 * The 8 letter-tile colours (index = `AvatarColor.index(accountId)`), one set per theme. The letter on them is always
 * white; every token has a contrast ratio of at least 4.5 to 1 against white (AvatarTilesTest checks all 16).
 * Hues in order: red, orange, olive, green, teal, blue, violet, magenta.
 */
object AvatarTiles {
    val onTile = Color.White

    val light = listOf(
        Color(0xFFB3261E), Color(0xFFA14A00), Color(0xFF5C6B00), Color(0xFF1B6E3A),
        Color(0xFF00696B), Color(0xFF1A5FB4), Color(0xFF5B4BB8), Color(0xFFA8327A),
    )

    val dark = listOf(
        Color(0xFFA8453F), Color(0xFF9B5A1A), Color(0xFF6B7A1F), Color(0xFF2F7D4A),
        Color(0xFF0F7578), Color(0xFF3F6FB5), Color(0xFF7566C9), Color(0xFFB04A8E),
    )

    /** The tile for the person with [colorKey] (their account ID); null (the neutral tile) when there is no key. */
    fun tileFor(colorKey: String?, darkTheme: Boolean): Color? =
        if (colorKey.isNullOrEmpty()) null else (if (darkTheme) dark else light)[AvatarColor.index(colorKey)]
}

/** The theme [AppTheme] was asked for; null outside it, where the system setting decides. */
internal val LocalDarkTheme = staticCompositionLocalOf<Boolean?> { null }

@Composable
internal fun isDarkTheme(): Boolean = LocalDarkTheme.current ?: isSystemInDarkTheme()
