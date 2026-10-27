package org.example.app.calls.session

import kotlinx.coroutines.CancellationException
import org.example.app.calls.policy.AudioProfile
import org.example.app.calls.policy.IceState
import org.example.app.calls.policy.QualityAdaptor
import org.example.app.calls.policy.ReconnectAction
import org.example.app.calls.policy.ReconnectPolicy
import org.example.app.calls.policy.RelayDecision
import org.example.app.calls.policy.VideoLadder
import org.example.app.calls.policy.VideoTier
import org.example.app.contracts.CallSealException
import org.example.app.contracts.CallSignalPayload
import org.example.app.contracts.CallSignalPayload.Kind
import org.example.app.contracts.CallSignalSealer
import org.example.app.contracts.DataMode
import org.example.app.contracts.IceCandidate
import org.example.app.core.calls.CallBanner
import org.example.app.core.calls.CallEndReason
import org.example.app.core.calls.CallPhase
import org.example.app.core.calls.CallQuality
import org.example.app.core.calls.CallState
import org.example.app.core.calls.DataModePolicy
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.CallSummary
import org.example.app.core.chats.Contact
import org.example.app.calls.policy.CallBanner as PolicyBanner
import org.example.app.calls.policy.CallQuality as PolicyQuality
import org.example.app.calls.policy.IPHiding as PolicyIPHiding
import org.example.app.core.calls.DataMode as CoreMode

/**
 * The 1:1 call state machine, caller and callee: events in, effects out, no I/O ([handle]). A port of the
 * iPhone's `CallSessionMachine` (CallCore), transition for transition, with calling/lane-k's push binding
 * and local-failure rule.
 *
 * [ingest] adds the receiver checks of docs/protocol/calls.md for a server frame: it opens the payload with
 * [sealer] (decryption, sender account), drops one whose `callID` differs from the frame's, and lets each
 * frame carry only the kinds that travel in it. The rest (device binding, state, fingerprint) is in [handle].
 *
 * Not thread-safe: the engine calls it from one coroutine at a time.
 */
class CallMachine(context: CallContext, private val sealer: CallSignalSealer, private val clock: Clock) {
    companion object {
        /** Caps for what a peer can make us buffer before a call is answered. */
        const val MAX_EARLY_DEVICES = 8
        const val MAX_EARLY_CANDIDATES_PER_DEVICE = 64
        const val MAX_PENDING_LOCAL_CANDIDATES = 64

        /** Restarts are coalesced: one in flight at a time, and a peer's requests are honoured at most once per window. */
        private const val RESTART_WINDOW_MS = ReconnectPolicy.RETRY_EVERY_MS
        private const val STALE_ANSWER_AFTER_MS = 10_000L

        /** `offer` travels only in `call.answer` (so it arrives with `call.answered`); the rest only in `call.signal`. */
        private val answeredKinds = setOf(Kind.offer)
        private val signalKinds = setOf(Kind.answer, Kind.candidate, Kind.renegotiate, Kind.renegotiateAnswer, Kind.modeChange)

        private fun reason(server: String): CallEndReason = when (server) {
            "declined" -> CallEndReason.Declined
            "missed" -> CallEndReason.Missed
            "hangup" -> CallEndReason.HungUp
            "answered_elsewhere" -> CallEndReason.AnsweredElsewhere
            else -> CallEndReason.Failed
        }
    }

    var state: CallState? = null
        private set

    private var context = context
    private var callID = ""
    private var contactID = ""
    private var outgoing = true
    private var adaptor = QualityAdaptor(DataMode.UltraLow, videoWanted = false)
    private var reconnect = ReconnectPolicy(isCaller = true)
    private var peerCeiling: DataMode? = null

    private var answeringDevice: String? = null
    private var remoteDescriptionSet = false
    /** Normalised (see [Fingerprint.normalise]). */
    private var pinnedFingerprint: String? = null
    private var renegotiateOutstanding = false
    private var answerSent = false
    private var answerRequested = false          // callee: the user answered
    private var localOfferSDP: String? = null     // callee: our prewarmed offer
    private var pendingRenegotiateSince: Long? = null   // accepted a renegotiate offer, owe the answer
    private var outstandingSince: Long? = null          // our renegotiate offer is out, answer not yet accepted
    private var lastHonouredRequest: Long? = null       // caller: last restart request we acted on
    private var videoStartsOff = false
    /** The send profile the current remote description was set with; a change of ptime, FEC or DTX needs a renegotiation. */
    private var negotiatedSend: AudioProfile? = null
    private val pendingLocalCandidates = mutableListOf<IceCandidate>()
    /** Remote candidates received before we can use them, by sending device. */
    private val earlyRemoteCandidates = LinkedHashMap<String, MutableList<IceCandidate>>()
    private var activeSince: Long? = null
    private var everActive = false
    private var lastSentMode: DataMode? = null
    private var lastSentTier: VideoTier? = null
    /** The ceiling the media layer last got with a [CallEffect.SetVideo]. */
    private var lastSentCeiling: DataMode? = null

