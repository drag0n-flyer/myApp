package org.example.app.core.chats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeliveryStatusRankTest {
    @Test fun theRankIsExplicit() {
        val order = listOf(DeliveryStatus.Sending, DeliveryStatus.Sent, DeliveryStatus.Delivered, DeliveryStatus.Read)
        assertEquals(listOf(0, 1, 2, 3), order.map { it.rank() })
        assertNull(DeliveryStatus.Failed.rank())
    }

    @Test fun aFailedMessageNeverReachesAnything() {
        for (wanted in DeliveryStatus.entries) assertFalse(DeliveryStatus.Failed.reaches(wanted), "$wanted")
        assertFalse(DeliveryStatus.Read.reaches(DeliveryStatus.Failed))
    }

    @Test fun laterStatesReachEarlierOnes() {
        assertTrue(DeliveryStatus.Read.reaches(DeliveryStatus.Delivered))
        assertTrue(DeliveryStatus.Delivered.reaches(DeliveryStatus.Delivered))
        assertFalse(DeliveryStatus.Sent.reaches(DeliveryStatus.Delivered))
    }
}
