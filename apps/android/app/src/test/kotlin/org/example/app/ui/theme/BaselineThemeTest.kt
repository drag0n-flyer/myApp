package org.example.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** API 29 has no dynamic colour, so the blue baseline must supply every role the components use. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BaselineThemeTest {
    @get:Rule val rule = createComposeRule()

    private fun navBarColor(dark: Boolean): Pair<Color, Color> {
        var bar = Color.Unspecified
        var container = Color.Unspecified
        rule.setContent {
            AppTheme(darkTheme = dark) {
                bar = NavigationBarDefaults.containerColor
                container = MaterialTheme.colorScheme.surfaceContainer
            }
        }
        rule.waitForIdle()
        return bar to container
    }

    @Test
    fun lightNavigationBarUsesBlueSeededSurfaceContainer() {
        val (bar, container) = navBarColor(dark = false)
        assertEquals(BaselineLight.surfaceContainer, bar)
        assertEquals(container, bar)
        assertNotEquals(lightColorScheme().surfaceContainer, bar)
    }

    @Test
    fun darkNavigationBarUsesBlueSeededSurfaceContainer() {
        val (bar, _) = navBarColor(dark = true)
        assertEquals(BaselineDark.surfaceContainer, bar)
        assertNotEquals(darkColorScheme().surfaceContainer, bar)
    }

    @Test
    fun noRoleKeepsTheDefaultPurpleTint() {
        // Every colour role the baseline sets by hand must differ from the stock M3 purple scheme, except those
        // that are the same in both by design (white/black).
        val sameByDesign = setOf("onPrimary", "onSecondary", "onTertiary", "onError", "scrim", "surfaceContainerLowest", "error", "onErrorContainer")
        for ((name, stock, baseline) in listOf(
            Triple("light", lightColorScheme(), BaselineLight),
            Triple("dark", darkColorScheme(), BaselineDark),
        ).map { Triple(it.first, roles(it.second), roles(it.third)) }) {
            val unchanged = stock.filter { (role, c) -> baseline[role] == c && role !in sameByDesign }.keys
            assertEquals(emptySet(), unchanged, "$name roles still at the stock default")
        }
    }

    private fun roles(s: androidx.compose.material3.ColorScheme): Map<String, Color> = mapOf(
        "primary" to s.primary, "onPrimary" to s.onPrimary, "primaryContainer" to s.primaryContainer,
        "onPrimaryContainer" to s.onPrimaryContainer, "inversePrimary" to s.inversePrimary,
        "secondary" to s.secondary, "onSecondary" to s.onSecondary, "secondaryContainer" to s.secondaryContainer,
        "onSecondaryContainer" to s.onSecondaryContainer, "tertiary" to s.tertiary, "onTertiary" to s.onTertiary,
        "tertiaryContainer" to s.tertiaryContainer, "onTertiaryContainer" to s.onTertiaryContainer,
        "background" to s.background, "onBackground" to s.onBackground, "surface" to s.surface,
        "onSurface" to s.onSurface, "surfaceVariant" to s.surfaceVariant, "onSurfaceVariant" to s.onSurfaceVariant,
        "surfaceTint" to s.surfaceTint, "inverseSurface" to s.inverseSurface, "inverseOnSurface" to s.inverseOnSurface,
        "error" to s.error, "onError" to s.onError, "errorContainer" to s.errorContainer,
        "onErrorContainer" to s.onErrorContainer, "outline" to s.outline, "outlineVariant" to s.outlineVariant,
        "scrim" to s.scrim, "surfaceBright" to s.surfaceBright, "surfaceDim" to s.surfaceDim,
        "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest, "surfaceContainerLow" to s.surfaceContainerLow,
        "surfaceContainerLowest" to s.surfaceContainerLowest,
        "primaryFixed" to s.primaryFixed, "primaryFixedDim" to s.primaryFixedDim,
        "onPrimaryFixed" to s.onPrimaryFixed, "onPrimaryFixedVariant" to s.onPrimaryFixedVariant,
        "secondaryFixed" to s.secondaryFixed, "secondaryFixedDim" to s.secondaryFixedDim,
        "onSecondaryFixed" to s.onSecondaryFixed, "onSecondaryFixedVariant" to s.onSecondaryFixedVariant,
        "tertiaryFixed" to s.tertiaryFixed, "tertiaryFixedDim" to s.tertiaryFixedDim,
        "onTertiaryFixed" to s.onTertiaryFixed, "onTertiaryFixedVariant" to s.onTertiaryFixedVariant,
    )
}
