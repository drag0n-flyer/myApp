import Foundation

/// The chats shown in the approved mockups (direct chats only; groups are Phase 2),
/// plus long and right-to-left names for layout checks.
public struct SampleData: Sendable {
    public var me: Contact
    public var contacts: [Contact]
    public var requestSenders: [Contact]
    public var folders: [ChatFolder]
    public var chats: [Chat]
    /// Usernames that "Add contact" can find.
    public var directory: [String: String]

    public static var `default`: SampleData { make(now: Date()) }

    public static func make(now: Date) -> SampleData {
        let me = Contact(id: "me", displayName: "Your Name", username: "yourname")
        let personal = ChatFolder(id: "personal", name: "Personal", kind: .custom)
        let work = ChatFolder(id: "work", name: "Work", kind: .custom)

        func person(_ id: String, _ name: String) -> Contact { Contact(id: id, displayName: name, username: id) }
        let riya = person("riya", "Riya")
        let arjun = person("arjun", "Arjun")
        let sam = person("sam", "Sam")
        let meera = person("meera", "Meera")
        let kiran = person("kiran", "Kiran")
        let dev = person("dev", "Dev")
        let longName = person("ananth", "Dr. Anantharaman Venkatasubramanian")
        let maryam = person("maryam", "مريم")
        let priya = person("priya", "प्रिया")
        let stranger = person("stranger1", "Unknown Caller")

        var counter = 0
        func m(_ sender: Contact, _ minutesAgo: Double, _ content: MessageContent, _ status: DeliveryStatus = .read, reply: String? = nil, reactions: [String] = []) -> Message {
            counter += 1
            return Message(id: "s\(counter)", senderID: sender.id, sentAt: now.addingTimeInterval(-minutesAgo * 60), content: content, status: status, replyToID: reply, reactions: reactions)
        }
        func chat(_ c: Contact, _ folders: Set<String>, unread: Int = 0, enc: ChatEncryption = .endToEnd, request: Bool = false, _ messages: [Message]) -> Chat {
            Chat(id: "chat-\(c.id)", contactID: c.id, encryption: enc, folderIDs: folders, unreadCount: unread, messages: messages, isRequest: request)
        }

        let day = 24.0 * 60
        let riyaFirst = m(riya, 11, .text("Are you coming tonight?"))
        let chats = [
            chat(riya, [personal.id], unread: 2, [
                riyaFirst,
                m(me, 10, .text("Yes! Around 6 🙂")),
                m(riya, 7, .voice(duration: 14), reactions: ["❤️"]),
                m(riya, 5, .photo(bytes: 240_000)),
                m(me, 4, .text("Call me when you leave"), reply: riyaFirst.id),
                m(riya, 3, .text("See you at 6, call me when you leave")),
            ]),
            chat(arjun, [personal.id], [m(arjun, day + 30, .call(.missedAudio))]),
            chat(sam, [work.id], enc: .serverReadable, [
                m(sam, day + 60, .text("Report is uploaded to the shared folder")),
                m(me, day + 50, .text("I'll review it in the morning")),
                m(sam, day + 45, .file(name: "Report.pdf", bytes: 1_200_000)),
                m(sam, day + 40, .text("Report is uploaded")),
            ]),
            chat(meera, [personal.id], [m(meera, 3 * day, .photo(bytes: 240_000))]),
            chat(kiran, [], [m(kiran, 4 * day, .text("Thanks! 🙏"))]),
            chat(dev, [], [m(dev, 5 * day, .call(.video(duration: 720)))]),
            chat(longName, [work.id], [m(longName, 2 * day, .text("Please send the signed copy before Friday."))]),
            chat(maryam, [personal.id], [m(maryam, 2 * day + 10, .text("شكراً جزيلاً"))]),
            chat(priya, [personal.id], [m(priya, 2 * day + 20, .text("कल मिलते हैं"))]),
            chat(stranger, [], request: true, [m(stranger, 90, .text("Hi, is this the right number?"))]),
        ]

        return SampleData(
            me: me,
            contacts: [riya, arjun, sam, meera, kiran, dev, longName, maryam, priya],
            requestSenders: [stranger],
            folders: [.all, .unread, personal, work],
            chats: chats,
            directory: ["neha": "Neha"]
        )
    }
}
