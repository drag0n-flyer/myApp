package org.example.app.calls.android

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.example.app.calls.policy.IceState
import org.example.app.calls.session.CallContext
import org.example.app.calls.session.CallEffect
import org.example.app.calls.session.CallEvent
import org.example.app.calls.session.CallMachine
import org.example.app.calls.session.CallServerEvent
import org.example.app.calls.session.Clock
import org.example.app.calls.session.MediaEvent
import org.example.app.contracts.CallSealException
import org.example.app.contracts.CallSignalPayload
import org.example.app.contracts.CallSignalPayload.Kind
import org.example.app.contracts.CallSignalSealer
import org.example.app.contracts.DataMode
import org.example.app.contracts.OpenedCallSignal
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.Contact
import org.example.app.core.settings.CallSettings
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.ServerPolicy
import org.example.app.core.settings.UserSettings
import org.junit.Test
import org.example.app.core.calls.DataMode as CoreMode

/** Two machines in an active call; the engine's network-change effect handling sits between them. */
class HandoverTest {
    private val json = Json { explicitNulls = false }
    private val server = ServerPolicy(
        defaultPreference = ModePreference.Auto, maximum = CoreMode.High,
        defaultIPHiding = IPHiding.NonContacts, requiresInvite = true, host = "h",
    )
    private fun ctx(network: NetworkType) = CallContext(
        server, UserSettings(call = CallSettings(mobileDataLimit = CoreMode.DataSaver), ipHiding = IPHiding.NonContacts), network, true,
    )
    private fun sdp(f: String) = "v=0\r\na=fingerprint:$f\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
    private val calleeFp = "sha-256 11:22:33"
    private val callerFp = "sha-256 AB:CD:EF"
    private val alice = Contact("alice", "Alice", "alice")
    private val bob = Contact("bob", "Bob", "bob")
    private val clock = object : Clock { override fun nowMs() = 0L }

    private inner class Sealer(val me: String) : CallSignalSealer {
        override suspend fun seal(payload: CallSignalPayload, to: String) = bytes(me, payload)
        override suspend fun open(data: ByteArray, from: String): OpenedCallSignal {
            val text = data.decodeToString()
            if (text.substringBefore('|') != from) throw CallSealException.WrongSender
            return OpenedCallSignal(json.decodeFromString(CallSignalPayload.serializer(), text.substringAfter('|')), null)
        }
    }
    private fun bytes(me: String, p: CallSignalPayload) = "$me|${json.encodeToString(CallSignalPayload.serializer(), p)}".encodeToByteArray()

    private lateinit var caller: CallMachine
    private lateinit var callee: CallMachine

    private fun active() = runBlocking {
        caller = CallMachine(ctx(NetworkType.Wifi), Sealer("alice"), clock)
        callee = CallMachine(ctx(NetworkType.Wifi), Sealer("bob"), clock)
        caller.handle(CallEvent.Start(bob, false, "c1"))
        callee.handle(CallEvent.Incoming("c1", alice, "dA", CallSignalPayload(Kind.ring, "c1", video = false)))
        callee.handle(CallEvent.LocalOffer(sdp(calleeFp)))
        callee.handle(CallEvent.Answer(false))
        val offer = CallSignalPayload(Kind.offer, "c1", sdp = sdp(calleeFp), dtlsFingerprint = calleeFp, ceiling = DataMode.High)
        caller.ingest(CallServerEvent.Answered("c1", "d1", bytes("bob", offer)))
        caller.handle(CallEvent.LocalAnswer(sdp(callerFp)))
        val answer = CallSignalPayload(Kind.answer, "c1", sdp = sdp(callerFp), dtlsFingerprint = callerFp, ceiling = DataMode.High)
        callee.ingest(CallServerEvent.Signal("c1", "dA", bytes("alice", answer)))
        caller.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Connected)))
        callee.handle(CallEvent.Media(MediaEvent.IceStateChanged(IceState.Connected)))
        Unit
    }

    /** What the engine does with a network change on [m]: the effects it runs, in order. */
    private fun networkChange(m: CallMachine, network: NetworkType): List<CallEffect> {
        val fx = restartOnHandover(m.handle(CallEvent.ContextChanged(ctx(network))))
        val restarted = fx.any { it is CallEffect.RestartIce }
        val path = m.handle(CallEvent.PathChanged)
        return fx + if (restarted) withoutRestartRequest(path) else path
    }

    private fun List<CallEffect>.frames() = filterIsInstance<CallEffect.SendFrame>().mapNotNull { it.payload }
    private fun List<CallEffect>.restarts() = count { it is CallEffect.RestartIce }
    private fun List<CallEffect>.offers() = count { it is CallEffect.RestartIce || it is CallEffect.CreateOffer }

    /** The restart offer the media layer makes: the engine sends it as a renegotiate with the sender's own ceiling. */
    private fun restartOffer(from: String, fp: String, ceiling: DataMode) =
        bytes(from, CallSignalPayload(Kind.renegotiate, "c1", sdp = sdp(fp), dtlsFingerprint = fp, ceiling = ceiling))

    @Test fun calleeHandoverSendsOneRestartAndNoModeChange() = runBlocking<Unit> {
        active()
        val fx = networkChange(callee, NetworkType.Mobile)
        assertEquals(1, fx.restarts(), "$fx")
        assertEquals(1, fx.offers(), "$fx")
        assertFalse(fx.frames().any { it.kind == Kind.modeChange }, "mode-change frame ahead of the restart offer: $fx")
        assertFalse(fx.frames().any { it.kind == Kind.renegotiate && it.sdp == null }, "restart request: $fx")
        // The caller takes the restart offer and answers it; it makes none of its own.
        val reply = caller.ingest(CallServerEvent.Signal("c1", "d1", restartOffer("bob", calleeFp, DataMode.DataSaver)))
        assertTrue(reply.any { it is CallEffect.AcceptOffer }, "$reply")
        assertEquals(0, reply.offers(), "$reply")
        // A late mode change changes nothing.
        val modeChange = CallSignalPayload(Kind.modeChange, "c1", ceiling = DataMode.DataSaver)
        val late = caller.ingest(CallServerEvent.Signal("c1", "d1", bytes("bob", modeChange)))
        assertEquals(0, late.offers(), "$late")
    }

    @Test fun callerHandoverSendsOneRestart() {
        active()
        val fx = networkChange(caller, NetworkType.Mobile)
        assertEquals(1, fx.restarts(), "$fx")
        assertEquals(1, fx.offers(), "$fx")
        assertFalse(fx.frames().any { it.kind == Kind.modeChange }, "$fx")
    }

    @Test fun modeChangeWithoutNetworkChangeStillSendsModeChangeAndPlainOffer() {
        active()
        val fx = callee.handle(CallEvent.SetPreference(ModePreference.Fixed(CoreMode.DataSaver)))
        assertTrue(fx.frames().any { it.kind == Kind.modeChange }, "$fx")
        assertEquals(1, fx.count { it is CallEffect.CreateOffer }, "$fx")
        assertEquals(0, fx.restarts(), "$fx")
    }

    @Test fun restartOnHandoverKeepsModeChangeWhenThereIsNoOffer() {
        val mc = CallEffect.SendFrame("signal", CallSignalPayload(Kind.modeChange, "c1", ceiling = DataMode.DataSaver))
        assertEquals(listOf<CallEffect>(mc), restartOnHandover(listOf(mc)))
    }
}
