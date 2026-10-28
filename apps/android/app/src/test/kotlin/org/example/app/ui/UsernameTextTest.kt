package org.example.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class UsernameTextTest {
    @get:Rule val rule = createComposeRule()

    private val lre = '‪'
    private val rle = '‫'
    private val pdf = '‬'

    private fun render(name: String, direction: LayoutDirection): String {
        var text = ""
        rule.setContent { CompositionLocalProvider(LocalLayoutDirection provides direction) { AppTheme { text = usernameText(name) } } }
        rule.waitForIdle()
        return text
    }

    @Test
    fun plainLatinNameInLtrIsLeftUntouched() {
        assertEquals("@riya", render("riya", LayoutDirection.Ltr))
    }

    @Test
    fun latinNameInRtlIsIsolatedTogetherWithTheAtSign() {
        val text = render("riya", LayoutDirection.Rtl)
        assertTrue(text.contains("@riya"), text)
        assertEquals(lre, text.first { it == lre || it == rle }, "isolated as a left-to-right run: $text")
        assertTrue(text.indexOf(lre) < text.indexOf('@'), "the at sign is inside the isolate: $text")
        assertTrue(text.indexOf(pdf) > text.indexOf("riya"), "the isolate ends after the name: $text")
    }

    @Test
    fun hebrewNameInLtrIsIsolatedTogetherWithTheAtSign() {
        val text = render("ריה", LayoutDirection.Ltr)
        assertTrue(text.indexOf(rle) in 0 until text.indexOf('@'), "the at sign is inside the isolate: $text")
        assertTrue(text.indexOf(pdf) > text.indexOf('ה'), text)
        assertFalse(text.startsWith("@"), "the formatter wraps the whole string: $text")
    }
}
