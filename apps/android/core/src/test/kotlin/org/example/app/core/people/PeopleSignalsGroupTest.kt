package org.example.app.core.people

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Typing in a group is per member; presence and "last seen" never show there. */
@OptIn(ExperimentalCoroutinesApi::class)
class PeopleSignalsGroupTest {
    private val group = "g:c1"

    private class Rig(val test: TestScope) {
        val channel = ScriptedSignals()
        val blockedAccounts = HashSet<String>()
        val model = PeopleSignals(
            scope = test.backgroundScope, channel = channel,
            typingEnabled = { true }, presenceEnabled = { true },
            blocked = { false }, presenceTargets = { listOf("g:c1", "riya") },
            blockedAccount = { it in blockedAccounts },
            nowMs = { test.currentTime },
        )
        init { test.runCurrent() }
        fun typers(chat: String) = model.views.value[chat]?.typers.orEmpty()
    }

    private fun rig(block: suspend TestScope.(Rig) -> Unit) = runTest { block(Rig(this)) }

    @Test fun eachMemberWhoTypesIsListedOldestFirst() = rig { r ->
        r.channel.emit(group, Signal.Typing(true), from = "acc-bob")
        runCurrent()
        assertEquals(listOf("acc-bob"), r.typers(group))
        assertTrue(r.model.views.value.getValue(group).typing)
        advanceTimeBy(1_000)
        r.channel.emit(group, Signal.Typing(true), from = "acc-cat")
        runCurrent()
        assertEquals(listOf("acc-bob", "acc-cat"), r.typers(group))
    }

    @Test fun oneMemberStoppingLeavesTheOther() = rig { r ->
        r.channel.emit(group, Signal.Typing(true), from = "acc-bob")
        r.channel.emit(group, Signal.Typing(true), from = "acc-cat")
        runCurrent()
        r.channel.emit(group, Signal.Typing(false), from = "acc-bob")
        runCurrent()
        assertEquals(listOf("acc-cat"), r.typers(group))
        r.channel.emit(group, Signal.Typing(false), from = "acc-cat")
        runCurrent()
        assertNull(r.model.views.value[group])
    }

    @Test fun eachMembersTypingEndsAfterTenSeconds() = rig { r ->
        r.channel.emit(group, Signal.Typing(true), from = "acc-bob")
        runCurrent()
        advanceTimeBy(6_000)
        r.channel.emit(group, Signal.Typing(true), from = "acc-cat")
        runCurrent()
        advanceTimeBy(4_001)
        runCurrent()
        assertEquals(listOf("acc-cat"), r.typers(group))
        advanceTimeBy(6_000)
        runCurrent()
        assertNull(r.model.views.value[group])
    }

    @Test fun aMessageFromAMemberEndsOnlyTheirTyping() = rig { r ->
        r.channel.emit(group, Signal.Typing(true), from = "acc-bob")
        r.channel.emit(group, Signal.Typing(true), from = "acc-cat")
        runCurrent()
        r.model.messageArrived(group, from = "acc-bob")
        assertEquals(listOf("acc-cat"), r.typers(group))
    }

    @Test fun aBlockedMemberNeverShowsAsTyping() = rig { r ->
        r.blockedAccounts += "acc-bob"
        r.channel.emit(group, Signal.Typing(true), from = "acc-bob")
        runCurrent()
        assertNull(r.model.views.value[group])
    }

    @Test fun aSignalWithNoSenderInAGroupIsIgnored() = rig { r ->
        r.channel.emit(group, Signal.Typing(true))
        runCurrent()
        assertNull(r.model.views.value[group])
    }

    @Test fun noPresenceAndNoLastSeenInAGroup() = rig { r ->
        r.channel.emit(group, Signal.Presence(true), from = "acc-bob")
        r.model.messageArrived(group, from = "acc-bob")
        runCurrent()
        assertNull(r.model.views.value[group])
        r.model.setForeground(true)
        runCurrent()
        assertTrue(r.channel.sent.none { it.first == group }, "online goes only to one-to-one chats: ${r.channel.sent}")
    }

    @Test fun myOwnTypingInAGroupIsSentLikeAnyChats() = rig { r ->
        r.model.composerChanged(group, isEmpty = false)
        runCurrent()
        r.model.messageSent(group)
        runCurrent()
        assertEquals(listOf(group to Signal.Typing(true), group to Signal.Typing(false)), r.channel.sent)
    }

    @Test fun oneToOneTypingIsAsBefore() = rig { r ->
        r.channel.emit("riya", Signal.Typing(true))
        runCurrent()
        assertEquals(emptyList(), r.typers("riya"))
        assertTrue(r.model.views.value.getValue("riya").typing)
    }
}
