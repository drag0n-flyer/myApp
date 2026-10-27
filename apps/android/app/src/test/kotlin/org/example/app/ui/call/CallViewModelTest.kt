package org.example.app.ui.call

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.calls.SimulatedCallEngine
import org.example.app.core.chats.CallSummary
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import org.example.app.core.settings.CallSettings
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.ServerPolicy

class CallViewModelTest {
    private val server = ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, "home.example.org")
    private val settings = CallSettings()
    private val store = SampleChatStore(SampleData.make(Instant.now()))
    private val engine = SimulatedCallEngine(server, settings, NetworkType.Wifi)
    private val vm = CallViewModel(engine, store, server, settings, simulator = engine)

    private fun summaries() = store.chat("chat-riya")!!.messages.map { it.content }.filterIsInstance<MessageContent.Call>().map { it.summary }

    @Test
    fun aDeclinedCallAfterAnEarlierCallIsLogged() {
        val before = summaries().size
        vm.start("riya", false)
        vm.advance(5.0)
        vm.hangUp()
        assertEquals(before + 1, summaries().size)
        vm.dismissEnded()

        engine.simulateIncoming("riya", video = false)
        vm.decline()

        val after = summaries()
        assertEquals(before + 2, after.size)
        assertEquals(CallSummary.MissedAudio, after.last())
    }
}