    fun handle(event: CallEvent): List<CallEffect> = when (event) {
        is CallEvent.Start -> start(event.contact, event.video, event.callID)
        is CallEvent.Incoming -> incoming(event.callID, event.contact, event.fromDeviceID, event.ring)
        is CallEvent.Answer -> answer(event.video)
        CallEvent.Decline -> decline()
        CallEvent.Hangup -> hangup()
        is CallEvent.SetPreference -> setPreference(event)
        is CallEvent.SetVideo -> setVideo(event.on)
        is CallEvent.SetMuted -> live { it.copy(isMuted = event.on) }?.let { it + CallEffect.SetMuted(event.on) } ?: emptyList()
        is CallEvent.SetSpeaker -> live { it.copy(isSpeakerOn = event.on) } ?: emptyList()
        is CallEvent.Server -> server(event.event)
        is CallEvent.Status -> status(event.callID, event.status)
        is CallEvent.Remote -> remote(event.payload, event.fromDeviceID)
        is CallEvent.RemoteRejected -> {
            val answering = answeringDevice
            if (answering != null && event.fromDeviceID == answering) end(CallEndReason.Failed, notifyServer = true) else emptyList()
        }
        is CallEvent.Media -> media(event.event)
        is CallEvent.LocalOffer -> localOffer(event.sdp)
        is CallEvent.LocalAnswer -> localAnswer(event.sdp)
        is CallEvent.Stats -> stats(event)
        CallEvent.PathChanged -> reconnectActions(reconnect.pathChanged(clock.nowMs()))
        CallEvent.LocalFailure -> localFailure()
        CallEvent.Tick -> tick()
        is CallEvent.ContextChanged -> contextChanged(event.context)
    }

    /**
     * A server frame for this call. Payloads are opened as sent by the call's contact before [handle] sees them;
     * one that fails to open becomes [CallEvent.RemoteRejected]. Rings are the engine's business and are ignored.
     */
    suspend fun ingest(event: CallServerEvent): List<CallEffect> {
        if (event is CallServerEvent.Ring || event.callID != callID || !isLive) return emptyList()
        return when (event) {
            is CallServerEvent.Ended -> handle(CallEvent.Server(event))
            is CallServerEvent.Answered -> {
                val fx = handle(CallEvent.Server(event))
                if (outgoing && isLive && answeringDevice == event.deviceID) {
                    fx + open(event.callID, event.deviceID, event.payload, answeredKinds)
                } else {
                    fx
                }
            }
            is CallServerEvent.Signal -> open(event.callID, event.fromDeviceID, event.payload, signalKinds)
            is CallServerEvent.Ring -> emptyList()
        }
    }

    private suspend fun open(frameCallID: String, device: String, data: ByteArray, allowed: Set<Kind>): List<CallEffect> {
        val p = try {
            sealer.open(data, contactID)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CallSealException) {
            return handle(CallEvent.RemoteRejected(e, device))
        } catch (e: Exception) {
            return handle(CallEvent.RemoteRejected(CallSealException.Undecryptable, device))
        }
        if (p.callID != frameCallID || p.kind !in allowed) return emptyList()
        return handle(CallEvent.Remote(p, device))
    }

    // Inputs

    private val ownCeiling: DataMode
        get() = DataModePolicy.ceiling(context.server, context.settings.call, context.network, state?.inCallPreference).wire()

    /** The call's ceiling: the lower of ours and the peer's. Before the peer's arrives, ours. */
    private val effectiveCeiling: DataMode get() = minOf(ownCeiling, peerCeiling ?: DataMode.High)

