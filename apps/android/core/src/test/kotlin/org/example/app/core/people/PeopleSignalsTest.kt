package org.example.app.core.people

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.app.core.chats.Chat
import org.example.app.core.chats.ChatEncryption
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent

@OptIn(ExperimentalCoroutinesApi::class)
class PeopleSignalsTest {
    private class Memory : WitnessStore {
        val data = HashMap<String, Long>()
        override suspend fun load() = data.toMap()
        override suspend fun save(chatId: String, atMs: Long) { data[chatId] = atMs }
    }

    private class Rig(val test: TestScope, val store: WitnessStore = NoWitnessStore) {
        val channel = ScriptedSignals()
        var typingOn = true
        var presenceOn = true
        val blocked = HashSet<String>()
        var targets = listOf("riya", "sam")
        val model = PeopleSignals(
            scope = test.backgroundScope, channel = channel,
            typingEnabled = { typingOn }, presenceEnabled = { presenceOn },
            blocked = { it in blocked }, presenceTargets = { targets },
            witnesses = store, nowMs = { test.currentTime },
        )
        init { test.runCurrent() } // the collectors are running before the first signal
        val sent get() = channel.sent
        fun typing(chat: String) = model.views.value[chat]?.typing == true
        fun line(chat: String) = model.views.value[chat]?.presence
    }

    private fun rig(block: suspend TestScope.(Rig) -> Unit) = runTest { block(Rig(this)) }

    @Test fun composingSendsOnThenOffWhenSent() = rig { r ->
        r.model.composerChanged("riya", isEmpty = false)
        runCurrent()
        r.model.messageSent("riya")
        runCurrent()
        assertEquals(listOf("riya" to Signal.Typing(true), "riya" to Signal.Typing(false)), r.sent)
    }

