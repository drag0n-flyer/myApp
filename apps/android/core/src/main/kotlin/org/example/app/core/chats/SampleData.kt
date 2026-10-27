package org.example.app.core.chats

import java.time.Instant

/**
 * The chats shown in the approved mockups (direct chats only; groups are Phase 2), plus long and
 * right-to-left names for layout checks. Mirrors the iPhone `SampleData` so both apps demo the same.
 */
data class SampleData(
    val me: Contact,
    val contacts: List<Contact>,
    val requestSenders: List<Contact>,
    val folders: List<ChatFolder>,
    val chats: List<Chat>,
    /** Usernames that "Add contact" can find. */
    val directory: Map<String, String>,
) {
    companion object {
        val default: SampleData get() = make(Instant.now())

        fun make(now: Instant): SampleData {
            val me = Contact("me", "Your Name", "yourname")
            val personal = ChatFolder("personal", "Personal", ChatFolder.Kind.Custom)
            val work = ChatFolder("work", "Work", ChatFolder.Kind.Custom)

            fun person(id: String, name: String) = Contact(id, name, id)
            val riya = person("riya", "Riya")
            val arjun = person("arjun", "Arjun")
            val sam = person("sam", "Sam")
            val meera = person("meera", "Meera")
            val kiran = person("kiran", "Kiran")
            val dev = person("dev", "Dev")
            val longName = person("ananth", "Dr. Anantharaman Venkatasubramanian")
            val maryam = person("maryam", "مريم")
            val priya = person("priya", "प्रिया")
            val stranger = person("stranger1", "Unknown Caller")

            var counter = 0
            fun m(
                sender: Contact, minutesAgo: Double, content: MessageContent,
                status: DeliveryStatus = DeliveryStatus.Read, reply: String? = null, reactions: List<String> = emptyList(),
            ): Message {
                counter += 1
                return Message("s$counter", sender.id, now.minusMillis((minutesAgo * 60_000).toLong()), content, status, reply, reactions)
            }

            fun chat(
                c: Contact, folders: Set<String>, messages: List<Message>,
                unread: Int = 0, enc: ChatEncryption = ChatEncryption.EndToEnd, request: Boolean = false,
            ) = Chat("chat-${c.id}", c.id, enc, folders, unread, messages, request)

            val day = 24.0 * 60
            val riyaFirst = m(riya, 11.0, MessageContent.Text("Are you coming tonight?"))
            val chats = listOf(
                chat(riya, setOf(personal.id), unread = 2, messages = listOf(
                    riyaFirst,
                    m(me, 10.0, MessageContent.Text("Yes! Around 6 🙂")),
                    m(riya, 7.0, MessageContent.Voice(14.0), reactions = listOf("❤️")),
                    m(riya, 5.0, MessageContent.Photo(240_000)),
                    m(me, 4.0, MessageContent.Text("Call me when you leave"), reply = riyaFirst.id),
                    m(riya, 3.0, MessageContent.Text("See you at 6, call me when you leave")),
                )),
                chat(arjun, setOf(personal.id), listOf(m(arjun, day + 30, MessageContent.Call(CallSummary.MissedAudio)))),
                chat(sam, setOf(work.id), enc = ChatEncryption.ServerReadable, messages = listOf(
                    m(sam, day + 60, MessageContent.Text("Report is uploaded to the shared folder")),
                    m(me, day + 50, MessageContent.Text("I'll review it in the morning")),
                    m(sam, day + 45, MessageContent.File("Report.pdf", 1_200_000)),
                    m(sam, day + 40, MessageContent.Text("Report is uploaded")),
                )),
                chat(meera, setOf(personal.id), listOf(m(meera, 3 * day, MessageContent.Photo(240_000)))),
                chat(kiran, emptySet(), listOf(m(kiran, 4 * day, MessageContent.Text("Thanks! 🙏")))),
                chat(dev, emptySet(), listOf(m(dev, 5 * day, MessageContent.Call(CallSummary.Video(720.0))))),
                chat(longName, setOf(work.id), listOf(m(longName, 2 * day, MessageContent.Text("Please send the signed copy before Friday.")))),
                chat(maryam, setOf(personal.id), listOf(m(maryam, 2 * day + 10, MessageContent.Text("شكراً جزيلاً")))),
                chat(priya, setOf(personal.id), listOf(m(priya, 2 * day + 20, MessageContent.Text("कल मिलते हैं")))),
                chat(stranger, emptySet(), request = true, messages = listOf(m(stranger, 90.0, MessageContent.Text("Hi, is this the right number?")))),
            )

            return SampleData(
                me = me,
                contacts = listOf(riya, arjun, sam, meera, kiran, dev, longName, maryam, priya),
                requestSenders = listOf(stranger),
                folders = listOf(ChatFolder.All, ChatFolder.Unread, personal, work),
                chats = chats,
                directory = mapOf("neha" to "Neha"),
            )
        }
    }
}