    private fun start(contact: Contact, video: Boolean, id: String): List<CallEffect> {
        if (isLive) return emptyList()
        resetCall(id, contact.id, isOutgoing = true)

        val ceiling = ownCeiling
        val videoOn = video && !startsOff(ceiling)
        adaptor = QualityAdaptor(ceiling, videoOn)
        lastSentMode = adaptor.currentMode
        lastSentTier = adaptor.currentTier
        state = CallState(
            contactId = contact.id, phase = CallPhase.Ringing, isVideoCall = video, isVideoOn = videoOn,
            effectiveMode = ceiling.core(), network = context.network, isSpeakerOn = video,
        )
        return listOf(
            CallEffect.ConfigureMedia(relayPolicy(), video),
            CallEffect.SetNoiseSuppression(context.settings.noiseSuppression),
            CallEffect.SendFrame(CallFrame.INVITE, CallSignalPayload(Kind.ring, id, video = video, ceiling = ceiling)),
            CallEffect.Publish(state),
        )
    }

    private fun resetCall(id: String, contact: String, isOutgoing: Boolean) {
        state = null   // the last call's in-call preference must not set this call's ceiling
        callID = id
        contactID = contact
        outgoing = isOutgoing
        peerCeiling = null
        answeringDevice = null; remoteDescriptionSet = false; answerSent = false
        answerRequested = false; localOfferSDP = null; pendingRenegotiateSince = null
        outstandingSince = null; lastHonouredRequest = null; negotiatedSend = null
        pinnedFingerprint = null; renegotiateOutstanding = false
        pendingLocalCandidates.clear(); earlyRemoteCandidates.clear()
        activeSince = null; everActive = false; lastSentMode = null; lastSentTier = null; lastSentCeiling = null
        videoStartsOff = false
        reconnect = ReconnectPolicy(isCaller = isOutgoing)
    }

    private fun incoming(id: String, contact: Contact, device: String, ring: CallSignalPayload): List<CallEffect> {
        if (isLive) return emptyList()
        if (ring.kind != Kind.ring || ring.callID != id) return emptyList()
        resetCall(id, contact.id, isOutgoing = false)
        answeringDevice = device              // bound to the caller's device from the ring; empty after a push
        peerCeiling = ring.ceiling
        val video = ring.video ?: false
        val ceiling = ownCeiling
        videoStartsOff = startsOff(effectiveCeiling)
        adaptor = QualityAdaptor(effectiveCeiling, videoWanted = false)
        lastSentMode = adaptor.currentMode
        lastSentTier = null
        state = CallState(
            contactId = contact.id, phase = CallPhase.Incoming, isVideoCall = video, isVideoOn = false,
            effectiveMode = adaptor.currentMode.core(), network = context.network,
        )
        return listOf(
            CallEffect.ConfigureMedia(relayPolicy(), video && !videoStartsOff),
            CallEffect.SetNoiseSuppression(context.settings.noiseSuppression),
            CallEffect.CreateOffer(AudioProfile.forMode(ceiling)),
            CallEffect.Publish(state),
        )
    }

    private fun answer(video: Boolean): List<CallEffect> {
        if (!isLive || outgoing || state?.phase != CallPhase.Incoming || answerRequested) return emptyList()
        answerRequested = true
        val on = video && state?.isVideoCall == true && !videoStartsOff
        adaptor = QualityAdaptor(effectiveCeiling, on)   // fresh start with the answer's video choice
        val tier = adaptor.currentTier
        lastSentTier = tier
        update { it.copy(phase = CallPhase.Connecting, isVideoOn = tier != null, isSpeakerOn = on) }
        val fx = mutableListOf<CallEffect>()
        localOfferSDP?.let { fx += sendAnswer(it) }
        if (isLive) fx += CallEffect.Publish(state)
        return fx
    }

    private fun decline(): List<CallEffect> {
        if (!isLive || outgoing || state?.phase != CallPhase.Incoming) return emptyList()
        return listOf(CallEffect.SendFrame(CallFrame.DECLINE)) + end(CallEndReason.Declined, notifyServer = false)
    }

    /** Callee: sends our prewarmed offer, then the candidates gathered while ringing. */
    private fun sendAnswer(sdp: String): List<CallEffect> {
        val fingerprint = Fingerprint.local(sdp) ?: return end(CallEndReason.Failed, notifyServer = true)
        answerSent = true
        val fx = mutableListOf<CallEffect>(
            CallEffect.SendFrame(
                CallFrame.ANSWER,
                CallSignalPayload(Kind.offer, callID, sdp = sdp, dtlsFingerprint = fingerprint, ceiling = ownCeiling),
            ),
        )
        fx += flushLocalCandidates()
        return fx
    }

    private fun flushLocalCandidates(): List<CallEffect> {
        val fx = pendingLocalCandidates.map { candidateFrame(it) }
        pendingLocalCandidates.clear()
        return fx
    }

