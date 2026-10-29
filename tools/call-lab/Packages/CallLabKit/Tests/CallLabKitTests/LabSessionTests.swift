import Testing
import Foundation
@testable import CallLabKit

@Suite struct LabSessionTests {
    // MARK: run naming
    @Test func runIDFormat() {
        var c = DateComponents(); c.year = 2026; c.month = 10; c.day = 28; c.hour = 9; c.minute = 5; c.second = 7
        var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "UTC")!
        let d = cal.date(from: c)!
        let id = RunNaming.runID(date: d, preset: "ultraLow", path: .relay, timeZone: TimeZone(identifier: "UTC")!)
        #expect(id == "20261028-090507-ultraLow-relay")
        #expect(RunNaming.zipName(runID: id) == "20261028-090507-ultraLow-relay.zip")
    }

    @Test func presetNameMatchesOrCustom() {
        #expect(RunNaming.presetName(for: LabPreset.balanced.knobs) == "balanced")
        var k = LabPreset.high.knobs
        k.path = .relay; k.iceRestartAfter = 5; k.senderMaxBitrate = 20_000
        #expect(RunNaming.presetName(for: k) == "high")
        k.ptime = 120
        #expect(RunNaming.presetName(for: k) == "custom")
    }

    // MARK: sender limits
    @Test func senderLimitsAudioOnly() {
        var k = LabPreset.ultraLow.knobs
        #expect(SenderLimits(k).audioMaxBitrateBps == nil)
        k.senderMaxBitrate = 12_000
        let l = SenderLimits(k)
        #expect(l.audioMaxBitrateBps == 12_000 && l.videoMaxBitrateBps == nil && l.capture == nil)
    }

    @Test func senderLimitsVideoFromRung() {
        let l = SenderLimits(LabPreset.balanced.knobs)
        #expect(l.videoMaxBitrateBps == 175_000)
        #expect(l.capture == CaptureFormat(width: 480, height: 270, fps: 15))
    }

    /// Mirrors the apps' bandwidth estimator ceiling (CallCore `MediaLogic.bweMaxBitrate`): the wire budget of the
    /// lowest app mode whose Opus target covers the Opus rate, plus the video cap.
    @Test func bweCeilingIsTheModeWireBudget() {
        #expect(SenderLimits(LabPreset.ultraLow.knobs).bweMaxBitrateBps == 21_000)
        #expect(SenderLimits(LabPreset.dataSaver.knobs).bweMaxBitrateBps == 30_000)
        #expect(SenderLimits(LabPreset.balanced.knobs).bweMaxBitrateBps == 51_000 + 175_000)
        var k = LabPreset.high.knobs
        k.video = nil
        #expect(SenderLimits(k).bweMaxBitrateBps == 75_000)
        k.opusBitrate = 64_000
        #expect(SenderLimits(k).bweMaxBitrateBps == 75_000)
        k = LabPreset.ultraLow.knobs
        k.senderMaxBitrate = 6_000
        #expect(SenderLimits(k).bweMaxBitrateBps == 21_000)
    }

    /// The lab's copy of the wire budgets must match the apps' table (CallCore `AudioProfile.table`), read from source
    /// because the lab does not link CallCore.
    @Test func wireBudgetsMatchTheAppsAudioProfileTable() throws {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("apps/apple/Packages/CallCore/Sources/CallCore/Policy/AudioProfile+Table.swift")
        let src = try String(contentsOf: url, encoding: .utf8)
        func numbers(_ key: String) -> [Int] {
            src.components(separatedBy: key + ": ").dropFirst().compactMap {
                Int($0.prefix { $0.isNumber || $0 == "_" }.replacingOccurrences(of: "_", with: ""))
            }
        }
        let app = zip(numbers("maxAverageBitrate"), numbers("wireBudget")).sorted { $0.0 < $1.0 }
        #expect(app.count == 4)
        #expect(app.map(\.0) == SenderLimits.wireBudgets.map(\.opus))
        #expect(app.map(\.1) == SenderLimits.wireBudgets.map(\.wire))
    }

    // MARK: signaling frames
    @Test func inviteFrameJSON() throws {
        let f = LabClientFrame.invite(callID: "c1", to: "acct", video: true, payload: "AAA")
        let obj = try #require(JSONSerialization.jsonObject(with: f.json(id: "7")) as? [String: Any])
        #expect(obj["type"] as? String == "call.invite" && obj["id"] as? String == "7")
        let data = try #require(obj["data"] as? [String: Any])
        #expect(data["callID"] as? String == "c1" && data["to"] as? String == "acct"
                && data["video"] as? Bool == true && data["payload"] as? String == "AAA")
    }

    @Test func otherClientFrames() throws {
        func type(_ f: LabClientFrame) throws -> String {
            let o = try #require(JSONSerialization.jsonObject(with: f.json(id: nil)) as? [String: Any])
            #expect(o["id"] == nil)
            return o["type"] as! String
        }
        #expect(try type(.answer(callID: "c", payload: "p")) == "call.answer")
        #expect(try type(.signal(callID: "c", payload: "p")) == "call.signal")
        #expect(try type(.hangup(callID: "c")) == "call.hangup")
        #expect(try type(.decline(callID: "c")) == "call.decline")
    }

    @Test func parseServerFrames() throws {
        func parse(_ s: String) throws -> LabServerEvent { try LabServerEvent.parse(Data(s.utf8)) }
        #expect(try parse(#"{"type":"call.ring","data":{"callID":"c","fromAccountID":"a","fromDeviceID":"d","video":false,"payload":"P"}}"#)
                == .ring(callID: "c", fromAccountID: "a", fromDeviceID: "d", video: false, payload: "P"))
        #expect(try parse(#"{"type":"call.answered","data":{"callID":"c","deviceID":"d","payload":"P"}}"#)
                == .answered(callID: "c", deviceID: "d", payload: "P"))
        #expect(try parse(#"{"type":"call.signal","data":{"callID":"c","fromDeviceID":"d","payload":"P"}}"#)
                == .signal(callID: "c", fromDeviceID: "d", payload: "P"))
        #expect(try parse(#"{"type":"call.ended","data":{"callID":"c","reason":"hangup"}}"#)
                == .ended(callID: "c", reason: "hangup"))
        #expect(try parse(#"{"type":"ack","id":"3"}"#) == .ack(id: "3"))
        #expect(try parse(#"{"type":"error","id":"3","data":{"code":"not_found"}}"#) == .error(id: "3", code: "not_found"))
        #expect(try parse(#"{"type":"inbox","data":{}}"#) == .other(type: "inbox"))
        #expect(throws: (any Error).self) { try parse("not json") }
    }

    @Test func turnConfigDecodes() throws {
        let j = #"{"iceServers":[{"urls":["stun:h:3478","turn:h:3478?transport=udp"],"username":"u","credential":"c"}],"ttl":43200}"#
        let t = try JSONDecoder().decode(TurnConfig.self, from: Data(j.utf8))
        #expect(t.iceServers.count == 1 && t.iceServers[0].urls.count == 2 && t.iceServers[0].username == "u" && t.ttl == 43200)
    }

    // MARK: event log
    @Test func eventLogCSV() {
        var log = EventLog()
        log.record(.inviteSent, atMs: 0)
        log.record(.iceConnected, atMs: 1234, detail: "pair, udp")
        #expect(EventLog.csvHeader == "t_ms,event,detail")
        #expect(log.csv == "t_ms,event,detail\n0,invite_sent,\n1234,ice_connected,\"pair, udp\"\n")
        #expect(LabEvent.allCases.map(\.rawValue) == ["invite_sent", "ring_received", "answer_sent", "answer_received",
            "ice_connected", "first_audio_packet", "disconnected", "restart_sent", "reconnected", "ended"])
    }

    // MARK: stats mapping
    func sample() -> [LabStat] {
        [
            LabStat(type: "transport", id: "T", values: ["selectedCandidatePairId": .string("P")]),
            LabStat(type: "candidate-pair", id: "P", values: [
                "state": .string("succeeded"), "nominated": .bool(true), "currentRoundTripTime": .number(0.0425),
                "bytesSent": .number(1000), "bytesReceived": .number(2000), "availableOutgoingBitrate": .number(55_000),
                "localCandidateId": .string("L"), "remoteCandidateId": .string("R")]),
            LabStat(type: "local-candidate", id: "L", values: ["candidateType": .string("relay")]),
            LabStat(type: "remote-candidate", id: "R", values: ["candidateType": .string("host")]),
            LabStat(type: "outbound-rtp", id: "OA", values: ["kind": .string("audio"), "bytesSent": .number(500),
                "headerBytesSent": .number(100), "packetsSent": .number(25)]),
            LabStat(type: "outbound-rtp", id: "OV", values: ["kind": .string("video"), "bytesSent": .number(9000),
                "frameWidth": .number(480), "framesPerSecond": .number(14.5)]),
            LabStat(type: "inbound-rtp", id: "IA", values: ["kind": .string("audio"), "bytesReceived": .number(400),
                "headerBytesReceived": .number(80), "packetsReceived": .number(20), "packetsLost": .number(2),
                "jitter": .number(0.0031), "concealedSamples": .number(960), "totalSamplesReceived": .number(48000),
                "concealmentEvents": .number(3)]),
            LabStat(type: "inbound-rtp", id: "IV", values: ["kind": .string("video"), "bytesReceived": .number(7000)]),
        ]
    }

    @Test func statsMapping() {
        let k = LabPreset.balanced.knobs
        let r = StatsMapper.row(from: sample(), tMs: 1000, runID: "run", preset: "balanced", knobs: k)
        #expect(r.preset == "balanced" && r.opusBps == 24_000 && r.ptime == 20 && r.dtx == true && r.fec == true)
        #expect(r.playbackHz == 48_000 && r.twcc == true && r.videoRung == "p270_15" && r.path == "direct")
        #expect(r.localCand == "relay" && r.remoteCand == "host")
        #expect(r.rttMs == 42.5 && r.pairBytesSent == 1000 && r.pairBytesRecv == 2000 && r.availOutBps == 55_000)
        #expect(r.aBytesSent == 500 && r.aHdrBytesSent == 100 && r.aPktsSent == 25 && r.vBytesSent == 9000)
        #expect(r.vFrameW == 480 && r.vFpsSent == 14.5)
        #expect(r.aBytesRecv == 400 && r.aHdrBytesRecv == 80 && r.aPktsRecv == 20 && r.aPktsLost == 2)
        #expect(r.aJitterMs != nil && abs(r.aJitterMs! - 3.1) < 1e-9)
        #expect(r.concealedSamples == 960 && r.totalSamplesRecv == 48000 && r.concealmentEvents == 3 && r.vBytesRecv == 7000)
        #expect(r.csvLine.split(separator: ",", omittingEmptySubsequences: false).count
                == StatsRow.csvHeader.split(separator: ",").count)
    }

    @Test func statsFallbackToNominatedPairAndMissing() {
        let entries = [LabStat(type: "candidate-pair", id: "X", values: ["state": .string("succeeded"), "nominated": .bool(true),
                                                                       "bytesSent": .number(5)])]
        let r = StatsMapper.row(from: entries, tMs: 0, runID: "r", preset: "custom", knobs: LabPreset.ultraLow.knobs)
        #expect(r.pairBytesSent == 5 && r.rttMs == nil && r.localCand == nil && r.aPktsSent == nil && r.videoRung == nil)
        let empty = StatsMapper.row(from: [], tMs: 0, runID: "r", preset: "x", knobs: LabPreset.ultraLow.knobs)
        #expect(empty.pairBytesSent == nil)
    }

    @Test func firstAudioPacket() {
        #expect(StatsMapper.hasInboundAudio(sample()))
        var s = sample()
        s[6].values["packetsReceived"] = .number(0)
        #expect(!StatsMapper.hasInboundAudio(s))
        #expect(!StatsMapper.hasInboundAudio([]))
    }

    // MARK: ICE restart policy
    @Test func restartAfterDelay() {
        var p = IceRestartPolicy(afterSeconds: 5)
        let t1 = p.tick(nowMs: 0); #expect(!t1)
        p.stateChanged(.disconnected, atMs: 1000)
        let t2 = p.tick(nowMs: 5999); #expect(!t2)
        let t3 = p.tick(nowMs: 6000); #expect(t3)
        let t4 = p.tick(nowMs: 9000); #expect(!t4)            // only once per disconnection
        p.stateChanged(.connected, atMs: 10_000)
        p.stateChanged(.disconnected, atMs: 11_000)
        let t5 = p.tick(nowMs: 16_000); #expect(t5)
    }

    @Test func restartCancelledByReconnect() {
        var p = IceRestartPolicy(afterSeconds: 5)
        p.stateChanged(.disconnected, atMs: 0)
        p.stateChanged(.connected, atMs: 2000)
        let t6 = p.tick(nowMs: 9000); #expect(!t6)
    }

    @Test func restartOffAndInterfaceChange() {
        var off = IceRestartPolicy(afterSeconds: nil)
        off.stateChanged(.disconnected, atMs: 0)
        let t7 = off.tick(nowMs: 100_000); #expect(!t7)
        let offChanged = off.interfaceChanged()
        #expect(!offChanged)
        var on = IceRestartPolicy(afterSeconds: 5)
        let onChanged = on.interfaceChanged()
        #expect(onChanged)
    }
}

@Suite struct LabEndpointTests {
    @Test func urls() {
        #expect(LabEndpoint.wsURL(host: "home.example.org")?.absoluteString == "wss://home.example.org/v1/ws")
        #expect(LabEndpoint.turnURL(host: " home.example.org ")?.absoluteString == "https://home.example.org/v1/turn")
        #expect(LabEndpoint.wsURL(host: "http://192.168.1.5:8080")?.absoluteString == "ws://192.168.1.5:8080/v1/ws")
        #expect(LabEndpoint.turnURL(host: "https://h.example/")?.absoluteString == "https://h.example/v1/turn")
        #expect(LabEndpoint.wsURL(host: "") == nil)
    }
}
