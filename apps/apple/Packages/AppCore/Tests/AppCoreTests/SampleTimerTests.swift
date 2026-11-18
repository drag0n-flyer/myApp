import Foundation
import Testing
@testable import AppCore

@MainActor
@Suite struct SampleTimerTests {
    private func store() -> (SampleChatStore, String) {
        let s = SampleChatStore()
        let id = s.chats(in: .all).first!.id
        return (s, id)
    }

    @Test func startsOff() {
        let (s, id) = store()
        #expect(s.timerSeconds(id) == 0)
    }

    @Test func choosingATimerWritesTheOwnSystemLine() {
        let (s, id) = store()
        let before = s.chat(id: id)!.messages.count
        s.setTimer(id, seconds: 86_400)
        #expect(s.timerSeconds(id) == 86_400)
        let last = s.chat(id: id)!.messages.last!
        #expect(last.content == .system(.timer(seconds: 86_400, name: nil)))
        #expect(s.chat(id: id)!.messages.count == before + 1)
    }

    @Test func sameChoiceAndOddNumbersDoNothing() {
        let (s, id) = store()
        s.setTimer(id, seconds: 604_800)
        let count = s.chat(id: id)!.messages.count
        s.setTimer(id, seconds: 604_800)
        s.setTimer(id, seconds: 12)
        #expect(s.chat(id: id)!.messages.count == count)
        #expect(s.timerSeconds(id) == 604_800)
    }

    @Test func nextMessageCarriesTheExpiry() {
        let (s, id) = store()
        s.setTimer(id, seconds: 86_400)
        s.send(.text("hi"), in: id, replyTo: nil)
        let sent = s.chat(id: id)!.messages.last!
        #expect(sent.expiresAt == sent.sentAt.addingTimeInterval(86_400))
        #expect(s.nextExpiry() == sent.expiresAt)
        s.setTimer(id, seconds: 0)
        s.send(.text("later"), in: id, replyTo: nil)
        #expect(s.chat(id: id)!.messages.last!.expiresAt == nil)
    }

    @Test func sweepRemovesOnlyDueMessages() {
        let (s, id) = store()
        s.setTimer(id, seconds: 86_400)
        s.send(.text("hi"), in: id, replyTo: nil)
        let sent = s.chat(id: id)!.messages.last!
        #expect(s.sweepExpired(now: sent.expiresAt!.addingTimeInterval(-0.001)).isEmpty)
        #expect(s.sweepExpired(now: sent.expiresAt!) == [sent.id])
        #expect(s.chat(id: id)!.messages.contains { $0.id == sent.id } == false)
        #expect(s.nextExpiry() == nil)
    }
}