    private fun candidateFrame(c: IceCandidate) =
        CallEffect.SendFrame(CallFrame.SIGNAL, CallSignalPayload(Kind.candidate, callID, candidate = c))

    private fun bufferEarly(c: IceCandidate, device: String) {
        if (device !in earlyRemoteCandidates && earlyRemoteCandidates.size >= MAX_EARLY_DEVICES) return
        val list = earlyRemoteCandidates.getOrPut(device) { mutableListOf() }
        if (list.size >= MAX_EARLY_CANDIDATES_PER_DEVICE) return
        list += c
    }

    private fun takeEarly(device: String): List<CallEffect> =
        (earlyRemoteCandidates.remove(device) ?: emptyList<IceCandidate>()).map { CallEffect.AddCandidate(it) }

    private fun server(e: CallServerEvent): List<CallEffect> = when (e) {
        is CallServerEvent.Answered -> {
            if (outgoing && e.callID == callID && isLive && answeringDevice == null) {
                answeringDevice = e.deviceID
                // Keep only the answering device's buffered candidates.
                earlyRemoteCandidates.keys.retainAll(setOf(e.deviceID))
            }
            emptyList()
        }
        is CallServerEvent.Ended ->
            if (e.callID == callID && isLive) end(reason(e.reason), notifyServer = false) else emptyList()
        is CallServerEvent.Ring, is CallServerEvent.Signal -> emptyList()   // payloads arrive as Remote after opening
    }

    /** A push-woken device asks the server where the call stands; a call that is over ends here, silently. */
    private fun status(id: String, s: CallStatus): List<CallEffect> {
        if (id != callID || !isLive) return emptyList()
        return when (s) {
            is CallStatus.Ended -> end(reason(s.reason), notifyServer = false)
            CallStatus.Answered ->
                if (!outgoing && state?.phase == CallPhase.Incoming) end(CallEndReason.AnsweredElsewhere, notifyServer = false) else emptyList()
            CallStatus.Ringing, CallStatus.Unknown -> emptyList()
        }
    }

    private fun remote(p: CallSignalPayload, device: String): List<CallEffect> {
        if (p.callID != callID || !isLive) return emptyList()
        return when (p.kind) {
            Kind.candidate -> remoteCandidate(p, device)
            Kind.offer -> remoteOffer(p, device)
            Kind.answer -> remoteAnswer(p, device)
            Kind.renegotiate -> remoteRenegotiate(p, device)
            Kind.renegotiateAnswer -> remoteRenegotiateAnswer(p, device)
            Kind.modeChange -> {
                if (device != answeringDevice) return emptyList()
                p.ceiling?.let { setPeerCeiling(it) }
                syncMedia() + CallEffect.Publish(state) + renegotiationIfNeeded()
            }
            Kind.ring -> emptyList()
        }
    }

    private fun remoteCandidate(p: CallSignalPayload, device: String): List<CallEffect> {
        val c = p.candidate ?: return emptyList()
        val answering = answeringDevice
        if (answering == null) {
            bufferEarly(c, device)   // before call.answered: per device, capped
            return emptyList()
        }
        if (device != answering) return emptyList()
        if (remoteDescriptionSet) return listOf(CallEffect.AddCandidate(c))
        bufferEarly(c, answering)
        return emptyList()
    }

    /** Caller: the answering callee device's offer. */
    private fun remoteOffer(p: CallSignalPayload, device: String): List<CallEffect> {
        val answering = answeringDevice
        if (!outgoing || answering == null || remoteDescriptionSet || device != answering) return emptyList()
        val sdp = p.sdp
        if (sdp == null || !Fingerprint.isValid(sdp, p.dtlsFingerprint)) return end(CallEndReason.Failed, notifyServer = true)
        p.ceiling?.let { setPeerCeiling(it) }
        remoteDescriptionSet = true
        pinnedFingerprint = Fingerprint.normalise(p.dtlsFingerprint)
        val send = AudioProfile.forMode(currentMode())
        negotiatedSend = send
        val fx = mutableListOf<CallEffect>(CallEffect.AcceptOffer(sdp, send, AudioProfile.forMode(ownCeiling)))
        fx += takeEarly(answering)
        update { it.copy(phase = CallPhase.Connecting) }
        fx += syncMedia()   // the peer's ceiling may be lower than ours: clamp video now
        fx += CallEffect.Publish(state)
        return fx
    }

