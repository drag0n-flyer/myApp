import AppCore
import Foundation
import LocalStore
import Testing
@testable import Messaging

@Suite struct ExpiryMappingTests {
    private func record(id: String = "m1", expiresAt: Date?, deleted: Bool = false) -> MessageRecord {
        MessageRecord(
            id: id, chatID: "c1", conversationID: nil, senderAccount: "acc-bob", senderDevice: nil,
            sentAt: Date(timeIntervalSince1970: 1_000), receivedAt: Date(timeIntervalSince1970: 1_001),
            envelope: ContentEnvelope(id: id, at: 1_000_000, body: .text("hi", replyTo: nil), ttl: 60).encoded(),
            status: "sent", replyTo: nil, editedAt: nil, deleted: deleted, undecryptable: false, folded: "hi", expiresAt: expiresAt)
    }

    @Test func messageCarriesItsExpiry() {
        let at = Date(timeIntervalSince1970: 1_060)
        let m = ChatMapping.message(record(expiresAt: at), attachment: nil, reactions: [], displayName: { _ in nil })
        #expect(m.expiresAt == at)
        #expect(m.disappears)
    }

    @Test func messageWithoutExpiryHasNone() {
        let m = ChatMapping.message(record(expiresAt: nil), attachment: nil, reactions: [], displayName: { _ in nil })
        #expect(m.expiresAt == nil)
        #expect(!m.disappears)
    }

    @Test func expiryIsKeptOnADeletedMessage() {
        let at = Date(timeIntervalSince1970: 1_060)
        let m = ChatMapping.message(record(expiresAt: at, deleted: true), attachment: nil, reactions: [], displayName: { _ in nil })
        #expect(m.content == .deleted)
        #expect(m.expiresAt == at)
    }
}
