package org.example.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.core.chats.MessageContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class ExpirySweepEffectTest {
    @get:Rule val rule = createComposeRule()

    @Test fun onScreenItSweepsExpiredMessagesAndNamesTheirChatsForNotifications() {
        val past = Instant.now().minusSeconds(5)
        val store = storeWith(listOf(msg("1", "riya", MessageContent.Text("keep")), msg("2", "riya", MessageContent.Text("old")).copy(expiresAt = past)))
        val cancelled = java.util.concurrent.CopyOnWriteArrayList<String>()
        // A day and a second later, as the clock of the effect.
        rule.setContent { ExpirySweepEffect(store, now = System::currentTimeMillis) { left -> cancelled += left.keys } }
        rule.waitUntil(5_000) { cancelled.isNotEmpty() }
        assertEquals(listOf("chat-riya"), cancelled.toList())
        val texts = store.chat("chat-riya")!!.messages.mapNotNull { (it.content as? MessageContent.Text)?.text }
        assertTrue("old" !in texts && "keep" in texts, texts.toString())
    }
}