    /**
     * Callee: the caller's answer. A push-woken callee never saw the WebSocket ring, so it has no caller device
     * yet (empty): it binds to the device whose answer arrives. The payload was opened as sent by the caller's account.
     */
    private fun remoteAnswer(p: CallSignalPayload, device: String): List<CallEffect> {
        val bound = answeringDevice
        if (outgoing || !answerSent || remoteDescriptionSet || bound == null || (device != bound && bound.isNotEmpty())) return emptyList()
        val sdp = p.sdp
        if (sdp == null || !Fingerprint.isValid(sdp, p.dtlsFingerprint)) return end(CallEndReason.Failed, notifyServer = true)
        answeringDevice = device
        p.ceiling?.let { setPeerCeiling(it) }
        remoteDescriptionSet = true
        pinnedFingerprint = Fingerprint.normalise(p.dtlsFingerprint)
        val send = AudioProfile.forMode(currentMode())
        negotiatedSend = send
        val fx = mutableListOf<CallEffect>(CallEffect.AcceptAnswer(sdp, send))
        fx += takeEarly(device)
        fx += syncMedia()   // the peer's ceiling may be lower than ours: clamp video now
        fx += CallEffect.Publish(state)
        return fx
    }

    /**
     * `renegotiate` without an SDP is a restart REQUEST from the callee: only the caller acts on it, with its own
     * ICE-restart offer. `renegotiate` with an SDP is an OFFER (mode change or restart).
     */
    private fun remoteRenegotiate(p: CallSignalPayload, device: String): List<CallEffect> {
        if (device != answeringDevice || !remoteDescriptionSet || !answerSent) return emptyList()
        val now = clock.nowMs()
        val sdp = p.sdp
        if (sdp == null) {
            if (!outgoing) return emptyList()
            // Never start our own offer while we still owe an answer (have-remote-offer).
            if (owesAnswer(now) || offerOutstanding(now)) return emptyList()
            lastHonouredRequest?.let { if (now - it < RESTART_WINDOW_MS) return emptyList() }
            lastHonouredRequest = now
            renegotiateOutstanding = true
            outstandingSince = now
            return listOf(CallEffect.RestartIce(AudioProfile.forMode(ownCeiling)))
        }
        // One offer to answer at a time: a second one before we answered the first is not accepted.
        if (owesAnswer(now)) return emptyList()
        if (!Fingerprint.isValid(sdp, p.dtlsFingerprint, pinnedFingerprint, requirePin = true)) {
            return end(CallEndReason.Failed, notifyServer = true)
        }
        val fx = mutableListOf<CallEffect>()
        if (renegotiateOutstanding) {
            // Glare: the caller's offer wins. The caller ignores the callee's offer while its own is fresh;
            // the callee (or a caller whose offer went stale) rolls its own offer back first.
            // As on iPhone (owner to confirm there): a caller rolls back too when its own offer is stale
            // (>= the restart window with no answer), so a lost answer cannot block the peer's offer forever.
            val fresh = outstandingSince?.let { now - it < RESTART_WINDOW_MS } ?: false
            if (outgoing && fresh) return emptyList()
            fx += CallEffect.Rollback
            renegotiateOutstanding = false
            outstandingSince = null
        }
        p.ceiling?.let { setPeerCeiling(it) }
        pendingRenegotiateSince = now
        val send = AudioProfile.forMode(currentMode())
        negotiatedSend = send
        fx += CallEffect.AcceptOffer(sdp, send, AudioProfile.forMode(ownCeiling))
        return fx + syncMedia()
    }

    private fun remoteRenegotiateAnswer(p: CallSignalPayload, device: String): List<CallEffect> {
        val sdp = p.sdp
        if (device != answeringDevice || !remoteDescriptionSet || !renegotiateOutstanding || sdp == null) return emptyList()
        // The fingerprint must match the SDP and the one pinned from the original offer or answer.
        if (!Fingerprint.isValid(sdp, p.dtlsFingerprint, pinnedFingerprint, requirePin = true)) {
            return end(CallEndReason.Failed, notifyServer = true)
        }
        renegotiateOutstanding = false
        outstandingSince = null
        val send = AudioProfile.forMode(currentMode())
        negotiatedSend = send
        return listOf(CallEffect.AcceptAnswer(sdp, send))
    }

