import AVFoundation
import CallLabKit
import CoreMedia
import Foundation
import Network
@preconcurrency import WebRTC

// UNVERIFIED: written against the WebRTC M156 Objective-C API (RTCPeerConnection, RTCRtpSender.parameters,
// RTCStatisticsReport) without a compiler. Fix any signature drift on the first CI build.
// All pure logic (SDP munging, sender limits, frames, stats rows, restart policy, run naming) lives in
// CallLabKit and has Linux tests.

struct UncheckedBox<T>: @unchecked Sendable { let value: T }

struct IncomingCall {
    var callID: String
    var fromAccountID: String
    var fromDeviceID: String
    var video: Bool
    var offerSDP: String
    var knobs: LabKnobs?
}

enum LabError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let m) = self { m } else { nil } }
}

/// Bridges the peer connection's delegate callbacks (WebRTC threads) to `LabCall` on the main actor.
final class PeerDelegate: NSObject, RTCPeerConnectionDelegate, @unchecked Sendable {
    var onIceState: (@Sendable (RTCIceConnectionState) -> Void)?
    var onCandidate: (@Sendable (String, String?, Int32) -> Void)?
    var onRemoteVideo: (@Sendable (UncheckedBox<RTCVideoTrack>) -> Void)?

    func peerConnection(_ pc: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ pc: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ pc: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ pc: RTCPeerConnection) {}
    func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceConnectionState) { onIceState?(newState) }
    func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    func peerConnection(_ pc: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        onCandidate?(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
    }
    func peerConnection(_ pc: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ pc: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
    func peerConnection(_ pc: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams: [RTCMediaStream]) {
        if let v = rtpReceiver.track as? RTCVideoTrack { onRemoteVideo?(UncheckedBox(value: v)) }
    }
}

@MainActor
final class LabCall: ObservableObject {
    @Published var knobs = LabPreset.balanced.knobs
    @Published var status = "Idle"
    @Published var live = ""
    @Published var showCall = false
    @Published var lastError: String?
    @Published var incoming: IncomingCall?
    @Published var finished = false
    @Published var localVideo: RTCVideoTrack?
    @Published var remoteVideo: RTCVideoTrack?

    private var host = "", token = ""
    private var signaling: Signaling?
    private var eventsTask: Task<Void, Never>?
    private var statsTask: Task<Void, Never>?

    private var factory: RTCPeerConnectionFactory?
    private var pc: RTCPeerConnection?
    private let delegate = PeerDelegate()
    private var audioSender: RTCRtpSender?
    private var videoSender: RTCRtpSender?
    private var capturer: RTCCameraVideoCapturer?

    private var recorder: StatsRecorder?
    private var presetName = "custom"
    private var callID = ""
    private var isCaller = false
    private var peerDeviceID: String?
    private var pendingCandidates: [RTCIceCandidate] = []
    private var policy = IceRestartPolicy(afterSeconds: nil)
    private var everConnected = false
    private var disconnected = false
    private var pathMonitor: NWPathMonitor?
    private var lastPathSignature: String?

    func configure(host: String, token: String) { self.host = host; self.token = token }

    // MARK: entry points

    func listen() async {
        lastError = nil; finished = false
        do {
            try connectSignaling()
            status = "Waiting for a call"
            showCall = true
        } catch { fail(error) }
    }

    func start(peerAccountID: String) async {
        lastError = nil; finished = false
        do {
            try connectSignaling()
            isCaller = true
            callID = UUID().uuidString
            try beginRun()
            status = "Calling"
            showCall = true
            let turn = try await Signaling.fetchTurn(host: host, token: token)
            try await makePeerConnection(turn: turn)
            let sdp = try await createOffer(iceRestart: false)
            let munged = SDPMunger.apply(knobs, to: sdp)
            try await setLocal(.offer, munged)
            applySenderLimits()
            let payload = try LabPayload(kind: .offer, sdp: munged, knobs: knobs).encoded()
            try await signaling?.send(.invite(callID: callID, to: peerAccountID, video: knobs.video != nil, payload: payload))
            recorder?.event(.inviteSent)
        } catch { fail(error) }
    }

    func answer() async {
        guard let inc = incoming else { return }
        incoming = nil
        do {
            isCaller = false
            if let k = inc.knobs { knobs = k }
            let turn = try await Signaling.fetchTurn(host: host, token: token)
            try await makePeerConnection(turn: turn)
            try await setRemote(.offer, inc.offerSDP)
            try await flushPendingCandidates()
            let sdp = try await createAnswer()
            let munged = SDPMunger.apply(knobs, to: sdp)
            try await setLocal(.answer, munged)
            applySenderLimits()
            let payload = try LabPayload(kind: .answer, sdp: munged).encoded()
            try await signaling?.send(.answer(callID: callID, payload: payload))
            recorder?.event(.answerSent)
            status = "Connecting"
        } catch { fail(error) }
    }

    func decline() async {
        guard let inc = incoming else { return }
        incoming = nil
        try? await signaling?.send(.decline(callID: inc.callID))
        recorder?.event(.ended, detail: "declined")
        finish()
    }

    func hangup() async {
        if !callID.isEmpty { try? await signaling?.send(.hangup(callID: callID)) }
        recorder?.event(.ended, detail: "hangup")
        finish()
    }

    /// Zips the run folder for sharing; nil if there was no run.
    func exportZip() -> URL? {
        guard let dir = recorder?.runDir else { return nil }
        return try? RunExport.zip(runDir: dir)
    }

    // MARK: signaling

    private func connectSignaling() throws {
        guard signaling == nil else { return }
        guard let s = Signaling(host: host, token: token) else { throw LabError.message("Bad host") }
        signaling = s
        s.connect()
        eventsTask = Task { [weak self] in
            for await ev in s.events { await self?.handle(ev) }
        }
    }

    private func beginRun() throws {
        presetName = RunNaming.presetName(for: knobs)
        let id = RunNaming.runID(date: Date(), preset: presetName, path: knobs.path)
        recorder = try StatsRecorder(runID: id)
        policy = IceRestartPolicy(afterSeconds: knobs.iceRestartAfter)
        everConnected = false; disconnected = false; peerDeviceID = nil; pendingCandidates = []
    }

    private func handle(_ ev: LabServerEvent) async {
        do {
            switch ev {
            case let .ring(cid, from, fromDev, video, payload):
                guard callID.isEmpty || finished else { return }   // busy
                let p = try LabPayload.decode(payload)
                guard p.kind == .offer, let sdp = p.sdp else { return }
                callID = cid; isCaller = false; peerDeviceID = fromDev
                if let k = p.knobs { knobs = k }
                try beginRun()
                recorder?.event(.ringReceived)
                incoming = IncomingCall(callID: cid, fromAccountID: from, fromDeviceID: fromDev, video: video,
                                        offerSDP: sdp, knobs: p.knobs)
                status = "Incoming call"
                showCall = true
            case let .answered(cid, deviceID, payload):
                guard cid == callID, isCaller else { return }
                let p = try LabPayload.decode(payload)
                guard p.kind == .answer, let sdp = p.sdp else { return }
                peerDeviceID = deviceID
                recorder?.event(.answerReceived)
                try await setRemote(.answer, sdp)
                try await flushPendingCandidates()
                status = "Connecting"
            case let .signal(cid, fromDev, payload):
                guard cid == callID, fromDev == peerDeviceID else { return }
                try await handleSignal(LabPayload.decode(payload))
            case let .ended(cid, reason):
                guard cid == callID else { return }
                recorder?.event(.ended, detail: reason)
                incoming = nil
                finish()
            case let .error(_, code):
                lastError = "Server error: \(code)"
            case .ack, .other: break
            }
        } catch { fail(error) }
    }

    private func handleSignal(_ p: LabPayload) async throws {
        switch p.kind {
        case .candidate:
            guard let c = p.candidate else { return }
            let cand = RTCIceCandidate(sdp: c, sdpMLineIndex: p.sdpMLineIndex ?? 0, sdpMid: p.sdpMid)
            if let pc, pc.remoteDescription != nil { try await add(cand) } else { pendingCandidates.append(cand) }
        case .offer:   // ICE restart from the peer
            guard let sdp = p.sdp, let pc else { return }
            if pc.signalingState == .haveLocalOffer {
                if isCaller { return }   // caller is impolite: keeps its own offer
                try await setLocal(.rollback, "")
            }
            try await setRemote(.offer, sdp)
            let a = SDPMunger.apply(knobs, to: try await createAnswer())
            try await setLocal(.answer, a)
            try await signaling?.send(.signal(callID: callID, payload: try LabPayload(kind: .answer, sdp: a).encoded()))
        case .answer:
            if let sdp = p.sdp { try await setRemote(.answer, sdp) }
        case .bye:
            recorder?.event(.ended, detail: "bye"); finish()
        }
    }

    // MARK: peer connection

    private func makePeerConnection(turn: TurnConfig) async throws {
        configureAudioSession()
        let factory = RTCPeerConnectionFactory()
        self.factory = factory
        let cfg = RTCConfiguration()
        cfg.iceServers = turn.iceServers.map { RTCIceServer(urlStrings: $0.urls, username: $0.username, credential: $0.credential) }
        cfg.sdpSemantics = .unifiedPlan
        cfg.iceTransportPolicy = knobs.path == .relay ? .relay : .all
        cfg.continualGatheringPolicy = .gatherContinually
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        delegate.onIceState = { [weak self] s in Task { @MainActor in self?.iceStateChanged(s) } }
        delegate.onCandidate = { [weak self] sdp, mid, idx in
            Task { @MainActor in await self?.sendCandidate(sdp, mid, idx) }
        }
        delegate.onRemoteVideo = { [weak self] box in Task { @MainActor in self?.remoteVideo = box.value } }
        guard let pc = factory.peerConnection(with: cfg, constraints: constraints, delegate: delegate) else {
            throw LabError.message("Could not create the peer connection")
        }
        self.pc = pc

        let audioSource = factory.audioSource(with: constraints)
        audioSender = pc.add(factory.audioTrack(with: audioSource, trackId: "a0"), streamIds: ["s0"])
        if let rung = knobs.video { startVideo(factory: factory, pc: pc, rung: rung) }
        startMonitors()
    }

    private func startVideo(factory: RTCPeerConnectionFactory, pc: RTCPeerConnection, rung: VideoRung) {
        let source = factory.videoSource()
        let track = factory.videoTrack(with: source, trackId: "v0")
        videoSender = pc.add(track, streamIds: ["s0"])
        localVideo = track
        let cap = RTCCameraVideoCapturer(delegate: source)
        capturer = cap
        guard let device = RTCCameraVideoCapturer.captureDevices().first(where: { $0.position == .front })
            ?? RTCCameraVideoCapturer.captureDevices().first else { return }
        let formats = RTCCameraVideoCapturer.supportedFormats(for: device)
        let best = formats.min { a, b in
            func d(_ f: AVCaptureDevice.Format) -> Int32 {
                let dim = CMVideoFormatDescriptionGetDimensions(f.formatDescription)
                return abs(dim.width - Int32(rung.width)) + abs(dim.height - Int32(rung.height))
            }
            return d(a) < d(b)
        }
        guard let fmt = best else { return }
        // The camera format is the closest one; the source then scales to exactly the rung.
        source.adaptOutputFormat(toWidth: Int32(rung.width), height: Int32(rung.height), fps: Int32(rung.fps))
        cap.startCapture(with: device, format: fmt, fps: rung.fps)
    }

    private func configureAudioSession() {
        #if os(iOS)
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetooth])
        try? s.setActive(true)
        #endif
    }

    /// maxBitrate from `SenderLimits` (audio: senderMaxBitrate; video: the rung's maxBitrate).
    private func applySenderLimits() {
        let l = SenderLimits(knobs)
        func setMax(_ sender: RTCRtpSender?, _ bps: Int?) {
            guard let sender, let bps else { return }
            let p = sender.parameters
            for e in p.encodings { e.maxBitrateBps = NSNumber(value: bps) }
            sender.parameters = p
        }
        setMax(audioSender, l.audioMaxBitrateBps)
        setMax(videoSender, l.videoMaxBitrateBps)
    }

    private func iceStateChanged(_ s: RTCIceConnectionState) {
        guard let rec = recorder else { return }
        switch s {
        case .connected, .completed:
            if disconnected {
                disconnected = false; rec.event(.reconnected)
            } else if !everConnected {
                everConnected = true; rec.event(.iceConnected)
                status = "Connected"
                applySenderLimits()
                startStats()
            }
            policy.stateChanged(.connected, atMs: rec.nowMs)
        case .disconnected:
            if !disconnected { disconnected = true; rec.event(.disconnected) }
            policy.stateChanged(.disconnected, atMs: rec.nowMs)
        case .failed:
            rec.event(.disconnected, detail: "failed")
            status = "Failed"
        default: break
        }
    }

    private func sendCandidate(_ sdp: String, _ mid: String?, _ idx: Int32) async {
        guard !callID.isEmpty, let payload = try? LabPayload(kind: .candidate, candidate: sdp, sdpMid: mid, sdpMLineIndex: idx).encoded()
        else { return }
        try? await signaling?.send(.signal(callID: callID, payload: payload))
    }

    // MARK: ICE restart

    private func startMonitors() {
        pathMonitor?.cancel()
        lastPathSignature = nil
        let monitor = NWPathMonitor()
        pathMonitor = monitor
        monitor.pathUpdateHandler = { [weak self] path in
            let sig = path.availableInterfaces.map { "\($0.type)-\($0.name)" }.joined(separator: ",")
            Task { @MainActor in self?.pathChanged(sig) }
        }
        monitor.start(queue: DispatchQueue(label: "calllab.path"))
    }

    private func pathChanged(_ sig: String) {
        defer { lastPathSignature = sig }
        guard lastPathSignature != nil, lastPathSignature != sig, everConnected else { return }
        if policy.interfaceChanged() { Task { await sendRestart() } }
    }

    private func sendRestart() async {
        guard pc != nil, !finished else { return }
        do {
            let sdp = SDPMunger.apply(knobs, to: try await createOffer(iceRestart: true))
            try await setLocal(.offer, sdp)
            try await signaling?.send(.signal(callID: callID, payload: try LabPayload(kind: .offer, sdp: sdp, knobs: knobs).encoded()))
            recorder?.event(.restartSent)
        } catch { lastError = error.localizedDescription }
    }

    // MARK: stats

    private func startStats() {
        statsTask?.cancel()
        statsTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                await self?.sampleStats()
            }
        }
    }

    private func sampleStats() async {
        guard let pc, let rec = recorder, !finished else { return }
        let stats: [LabStat] = await withCheckedContinuation { cont in
            pc.statistics { report in
                cont.resume(returning: report.statistics.values.map { s in
                    var values: [String: LabStatValue] = [:]
                    for (k, v) in s.values {
                        if let n = v as? NSNumber {
                            values[k] = CFGetTypeID(n) == CFBooleanGetTypeID() ? .bool(n.boolValue) : .number(n.doubleValue)
                        } else if let str = v as? String {
                            values[k] = .string(str)
                        }
                    }
                    return LabStat(type: s.type, id: s.id, values: values)
                })
            }
        }
        rec.sample(stats, knobs: knobs, preset: presetName)
        let r = StatsMapper.row(from: stats, tMs: rec.nowMs, runID: rec.runID, preset: presetName, knobs: knobs)
        live = "RTT \(r.rttMs.map { String(format: "%.0f ms", $0) } ?? "-"), sent \(r.aPktsSent ?? 0) audio packets, "
            + "local \(r.localCand ?? "-"), remote \(r.remoteCand ?? "-")"
        if policy.tick(nowMs: rec.nowMs) { await sendRestart() }
    }

    // MARK: teardown

    private func fail(_ error: Error) {
        lastError = error.localizedDescription
        status = "Failed"
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        statsTask?.cancel()
        pathMonitor?.cancel(); pathMonitor = nil
        capturer?.stopCapture()
        capturer = nil
        pc?.close()
        pc = nil; audioSender = nil; videoSender = nil
        localVideo = nil; remoteVideo = nil
        recorder?.close()
        signaling?.close(); signaling = nil
        eventsTask?.cancel()
        status = "Ended"
        callID = ""
    }

    // MARK: async wrappers over the callback API (only strings cross threads)

    private func createOffer(iceRestart: Bool) async throws -> String {
        guard let pc else { throw LabError.message("No peer connection") }
        let c = RTCMediaConstraints(mandatoryConstraints: iceRestart ? ["IceRestart": "true"] : nil, optionalConstraints: nil)
        return try await withCheckedThrowingContinuation { cont in
            pc.offer(for: c) { sdp, err in
                if let sdp { cont.resume(returning: sdp.sdp) } else { cont.resume(throwing: err ?? LabError.message("offer failed")) }
            }
        }
    }

    private func createAnswer() async throws -> String {
        guard let pc else { throw LabError.message("No peer connection") }
        let c = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        return try await withCheckedThrowingContinuation { cont in
            pc.answer(for: c) { sdp, err in
                if let sdp { cont.resume(returning: sdp.sdp) } else { cont.resume(throwing: err ?? LabError.message("answer failed")) }
            }
        }
    }

    private func setLocal(_ type: RTCSdpType, _ sdp: String) async throws {
        guard let pc else { throw LabError.message("No peer connection") }
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            pc.setLocalDescription(RTCSessionDescription(type: type, sdp: sdp)) { err in
                if let err { cont.resume(throwing: err) } else { cont.resume() }
            }
        }
    }

    private func setRemote(_ type: RTCSdpType, _ sdp: String) async throws {
        guard let pc else { throw LabError.message("No peer connection") }
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            pc.setRemoteDescription(RTCSessionDescription(type: type, sdp: sdp)) { err in
                if let err { cont.resume(throwing: err) } else { cont.resume() }
            }
        }
    }

    private func add(_ c: RTCIceCandidate) async throws {
        guard let pc else { return }
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            pc.add(c) { err in if let err { cont.resume(throwing: err) } else { cont.resume() } }
        }
    }

    private func flushPendingCandidates() async throws {
        let list = pendingCandidates
        pendingCandidates = []
        for c in list { try await add(c) }
    }
}
