package org.example.app

import android.content.Intent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.example.app.push.MessageNotifier
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatIntentTest {
    @Test fun anIntentWithTheExtraReturnsTheId() =
        assertEquals("c1", chatIdFrom(Intent().putExtra(MessageNotifier.EXTRA_CHAT_ID, "c1")))

    @Test fun aNullIntentReturnsNull() = assertNull(chatIdFrom(null))

    @Test fun aMissingExtraReturnsNull() = assertNull(chatIdFrom(Intent()))

    @Test fun aBlankExtraReturnsNull() {
        assertNull(chatIdFrom(Intent().putExtra(MessageNotifier.EXTRA_CHAT_ID, "")))
        assertNull(chatIdFrom(Intent().putExtra(MessageNotifier.EXTRA_CHAT_ID, "   ")))
    }
}
