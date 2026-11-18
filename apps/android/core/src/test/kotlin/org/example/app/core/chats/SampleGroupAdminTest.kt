package org.example.app.core.chats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.example.app.core.groups.GroupRole
import org.example.app.core.groups.MyGroupState

class SampleGroupAdminTest {
    /** A change that is done is true; one the store refuses (a [GroupChangeFailed]) is false. */
    private fun ok(block: suspend () -> Any?): Boolean = try { runBlocking { block() }; true } catch (_: GroupChangeFailed) { false }

    private val store = SampleChatStore()
    private val ids = store.contacts.value.map { it.id }

    private fun group(): String = runBlocking { store.createGroup(ids.take(2), "Book club", null).chatId }
    /** A JPEG start with a 64x64 baseline frame header: all the profile photo rule reads. */
    private fun jpeg(): ByteArray =
        byteArrayOf(-1, -40, -1, -64, 0, 11, 8, 0, 64, 0, 64, 1, 1, 0x11, 0).copyOf(100)

    private fun roleOf(chat: String, who: String) = store.chat(chat)?.group?.roleOf(who)

    @Test
    fun aNewSampleGroupHasMeAsOwnerAndThePeopleAsMembers() {
        val chat = group()
        val info = store.chat(chat)!!.group!!
        assertEquals(3, info.members.size)
        assertEquals(GroupRole.Owner, info.roleOf(store.me.id))
        assertEquals(GroupRole.Member, info.roleOf(ids[0]))
        assertEquals(MyGroupState.Member, info.myState)
    }

    @Test
    fun aOneToOneChatHasNoGroupInfo() {
        assertNull(store.chats.value.first { !it.isGroup }.group)
    }

    @Test
    fun addMembersAddsNewPeopleOnlyAndRespectsTheLimit() = runBlocking {
        val chat = group()
        assertTrue(ok { store.addMembers(chat, listOf(ids[2])) })
        assertEquals(GroupRole.Member, roleOf(chat, ids[2]))
        assertTrue(ok { store.addMembers(chat, listOf(ids[2])) }, "already in: nothing to add")
        assertTrue(ok { store.addMembers(chat, emptyList()) })
        assertFalse(ok { store.addMembers(chat, List(100) { "x$it" }) }, "over 100")
    }

    @Test
    fun removeMemberFollowsTheRoleRules() = runBlocking {
        val chat = group()
        assertTrue(ok { store.setRole(chat, ids[0], GroupRole.Admin) })
        assertTrue(ok { store.removeMember(chat, ids[1]) })
        assertNull(roleOf(chat, ids[1]))
        assertFalse(ok { store.removeMember(chat, store.me.id) }, "the owner is not removed")
        assertTrue(ok { store.removeMember(chat, ids[0]) }, "the owner removes an admin")
    }

    @Test
    fun onlyTheOwnerSetsRolesAndMakingAnOwnerHandsOverOwnership() = runBlocking {
        val chat = group()
        assertTrue(ok { store.setRole(chat, ids[0], GroupRole.Owner) })
        assertEquals(GroupRole.Owner, roleOf(chat, ids[0]))
        assertEquals(GroupRole.Admin, roleOf(chat, store.me.id))
        assertFalse(ok { store.setRole(chat, ids[1], GroupRole.Admin) }, "no longer the owner")
    }

    @Test
    fun theOwnerMustHandOverBeforeLeaving() = runBlocking {
        val chat = group()
        assertFalse(ok { store.leaveGroup(chat) })
        assertEquals(MyGroupState.Member, store.chat(chat)!!.group!!.myState)
        assertTrue(ok { store.setRole(chat, ids[0], GroupRole.Owner) })
        assertTrue(ok { store.leaveGroup(chat) })
        val info = store.chat(chat)!!.group!!
        assertEquals(MyGroupState.Left, info.myState)
        assertNull(info.roleOf(store.me.id))
        assertFalse(ok { store.addMembers(chat, listOf(ids[2])) }, "nothing changes after leaving")
        assertFalse(ok { store.renameGroup(chat, "Other") })
    }

    @Test
    fun renameKeepsTheNameRuleAndNeedsAnAdmin() = runBlocking {
        val chat = group()
        assertTrue(ok { store.renameGroup(chat, "  Reading  ") })
        assertEquals("Reading", store.chat(chat)!!.title)
        assertFalse(ok { store.renameGroup(chat, "   ") })
        assertFalse(ok { store.renameGroup(chat, "x".repeat(51)) })
        assertEquals("Reading", store.chat(chat)!!.title)
    }

    @Test
    fun theGroupPhotoIsKeptOnlyWhenItPassesTheProfilePhotoRule() = runBlocking {
        val chat = group()
        assertFalse(ok { store.setGroupPhoto(chat, ByteArray(30_000)) })
        assertNull(store.profiles.value[chat])
        val jpeg = jpeg()
        assertTrue(ok { store.setGroupPhoto(chat, jpeg) })
        assertNotNull(store.profiles.value[chat]?.photo)
        assertTrue(ok { store.setGroupPhoto(chat, null) })
        assertNull(store.profiles.value[chat]?.photo)
    }

    @Test
    fun deleteChatRemovesOnlyAGroupThePersonIsNoLongerIn() = runBlocking {
        val chat = group()
        store.deleteChat(chat)
        assertNotNull(store.chat(chat), "still a member: kept")
        assertTrue(ok { store.setRole(chat, ids[0], GroupRole.Owner) })
        assertTrue(ok { store.leaveGroup(chat) })
        store.deleteChat(chat)
        assertNull(store.chat(chat))
        val oneToOne = store.chats.value.first { !it.isGroup }.id
        store.deleteChat(oneToOne)
        assertNotNull(store.chat(oneToOne), "a one-to-one chat is never deleted this way")
        Unit
    }
}
