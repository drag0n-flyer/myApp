package org.example.app.calls.android

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.example.app.calls.session.CallContext
import org.example.app.calls.session.CallEffect
import org.example.app.calls.session.CallEvent
import org.example.app.calls.session.CallFrame
import org.example.app.calls.session.CallMachine
import org.example.app.calls.session.CallServerEvent
import org.example.app.calls.session.CallSignaling
import org.example.app.calls.session.CallStatus
import org.example.app.calls.session.Clock
import org.example.app.calls.session.MediaSession
import org.example.app.calls.session.MediaSessionFactory
import org.example.app.contracts.CallSignalPayload
import org.example.app.contracts.CallSignalSealer
import org.example.app.contracts.RingOutcome
import org.example.app.core.calls.CallEndReason
import org.example.app.core.calls.CallEngine
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.CallPhase
import org.example.app.core.calls.CallState
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.CallSummary
import org.example.app.core.chats.Contact
import org.example.app.push.IncomingCallSink
import org.example.app.push.MissedCallRecorder

/**
 * The real [CallEngine]: runs [CallMachine] against the server ([signaling]), the peer connection ([media]) and
 * the phone ([controller]). Every machine input goes through one queue, so the machine sees one event at a time
 * in order; the network checks before a ring (opening the payload, `call.status`) run outside it.
 *
 * Rings come two ways. Over the WebSocket ([attach]): the payload is opened with [sealer] (D7's sealer accepts a
 * ring only from the 1:1 chat with its sender), its device must be the frame's, and only a contact that is not
 * blocked rings; a stranger's ring is a quiet missed call. By push ([ring], after D7's `NotificationProcessor`
 * checked the sender): the server is asked `call.status` first, so a ring whose call is already over never rings. The ring (50 s) and setup (30 s)
 * deadlines and the answering-device binding are the machine's.
 *
 * Call [attach] once before use.
 */
