package org.example.app.core.groups

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroupInfoTest {
    private val owner = Member("o", GroupRole.Owner)
    private val admin = Member("a", GroupRole.Admin)
    private val plain = Member("m", GroupRole.Member)

    @Test
    fun membersSortOwnerThenAdminsThenMembersByName() {
        val members = listOf(
            Member("1", GroupRole.Member), Member("2", GroupRole.Admin), Member("3", GroupRole.Owner),
            Member("4", GroupRole.Member), Member("5", GroupRole.Admin),
        )
        val names = mapOf("1" to "Zed", "2" to "Mia", "3" to "Yan", "4" to "amy", "5" to "Bo")
        val sorted = MemberOrder.sorted(members, { names.getValue(it) }, Locale.ENGLISH)
        assertEquals(listOf("3", "5", "2", "4", "1"), sorted.map { it.accountId })
    }

    @Test
    fun equalNamesFallBackToTheAccountId() {
        val sorted = MemberOrder.sorted(listOf(Member("b", GroupRole.Member), Member("a", GroupRole.Member)), { "Sam" }, Locale.ENGLISH)
        assertEquals(listOf("a", "b"), sorted.map { it.accountId })
    }

    @Test
    fun theInfoFindsRolesAndNothingForAStranger() {
        val info = GroupInfo(listOf(owner, plain))
        assertEquals(GroupRole.Owner, info.roleOf("o"))
        assertNull(info.roleOf("x"))
        assertEquals(2, info.members.size)
    }

    @Test
    fun anOwnerMenuOnAMember() {
        assertEquals(
            listOf(MemberAction.Message, MemberAction.ViewSafetyNumber, MemberAction.MakeAdmin, MemberAction.MakeOwner, MemberAction.Remove),
            MemberActions.forMember(GroupRole.Owner, plain, isMe = false, canMessage = true, canVerify = true),
        )
    }

    @Test
    fun anOwnerMenuOnAnAdminOffersRemoveAsAdmin() {
        assertEquals(
            listOf(MemberAction.RemoveAdmin, MemberAction.MakeOwner, MemberAction.Remove),
            MemberActions.forMember(GroupRole.Owner, admin, isMe = false, canMessage = false, canVerify = false),
        )
    }

    @Test
    fun anAdminRemovesOnlyAMemberAndSetsNoRoles() {
        assertEquals(listOf(MemberAction.Remove), MemberActions.forMember(GroupRole.Admin, plain, false, false, false))
        assertEquals(emptyList(), MemberActions.forMember(GroupRole.Admin, admin, false, false, false))
        assertEquals(emptyList(), MemberActions.forMember(GroupRole.Admin, owner, false, false, false))
    }

    @Test
    fun aPlainMemberOnlyMessagesAndChecksNumbers() {
        assertEquals(
            listOf(MemberAction.Message, MemberAction.ViewSafetyNumber),
            MemberActions.forMember(GroupRole.Member, admin, false, canMessage = true, canVerify = true),
        )
    }

    @Test
    fun nobodyActsOnTheOwnerOrOnThemselves() {
        assertEquals(emptyList(), MemberActions.forMember(GroupRole.Owner, owner, isMe = true, canMessage = true, canVerify = true))
        assertEquals(emptyList(), MemberActions.forMember(GroupRole.Owner, Member("x", GroupRole.Owner), isMe = false, canMessage = false, canVerify = false))
    }

    @Test
    fun aPersonNoLongerInTheGroupHasNoRoleAndNoMenu() {
        assertEquals(emptyList(), MemberActions.forMember(null, plain, isMe = false, canMessage = false, canVerify = false))
    }

    @Test
    fun countsAndLimits() {
        assertEquals(99, GroupInfo(listOf(owner), myState = MyGroupState.Member).roomLeft())
        assertEquals(0, GroupInfo(List(100) { Member("p$it", GroupRole.Member) }).roomLeft())
    }
}
