package org.example.app.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormattedTextTest {
    private fun fmt(text: String) = formattedText(text, Color.Blue)

    @Test fun boldAndItalicAreStyledAndTheMarkersAreGone() {
        val s = fmt("*a* _b_")
        assertEquals("a b", s.text)
        assertEquals(FontWeight.Bold, s.spanStyles.single { it.item.fontWeight != null }.item.fontWeight)
        assertEquals(FontStyle.Italic, s.spanStyles.single { it.item.fontStyle != null }.item.fontStyle)
    }

    @Test fun monoUsesTheMonospaceFamilyAndKeepsItsInnerMarkers() {
        val s = fmt("`a*b*`")
        assertEquals("a*b*", s.text)
        assertEquals(FontFamily.Monospace, s.spanStyles.single { it.item.fontFamily != null }.item.fontFamily)
    }

    @Test fun strikeIsLineThrough() {
        assertEquals(TextDecoration.LineThrough, fmt("~a~").spanStyles.single { it.item.textDecoration != null }.item.textDecoration)
    }

    @Test fun linksStayTappableOutsideMonoOnly() {
        val s = fmt("*a* https://x.example/_b_ `https://y.example`")
        val links = s.getLinkAnnotations(0, s.length)
        assertEquals(1, links.size)
        assertEquals("https://x.example/_b_", (links.single().item as LinkAnnotation.Url).url)
        assertTrue(s.text.contains("https://x.example/_b_"))
    }

    @Test fun textWithoutMarkersHasNoStyles() {
        val s = fmt("plain 2*3*4")
        assertEquals("plain 2*3*4", s.text)
        assertTrue(s.spanStyles.isEmpty())
    }
}
