package org.example.app.ui.chatinfo

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.example.app.core.chats.Chat
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.groups.GroupRole
import org.example.app.core.groups.Member
import org.example.app.core.people.Profile
import org.example.app.profile.LocalProfilePhotos
import org.example.app.ui.AVATAR_PHOTO_TAG
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class GroupInfoScreenTest {
    @get:Rule val rule = createComposeRule()

    private val sample = SampleChatStore()
    private val chatId = runBlocking { sample.createGroup(listOf("riya", "arjun"), "Book club", null).chatId }
    private var deleted = 0
    private var opened = mutableListOf<Pair<String, Boolean>>()

    private fun launch(store: ChatStore = sample) = rule.setContent {
        AppTheme {
            CompositionLocalProvider(LocalProfilePhotos provides store.profiles) {
                ChatInfoScreen(
                    store, chatId, onBack = {}, onCall = {}, onSearch = {}, onBlocked = {},
                    onOpenMember = { id, safety -> opened += id to safety }, onDeleted = { deleted++ },
                )
            }
        }
    }

    /** The same group seen by a plain member: Riya owns it, I am a member. */
    private fun asPlainMember(): ChatStore = object : ChatStore by sample {
        override fun chat(id: String): Chat? = sample.chat(id)?.let {
            if (it.id != chatId) it
            else it.copy(group = it.group!!.copy(members = listOf(Member("riya", GroupRole.Owner), Member("me", GroupRole.Member), Member("arjun", GroupRole.Member))))
        }
    }

    @Test
    fun listsTheNameTheCountAndEveryMemberWithTheOwnerFirst() {
        launch()
        rule.onNodeWithText("Book club").assertIsDisplayed()
        rule.onNodeWithText("3 members").assertIsDisplayed()
        rule.onNodeWithContentDescription("Your Name, Owner").assertIsDisplayed()
        rule.onNodeWithText("Riya").assertIsDisplayed()
        rule.onNodeWithText("Arjun").assertIsDisplayed()
        rule.onNodeWithText("Owner").assertIsDisplayed()
    }

    @Test
    fun aMemberRowReadsNameAndRole() {
        runBlocking { sample.setRole(chatId, "riya", GroupRole.Admin) }
        launch()
        rule.onNodeWithContentDescription("Riya, Admin").assertIsDisplayed()
        rule.onNodeWithContentDescription("Your Name, Owner").assertIsDisplayed()
        rule.onNodeWithContentDescription("Arjun").assertIsDisplayed()
    }

    @Test
    fun aPlainMemberSeesNoAddMembersAndNoEditingAndNoDisappearingControl() {
        launch(asPlainMember())
        rule.onNodeWithText("Add members").assertDoesNotExist()
        rule.onNodeWithText("Edit group name").assertDoesNotExist()
        rule.onNodeWithText("Change photo").assertDoesNotExist()
        rule.onNode(hasText("Disappearing messages") and hasClickAction()).assertDoesNotExist()
        rule.onNodeWithText("Leave group").assertIsDisplayed()
        rule.onNodeWithText("Media and files").assertIsDisplayed()
        rule.onNodeWithText("Mute").assertIsDisplayed()
    }

    @Test
    fun anOwnerSeesAddMembersAndEditingAndTheTimer() {
        launch()
        rule.onNodeWithText("Add members").assertIsDisplayed()
        rule.onNodeWithText("Edit group name").assertIsDisplayed()
        rule.onNodeWithText("Change photo").assertIsDisplayed()
        rule.onNode(hasText("Disappearing messages") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun theEncryptionTypeIsShownHereAndNowhereElseOnTheScreen() {
        launch()
        rule.onNodeWithText("End-to-end encrypted").assertIsDisplayed()
    }

    @Test
    fun leaveAsksFirstAndThenTheChatCanBeDeleted() {
        runBlocking { sample.setRole(chatId, "riya", GroupRole.Owner) }
        launch()
        rule.onNodeWithText("Leave group").performClick()
        rule.onNodeWithText("Leave this group?").assertIsDisplayed()
        assertEquals(org.example.app.core.groups.MyGroupState.Member, sample.chat(chatId)!!.group!!.myState)
        rule.onAllNodesWithText("Cancel").onFirst().performClick()
        rule.onNodeWithText("Leave this group?").assertDoesNotExist()
        assertEquals(org.example.app.core.groups.MyGroupState.Member, sample.chat(chatId)!!.group!!.myState)

        rule.onNodeWithText("Leave group").performClick()
        rule.onAllNodesWithText("Leave group").onLast().performClick()
        rule.waitForIdle()
        assertEquals(org.example.app.core.groups.MyGroupState.Left, sample.chat(chatId)!!.group!!.myState)
        rule.onNodeWithText("You're no longer in this group").assertIsDisplayed()
        rule.onNodeWithText("Leave group").assertDoesNotExist()
        rule.onNodeWithText("Add members").assertDoesNotExist()
        rule.onNodeWithText("Delete chat").performClick()
        rule.onNodeWithText("Delete this chat?").assertIsDisplayed()
        assertEquals(0, deleted)
        rule.onAllNodesWithText("Delete chat").onLast().performClick()
        rule.waitForIdle()
        assertNull(sample.chat(chatId))
        assertEquals(1, deleted)
    }

    @Test
    fun anOwnerWhoStillHasPeopleIsToldToHandOverFirst() {
        launch()
        rule.onNodeWithText("Leave group").performClick()
        rule.onNodeWithText("Choose a new owner before you leave.").assertIsDisplayed()
        rule.onNodeWithText("Leave this group?").assertDoesNotExist()
        rule.onNodeWithText("OK").performClick()
        assertEquals(org.example.app.core.groups.MyGroupState.Member, sample.chat(chatId)!!.group!!.myState)
    }

    @Test
    fun theOwnersMenuOnAMemberMakesAnAdmin() {
        launch()
        rule.onNodeWithContentDescription("Actions for Riya").performClick()
        rule.onNodeWithText("Make admin").assertIsDisplayed()
        rule.onNodeWithText("Make owner").assertIsDisplayed()
        rule.onNodeWithText("Remove from group").assertIsDisplayed()
        rule.onNodeWithText("Message").assertIsDisplayed()
        rule.onNodeWithText("View safety number").assertIsDisplayed()
        rule.onNodeWithText("Remove as admin").assertDoesNotExist()
        rule.onNodeWithText("Make admin").performClick()
        rule.waitForIdle()
        assertEquals(GroupRole.Admin, sample.chat(chatId)!!.group!!.roleOf("riya"))
        rule.onNodeWithContentDescription("Riya, Admin").assertIsDisplayed()
    }

    @Test
    fun removeAsksFirst() {
        launch()
        rule.onNodeWithContentDescription("Actions for Arjun").performClick()
        rule.onNodeWithText("Remove from group").performClick()
        rule.onNodeWithText("Remove Arjun?").assertIsDisplayed()
        assertEquals(GroupRole.Member, sample.chat(chatId)!!.group!!.roleOf("arjun"))
        rule.onAllNodesWithText("Remove from group").onLast().performClick()
        rule.waitForIdle()
        assertNull(sample.chat(chatId)!!.group!!.roleOf("arjun"))
        rule.onNodeWithText("2 members").assertIsDisplayed()
    }

    @Test
    fun makeOwnerAsksFirstBecauseItGivesTheGroupAway() {
        launch()
        rule.onNodeWithContentDescription("Actions for Riya").performClick()
        rule.onNodeWithText("Make owner").performClick()
        rule.onNodeWithText("Make Riya the owner?").assertIsDisplayed()
        assertEquals(GroupRole.Owner, sample.chat(chatId)!!.group!!.roleOf("me"))
        rule.onAllNodesWithText("Make owner").onLast().performClick()
        rule.waitForIdle()
        assertEquals(GroupRole.Owner, sample.chat(chatId)!!.group!!.roleOf("riya"))
    }

    @Test
    fun messageAndSafetyNumberOpenThatPersonsPage() {
        launch()
        rule.onNodeWithContentDescription("Actions for Riya").performClick()
        rule.onNodeWithText("Message").performClick()
        rule.onNodeWithContentDescription("Actions for Riya").performClick()
        rule.onNodeWithText("View safety number").performClick()
        assertEquals(listOf("riya" to false, "riya" to true), opened)
    }

    @Test
    fun aPlainMembersMenuHasNoRoleOrRemoveEntries() {
        launch(asPlainMember())
        rule.onNodeWithContentDescription("Actions for Arjun").performClick()
        rule.onNodeWithText("Message").assertIsDisplayed()
        rule.onNodeWithText("Remove from group").assertDoesNotExist()
        rule.onNodeWithText("Make admin").assertDoesNotExist()
    }

    @Test
    fun safetyNumberNeedsAnExistingOneToOneChat() {
        launch()
        rule.onNodeWithContentDescription("Actions for Arjun").performClick()
        rule.onNodeWithText("View safety number").assertIsDisplayed()
    }

    @Test
    fun noSafetyNumberEntryWithoutAOneToOneChatButMessageStays() {
        val noDirect = object : ChatStore by sample {
            override val chats = kotlinx.coroutines.flow.MutableStateFlow(sample.chats.value.filter { it.isGroup })
        }
        launch(noDirect)
        rule.onNodeWithContentDescription("Actions for Arjun").performClick()
        rule.onNodeWithText("Message").assertIsDisplayed()
        rule.onNodeWithText("View safety number").assertDoesNotExist()
    }

    @Test
    fun noMenuOnMyselfOrWhereNothingIsAllowed() {
        launch()
        rule.onNodeWithContentDescription("Actions for Your Name").assertDoesNotExist()
    }

    @Test
    fun addMembersListsOnlyPeopleNotInTheGroupAndAddsThePicked() {
        launch()
        rule.onNodeWithText("Add members").performClick()
        rule.onNodeWithText("Riya").assertDoesNotExist()
        val newcomer = sample.contacts.value.first { it.id !in setOf("riya", "arjun") }
        rule.onNodeWithText("Add").assertIsNotEnabled()
        rule.onNodeWithText(newcomer.displayName).performClick()
        rule.onNodeWithText("Add").performClick()
        rule.waitForIdle()
        assertEquals(GroupRole.Member, sample.chat(chatId)!!.group!!.roleOf(newcomer.id))
        rule.onNodeWithText("4 members").assertIsDisplayed()
    }

    @Test
    fun theNameFormKeepsTheNameRule() {
        var saved: String? = null
        rule.setContent { AppTheme { GroupNameForm("Book club", onSave = { saved = it }, onCancel = {}) } }
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.onNodeWithText("Group name").performTextClearance()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Group name").performTextInput("   ")
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Group name").performTextInput("  Reading ")
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.onNodeWithText("Save").performClick()
        assertEquals("Reading", saved)
    }

    @Test
    fun theNameFormNeverHoldsMoreThanFiftyCharacters() {
        rule.setContent { AppTheme { GroupNameForm("", onSave = {}, onCancel = {}) } }
        rule.onNodeWithText("Group name").performTextInput("x".repeat(80))
        rule.onNodeWithText("x".repeat(50)).assertIsDisplayed()
    }

    @Test
    fun editNameOpensTheFormAndSaveRenamesTheGroup() {
        val renames = mutableListOf<String>()
        val recording = object : ChatStore by sample {
            override suspend fun renameGroup(chatId: String, name: String) { renames += name; sample.renameGroup(chatId, name) }
        }
        launch(recording)
        rule.onNodeWithText("Edit group name").performClick()
        rule.onNodeWithText("Group name").assertIsDisplayed()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertEquals(listOf("Book club"), renames)
    }

    @Test
    fun theGroupPhotoIsDrawnOnlyAfterTheProfilePhotoRulePasses() {
        sample.putProfile(Profile(chatId, "Book club", ByteArray(30_000), 1))
        launch()
        rule.onNodeWithTag(AVATAR_PHOTO_TAG).assertDoesNotExist()
        rule.onNodeWithText("Remove photo").assertIsDisplayed() // the store has one; the tile still shows the letter
    }

    @Test
    fun muteSwitchesTheChatsMute() {
        launch()
        rule.onNodeWithText("Mute").performClick()
        rule.waitForIdle()
        assertTrue(sample.mutedChatIds.value.contains(chatId))
    }

    @Test
    fun aOneToOneChatStillGetsTheContactPage() {
        rule.setContent { AppTheme { ChatInfoScreen(sample, "chat-riya", onBack = {}, onCall = {}, onSearch = {}, onBlocked = {}) } }
        rule.onNodeWithText("Block Riya").assertIsDisplayed()
        rule.onNodeWithText("Leave group").assertDoesNotExist()
    }
}