    private fun localAnswer(sdp: String): List<CallEffect> {
        if (pendingRenegotiateSince != null) {
            if (!isLive) return emptyList()
            pendingRenegotiateSince = null
            val fingerprint = Fingerprint.local(sdp) ?: return end(CallEndReason.Failed, notifyServer = true)
            return listOf(
                CallEffect.SendFrame(
                    CallFrame.SIGNAL,
                    CallSignalPayload(Kind.renegotiateAnswer, callID, sdp = sdp, dtlsFingerprint = fingerprint, ceiling = ownCeiling),
                ),
            )
        }
        if (!outgoing || !isLive || !remoteDescriptionSet || answerSent) return emptyList()
        // Stricter than the iPhone, which sends an answer without a fingerprint: the callee would drop it anyway.
        val fingerprint = Fingerprint.local(sdp) ?: return end(CallEndReason.Failed, notifyServer = true)
        answerSent = true
        val fx = mutableListOf<CallEffect>(
            CallEffect.SendFrame(
                CallFrame.SIGNAL,
                CallSignalPayload(Kind.answer, callID, sdp = sdp, dtlsFingerprint = fingerprint, ceiling = ownCeiling),
            ),
        )
        fx += flushLocalCandidates()
        return fx
    }

    private fun localOffer(sdp: String): List<CallEffect> {
        if (!outgoing && !answerSent) {
            // Callee: our prewarmed offer. It goes out only once the user answers.
            if (!isLive || localOfferSDP != null) return emptyList()
            localOfferSDP = sdp
            return if (answerRequested) sendAnswer(sdp) + CallEffect.Publish(state) else emptyList()
        }
        // After the answer, either side: an offer we asked for (ICE restart or mode change).
        if (!isLive || !answerSent || !renegotiateOutstanding) return emptyList()
        val fingerprint = Fingerprint.local(sdp) ?: return end(CallEndReason.Failed, notifyServer = true)
        return listOf(
            CallEffect.SendFrame(
                CallFrame.SIGNAL,
                CallSignalPayload(Kind.renegotiate, callID, sdp = sdp, dtlsFingerprint = fingerprint, ceiling = ownCeiling),
            ),
        )
    }

    private fun media(e: MediaEvent): List<CallEffect> {
        if (!isLive) return emptyList()
        return when (e) {
            is MediaEvent.LocalCandidate -> {
                if (answerSent) return listOf(candidateFrame(e.candidate))
                if (pendingLocalCandidates.size < MAX_PENDING_LOCAL_CANDIDATES) pendingLocalCandidates += e.candidate
                emptyList()
            }
            is MediaEvent.RemoteVideo -> emptyList()
            is MediaEvent.IceStateChanged -> {
                val fx = reconnectActions(reconnect.ice(e.state, clock.nowMs())).toMutableList()
                val phase = state?.phase
                val up = e.state == IceState.Connected || e.state == IceState.Completed
                if (isLive && up && (phase == CallPhase.Connecting || phase == CallPhase.Ringing)) {
                    markActive()
                    update { it.copy(phase = CallPhase.Active) }
                    fx += CallEffect.Publish(state)
                }
                fx
            }
        }
    }

    private fun reconnectActions(actions: List<ReconnectAction>): List<CallEffect> {
        if (!isLive) return emptyList()
        val fx = mutableListOf<CallEffect>()
        for (a in actions) {
            when (a) {
                ReconnectAction.ShowReconnecting -> {
                    update { it.copy(phase = CallPhase.Reconnecting, banner = CallBanner.Reconnecting) }
                    fx += CallEffect.Publish(state)
                }
                ReconnectAction.RestartIce -> {
                    val now = clock.nowMs()
                    // Nothing to restart before the first offer and answer; never offer while we owe an answer.
                    if (!negotiated() || owesAnswer(now) || offerOutstanding(now)) continue
                    renegotiateOutstanding = true
                    outstandingSince = now
                    fx += CallEffect.RestartIce(AudioProfile.forMode(ownCeiling))
                }
                ReconnectAction.RequestRestart -> {
                    if (!negotiated() || owesAnswer(clock.nowMs())) continue
                    fx += CallEffect.SendFrame(CallFrame.SIGNAL, CallSignalPayload(Kind.renegotiate, callID))   // callee asks; no SDP
                }
                ReconnectAction.Recovered -> {
                    markActive()
                    update { it.copy(phase = CallPhase.Active, banner = null) }
                    fx += CallEffect.Publish(state)
                }
                ReconnectAction.GiveUp -> {
                    update { it.copy(banner = CallBanner.ConnectionLost) }
                    fx += end(CallEndReason.ConnectionLost, notifyServer = true)
                }
            }
        }
        return fx
    }

