package org.example.app.contracts

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An [MlsEngine] for tests and sample data. It does no cryptography: plaintext passes through
 * inside a small tagged frame. The signature key of a member is `"fake-sig:<identity>"`.
 */
class FakeMlsEngine(private val identity: String) : MlsEngine {
    private class Group(var epoch: Long, var members: List<String>, var pending: Pending? = null)

    private class Pending(val newEpoch: Long, val members: List<String>)

    private val lock = Mutex()
    private val groups = HashMap<GroupId, Group>()
    private var counter = 0L

    override suspend fun makeKeyPackages(count: Int): List<ByteArray> = lock.withLock {
        List(count) {
            counter += 1
            frame(KEY_PACKAGE) { writeUTF(identity); writeLong(counter) }
        }
    }

    override suspend fun createGroup(): GroupId = lock.withLock {
        counter += 1
        val id = GroupId("fake-group:$identity:$counter".toByteArray())
        groups[id] = Group(epoch = 0, members = listOf(identity))
        id
    }

    override suspend fun add(keyPackages: List<ByteArray>, to: GroupId): CommitBundle = lock.withLock {
        val group = groupOrThrow(to)
        if (group.pending != null) throw MlsException.PendingCommit
        val added = keyPackages.map { readFrame(it, KEY_PACKAGE) { readUTF() } }
        if (added.isEmpty() || added.any { it in group.members } || added.toSet().size != added.size) {
            throw MlsException.Malformed
        }
        val members = (group.members + added).sorted()
        val newEpoch = group.epoch + 1
        group.pending = Pending(newEpoch, members)
        val commit = frame(COMMIT) {
            putBytes(to.raw); writeLong(newEpoch); putStrings(added); putStrings(emptyList())
        }
        val welcome = frame(WELCOME) { putBytes(to.raw); writeLong(newEpoch); putStrings(members) }
        CommitBundle(commit, welcome, newEpoch)
    }

    override suspend fun remove(members: List<String>, from: GroupId): CommitBundle = lock.withLock {
        val group = groupOrThrow(from)
        if (group.pending != null) throw MlsException.PendingCommit
        if (members.isEmpty() || members.any { it !in group.members }) throw MlsException.NotMember
        val remaining = group.members.filter { it !in members }
        val newEpoch = group.epoch + 1
        group.pending = Pending(newEpoch, remaining)
        val commit = frame(COMMIT) {
            putBytes(from.raw); writeLong(newEpoch); putStrings(emptyList()); putStrings(members)
        }
        CommitBundle(commit, null, newEpoch)
    }

    override suspend fun commitAccepted(group: GroupId): Unit = lock.withLock {
        val state = groupOrThrow(group)
        val pending = state.pending ?: return@withLock
        state.epoch = pending.newEpoch
        state.members = pending.members
        state.pending = null
    }

    override suspend fun commitRejected(group: GroupId): Unit = lock.withLock {
        groupOrThrow(group).pending = null
    }

    override suspend fun encrypt(plaintext: ByteArray, group: GroupId): Encrypted = lock.withLock {
        val state = groupOrThrow(group)
        val ciphertext = frame(APPLICATION) {
            putBytes(group.raw); writeLong(state.epoch); writeUTF(identity); putBytes(plaintext)
        }
        Encrypted(ciphertext, state.epoch)
    }

    override suspend fun process(message: ByteArray): Incoming = lock.withLock {
        when (message.firstOrNull()) {
            WELCOME -> readFrame(message, WELCOME) {
                val id = GroupId(takeBytes()); val epoch = readLong(); val members = takeStrings()
                if (id in groups) throw MlsException.Duplicate
                if (identity !in members) throw MlsException.NotMember
                groups[id] = Group(epoch, members)
                Incoming.Joined(id, epoch)
            }
            COMMIT -> readFrame(message, COMMIT) {
                val id = GroupId(takeBytes()); val newEpoch = readLong()
                val added = takeStrings(); val removed = takeStrings()
                val group = groupOrThrow(id)
                if (newEpoch <= group.epoch) throw MlsException.Duplicate
                if (newEpoch != group.epoch + 1) throw MlsException.EpochGap
                val removedMe = identity in removed
                if (removedMe) {
                    groups.remove(id)
                } else {
                    group.members = ((group.members - removed.toSet()) + added).sorted()
                    group.epoch = newEpoch
                    group.pending = null
                }
                Incoming.Commit(id, newEpoch, removedMe)
            }
            APPLICATION -> readFrame(message, APPLICATION) {
                val id = GroupId(takeBytes()); val epoch = readLong()
                val sender = readUTF(); val plaintext = takeBytes()
                val group = groupOrThrow(id)
                if (sender !in group.members) throw MlsException.NotMember
                if (epoch > group.epoch) throw MlsException.EpochGap
                Incoming.Application(id, sender, plaintext, epoch)
            }
            else -> throw MlsException.Malformed
        }
    }

    override suspend fun epoch(group: GroupId): Long = lock.withLock { groupOrThrow(group).epoch }

    /** Deterministic from the group, epoch and label, so every member derives the same bytes. */
    override suspend fun exportSecret(group: GroupId, label: String, length: Int): ByteArray = lock.withLock {
        val state = groupOrThrow(group)
        require(length >= 0) { "length" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (group.raw.isEmpty()) byteArrayOf(0) else group.raw, "HmacSHA256"))
        val out = ByteArrayOutputStream()
        var block = 0
        while (out.size() < length) {
            mac.update("${state.epoch}|$label|$block".toByteArray())
            out.write(mac.doFinal())
            block += 1
        }
        out.toByteArray().copyOf(length)
    }

    override suspend fun members(group: GroupId): List<String> = lock.withLock { groupOrThrow(group).members.sorted() }

    override suspend fun memberKeys(group: GroupId): List<MemberKey> = lock.withLock {
        groupOrThrow(group).members.sorted().map { MemberKey(it, "fake-sig:$it".toByteArray()) }
    }

    private fun groupOrThrow(id: GroupId): Group = groups[id] ?: throw MlsException.UnknownGroup

    private companion object {
        const val KEY_PACKAGE: Byte = 'K'.code.toByte()
        const val WELCOME: Byte = 'W'.code.toByte()
        const val COMMIT: Byte = 'C'.code.toByte()
        const val APPLICATION: Byte = 'A'.code.toByte()

        fun frame(tag: Byte, body: DataOutputStream.() -> Unit): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { it.writeByte(tag.toInt()); it.body() }
            return bytes.toByteArray()
        }

        fun <T> readFrame(data: ByteArray, tag: Byte, body: DataInputStream.() -> T): T {
            if (data.firstOrNull() != tag) throw MlsException.Malformed
            try {
                DataInputStream(ByteArrayInputStream(data, 1, data.size - 1)).use { return it.body() }
            } catch (e: IOException) {
                throw MlsException.Malformed
            }
        }

        fun DataOutputStream.putBytes(value: ByteArray) { writeInt(value.size); write(value) }

        fun DataOutputStream.putStrings(values: List<String>) { writeInt(values.size); values.forEach { writeUTF(it) } }

        fun DataInputStream.takeBytes(): ByteArray {
            val size = readInt()
            if (size < 0 || size > available()) throw MlsException.Malformed
            return ByteArray(size).also { readFully(it) }
        }

        fun DataInputStream.takeStrings(): List<String> {
            val size = readInt()
            if (size < 0 || size > available()) throw MlsException.Malformed
            return List(size) { readUTF() }
        }
    }
}
