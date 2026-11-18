package org.example.app.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.app.core.chats.DeliveryStatus

class ChatMappingStatusTest {
    @Test fun anOutboxFailedMessageIsFailed() = assertEquals(DeliveryStatus.Failed, ChatMapping.status("failed", showRead = true))

    @Test fun theOtherStatesAreUnchanged() {
        assertEquals(DeliveryStatus.Sending, ChatMapping.status("sending", showRead = true))
        assertEquals(DeliveryStatus.Sent, ChatMapping.status("sent", showRead = true))
        assertEquals(DeliveryStatus.Delivered, ChatMapping.status("delivered", showRead = true))
        assertEquals(DeliveryStatus.Read, ChatMapping.status("read", showRead = true))
        assertEquals(DeliveryStatus.Delivered, ChatMapping.status("read", showRead = false))
    }
}
