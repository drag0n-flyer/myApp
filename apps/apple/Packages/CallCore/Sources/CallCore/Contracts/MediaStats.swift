import Foundation

public enum IceTransportPolicy: Sendable, Equatable { case all, relay }
public struct IceServer: Sendable, Equatable {
    public var urls: [String]
    public var username: String?
    public var credential: String?
    public init(urls: [String], username: String? = nil, credential: String? = nil) {
        self.urls = urls; self.username = username; self.credential = credential
    }
}
public enum IceState: Sendable, Equatable { case new, checking, connected, completed, disconnected, failed, closed }
public enum VideoTier: Int, Sendable, Comparable, CaseIterable {
    case p180, p270, p360
    public static func < (a: VideoTier, b: VideoTier) -> Bool { a.rawValue < b.rawValue }
}

public struct MediaStats: Sendable, Equatable {
    public var availableOutgoingBitrate: Double?   // bps, from the selected candidate pair
    public var roundTripTime: Double?              // seconds
    public var inboundLossFraction: Double         // 0…1, audio inbound-rtp, since the last sample
    public var jitter: Double?                     // seconds
    public var bytesSent: Int64, bytesReceived: Int64, packetsSent: Int64, packetsReceived: Int64
    public var usingRelay: Bool                    // local candidate type == relay
    public var audioCodec: String?                 // log only

    public init(availableOutgoingBitrate: Double? = nil, roundTripTime: Double? = nil,
                inboundLossFraction: Double = 0, jitter: Double? = nil,
                bytesSent: Int64 = 0, bytesReceived: Int64 = 0,
                packetsSent: Int64 = 0, packetsReceived: Int64 = 0,
                usingRelay: Bool = false, audioCodec: String? = nil) {
        self.availableOutgoingBitrate = availableOutgoingBitrate; self.roundTripTime = roundTripTime
        self.inboundLossFraction = inboundLossFraction; self.jitter = jitter
        self.bytesSent = bytesSent; self.bytesReceived = bytesReceived
        self.packetsSent = packetsSent; self.packetsReceived = packetsReceived
        self.usingRelay = usingRelay; self.audioCodec = audioCodec
    }
}
