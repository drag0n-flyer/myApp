import Testing
import Foundation
@testable import AppCore

@Test func simulatedIncomingAnswer() {
    let e = engine()
    e.simulateIncoming(from: riya, video: false)
    #expect(e.state?.phase == .incoming)
    e.answer(video: false)
    #expect(e.state?.phase == .active)
}

@Test func simulatedDecline() {
    let e = engine()
    e.simulateIncoming(from: riya, video: false)
    e.decline()
    #expect(e.state?.phase == .ended(.declined))
    e.setMuted(true)
    #expect(e.state?.isMuted == false)
}

@Test func endReasonTexts() {
    #expect(CallEndReason.declined.text == "Call declined")
    #expect(CallEndReason.missed.text == "No answer")
    #expect(CallEndReason.answeredElsewhere.text == "Answered on another device")
    #expect(CallEndReason.failed.text == "Call failed")
}

@Test func recordCallAppendsHistory() {
    let store = SampleChatStore()
    let c = store.contacts[0]
    let chatID = store.ensureChat(with: c.id)
    let d = Date(timeIntervalSince1970: 1_000)
    store.recordCall(.missedAudio, contactID: c.id, outgoing: false, at: d)
    let last = store.chat(id: chatID)?.messages.last
    #expect(last?.content == .call(.missedAudio) && last?.senderID == c.id && last?.sentAt == d)
    store.recordCall(.audio(duration: 3), contactID: c.id, outgoing: true, at: d)
    #expect(store.chat(id: chatID)?.messages.last?.senderID == store.me.id)
}