    private fun tick(): List<CallEffect> {
        if (!isLive) return emptyList()
        val fx = mutableListOf<CallEffect>()
        val since = activeSince
        val phase = state?.phase
        if (since != null && (phase == CallPhase.Active || phase == CallPhase.Reconnecting)) {
            val e = (clock.nowMs() - since) / 1000.0
            if (e != state?.elapsed) {
                update { it.copy(elapsed = e) }
                fx += CallEffect.Publish(state)
            }
        }
        fx += reconnectActions(reconnect.tick(clock.nowMs()))
        return fx
    }

    private fun stats(event: CallEvent.Stats): List<CallEffect> {
        val phase = state?.phase
        if (!isLive || !(phase == CallPhase.Active || phase == CallPhase.Reconnecting)) return emptyList()
        val s = event.stats
        val out = adaptor.step(s)
        update {
            it.copy(
                effectiveMode = out.mode.core(),
                quality = out.quality.core(),
                isVideoOn = out.video != null,
                banner = if (it.phase != CallPhase.Reconnecting) out.banner?.core() else it.banner,
                bytesUsed = s.bytesSent + s.bytesReceived + 28 * (s.packetsSent + s.packetsReceived),
            )
        }
        return syncMedia() + CallEffect.Publish(state) + renegotiationIfNeeded()
    }

    /** Instant media changes: bitrate and video tier follow the adaptor without renegotiation. */
    private fun syncMedia(): List<CallEffect> {
        val fx = mutableListOf<CallEffect>()
        val mode = adaptor.currentMode
        val tier = adaptor.currentTier
        if (mode != lastSentMode) {
            lastSentMode = mode
            fx += CallEffect.SetAudioMaxBitrate(AudioProfile.forMode(mode).maxAverageBitrate)
        }
        // The media layer clamps the tier to the ceiling it is given, so a ceiling change re-sends a running tier.
        val ceiling = effectiveCeiling
        if (tier != lastSentTier || (tier != null && ceiling != lastSentCeiling)) {
            lastSentTier = tier
            lastSentCeiling = ceiling
            fx += CallEffect.SetVideo(tier, ceiling)
        }
        return fx
    }

    /** A change of my send ptime, FEC or DTX needs a new offer; bitrate-only changes do not. */
    private fun renegotiationIfNeeded(): List<CallEffect> {
        val negotiated = negotiatedSend
        if (!isLive || state?.phase != CallPhase.Active || !negotiated() || negotiated == null) return emptyList()
        val want = AudioProfile.forMode(currentMode())
        if (want.ptime == negotiated.ptime && want.inbandFEC == negotiated.inbandFEC && want.dtx == negotiated.dtx) return emptyList()
        val now = clock.nowMs()
        if (offerOutstanding(now) || owesAnswer(now)) return emptyList()
        renegotiateOutstanding = true
        outstandingSince = now
        return listOf(CallEffect.CreateOffer(AudioProfile.forMode(ownCeiling)))
    }

    private fun modeChangeSignal(): List<CallEffect> {
        if (!answerSent) return emptyList()   // before that, the ring/offer/answer ceiling already carries it
        return listOf(CallEffect.SendFrame(CallFrame.SIGNAL, CallSignalPayload(Kind.modeChange, callID, ceiling = ownCeiling)))
    }

    private fun setPreference(event: CallEvent.SetPreference): List<CallEffect> {
        if (!isLive) return emptyList()
        val before = ownCeiling
        update { it.copy(inCallPreference = event.preference) }
        return ceilingChanged(before)
    }

    private fun setVideo(on: Boolean): List<CallEffect> {
        if (!isLive) return emptyList()
        adaptor.setVideoWanted(on)
        val tier = adaptor.currentTier
        val ceiling = effectiveCeiling
        lastSentTier = tier
        lastSentCeiling = ceiling
        update { it.copy(isVideoOn = tier != null) }
        return listOf(CallEffect.SetVideo(tier, ceiling), CallEffect.Publish(state))
    }

    private fun contextChanged(c: CallContext): List<CallEffect> {
        val before = ownCeiling
        context = c
        if (!isLive) return emptyList()
        update { it.copy(network = c.network) }
        return ceilingChanged(before)
    }

    /** Own setting may have moved: re-clamp the adaptor, tell the media layer, tell the peer, renegotiate if needed. */
    private fun ceilingChanged(before: DataMode): List<CallEffect> {
        adaptor.setCeiling(effectiveCeiling)
        val mode = adaptor.currentMode
        update { it.copy(effectiveMode = mode.core()) }
        val fx = syncMedia().toMutableList()
        if (ownCeiling != before) fx += modeChangeSignal()
        fx += CallEffect.Publish(state)
        return fx + renegotiationIfNeeded()
    }

