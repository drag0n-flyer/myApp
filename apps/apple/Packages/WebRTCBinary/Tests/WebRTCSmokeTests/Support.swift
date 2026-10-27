import Foundation
import WebRTC

/// A value filled in by a WebRTC callback on another thread.
final class Box<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: T?
    var value: T? {
        get { lock.lock(); defer { lock.unlock() }; return stored }
        set { lock.lock(); defer { lock.unlock() }; stored = newValue }
    }

    /// Polls until a value arrives or `seconds` pass.
    func wait(seconds: Double) async -> T? {
        let deadline = Date().addingTimeInterval(seconds)
        while value == nil, Date() < deadline {
            try? await Task.sleep(for: .milliseconds(50))
        }
        return value
    }
}

/// A peer connection delegate that records ICE state and forwards candidates.
final class PeerDelegate: NSObject, RTCPeerConnectionDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var _iceState: RTCIceConnectionState = .new
    var onCandidate: (@Sendable (RTCIceCandidate) -> Void)?

    var iceState: RTCIceConnectionState {
        lock.lock(); defer { lock.unlock() }
        return _iceState
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        lock.lock(); _iceState = newState; lock.unlock()
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        onCandidate?(candidate)
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}

/// One factory, one peer connection and one send/receive audio transceiver.
final class AudioFixture {
    let factory = RTCPeerConnectionFactory()
    let delegate = PeerDelegate()
    let connection: RTCPeerConnection
    let transceiver: RTCRtpTransceiver

    init?() {
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let connection = factory.peerConnection(
            with: RTCConfiguration(), constraints: constraints, delegate: delegate)
        else { return nil }
        let setup = RTCRtpTransceiverInit()
        setup.direction = .sendRecv
        guard let transceiver = connection.addTransceiver(of: .audio, init: setup) else { return nil }
        self.connection = connection
        self.transceiver = transceiver
    }

    static var offerConstraints: RTCMediaConstraints {
        RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
    }
}
