package org.example.app.core.chats

import kotlin.test.Test
import kotlin.test.assertEquals

class LinkFinderTest {
    private fun urls(text: String) = LinkFinder.find(text).map { text.substring(it.range) to it.url }

    @Test fun findsHttpAndWww() {
        assertEquals(
            listOf("https://a.example/x?y=1" to "https://a.example/x?y=1", "www.b.example" to "https://www.b.example"),
            urls("see https://a.example/x?y=1 and www.b.example now"),
        )
    }

    @Test fun trailingPunctuationIsNotPartOfTheLink() {
        assertEquals(listOf("https://a.example/p" to "https://a.example/p"), urls("(https://a.example/p)."))
        assertEquals(listOf("https://a.example" to "https://a.example"), urls("go to https://a.example, ok"))
    }

    @Test fun plainTextAndOtherSchemesHaveNoLinks() {
        assertEquals(emptyList(), urls("hello there, file:///etc/passwd javascript:alert(1) a.b"))
    }
}