    private fun hangup(): List<CallEffect> {
        if (!isLive) return emptyList()
        if (!outgoing && state?.phase == CallPhase.Incoming) return decline()
        return end(CallEndReason.HungUp, notifyServer = true)
    }

    /**
     * A ringing callee failing on its own must not decline: that would end the call on the caller and every
     * other device. Once answered, or when calling, the peer is told.
     */
    private fun localFailure(): List<CallEffect> {
        if (!isLive) return emptyList()
        val ringingCallee = !outgoing && state?.phase == CallPhase.Incoming
        return end(CallEndReason.Failed, notifyServer = !ringingCallee)
    }

    // Helpers

    internal val hasBufferedState: Boolean
        get() = pendingLocalCandidates.isNotEmpty() || earlyRemoteCandidates.isNotEmpty() || answeringDevice != null

    private val isLive: Boolean get() = state?.isEnded == false

    /** The first offer and answer are done on both sides. */
    private fun negotiated() = remoteDescriptionSet && answerSent

    private fun owesAnswer(now: Long) = pendingRenegotiateSince?.let { now - it < STALE_ANSWER_AFTER_MS } ?: false

    private fun offerOutstanding(now: Long) =
        renegotiateOutstanding && (outstandingSince?.let { now - it < RESTART_WINDOW_MS } ?: false)

    private fun currentMode(): DataMode = state?.effectiveMode?.wire() ?: ownCeiling

    private fun startsOff(ceiling: DataMode) =
        VideoLadder.startsOff(ceiling, context.network == NetworkType.Mobile, context.settings.call.videoOffOnMobile)

    private fun relayPolicy() =
        RelayDecision.policy(PolicyIPHiding.valueOf(context.settings.ipHiding.name), context.isContact)

    private fun setPeerCeiling(c: DataMode) {
        peerCeiling = c
        adaptor.setCeiling(effectiveCeiling)
        val mode = adaptor.currentMode
        update { it.copy(effectiveMode = mode.core()) }
    }

    private fun markActive() {
        if (!everActive) {
            everActive = true
            activeSince = clock.nowMs()
        }
    }

    private inline fun update(change: (CallState) -> CallState) {
        state = state?.let(change)
    }

    private inline fun live(change: (CallState) -> CallState): List<CallEffect>? {
        if (!isLive) return null
        update(change)
        return listOf(CallEffect.Publish(state))
    }

    private fun end(reason: CallEndReason, notifyServer: Boolean): List<CallEffect> {
        val s = state
        if (!isLive || s == null) return emptyList()
        val duration = activeSince?.let { (clock.nowMs() - it) / 1000.0 } ?: 0.0
        val wasActive = everActive
        update {
            it.copy(
                phase = CallPhase.Ended(reason),
                banner = if (reason != CallEndReason.ConnectionLost) null else it.banner,
                elapsed = if (wasActive) duration else it.elapsed,
            )
        }
        val summary: CallSummary = when {
            wasActive -> if (s.isVideoCall) CallSummary.Video(duration) else CallSummary.Audio(duration)
            outgoing -> CallSummary.Audio(0.0)
            else -> if (s.isVideoCall) CallSummary.MissedVideo else CallSummary.MissedAudio
        }
        pendingLocalCandidates.clear()
        earlyRemoteCandidates.clear()
        localOfferSDP = null
        pendingRenegotiateSince = null
        outstandingSince = null
        lastHonouredRequest = null
        answeringDevice = null
        pinnedFingerprint = null
        renegotiateOutstanding = false
        val fx = mutableListOf<CallEffect>()
        if (notifyServer) fx += CallEffect.SendFrame(CallFrame.HANGUP)
        fx += CallEffect.End(reason)
        if (reason != CallEndReason.AnsweredElsewhere) fx += CallEffect.RecordHistory(summary, outgoing)
        fx += CallEffect.Publish(state)
        return fx
    }
}

// :core and :contracts each have their own DataMode (same cases, same order); the banner and quality enums too.
private fun CoreMode.wire(): DataMode = DataMode.valueOf(name)
private fun DataMode.core(): CoreMode = CoreMode.valueOf(name)
private fun PolicyBanner.core(): CallBanner = CallBanner.valueOf(name)
private fun PolicyQuality.core(): CallQuality = CallQuality.valueOf(name)