    @Test fun idleFiveSecondsSendsOff() = rig { r ->
        r.model.composerChanged("riya", isEmpty = false)
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(listOf("riya" to Signal.Typing(true)), r.sent)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("riya" to Signal.Typing(true), "riya" to Signal.Typing(false)), r.sent)
    }

    @Test fun keepsRepeatingEveryEightSecondsWhileKeysArrive() = rig { r ->
        for (t in 0..20_000 step 2_000) {
            advanceTimeBy(2_000.toLong().takeIf { t > 0 } ?: 0)
            r.model.composerChanged("riya", isEmpty = false)
            runCurrent()
        }
        // on at 0, repeats at 8,000 and 16,000
        assertEquals(3, r.sent.count { it.second == Signal.Typing(true) })
        assertTrue(r.sent.none { it.second == Signal.Typing(false) })
    }

    @Test fun emptiedTextSendsOff() = rig { r ->
        r.model.composerChanged("riya", isEmpty = false)
        r.model.composerChanged("riya", isEmpty = true)
        runCurrent()
        assertEquals(listOf(Signal.Typing(true), Signal.Typing(false)), r.sent.map { it.second })
    }

    @Test fun typingIndicatorOffSendsNothingAndShowsNothing() = rig { r ->
        r.typingOn = false
        r.model.composerChanged("riya", isEmpty = false)
        r.model.messageSent("riya")
        r.channel.emit("riya", Signal.Typing(true))
        runCurrent()
        assertEquals(emptyList(), r.sent)
        assertEquals(false, r.typing("riya"))
        assertNull(r.model.views.value["riya"])
    }

    @Test fun incomingTypingShowsForTenSecondsThenClears() = rig { r ->
        r.channel.emit("riya", Signal.Typing(true))
        runCurrent()
        assertTrue(r.typing("riya"))
        advanceTimeBy(9_999)
        assertTrue(r.typing("riya"))
        advanceTimeBy(1)
        runCurrent()
        assertEquals(false, r.typing("riya"))
    }

    @Test fun typingOffAndAMessageClearTheLine() = rig { r ->
        r.channel.emit("riya", Signal.Typing(true))
        runCurrent()
        r.channel.emit("riya", Signal.Typing(false))
        runCurrent()
        assertEquals(false, r.typing("riya"))
        r.channel.emit("sam", Signal.Typing(true))
        runCurrent()
        assertTrue(r.typing("sam"))
        r.model.messageArrived("sam")
        assertEquals(false, r.typing("sam"))
    }

    @Test fun blockedContactIsNeverSentToNorShown() = rig { r ->
        r.blocked += "riya"
        r.model.composerChanged("riya", isEmpty = false)
        r.model.setForeground(true)
        r.channel.emit("riya", Signal.Typing(true))
        r.channel.emit("riya", Signal.Presence(true))
        runCurrent()
        assertEquals(listOf("sam" to Signal.Presence(true)), r.sent)
        assertNull(r.model.views.value["riya"])
    }

    @Test fun contactBlockedLaterDisappearsFromTheView() = rig { r ->
        r.channel.emit("riya", Signal.Typing(true))
        runCurrent()
        assertTrue(r.typing("riya"))
        r.blocked += "riya"
        r.model.refresh()
        assertNull(r.model.views.value["riya"])
    }

    @Test fun foregroundSendsOnlineNowAndEverySixtySecondsThenAwayOnLeaving() = rig { r ->
        r.model.setForeground(true)
        runCurrent()
        assertEquals(listOf("riya" to Signal.Presence(true), "sam" to Signal.Presence(true)), r.sent)
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(2, r.sent.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(4, r.sent.size)
        r.model.setForeground(false)
        runCurrent()
        assertEquals(listOf("riya" to Signal.Presence(false), "sam" to Signal.Presence(false)), r.sent.drop(4))
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(6, r.sent.size)
    }

    @Test fun presenceSwitchOffSendsNothingAndShowsNothing() = rig { r ->
        r.presenceOn = false
        r.model.setForeground(true)
        r.channel.emit("riya", Signal.Presence(true))
        runCurrent()
        r.model.setForeground(false)
        runCurrent()
        assertEquals(emptyList(), r.sent)
        assertNull(r.model.views.value["riya"])
    }

    @Test fun onlineForNinetySecondsThenLastSeen() = rig { r ->
        advanceTimeBy(1_000)
        r.channel.emit("riya", Signal.Presence(true))
        runCurrent()
        assertEquals(PresenceLine.Result.Online, r.line("riya"))
        advanceTimeBy(89_999)
        assertEquals(PresenceLine.Result.Online, r.line("riya"))
        advanceTimeBy(1)
        runCurrent()
        assertEquals(PresenceLine.Result.LastSeen(1_000), r.line("riya"))
    }

    @Test fun awayEndsOnlineAtOnceAndIsTheLastSeenTime() = rig { r ->
        r.channel.emit("riya", Signal.Presence(true))
        runCurrent()
        advanceTimeBy(5_000)
        r.channel.emit("riya", Signal.Presence(false))
        runCurrent()
        assertEquals(PresenceLine.Result.LastSeen(5_000), r.line("riya"))
    }

    @Test fun nothingWitnessedShowsNothing() = rig { r ->
        runCurrent()
        assertNull(r.model.views.value["riya"])
    }

    @Test fun lastWitnessedSurvivesARestartThroughTheStore() = runTest {
        val store = Memory()
        val first = Rig(this, store)
        advanceTimeBy(7_000)
        first.channel.emit("riya", Signal.Presence(true))
        runCurrent()
        first.channel.emit("riya", Signal.Presence(false))
        runCurrent()
        assertEquals(7_000L, store.data["riya"])
        val second = Rig(this, store)
        runCurrent()
        assertEquals(PresenceLine.Result.LastSeen(7_000), second.line("riya"))
    }

    @Test fun anIncomingMessageCountsAsWitnessedAndWatchingStartsQuietly() = runTest {
        val r = Rig(this)
        val me = "me"
        fun chat(vararg sentAt: Long) = Chat(
            "riya", "riya", ChatEncryption.EndToEnd, emptySet(), 0,
            sentAt.mapIndexed { i, t -> Message("m$i", if (i % 2 == 0) "riya" else me, Instant.ofEpochMilli(t), MessageContent.Text("x"), DeliveryStatus.Delivered, null, emptyList()) },
            isRequest = false,
        )
        val chats = MutableStateFlow(listOf(chat(100)))
        r.model.watchMessages(chats, me)
        runCurrent()
        assertNull(r.model.views.value["riya"]) // history at start is not witnessed now
        r.channel.emit("riya", Signal.Typing(true))
        runCurrent()
        advanceTimeBy(2_000)
        chats.value = listOf(chat(100, 200, 300)) // a newer message from riya
        runCurrent()
        assertEquals(false, r.typing("riya"))
        assertEquals(PresenceLine.Result.LastSeen(2_000), r.line("riya"))
    }

    @Test fun groupsAreNotInThePresenceTargets() = rig { r ->
        r.targets = listOf("riya")
        r.model.setForeground(true)
        runCurrent()
        assertEquals(listOf("riya" to Signal.Presence(true)), r.sent)
    }
}
