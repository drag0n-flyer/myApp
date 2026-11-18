package org.example.app.core.groups

import kotlin.test.Test
import kotlin.test.assertEquals

class GroupTicksTest {
    private val me = "acc-me"
    private val members = listOf(
        Member("acc-me", GroupRole.Owner), Member("acc-a", GroupRole.Member), Member("acc-b", GroupRole.Member),
    )

    private fun r(account: String, kind: String) = GroupReceipt(account, kind)

    @Test fun noReceiptsIsSent() {
        assertEquals(GroupTicks.State.Sent, GroupTicks.state(emptyList(), members, me))
    }

    @Test fun delivered_onlyWhenEveryOtherMemberHasIt() {
        assertEquals(GroupTicks.State.Sent, GroupTicks.state(listOf(r("acc-a", "delivered")), members, me))
        assertEquals(
            GroupTicks.State.Delivered,
            GroupTicks.state(listOf(r("acc-a", "delivered"), r("acc-b", "delivered")), members, me),
        )
    }

    @Test fun readCountsAsDelivered() {
        assertEquals(GroupTicks.State.Delivered, GroupTicks.state(listOf(r("acc-a", "read"), r("acc-b", "delivered")), members, me))
        assertEquals(GroupTicks.State.Read, GroupTicks.state(listOf(r("acc-a", "read"), r("acc-b", "read")), members, me))
    }

    @Test fun oneReaderIsNotRead() {
        assertEquals(GroupTicks.State.Sent, GroupTicks.state(listOf(r("acc-a", "read")), members, me))
    }

    @Test fun aMemberWhoLeftDoesNotHoldTheTickBack() {
        val after = members.filter { it.accountId != "acc-b" }
        assertEquals(GroupTicks.State.Read, GroupTicks.state(listOf(r("acc-a", "read")), after, me))
    }

    @Test fun aReceiptFromANonMemberOrMyselfCountsForNothing() {
        assertEquals(
            GroupTicks.State.Sent,
            GroupTicks.state(listOf(r("acc-me", "read"), r("acc-x", "read"), r("acc-a", "read")), members, me),
        )
    }

    @Test fun aGroupWithNoOtherMemberStaysSent() {
        assertEquals(GroupTicks.State.Sent, GroupTicks.state(listOf(r("acc-a", "read")), listOf(Member(me, GroupRole.Owner)), me))
        assertEquals(GroupTicks.State.Sent, GroupTicks.state(emptyList(), emptyList(), me))
    }

    @Test fun anUnknownKindCountsForNothing() {
        assertEquals(GroupTicks.State.Sent, GroupTicks.state(listOf(r("acc-a", "seen"), r("acc-b", "seen")), members, me))
    }
}
