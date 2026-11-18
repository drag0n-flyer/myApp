package org.example.app.core.chats

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatListOrderTest {
    private fun at(n: Long) = Instant.ofEpochSecond(1_000_000 + n)

    private fun chat(
        id: String,
        lastAt: Long? = null,
        pinnedAt: Long? = null,
        unread: Int = 0,
        muted: Boolean = false,
        archived: Boolean = false,
    ) = Chat(
        id, "c-$id", ChatEncryption.EndToEnd, emptySet(), unread,
        messages = if (lastAt == null) emptyList() else listOf(Message("m-$id", "c-$id", at(lastAt), MessageContent.Text("hi"), DeliveryStatus.Delivered, null, emptyList())),
        isRequest = false,
        pinnedAt = pinnedAt?.let(::at), muted = muted, archived = archived,
    )

    @Test fun maxPinsIsFive() = assertEquals(5, ChatListOrder.MAX_PINS)

    @Test fun pinnedComeFirstNewestPinFirstThenByLastMessage() {
        val list = listOf(
            chat("old", lastAt = 1),
            chat("pinEarly", lastAt = 2, pinnedAt = 10),
            chat("new", lastAt = 50),
            chat("pinLate", lastAt = 1, pinnedAt = 20),
        )
        assertEquals(listOf("pinLate", "pinEarly", "new", "old"), ChatListOrder.sorted(list).map { it.id })
    }

    @Test fun aChatWithNoMessagesSortsLastAmongUnpinned() {
        val list = listOf(chat("empty"), chat("a", lastAt = 1))
        assertEquals(listOf("a", "empty"), ChatListOrder.sorted(list).map { it.id })
    }

    @Test fun equalTimesKeepAStableOrderById() {
        val list = listOf(chat("b", lastAt = 5), chat("a", lastAt = 5))
        assertEquals(listOf("a", "b"), ChatListOrder.sorted(list).map { it.id })
    }

    @Test fun mainListHidesArchivedAndArchiveShowsOnlyArchived() {
        val list = listOf(chat("a"), chat("b", archived = true), chat("c"))
        assertEquals(listOf("a", "c"), ChatListOrder.visible(list, archive = false).map { it.id })
        assertEquals(listOf("b"), ChatListOrder.visible(list, archive = true).map { it.id })
    }

    @Test fun badgeCountsUnreadUnmutedUnarchivedChats() {
        val list = listOf(
            chat("unread", unread = 3),
            chat("another", unread = 1),
            chat("read"),
            chat("muted", unread = 4, muted = true),
            chat("archived", unread = 2, archived = true),
        )
        assertEquals(2, ChatListOrder.badgeCount(list))
    }
}
