package org.example.app.core.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatIDTest {
    @Test fun groupIdsAreTheConversationIdWithAPrefix() {
        assertEquals("g:abc-123", ChatID.group("abc-123"))
        assertTrue(ChatID.isGroup("g:abc-123"))
        assertEquals("abc-123", ChatID.conversationID("g:abc-123"))
    }

    @Test fun aContactIdIsNeverAGroup() {
        assertFalse(ChatID.isGroup("acc-1"))
        assertNull(ChatID.conversationID("acc-1"))
        assertFalse(ChatID.isGroup(""))
        assertNull(ChatID.conversationID(""))
    }

    @Test fun thePrefixAloneIsNotAGroup() {
        assertFalse(ChatID.isGroup("g:"))
        assertNull(ChatID.conversationID("g:"))
        assertFalse(ChatID.isGroup("G:abc"))
    }

    @Test fun roundTrip() {
        for (id in listOf("a", "g:b", "00000000-0000-0000-0000-000000000000")) {
            assertEquals(id, ChatID.conversationID(ChatID.group(id)))
        }
    }
}

class GroupRoleTest {
    private val all = GroupRole.entries

    @Test fun wireNamesRoundTripAndUnknownIsNull() {
        for (r in all) assertEquals(r, GroupRole.from(r.wire))
        assertEquals(listOf("owner", "admin", "member"), all.map { it.wire })
        assertNull(GroupRole.from("root"))
        assertNull(GroupRole.from(""))
        assertNull(GroupRole.from("Owner"))
    }

    @Test fun ownerAndAdminAddAndEditInfoAndSetTimer() {
        for (r in listOf(GroupRole.Owner, GroupRole.Admin)) {
            assertTrue(r.canAdd, "$r canAdd"); assertTrue(r.canEditInfo, "$r canEditInfo"); assertTrue(r.canSetTimer, "$r canSetTimer")
        }
        assertFalse(GroupRole.Member.canAdd); assertFalse(GroupRole.Member.canEditInfo); assertFalse(GroupRole.Member.canSetTimer)
    }

    @Test fun onlyTheOwnerSetsRoles() {
        assertTrue(GroupRole.Owner.canSetRoles)
        assertFalse(GroupRole.Admin.canSetRoles)
        assertFalse(GroupRole.Member.canSetRoles)
    }

    @Test fun canRemoveEveryRolePair() {
        // (me, target) -> allowed. The owner removes an admin or a member; an admin removes only a member; a member nobody.
        val expected = mapOf(
            (GroupRole.Owner to GroupRole.Owner) to false,
            (GroupRole.Owner to GroupRole.Admin) to true,
            (GroupRole.Owner to GroupRole.Member) to true,
            (GroupRole.Admin to GroupRole.Owner) to false,
            (GroupRole.Admin to GroupRole.Admin) to false,
            (GroupRole.Admin to GroupRole.Member) to true,
            (GroupRole.Member to GroupRole.Owner) to false,
            (GroupRole.Member to GroupRole.Admin) to false,
            (GroupRole.Member to GroupRole.Member) to false,
        )
        for (me in all) for (target in all) assertEquals(expected.getValue(me to target), me.canRemove(target), "$me removes $target")
    }
}

class MemberDiffTest {
    private fun m(id: String, role: GroupRole = GroupRole.Member) = Member(id, role)

    @Test fun emptyDiffGivesNoLines() {
        val list = listOf(m("a", GroupRole.Owner), m("b"))
        assertEquals(emptyList(), MemberDiff.lines(list, list, actor = "a", me = "b"))
        assertEquals(emptyList(), MemberDiff.lines(list, list.reversed(), actor = null, me = "a"))
        assertEquals(emptyList(), MemberDiff.lines(emptyList(), emptyList(), actor = null, me = "a"))
    }

    @Test fun addRemoveAndRoleChangeInOneStepAreOrderedByAccountId() {
        val old = listOf(m("a", GroupRole.Owner), m("b"), m("c", GroupRole.Admin), m("d"))
        val new = listOf(m("a", GroupRole.Owner), m("b", GroupRole.Admin), m("c"), m("e"))
        assertEquals(
            listOf(
                SystemLine.MadeAdmin("b"),
                SystemLine.NoLongerAdmin("c"),
                SystemLine.Removed("a", "d"),
                SystemLine.Added("a", "e"),
            ),
            MemberDiff.lines(old, new, actor = "a", me = "z"),
        )
    }

    @Test fun inputOrderDoesNotChangeTheResult() {
        val old = listOf(m("a", GroupRole.Owner), m("d"), m("c"))
        val new = listOf(m("a", GroupRole.Owner), m("f"), m("e"))
        val one = MemberDiff.lines(old, new, actor = "a", me = "z")
        val two = MemberDiff.lines(old.reversed(), new.reversed(), actor = "a", me = "z")
        assertEquals(one, two)
        assertEquals(listOf("c", "d", "e", "f"), one.map { (it as? SystemLine.Added)?.who ?: (it as SystemLine.Removed).who })
    }

    @Test fun meRemovedIsYouWereRemovedWithNoActor() {
        val old = listOf(m("a", GroupRole.Owner), m("me"))
        val new = listOf(m("a", GroupRole.Owner))
        assertEquals(listOf(SystemLine.YouWereRemoved), MemberDiff.lines(old, new, actor = "a", me = "me"))
        assertEquals(listOf(SystemLine.YouWereRemoved), MemberDiff.lines(old, new, actor = null, me = "me"))
    }

