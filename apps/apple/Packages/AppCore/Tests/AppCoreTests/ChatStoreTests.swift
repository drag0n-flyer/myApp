import Foundation
import Testing
@testable import AppCore

func msg(_ c: MessageContent) -> Message {
    Message(id: "m", senderID: "riya", sentAt: .now, content: c, status: .read, replyToID: nil, reactions: [])
}

@MainActor @Test func previews() {
    #expect(msg(.voice(duration: 14)).previewText == "Voice message 0:14")
    #expect(msg(.photo(bytes: 240_000)).previewText == "Photo")
    #expect(msg(.file(name: "Report.pdf", bytes: 1_200_000)).previewText == "Report.pdf")
    #expect(msg(.call(.missedAudio)).previewText == "Missed audio call")
    #expect(msg(.call(.missedVideo)).previewText == "Missed video call")
    #expect(msg(.call(.audio(duration: 300))).previewText == "Audio call · 5 min")
    #expect(msg(.call(.video(duration: 720))).previewText == "Video call · 12 min")
    #expect(msg(.text("Hi")).previewText == "Hi")
}

@MainActor @Test func encryptionInfo() {
    #expect(ChatEncryption.endToEnd.infoText == "End-to-end encrypted")
    #expect(ChatEncryption.serverReadable.infoText == "Not end-to-end encrypted: the server admin can read this chat")
}

@MainActor @Test func foldersAndRequests() {
    let s = SampleChatStore()
    #expect(s.folders.map(\.name) == ["All", "Unread", "Personal", "Work"])
    #expect(s.chats(in: .all).allSatisfy { !$0.isRequest })
    #expect(s.folderChipTitle(.unread) == "Unread 1")
    #expect(s.messageRequestsRowTitle == "Message requests · 1")
}

