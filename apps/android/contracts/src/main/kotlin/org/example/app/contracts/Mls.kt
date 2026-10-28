package org.example.app.contracts

// MLS: the Kotlin form of the roadmap's SecureMessaging contract.

/** Identifies an MLS group. Equality and hash code follow the bytes. */
class GroupId(val raw: ByteArray) {
    override fun equals(other: Any?): Boolean = other is GroupId && raw.contentEquals(other.raw)
    override fun hashCode(): Int = raw.contentHashCode()
    override fun toString(): String = "GroupId(${raw.size} bytes)"
}

class CommitBundle(val commit: ByteArray, val welcome: ByteArray?, val newEpoch: Long)

class Encrypted(val ciphertext: ByteArray, val epoch: Long)

sealed interface Incoming {
    /** [sender] is `"<accountID>/<deviceID>"`. */
    class Application(val group: GroupId, val sender: String, val plaintext: ByteArray, val epoch: Long) : Incoming
    class Commit(val group: GroupId, val newEpoch: Long, val removedMe: Boolean) : Incoming
    class Joined(val group: GroupId, val epoch: Long) : Incoming
}

class MemberKey(val identity: String, val signatureKey: ByteArray)

sealed class MlsException(message: String) : Exception(message) {
    object Malformed : MlsException("malformed")
    object UnknownGroup : MlsException("unknownGroup")
    object NotMember : MlsException("notMember")
    object Duplicate : MlsException("duplicate")
    object EpochGap : MlsException("epochGap")
    object PendingCommit : MlsException("pendingCommit")
    class Storage(m: String) : MlsException(m)
    class Crypto(m: String) : MlsException(m)
    class Internal(m: String) : MlsException(m)
}

interface MlsEngine {
    suspend fun makeKeyPackages(count: Int): List<ByteArray>
    suspend fun createGroup(): GroupId
    suspend fun add(keyPackages: List<ByteArray>, to: GroupId): CommitBundle
    suspend fun remove(members: List<String>, from: GroupId): CommitBundle
    suspend fun commitAccepted(group: GroupId)
    suspend fun commitRejected(group: GroupId)
    suspend fun encrypt(plaintext: ByteArray, group: GroupId): Encrypted
    suspend fun process(message: ByteArray): Incoming

    /**
     * Like [process], but only an application message is accepted. A commit, a proposal or a Welcome is refused
     * with [MlsException.Malformed] before any stored group state changes. For channels that carry application
     * messages only (call signals).
     */
    suspend fun processApplication(message: ByteArray): Incoming.Application
    suspend fun epoch(group: GroupId): Long
    suspend fun exportSecret(group: GroupId, label: String, length: Int): ByteArray
    suspend fun members(group: GroupId): List<String>

    /** Sorted by identity; includes this device. */
    suspend fun memberKeys(group: GroupId): List<MemberKey>
}