    @Test fun aMemberWhoIsTheActorLeft() {
        val old = listOf(m("a", GroupRole.Owner), m("b"))
        val new = listOf(m("a", GroupRole.Owner))
        assertEquals(listOf(SystemLine.Left("b")), MemberDiff.lines(old, new, actor = "b", me = "a"))
    }

    @Test fun meLeavingIsNotYouWereRemoved() {
        val old = listOf(m("a", GroupRole.Owner), m("me"))
        val new = listOf(m("a", GroupRole.Owner))
        assertEquals(listOf(SystemLine.Left("me")), MemberDiff.lines(old, new, actor = "me", me = "me"))
    }

    @Test fun nilActorGivesTheFormsWithoutAnActor() {
        val old = listOf(m("a", GroupRole.Owner), m("b"))
        val new = listOf(m("a", GroupRole.Owner), m("c"))
        assertEquals(
            listOf(SystemLine.Removed(null, "b"), SystemLine.Added(null, "c")),
            MemberDiff.lines(old, new, actor = null, me = "a"),
        )
    }

    @Test fun addedMeIsAnAddedLine() {
        val old = listOf(m("a", GroupRole.Owner))
        val new = listOf(m("a", GroupRole.Owner), m("me"))
        assertEquals(listOf(SystemLine.Added("a", "me")), MemberDiff.lines(old, new, actor = "a", me = "me"))
    }

    @Test fun ownershipHandOverMakesTheOldOwnerAnAdminAndSaysNothingAboutTheNewOwner() {
        val old = listOf(m("a", GroupRole.Owner), m("b", GroupRole.Admin))
        val new = listOf(m("a", GroupRole.Admin), m("b", GroupRole.Owner))
        // b stops being an admin only by becoming owner: no line. a is now an admin.
        assertEquals(listOf(SystemLine.MadeAdmin("a")), MemberDiff.lines(old, new, actor = "b", me = "z"))
    }

    @Test fun aRoleChangeFromMemberToOwnerSaysNothing() {
        val old = listOf(m("a", GroupRole.Owner), m("b"))
        val new = listOf(m("a", GroupRole.Admin), m("b", GroupRole.Owner))
        assertEquals(listOf(SystemLine.MadeAdmin("a")), MemberDiff.lines(old, new, actor = "a", me = "z"))
    }

    @Test fun duplicateAccountsInAListCountOnce() {
        val old = listOf(m("a", GroupRole.Owner))
        val new = listOf(m("a", GroupRole.Owner), m("b"), m("b"))
        assertEquals(listOf(SystemLine.Added("a", "b")), MemberDiff.lines(old, new, actor = "a", me = "z"))
    }
}

class GroupNameRuleTest {
    @Test fun acceptsOneToFiftyCodePoints() {
        assertEquals("Book club", GroupNameRule.clean("Book club"))
        assertEquals("a", GroupNameRule.clean("a"))
        assertEquals("a".repeat(50), GroupNameRule.clean("a".repeat(50)))
    }

    @Test fun refusesEmptyBlankAndOverFifty() {
        assertNull(GroupNameRule.clean(""))
        assertNull(GroupNameRule.clean("   "))
        assertNull(GroupNameRule.clean("a".repeat(51)))
    }

    @Test fun countsCodePointsNotUtf16Units() {
        val fifty = "😀".repeat(50) // 100 UTF-16 units, 50 code points
        assertEquals(fifty, GroupNameRule.clean(fifty))
        assertNull(GroupNameRule.clean(fifty + "a"))
    }

    @Test fun trimsLikeADisplayName() {
        assertEquals("Team", GroupNameRule.clean("  Team  "))
    }

    @Test fun refusesWhatADisplayNameRefuses() {
        assertNull(GroupNameRule.clean("Team‮evil"))
        assertNull(GroupNameRule.clean("Line\nbreak"))
        assertNull(GroupNameRule.clean("Zero​width"))
        assertNull(GroupNameRule.clean("⠀"))
        assertEquals("Fam‍", GroupNameRule.clean("Fam‍")) // joiner kept as in a display name
    }

    @Test fun aNameOverFortyStillFailsTheDisplayRuleButPassesAsAGroupName() {
        val name = "b".repeat(45)
        assertNull(org.example.app.core.profile.DisplayNameRule.clean(name))
        assertEquals(name, GroupNameRule.clean(name))
    }
}

class GroupMergeTest {
    @Test fun aHigherVersionFromAnAdminOrOwnerIsAccepted() {
        for (role in listOf(GroupRole.Owner, GroupRole.Admin)) {
            assertTrue(GroupMerge.accept(null, 1, role), "$role first")
            assertTrue(GroupMerge.accept(3, 4, role), "$role higher")
        }
    }

    @Test fun anEqualOrLowerVersionIsRefused() {
        assertFalse(GroupMerge.accept(3, 3, GroupRole.Owner))
        assertFalse(GroupMerge.accept(3, 2, GroupRole.Owner))
    }

    @Test fun versionsBelowOneAreRefusedEvenWithNothingStored() {
        assertFalse(GroupMerge.accept(null, 0, GroupRole.Owner))
        assertFalse(GroupMerge.accept(null, -1, GroupRole.Admin))
    }

    @Test fun aPlainMemberOrAStrangerNeverChangesTheGroup() {
        assertFalse(GroupMerge.accept(null, 1, GroupRole.Member))
        assertFalse(GroupMerge.accept(1, 9, GroupRole.Member))
        assertFalse(GroupMerge.accept(null, 1, null))
    }
}
