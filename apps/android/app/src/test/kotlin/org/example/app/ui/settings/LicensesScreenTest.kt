package org.example.app.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val SAMPLE = """
{"texts": {"MIT": "The MIT text.", "sha-1": "Copyright Someone"},
 "components": [
  {"name": "alpha", "version": "1.0", "license": "MIT", "authors": "A. Author", "repository": "https://example.org/alpha",
   "textIds": ["MIT", "sha-1"], "pending": false},
  {"name": "beta", "version": "2.0", "license": "BSD-3-Clause", "authors": "", "repository": null,
   "textIds": [], "pending": true}]}
"""

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class LicensesScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun parsesComponentsAndJoinsTheirTexts() {
        val book = OssNotices.parse(SAMPLE)
        assertEquals(listOf("alpha", "beta"), book.components.map { it.name })
        assertEquals("The MIT text.\n\n----\n\nCopyright Someone", book.text(book.components[0]))
        assertEquals(null, book.components[1].repository)
        assertTrue(book.components[1].pending)
    }

    @Test
    fun listsEveryComponentWithVersionAndLicense() {
        rule.setContent { AppTheme { LicensesScreen(OssNotices.parse(SAMPLE), onBack = {}) } }
        rule.onNodeWithText("alpha").assertIsDisplayed()
        rule.onNodeWithText("1.0 · MIT").assertIsDisplayed()
        rule.onNodeWithText("beta").assertIsDisplayed()
        rule.onNodeWithText("2.0 · BSD-3-Clause").assertIsDisplayed()
    }

    @Test
    fun openingAComponentShowsItsTextAndBackReturnsToTheList() {
        var left = 0
        rule.setContent { AppTheme { LicensesScreen(OssNotices.parse(SAMPLE), onBack = { left++ }) } }
        rule.onNodeWithText("alpha").performClick()
        rule.onNodeWithText("A. Author").assertIsDisplayed()
        rule.onNodeWithText("https://example.org/alpha").assertIsDisplayed()
        rule.onNodeWithText("The MIT text.", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("beta").assertIsDisplayed()
        assertEquals(0, left)
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, left)
    }

    @Test
    fun aPendingComponentSaysItsTextComesLater() {
        rule.setContent { AppTheme { LicensesScreen(OssNotices.parse(SAMPLE), onBack = {}) } }
        rule.onNodeWithText("beta").performClick()
        rule.onNodeWithText("The full license text is added with this component's first release.").assertIsDisplayed()
    }

    @Test
    fun theBundledNoticesListEveryRuntimeDependencyOfTheReleaseBuild() {
        val dependencies = File(requireNotNull(System.getProperty("oss.runtimeDependencies")) { "oss.runtimeDependencies not set" })
            .readLines().filter { it.isNotBlank() }
        assertTrue(dependencies.size > 50, "dependency list looks empty: ${dependencies.size}")
        val book = OssNotices.load(ApplicationProvider.getApplicationContext())
        val listed = book.components.map { "${it.name}:${it.version}" }.toSet()
        val missing = dependencies.filter { it !in listed }
        assertTrue(missing.isEmpty(), "missing from assets/oss-notices.json (run scripts/generate-oss-notices.py): $missing")
        for (c in book.components) {
            assertTrue(c.textIds.all { it in book.texts } , "${c.name} has an unresolved text id")
        }
        assertTrue(book.components.any { it.name == "uniffi" }, "Rust crates of libmlsffi are listed")
    }
}
