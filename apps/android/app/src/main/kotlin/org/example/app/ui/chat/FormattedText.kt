package org.example.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.example.app.core.chats.MessageFormat
import org.example.app.core.chats.MessageFormat.Style

/**
 * The display form of a message: markers removed, styles applied, links tappable outside mono. Only what is shown
 * changes; copy, edit, forward and search use the stored text.
 */
fun formattedText(text: String, linkColor: Color, codeBackground: Color = Color.Unspecified): AnnotatedString {
    val layout = MessageFormat.layout(text)
    return buildAnnotatedString {
        append(layout.plain)
        for (span in layout.spans) {
            val s = span.styles
            val code = Style.Mono in s || Style.Block in s
            addStyle(
                SpanStyle(
                    fontWeight = if (Style.Bold in s) FontWeight.Bold else null,
                    fontStyle = if (Style.Italic in s) FontStyle.Italic else null,
                    textDecoration = if (Style.Strike in s) TextDecoration.LineThrough else null,
                    fontFamily = if (code) FontFamily.Monospace else null,
                    background = if (code) codeBackground else Color.Unspecified,
                ),
                span.start, span.end,
            )
        }
        for (link in layout.links) {
            addLink(
                LinkAnnotation.Url(link.url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))),
                link.start, link.end,
            )
        }
    }
}

/** Message text with its formatting and its web links; a tap on a link opens the system browser. */
@Composable
fun FormattedText(text: String) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val annotated = remember(text, linkColor, codeBackground) { formattedText(text, linkColor, codeBackground) }
    Text(annotated)
}
