import Foundation

public enum LabEvent: String, CaseIterable, Sendable {
    case inviteSent = "invite_sent", ringReceived = "ring_received", answerSent = "answer_sent"
    case answerReceived = "answer_received", iceConnected = "ice_connected", firstAudioPacket = "first_audio_packet"
    case disconnected, restartSent = "restart_sent", reconnected, ended
}

/// `events.csv`: `t_ms,event,detail`.
public struct EventLog: Sendable {
    public static let csvHeader = "t_ms,event,detail"
    public private(set) var lines: [String] = []
    public init() {}

    public mutating func record(_ e: LabEvent, atMs: Int, detail: String = "") {
        var d = detail
        if d.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" }) {
            d = "\"" + d.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        lines.append("\(atMs),\(e.rawValue),\(d)")
    }

    public var csv: String { ([Self.csvHeader] + lines).joined(separator: "\n") + "\n" }
}
