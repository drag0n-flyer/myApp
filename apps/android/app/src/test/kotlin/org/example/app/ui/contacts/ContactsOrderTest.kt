package org.example.app.ui.contacts

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import java.time.Instant
import kotlin.test.assertEquals
import org.example.app.core.chats.Contact
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class ContactsOrderTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun accentedNamesSortWithTheirBaseLetter() {
        val names = listOf("Zed", "Émile", "Adam", "Eve")
        val data = SampleData.make(Instant.now()).copy(
            contacts = names.mapIndexed { i, n -> Contact("c$i", n, "u$i") },
            chats = emptyList(),
        )
        rule.setContent {
            AppTheme { ContactsScreen(SampleChatStore(data), inviteUrl = "x", onOpenChat = {}, onAddContact = {}, onInvite = {}) }
        }
        val order = names.sortedBy { rule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(listOf("Adam", "Émile", "Eve", "Zed"), order)
        rule.onAllNodesWithText("E").assertCountEquals(1)
        rule.onAllNodesWithText("É").assertCountEquals(0)
    }

    private val en = java.util.Locale.ENGLISH

    @Test
    fun sectionLetterIsTheBaseLetter() {
        assertEquals("E", sectionLetter("Émile", en))
        assertEquals("C", sectionLetter("çelik", en))
        assertEquals("A", sectionLetter("Ångström", en))
        assertEquals("E", sectionLetter("  eve", en))
        assertEquals("Д", sectionLetter("дима", java.util.Locale("ru")))
        assertEquals("한", sectionLetter("한국", java.util.Locale.KOREAN))
    }

    @Test
    fun emojiDigitsAndSymbolsGoUnderHash() {
        assertEquals("#", sectionLetter("\uD83D\uDE00 Smile", en))
        assertEquals("#", sectionLetter("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 Family", en))
        assertEquals("#", sectionLetter("1st", en))
        assertEquals("#", sectionLetter("", en))
    }

    @Test
    fun hashSectionComesLast() {
        val names = listOf("\uD83D\uDE00 Smile", "Zed", "Adam")
        val data = SampleData.make(Instant.now()).copy(contacts = names.mapIndexed { i, n -> Contact("c$i", n, "u$i") }, chats = emptyList())
        rule.setContent { AppTheme { ContactsScreen(SampleChatStore(data), inviteUrl = "x", onOpenChat = {}, onAddContact = {}, onInvite = {}) } }
        val order = names.sortedBy { rule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(listOf("Adam", "Zed", "\uD83D\uDE00 Smile"), order)
    }
}
