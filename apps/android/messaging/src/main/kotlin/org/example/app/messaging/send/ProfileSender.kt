package org.example.app.messaging.send

import java.security.MessageDigest
import org.example.app.core.people.ProfilePhotoRule
import org.example.app.core.profile.DisplayNameRule
import org.example.app.messaging.ports.Repository

/**
 * Keeps contacts' copy of the person's own profile (name and photo) current. The own profile lives in the key-value
 * store: `profile.ver` (rises with every change), `profile.photo` (JPEG bytes, empty for none) and `profile.fp` (what
 * the last announced version looked like, so an announce with nothing changed sends nothing). Each chat remembers the
 * version it was last sent (`profile-sent:<chat>`), written only after the message was queued, so a failed queue
 * leaves the chat pending; a send is idempotent by version and a chat that appears later, is accepted, or is unblocked
 * gets the current version once.
 *
 * A new version is `max(stored + 1, now in seconds)`: a second device whose own counter started low still beats the
 * first device's version, so peers accept its first change.
 *
 * Targets: accepted one-to-one chats that have a conversation, not blocked, never the person's own chat. A message
 * request gets nothing until it is accepted.
 *
 * @param send queues the `profile` envelope for [chatId] (throws when it cannot); the caller flushes.
 */
class ProfileSender(
    private val repo: Repository,
    private val ownAccountId: String,
    private val ownName: () -> String,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val send: (chatId: String, name: String, photo: ByteArray?, ver: Int) -> Unit,
) {
    private val lock = Any()

    fun ver(): Int = repo.get(VER)?.decodeToString()?.toIntOrNull() ?: 0

    /** The person's own photo, or null. */
    fun photo(): ByteArray? = repo.get(PHOTO)?.takeIf { it.isNotEmpty() }

    /** Stores [jpeg] as the own photo (null removes it) and announces the change. */
    fun setPhoto(jpeg: ByteArray?) {
        synchronized(lock) { repo.set(PHOTO, jpeg ?: ByteArray(0)) }
        announce()
    }

    /** Bumps the version when the name or photo differs from the last announced one, then sends. */
    fun announce() {
        synchronized(lock) {
            val name = sendableName()
            if (name != null) {
                val fp = fingerprint(name, photo())
                if (repo.get(FP)?.decodeToString() != fp) bump(fp)
            }
            sync()
        }
    }

    /** True when [sync] would send something now (cheap: reads a few keys). */
    fun hasPending(): Boolean = synchronized(lock) {
        if (sendableName() == null) false else ver() == 0 || targets(ver()).isNotEmpty()
    }

    /** Sends the current version to every target that has not had it. With no version yet, a known name starts one. */
    fun sync() {
        synchronized(lock) {
            val name = sendableName() ?: return
            if (ver() == 0) bump(fingerprint(name, photo()))
            val ver = ver()
            val photo = photo()
            for (chatId in targets(ver)) {
                // The marker follows a successful queue; a failure leaves this chat pending for the next pass.
                try { send(chatId, name, photo, ver) } catch (e: Exception) { continue }
                repo.set(SENT + chatId, ver.toString().encodeToByteArray())
            }
        }
    }

    private fun bump(fp: String) {
        val next = maxOf(ver() + 1L, nowSeconds()).coerceAtMost(Int.MAX_VALUE.toLong())
        repo.set(VER, next.toString().encodeToByteArray())
        repo.set(FP, fp.encodeToByteArray())
    }

    /** The chats that may get a profile and have not had version [ver]. */
    private fun targets(ver: Int): List<String> {
        val blocked = repo.blockedIds().toSet()
        return repo.chats().filter { chat ->
            !chat.isRequest && chat.id != ownAccountId && chat.id !in blocked && repo.conversations(chat.id).isNotEmpty() &&
                (repo.get(SENT + chat.id)?.decodeToString()?.toIntOrNull() ?: 0) < ver
        }.map { it.id }
    }

    /** The name as it must appear on the wire (cleaned), or null when there is none to send. */
    private fun sendableName(): String? = DisplayNameRule.clean(ownName())

    companion object {
        const val VER = "profile.ver"
        const val PHOTO = "profile.photo"
        const val FP = "profile.fp"
        const val SENT = "profile-sent:"

        private fun fingerprint(name: String, photo: ByteArray?): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(name.encodeToByteArray())
            digest.update(0)
            photo?.let { digest.update(it) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * A `profile` that arrived from another device of the person's own account. With a version higher than the stored
         * one (and a photo that passes the rule) it replaces the stored own profile, and every chat is marked as sent at
         * that version: the device that made the change sends it to the chats. Returns whether it was taken.
         */
        fun adopt(repo: Repository, ownAccountId: String, name: String, photo: ByteArray?, ver: Int): Boolean {
            if (ver <= (repo.get(VER)?.decodeToString()?.toIntOrNull() ?: 0)) return false
            if (photo != null && !ProfilePhotoRule.isAcceptable(photo)) return false
            if (DisplayNameRule.clean(name) != name) return false
            repo.set(PHOTO, photo ?: ByteArray(0))
            repo.set(VER, ver.toString().encodeToByteArray())
            repo.set(FP, fingerprint(name, photo).encodeToByteArray())
            for (chat in repo.chats()) repo.set(SENT + chat.id, ver.toString().encodeToByteArray())
            repo.contact(ownAccountId)?.let { repo.upsertContact(it.copy(displayName = name)) }
            return true
        }
    }
}
