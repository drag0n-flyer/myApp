package org.example.app.ui.contacts

import androidx.compose.ui.test.junit4.createComposeRule
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
        assertEquals(listOf("Adam", "Eve", "Émile", "Zed"), order)
    }
}
