package org.example.app.messaging.send

import java.security.MessageDigest
import org.example.app.core.profile.DisplayNameRule
import org.example.app.messaging.ports.Repository

/**
 * Keeps contacts' copy of the person's own profile (name and photo) current. The own profile lives in the key-value
 * store: `profile.ver` (rises with every change), `profile.photo` (JPEG bytes, empty for none) and `profile.fp` (what
 * the last announced version looked like, so an announce with nothing changed sends nothing). Each chat remembers the
 * version it was last sent (`profile-sent:<chat>`), so a send is idempotent by version and a chat that appears later,
 * is accepted, or is unblocked gets the current version once.
 *
 * Targets: accepted one-to-one chats that have a conversation, not blocked, never the person's own chat. A message
 * request gets nothing until it is accepted.
 *
 * @param send queues the `profile` envelope for [chatId]; the caller flushes.
 */
class ProfileSender(
    private val repo: Repository,
    private val ownAccountId: String,
    private val ownName: () -> String,
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
                if (repo.get(FP)?.decodeToString() != fp) {
                    repo.set(VER, (ver() + 1).toString().encodeToByteArray())
                    repo.set(FP, fp.encodeToByteArray())
                }
            }
            sync()
        }
    }

    /** True when [sync] would send something now (cheap: reads a few keys). */
    fun hasPending(): Boolean = synchronized(lock) {
        if (sendableName() == null) false else ver() == 0 || targets(ver()).isNotEmpty()
    }

    /** Sends the current version to every target that has not had it. With no version yet, a known name starts version 1. */
    fun sync() {
        synchronized(lock) {
            val name = sendableName() ?: return
            if (ver() == 0) { announceFirst(name); return }
            val ver = ver()
            val photo = photo()
            for (chatId in targets(ver)) {
                send(chatId, name, photo, ver)
                repo.set(SENT + chatId, ver.toString().encodeToByteArray())
            }
        }
    }

    /** The chats that may get a profile and have not had version [ver]. */
    private fun targets(ver: Int): List<String> {
        val blocked = repo.blockedIds().toSet()
        return repo.chats().filter { chat ->
            !chat.isRequest && chat.id != ownAccountId && chat.id !in blocked && repo.conversations(chat.id).isNotEmpty() &&
                (repo.get(SENT + chat.id)?.decodeToString()?.toIntOrNull() ?: 0) < ver
        }.map { it.id }
    }

    private fun announceFirst(name: String) {
        repo.set(FP, fingerprint(name, photo()).encodeToByteArray())
        repo.set(VER, "1".encodeToByteArray())
        sync()
    }

    /** The name as it must appear on the wire (cleaned), or null when there is none to send. */
    private fun sendableName(): String? = DisplayNameRule.clean(ownName())

    private fun fingerprint(name: String, photo: ByteArray?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(name.encodeToByteArray())
        digest.update(0)
        photo?.let { digest.update(it) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val VER = "profile.ver"
        const val PHOTO = "profile.photo"
        const val FP = "profile.fp"
        const val SENT = "profile-sent:"
    }
}