class LiveCallEngine(
    private val scope: CoroutineScope,
    private val signaling: CallSignaling,
    private val sealer: CallSignalSealer,
    private val media: MediaSessionFactory,
    private val peers: CallPeers,
    private val context: (isContact: Boolean) -> CallContext,
    private val controller: CallController,
    private val history: CallHistory,
    /** A stranger's ring writes the same quiet missed call as a stranger's push ring. */
    private val strangers: MissedCallRecorder,
    private val clock: Clock,
    private val newCallId: () -> String = { UUID.randomUUID().toString().lowercase() },
) : CallEngine, IncomingCallSink {
    companion object {
        const val TICK_MS = 1_000L
        /** The quality adaptor takes a stats sample every 2 s. */
        const val STATS_EVERY_TICKS = 2
        /** How long a push-woken ring waits for `call.status` before it rings anyway (the ring deadline still applies). */
        const val STATUS_TIMEOUT_MS = 5_000L
        /** How long [shutdown] waits for the hang-up to go out before it ends the phone's call anyway. */
        const val SHUTDOWN_MS = 3_000L
        private const val MAX_REMEMBERED_CALLS = 64

        /**
         * Only a call the server ended as `missed` is a missed call, as on iPhone (VoIPPushDecision): `hangup` cannot
         * tell whether another device answered first.
         */
        private const val MISSED = "missed"
    }

    private val machine = CallMachine(context(false), sealer, clock)
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val _state = MutableStateFlow<CallState?>(null)
    override val state: StateFlow<CallState?> = _state.asStateFlow()

    // Only touched from the queue.
    private var callId: String? = null
    private var contact = Contact("", "", "")
    private var outgoing = false
    private var isContact = false
    private var network: NetworkType? = null
    private var shownPhase: CallPhase? = null
    /** The call the phone shows (Telecom, ringtone, notification, service) until it is reported ended. Any thread. */
    @Volatile private var shownCall: String? = null
    private var session: MediaSession? = null
    private var mediaJob: Job? = null
    private var ticker: Job? = null
    /** Calls whose history (or missed notice) is written: each is written once. */
    private val recorded = LinkedHashSet<String>()

    private val live: Boolean get() = machine.state?.isEnded == false

    /** Starts the queue and listens for server call frames. */
    fun attach(): Job = scope.launch {
        launch { for (job in queue) job() }
        signaling.events.collect { e ->
            when (e) {
                is CallServerEvent.Ring -> socketRing(e)
                else -> enqueue { run(machine.ingest(e)) }
            }
        }
    }

    // CallEngine

    override fun start(contactId: String, video: Boolean) = enqueue {
        if (live) return@enqueue
        val peer = peers.peer(contactId)
        val id = newCallId()
        begin(id, peer?.contact ?: Contact(contactId, "", ""), isOutgoing = true, contact = peer?.isContact == true)
        // The call service starts before anything goes out: a phone that refuses it fails the call before it rings anyone.
        try {
            controller.reportOutgoing(CallInfo(id, contact, video))
        } catch (_: Exception) {
            val net = callContext(isContact).network
            _state.value = CallState(contactId, CallPhase.Ended(CallEndReason.Failed), video, false, DataMode.entries.first(), net)
            return@enqueue
        }
        run(machine.handle(CallEvent.Start(contact, video, id)))
    }

    override fun answer(video: Boolean) = submit(CallEvent.Answer(video))
    override fun decline() = submit(CallEvent.Decline)
    override fun hangUp() = submit(CallEvent.Hangup)
    override fun setMode(preference: ModePreference) = submit(CallEvent.SetPreference(preference))
    override fun setMuted(on: Boolean) = submit(CallEvent.SetMuted(on))
    override fun setSpeaker(on: Boolean) = submit(CallEvent.SetSpeaker(on))
    override fun setVideo(on: Boolean) = submit(CallEvent.SetVideo(on))

    override fun setNetwork(type: NetworkType) = enqueue {
        network = type
        run(machine.handle(CallEvent.ContextChanged(callContext(isContact))))
        if (live) run(machine.handle(CallEvent.PathChanged))
    }

    override fun reset() = enqueue {
        if (_state.value?.isEnded == true) _state.value = null
    }

    /**
     * Sign-out or wipe: ends any call before the session's scope goes, so Telecom, the ringtone, the notification
     * and the call service do not carry over into the next account. A ringing call ends here only (declining would
     * end it on the account's other devices); a call in progress is hung up. Media closes with the call. If the
     * hang-up cannot go out in [SHUTDOWN_MS], the phone's call ends anyway.
     */
    suspend fun shutdown() {
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        enqueue {
            if (live) {
                val ringing = !outgoing && machine.state?.phase == CallPhase.Incoming
                run(machine.handle(if (ringing) CallEvent.LocalFailure else CallEvent.Hangup))
            }
            closeMedia()
            done.complete(Unit)
        }
        if (withTimeoutOrNull(SHUTDOWN_MS) { done.await() } == null) {
            shownCall?.let { id ->
                shownCall = null
                safely { controller.reportEnded(id, CallEndReason.HungUp) }
            }
        }
    }

    // IncomingCallSink: a ring that came by push. D7 already opened it and checked the caller.

    override fun ring(outcome: RingOutcome.Ring) {
        scope.launch { pushRing(outcome) }
    }

    private suspend fun pushRing(o: RingOutcome.Ring) {
        val status = withTimeoutOrNull(STATUS_TIMEOUT_MS) {
            try {
                signaling.status(o.callId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                CallStatus.Unknown
            }
        } ?: CallStatus.Unknown
        val peer = peers.peer(o.fromAccountId)
        val who = peer?.contact ?: Contact(o.fromAccountId, o.callerName, "")
        when (status) {
            // Over before this device could ring: a missed call if nobody answered, otherwise nothing.
            is CallStatus.Ended -> if (status.reason == MISSED) enqueue { missed(CallInfo(o.callId, who, o.video)) }
            CallStatus.Answered -> Unit
            CallStatus.Ringing, CallStatus.Unknown -> enqueue {
                // No WebSocket ring was seen, so no caller device: the machine binds to the device whose answer comes.
                incoming(o.callId, who, "", CallSignalPayload(CallSignalPayload.Kind.ring, o.callId, video = o.video))
            }
        }
    }

    private suspend fun socketRing(e: CallServerEvent.Ring) {
        val peer = peers.peer(e.fromAccountID)
        // A blocked account gets nothing.
        if (peer?.isBlocked == true) return
        val opened = try {
            sealer.open(e.payload, e.fromAccountID)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            return
        }
        // docs/protocol/calls.md: the device in the MLS credential must be the frame's device.
        if (opened.senderDeviceID != null && opened.senderDeviceID != e.fromDeviceID) return
        val p = opened.payload
        if (p.kind != CallSignalPayload.Kind.ring || p.callID != e.callID) return
        // A stranger never rings: a quiet missed call in the message request, as for a push ring (design §6.10).
        if (peer == null || !peer.isContact) {
            try {
                strangers.recordMissedCall(e.callID, e.fromAccountID)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
            }
            return
        }
        enqueue { incoming(e.callID, peer.contact, e.fromDeviceID, p) }
    }

    private suspend fun incoming(id: String, who: Contact, device: String, ring: CallSignalPayload) {
        if (live || id in recorded) return
        begin(id, who, isOutgoing = false, contact = true)
        run(machine.handle(CallEvent.Incoming(id, who, device, ring)))
    }

    private suspend fun begin(id: String, who: Contact, isOutgoing: Boolean, contact: Boolean) {
        callId = id
        this.contact = who
        outgoing = isOutgoing
        isContact = contact
        shownPhase = null
        run(machine.handle(CallEvent.ContextChanged(callContext(contact))))
    }

    private fun missed(call: CallInfo) {
        if (!remember(call.callId)) return
        history.record(if (call.video) CallSummary.MissedVideo else CallSummary.MissedAudio, call.contact.id, outgoing = false)
        safely { controller.reportMissed(call) }
    }

    // The queue

    private fun enqueue(job: suspend () -> Unit) {
        queue.trySend(job)
    }

    private fun submit(event: CallEvent) = enqueue { run(machine.handle(event)) }

    /** Runs effects in order. An effect may produce the machine's next input; once the call ends, the rest of the batch is stale. */
    private suspend fun run(effects: List<CallEffect>) {
        for (effect in effects) {
            val next = perform(effect) ?: continue
            run(machine.handle(next))
            if (!live) return
        }
    }

    private suspend fun perform(effect: CallEffect): CallEvent? = when (effect) {
        is CallEffect.SendFrame -> send(effect)
        is CallEffect.ConfigureMedia -> configure(effect)
        is CallEffect.MakeOffer -> mediaStep { CallEvent.LocalOffer(it.makeOffer(effect.receive, iceRestart = effect is CallEffect.RestartIce)) }
        is CallEffect.AcceptOffer -> mediaStep { CallEvent.LocalAnswer(it.acceptOffer(effect.sdp, effect.send, effect.receive)) }
        is CallEffect.AcceptAnswer -> mediaStep { it.acceptAnswer(effect.sdp, effect.send); null }
        CallEffect.Rollback -> quietly { it.rollback() }
        is CallEffect.AddCandidate -> quietly { it.add(effect.candidate) }
        is CallEffect.SetAudioMaxBitrate -> quietly { it.setAudioMaxBitrate(effect.bps) }
        is CallEffect.SetVideo -> quietly { it.setVideo(effect.tier, effect.ceiling) }
        is CallEffect.SetMuted -> quietly { it.setMuted(effect.on) }
        is CallEffect.SetNoiseSuppression -> quietly { it.setNoiseSuppression(effect.mode) }
        is CallEffect.End -> {
            closeMedia()
            null
        }
        is CallEffect.RecordHistory -> {
            val id = callId
            if (id != null && remember(id)) history.record(effect.summary, contact.id, effect.outgoing)
            null
        }
        is CallEffect.Publish -> publish(effect.state)
    }

    private suspend fun send(frame: CallEffect.SendFrame): CallEvent? {
        val id = frame.payload?.callID ?: callId ?: return null
        return try {
            when (frame.type) {
                CallFrame.INVITE -> signaling.invite(id, contact.id, frame.payload?.video ?: false, seal(frame))
                CallFrame.ANSWER -> signaling.answer(id, seal(frame))
                CallFrame.SIGNAL -> signaling.signal(id, seal(frame))
                CallFrame.DECLINE -> signaling.decline(id)
                CallFrame.HANGUP -> signaling.hangup(id)
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A call that cannot be placed or answered fails; a lost signal is left to ICE and the deadlines.
            if (frame.type == CallFrame.INVITE || frame.type == CallFrame.ANSWER) CallEvent.LocalFailure else null
        }
    }

    private suspend fun seal(frame: CallEffect.SendFrame): ByteArray = sealer.seal(requireNotNull(frame.payload), contact.id)

    private suspend fun configure(effect: CallEffect.ConfigureMedia): CallEvent? {
        closeMedia()
        return try {
            val s = media.create()
            session = s
            mediaJob = scope.launch { s.events.collect { submit(CallEvent.Media(it)) } }
            s.configure(signaling.iceServers(), effect.policy, effect.video)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            CallEvent.LocalFailure
        }
    }

    private suspend fun mediaStep(step: suspend (MediaSession) -> CallEvent?): CallEvent? {
        val s = session ?: return CallEvent.LocalFailure
        return try {
            step(s)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            CallEvent.LocalFailure
        }
    }

    private suspend fun quietly(step: suspend (MediaSession) -> Unit): CallEvent? {
        val s = session ?: return null
        try {
            step(s)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: a failure that matters shows up as an ICE state or in the stats.
        }
        return null
    }

    private suspend fun closeMedia() {
        ticker?.cancel(); ticker = null
        mediaJob?.cancel(); mediaJob = null
        val s = session ?: return
        session = null
        try {
            s.close()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Publishes the state and tells the phone about each new phase once. A phone that refuses a ring fails the call. */
    private fun publish(s: CallState?): CallEvent? {
        _state.value = s
        val id = callId ?: return null
        val phase = s?.phase ?: return null
        val before = shownPhase
        if (phase == before) return null
        shownPhase = phase
        shownCall = if (phase is CallPhase.Ended) null else id
        if (!phase.isEnded() && ticker == null) startTicker()
        val call = CallInfo(id, contact, s.isVideoCall)
        return when (phase) {
            CallPhase.Incoming -> if (before == null) refused { controller.reportIncoming(call) } else null
            // Reported by start(), before the invite.
            CallPhase.Ringing -> null
            // The call service starts here; a phone that refuses it (ForegroundServiceStartNotAllowedException) fails the call.
            CallPhase.Connecting ->
                if (before == CallPhase.Incoming || before == CallPhase.Ringing) refused { controller.reportAnswered(id, s.isVideoOn) } else null
            CallPhase.Active -> {
                if (before != CallPhase.Reconnecting) safely { controller.reportActive(id) }
                null
            }
            CallPhase.Reconnecting -> null
            is CallPhase.Ended -> {
                safely { controller.reportEnded(id, phase.reason) }
                null
            }
        }
    }

    private fun startTicker() {
        ticker = scope.launch {
            var n = 0
            while (true) {
                delay(TICK_MS)
                submit(CallEvent.Tick)
                if (++n % STATS_EVERY_TICKS == 0) enqueue { sampleStats() }
            }
        }
    }

    private suspend fun sampleStats() {
        val phase = machine.state?.phase
        if (phase != CallPhase.Active && phase != CallPhase.Reconnecting) return
        val s = session ?: return
        val stats = try {
            s.stats()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        run(machine.handle(CallEvent.Stats(stats)))
    }

    private fun callContext(contact: Boolean): CallContext {
        val c = context(contact)
        return network?.let { c.copy(network = it) } ?: c
    }

    private fun remember(id: String): Boolean {
        if (!recorded.add(id)) return false
        if (recorded.size > MAX_REMEMBERED_CALLS) recorded.remove(recorded.first())
        return true
    }

    private fun CallPhase.isEnded() = this is CallPhase.Ended

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // The phone's view of the call is best effort once it rang.
        }
    }

    private inline fun refused(block: () -> Unit): CallEvent? = try {
        block()
        null
    } catch (_: Exception) {
        CallEvent.LocalFailure
    }
}