@MainActor @Test func strangerFlow() {
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

@MainActor @Test func markReadClearsUnread() {
    let s = SampleChatStore()
    let riya = s.chats(in: .unread)[0]
    s.markRead(chatID: riya.id)
    #expect(s.folderChipTitle(.unread) == "Unread")
}

@MainActor @Test func sampleHasLongAndRTLNames() {
    let names = SampleChatStore().contacts.map(\.displayName)
    #expect(names.contains("مريم") && names.contains("प्रिया") && names.contains { $0.count > 30 })
}

@MainActor @Test func blockRemovesChatAndContact() {
    let s = SampleChatStore()
    let req = s.messageRequests[0]
    s.block(contactID: req.contactID)
    #expect(s.messageRequests.isEmpty && s.messageRequestsRowTitle == nil)
}

@MainActor @Test func addContactByUsername() async {
    let s = SampleChatStore()
    #expect(await s.addContact(username: "neha")?.displayName == "Neha")
    #expect(await s.addContact(username: "nobody") == nil)
}

@MainActor @Test func sendAppends() {
    let s = SampleChatStore()
    let chat = s.chats(in: .all)[0]
    s.send(.text("Hello"), in: chat.id)
    #expect(s.chat(id: chat.id)?.lastMessage?.content == .text("Hello"))
    #expect(s.chat(id: chat.id)?.lastMessage?.status == .sent)
}

@MainActor @Test func ensureChatCreatesOnce() async {
    let s = SampleChatStore()
    let neha = await s.addContact(username: "neha")!
    let id = s.ensureChat(with: neha.id)
    #expect(s.chat(id: id)?.isRequest == false)
    #expect(s.ensureChat(with: neha.id) == id)
    #expect(s.ensureChat(with: "riya") == "chat-riya")
}

@MainActor @Test func editFolders() {
    let s = SampleChatStore()
    let family = s.addFolder(name: "Family", chatIDs: ["chat-riya", "chat-arjun"])
    #expect(s.folders.map(\.name) == ["All", "Unread", "Personal", "Work", "Family"])
    #expect(Set(s.chats(in: family).map(\.id)) == ["chat-riya", "chat-arjun"])
    s.renameFolder(id: family.id, to: "Home")
    #expect(s.folders.last?.name == "Home")
    s.moveFolder(id: family.id, toCustomIndex: 0)
    #expect(s.folders.map(\.name) == ["All", "Unread", "Home", "Personal", "Work"])
    s.deleteFolder(id: family.id)
    #expect(s.folders.map(\.name) == ["All", "Unread", "Personal", "Work"])
    s.deleteFolder(id: ChatFolder.all.id)
    s.renameFolder(id: ChatFolder.unread.id, to: "x")
    #expect(s.folders.prefix(2).map(\.name) == ["All", "Unread"])
}

@MainActor @Test func previewsForNewContent() {
    #expect(msg(.deleted).previewText == "This message was deleted")
    #expect(msg(.unsupported).previewText == "Update [App] to see this message")
    #expect(msg(.system(.deviceAdded(name: "Ana"))).previewText == "Ana added a new device")
    #expect(msg(.system(.deviceAdded(name: nil))).previewText == "You added a new device")
    #expect(msg(.system(.deviceRemoved(name: "Ana"))).previewText == "Ana removed a device")
    #expect(msg(.system(.deviceRemoved(name: nil))).previewText == "You removed a device")
    #expect(msg(.system(.safetyNumberChanged)).previewText == "Safety number changed")
    #expect(msg(.system(.messagesPaused)).previewText == "Messages in this chat are paused. Open chat info for details.")
}

@MainActor @Test func modelDefaults() {
    let m = msg(.text("x"))
    #expect(m.editedAt == nil && m.media == nil && m.undecryptable == false)
    let c = Chat(id: "c", contactID: "x", encryption: .endToEnd, folderIDs: [], unreadCount: 0, messages: [], isRequest: false)
    #expect(c.modeMismatch == false)
    let media = MediaState(attachmentID: "a", phase: .downloading(0.5))
    #expect(media.phase == .downloading(0.5))
}

@MainActor @Test func searchFindsMessage() {
    let s = SampleChatStore()
    s.send(.text("Lunch at one?"), in: "chat-riya")
    let hits = s.searchMessages("lunch", limit: 10)
    #expect(hits.count == 1 && hits[0].chatID == "chat-riya" && hits[0].preview == "Lunch at one?")
    #expect(s.searchMessages("zzzz", limit: 10).isEmpty)
}

@MainActor @Test func deleteMakesDeleted() {
    let s = SampleChatStore()
    s.send(.text("oops"), in: "chat-riya")
    let id = s.chat(id: "chat-riya")!.lastMessage!.id
    s.delete(messageID: id, in: "chat-riya")
    #expect(s.chat(id: "chat-riya")?.lastMessage?.content == .deleted)
}

@MainActor @Test func reactToggles() {
    let s = SampleChatStore()
    let id = s.chat(id: "chat-riya")!.lastMessage!.id
    s.react("👍", to: id, in: "chat-riya")
    #expect(s.chat(id: "chat-riya")?.lastMessage?.reactions == ["👍"])
    s.react("👍", to: id, in: "chat-riya")
    #expect(s.chat(id: "chat-riya")?.lastMessage?.reactions == [])
}

@MainActor @Test func editSetsEditedAt() {
    let s = SampleChatStore()
    s.send(.text("helo"), in: "chat-riya")
    let id = s.chat(id: "chat-riya")!.lastMessage!.id
    s.edit(messageID: id, in: "chat-riya", text: "hello")
    let m = s.chat(id: "chat-riya")!.lastMessage!
    #expect(m.content == .text("hello") && m.editedAt != nil)
}

@MainActor @Test func sendWithReplyAndPaging() {
    let s = SampleChatStore()
    let first = s.chat(id: "chat-riya")!.messages[0].id
    s.send(.text("re"), in: "chat-riya", replyTo: first)
    #expect(s.chat(id: "chat-riya")?.lastMessage?.replyToID == first)
    let all = s.chat(id: "chat-riya")!.messages
    let last = s.messages(in: "chat-riya", before: nil, limit: 2)
    #expect(last.map(\.id) == all.suffix(2).map(\.id))
    let older = s.messages(in: "chat-riya", before: last[0].id, limit: 2)
    #expect(older.map(\.id) == Array(all.dropLast(2).suffix(2)).map(\.id))
}

@MainActor @Test func recordCallAddsMessage() {
    let s = SampleChatStore()
    s.recordCall(.audio(duration: 60), contactID: "riya", outgoing: true, at: .now)
    #expect(s.chat(id: "chat-riya")?.lastMessage?.content == .call(.audio(duration: 60)))
    #expect(s.chat(id: "chat-riya")?.lastMessage?.senderID == "me")
}

@MainActor @Test func safetyNumberSampleAndVerified() {
    let s = SampleChatStore()
    let n = s.safetyNumber(for: "chat-riya")
    #expect(n?.groups.count == 12 && n?.isVerified == false)
    s.setVerified(true, chatID: "chat-riya")
    #expect(s.safetyNumber(for: "chat-riya")?.isVerified == true)
    #expect(s.safetyNumber(for: "nope") == nil)
}

@MainActor @Test func sampleStoreCanAlwaysReadMembers() {
    #expect(!SampleChatStore().membersUnreadable(for: "chat-riya"))
}

@MainActor @Test func attachmentAndDownload() {
    let s = SampleChatStore()
    s.sendAttachment(URL(fileURLWithPath: "/tmp/x.pdf"), kind: .file, in: "chat-riya")
    let m = s.chat(id: "chat-riya")!.lastMessage!
    #expect(m.content == .file(name: "x.pdf", bytes: 0))
    s.download(messageID: m.id, in: "chat-riya")
}

@MainActor @Test func onChangeFires() {
    let s = SampleChatStore()
    var n = 0
    s.onChange = { n += 1 }
    s.send(.text("a"), in: "chat-riya")
    #expect(n == 1)
}
