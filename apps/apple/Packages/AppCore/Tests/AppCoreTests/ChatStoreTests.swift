import Foundation
import Testing
@testable import AppCore

func msg(_ c: MessageContent) -> Message {
    Message(id: "m", senderID: "riya", sentAt: .now, content: c, status: .read, replyToID: nil, reactions: [])
}

@Test func previews() {
    #expect(msg(.voice(duration: 14)).previewText == "Voice message 0:14")
    #expect(msg(.photo(bytes: 240_000)).previewText == "Photo")
    #expect(msg(.file(name: "Report.pdf", bytes: 1_200_000)).previewText == "Report.pdf")
    #expect(msg(.call(.missedAudio)).previewText == "Missed audio call")
    #expect(msg(.call(.missedVideo)).previewText == "Missed video call")
    #expect(msg(.call(.audio(duration: 300))).previewText == "Audio call · 5 min")
    #expect(msg(.call(.video(duration: 720))).previewText == "Video call · 12 min")
    #expect(msg(.text("Hi")).previewText == "Hi")
}

@Test func encryptionInfo() {
    #expect(ChatEncryption.endToEnd.infoText == "End-to-end encrypted")
    #expect(ChatEncryption.serverReadable.infoText == "Not end-to-end encrypted: the server admin can read this chat")
}

@Test func foldersAndRequests() {
    let s = SampleChatStore()
    #expect(s.folders.map(\.name) == ["All", "Unread", "Personal", "Work"])
    #expect(s.chats(in: .all).allSatisfy { !$0.isRequest })
    #expect(s.folderChipTitle(.unread) == "Unread 1")
    #expect(s.messageRequestsRowTitle == "Message requests · 1")
}

@Test func strangerFlow() {
    let s = SampleChatStore()
    let x = Contact(id: "x", displayName: "X", username: "x")
    s.receive(.text("hello"), from: x)
    #expect(s.messageRequests.count == 2)
    #expect(s.incomingCall(from: x, video: false) == .silentMissed)
    let req = s.messageRequests.first { $0.contactID == "x" }!
    #expect(req.lastMessage?.content == .call(.missedAudio))
    s.accept(requestID: req.id)
    #expect(s.contacts.contains(x) && s.incomingCall(from: x, video: true) == .ring)
}

@Test func markReadClearsUnread() {
    let s = SampleChatStore()
    let riya = s.chats(in: .unread)[0]
    s.markRead(chatID: riya.id)
    #expect(s.folderChipTitle(.unread) == "Unread")
}

@Test func sampleHasLongAndRTLNames() {
    let names = SampleChatStore().contacts.map(\.displayName)
    #expect(names.contains("مريم") && names.contains("प्रिया") && names.contains { $0.count > 30 })
}

@Test func blockRemovesChatAndContact() {
    let s = SampleChatStore()
    let req = s.messageRequests[0]
    s.block(contactID: req.contactID)
    #expect(s.messageRequests.isEmpty && s.messageRequestsRowTitle == nil)
}

@Test func addContactByUsername() {
    let s = SampleChatStore()
    #expect(s.addContact(username: "neha")?.displayName == "Neha")
    #expect(s.addContact(username: "nobody") == nil)
}

@Test func sendAppends() {
    let s = SampleChatStore()
    let chat = s.chats(in: .all)[0]
    s.send(.text("Hello"), in: chat.id)
    #expect(s.chat(id: chat.id)?.lastMessage?.content == .text("Hello"))
    #expect(s.chat(id: chat.id)?.lastMessage?.status == .sent)
}
