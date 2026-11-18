package org.example.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvatarTilesTest {
    /** WCAG 2.x relative luminance of an sRGB colour, computed here and not with Compose's own helper. */
    private fun relativeLuminance(c: Color): Double {
        fun lin(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private fun contrastWithWhite(c: Color) = 1.05 / (relativeLuminance(c) + 0.05)

    @Test fun eightTilesPerTheme() {
        assertEquals(8, AvatarTiles.light.size)
        assertEquals(8, AvatarTiles.dark.size)
        assertEquals(Color.White, AvatarTiles.onTile)
    }

    @Test fun whiteLetterHasAtLeast4_5ToOneOnAllSixteen() {
        for ((name, tiles) in listOf("light" to AvatarTiles.light, "dark" to AvatarTiles.dark)) {
            tiles.forEachIndexed { i, c ->
                assertTrue(contrastWithWhite(c) >= 4.5, "$name tile $i contrast ${contrastWithWhite(c)}")
            }
        }
    }

    @Test fun tilesWithinAThemeAreDistinct() {
        assertEquals(8, AvatarTiles.light.toSet().size)
        assertEquals(8, AvatarTiles.dark.toSet().size)
    }

    @Test fun tileFollowsTheAccountIdAndTheTheme() {
        // Indexes pinned in AvatarColorTest: "a" -> 4, "acc-ana" -> 5.
        assertEquals(AvatarTiles.light[4], AvatarTiles.tileFor("a", darkTheme = false))
        assertEquals(AvatarTiles.dark[4], AvatarTiles.tileFor("a", darkTheme = true))
        assertEquals(AvatarTiles.light[5], AvatarTiles.tileFor("acc-ana", darkTheme = false))
    }

    @Test fun noKeyKeepsTheNeutralTile() {
        assertNull(AvatarTiles.tileFor(null, darkTheme = false))
        assertNull(AvatarTiles.tileFor(null, darkTheme = true))
        assertNull(AvatarTiles.tileFor("", darkTheme = false))
    }
}
