import Foundation
import LocalStore
import SecureMessagingCore
import ServerKit
import Testing
@testable import Messaging
import MessagingTestKit

private actor RecordingSender: EphemeralSending {
    struct Sent: Equatable { let conversationID: String; let epoch: UInt64; let ciphertext: Data }
    private(set) var sent: [Sent] = []
    func sendEphemeral(conversationID: String, epoch: UInt64, ciphertext: Data) async throws {
        sent.append(Sent(conversationID: conversationID, epoch: epoch, ciphertext: ciphertext))
    }
}

private struct Rig {
    let device: TestDevice
    let spy: SpyMLSEngine
    let sender = RecordingSender()
    let channel: EphemeralChannel

    init() async throws {
        let server = FakeServer()
        device = await makeDevice(server, account: "acc-ana", username: "ana")
        spy = SpyMLSEngine(device.deps.mls)
        let repo = device.deps.repo
        try repo.upsertChat(ChatRecord(id: "acc-bob", isRequest: false, unread: 0, muted: false, lastActivity: testEpochDate))
        try repo.upsertConversation(ConversationRecord(
            serverID: "conv-1", chatID: "acc-bob", groupID: Data([1]), mode: "e2ee", innerMode: "e2ee", modeMismatch: false,
            epoch: 5, active: true, createdAt: testEpochDate))
        channel = EphemeralChannel(repo: repo, me: "acc-ana", sender: sender)
    }

    func frame(_ payload: Data, from account: String = "acc-bob", conversation: String = "conv-1", epoch: UInt64 = 99) -> ServerEvent {
        .conversationEphemeral(conversationID: conversation, epoch: epoch, fromAccountID: account, fromDeviceID: "d", ciphertext: payload)
    }

    func envelope(_ body: ContentEnvelope.Body) -> Data { ContentEnvelope(id: "e", at: 1, body: body).encoded() }

    /// What the channel yielded for the frames ingested so far (it yields synchronously).
    func drain(_ count: Int) async -> [EphemeralSignal] {
        var out: [EphemeralSignal] = []
        var it = channel.events.makeAsyncIterator()
        for _ in 0..<count { if let s = await it.next() { out.append(s) } }
        return out
    }
}

@Suite("TestEphemeralChannel") struct TestEphemeralChannel {
    @Test func sendPutsTheEnvelopeJSONInTheCiphertextField() async throws {
        let rig = try await Rig()
        try await rig.channel.send(chatID: "acc-bob", body: .typing(on: true))
        try await rig.channel.send(chatID: "acc-bob", body: .presence(.away))
        let sent = await rig.sender.sent
        #expect(sent.count == 2)
        #expect(sent[0].conversationID == "conv-1" && sent[0].epoch == 5)
        #expect(ContentEnvelope.decode(sent[0].ciphertext).body == .typing(on: true))
        #expect(ContentEnvelope.decode(sent[1].ciphertext).body == .presence(.away))
        #expect(String(decoding: sent[0].ciphertext, as: UTF8.self).contains("\"k\":\"typing\""))
        #expect(rig.spy.processed.isEmpty)
    }

    @Test func sendRefusesEverythingButTypingAndPresence() async throws {
        let rig = try await Rig()
        for body in [ContentEnvelope.Body.text("hi", replyTo: nil), .timer(seconds: 0), .delete(target: "x"), .unsupported(kind: "typing")] {
            await #expect(throws: EphemeralError.refused) { try await rig.channel.send(chatID: "acc-bob", body: body) }
        }
        #expect(await rig.sender.sent.isEmpty)
    }

    @Test func sendWithoutAConversationThrowsAndSendsNothing() async throws {
        let rig = try await Rig()
        await #expect(throws: EphemeralError.noConversation) { try await rig.channel.send(chatID: "acc-nobody", body: .typing(on: true)) }
        #expect(await rig.sender.sent.isEmpty)
    }

    @Test func roundTripYieldsChatAccountAndBody() async throws {
        let rig = try await Rig()
        rig.channel.ingest(rig.frame(rig.envelope(.typing(on: true))))
        rig.channel.ingest(rig.frame(rig.envelope(.presence(.online)), epoch: 1))
        let got = await rig.drain(2)
        #expect(got == [
            EphemeralSignal(chatID: "acc-bob", accountID: "acc-bob", body: .typing(on: true)),
            EphemeralSignal(chatID: "acc-bob", accountID: "acc-bob", body: .presence(.online)),
        ])
        #expect(rig.spy.processed.isEmpty)
    }

    @Test func everythingElseIsDroppedSilently() async throws {
        let rig = try await Rig()
        let good = rig.envelope(.typing(on: false))
        rig.channel.ingest(rig.frame(good, from: "acc-eve"))                          // not the other member
        rig.channel.ingest(rig.frame(good, from: "acc-ana"))                          // this account
        rig.channel.ingest(rig.frame(good, conversation: "conv-unknown"))             // unknown conversation
        rig.channel.ingest(rig.frame(rig.envelope(.text("hi", replyTo: nil))))        // a message
        rig.channel.ingest(rig.frame(rig.envelope(.unsupported(kind: "typing"))))     // invalid typing
        rig.channel.ingest(rig.frame(Data("not json".utf8)))
        let big = Data(good + Data(repeating: 0x20, count: 257 - good.count))
        #expect(big.count == 257)
        rig.channel.ingest(rig.frame(big))
        rig.channel.ingest(.deviceRevoked("d"))                                       // other events are not its business
        rig.channel.ingest(rig.frame(good))                                           // the one that counts
        let got = await rig.drain(1)
        #expect(got == [EphemeralSignal(chatID: "acc-bob", accountID: "acc-bob", body: .typing(on: false))])
    }

    @Test func aBlockedContactIsDropped() async throws {
        let rig = try await Rig()
        try rig.device.deps.repo.block("acc-bob")
        rig.channel.ingest(rig.frame(rig.envelope(.typing(on: true))))
        try rig.device.deps.repo.unblock("acc-bob")
        rig.channel.ingest(rig.frame(rig.envelope(.presence(.away))))
        let got = await rig.drain(1)
        #expect(got.map(\.body) == [.presence(.away)])
    }

    @Test func aPayloadOf256BytesIsKept() async throws {
        let rig = try await Rig()
        let good = rig.envelope(.typing(on: true))
        let padded = Data(good + Data(repeating: 0x20, count: 256 - good.count))
        rig.channel.ingest(rig.frame(padded))
        #expect(await rig.drain(1).count == 1)
    }
}

@Suite("TestEphemeralBuffer") struct TestEphemeralBuffer {
    @Test func the65thUnreadSignalDropsTheOldest() async throws {
        let rig = try await Rig()
        rig.channel.ingest(rig.frame(rig.envelope(.presence(.away))))            // the oldest
        for _ in 0..<64 { rig.channel.ingest(rig.frame(rig.envelope(.typing(on: true)))) }
        let got = await rig.drain(64)
        #expect(got.count == 64 && got.allSatisfy { $0.body == .typing(on: true) })
    }
}
