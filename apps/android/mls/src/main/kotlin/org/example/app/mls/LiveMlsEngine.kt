package org.example.app.mls

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.example.app.contracts.CommitBundle
import org.example.app.contracts.Encrypted
import org.example.app.contracts.GroupId
import org.example.app.contracts.Incoming
import org.example.app.contracts.MemberKey
import org.example.app.contracts.MlsEngine
import org.example.app.contracts.MlsException
import org.example.app.contracts.SecretStore
import org.example.app.mls.ffi.Engine
import org.example.app.mls.ffi.Processed

/**
 * The real MLS engine: a thin wrapper over the Rust `Engine` (mlsffi). It only passes data;
 * all cryptography is in Rust. Every call runs on [Dispatchers.IO] under [MlsGate], and every
 * [org.example.app.mls.ffi.FfiException] becomes the matching [MlsException].
 */
class LiveMlsEngine(identity: String, storageDir: File, secrets: SecretStore) : MlsEngine {
    /** False until MLS M7 exports real member keys; M7 flips it. The safety-number UI stays hidden while false. */
    val providesRealMemberKeys: Boolean = false

    private val engine: Engine
    private val lockChannel: FileChannel

    init {
        try {
            if (!storageDir.isDirectory && !storageDir.mkdirs()) throw MlsException.Storage("cannot create storage directory")
            // A second process on this directory would race the first one's state.
            lockChannel = FileChannel.open(
                File(storageDir, "mls.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            )
            try {
                if (lockChannel.tryLock() == null) throw MlsException.Storage("storage is in use by another process")
            } catch (_: OverlappingFileLockException) {
                // Another engine in this process: engineLock already serialises those.
            }
            val keypair = SigningKeys.loadOrCreate(secrets)
            engine = Engine(identity, keypair, storageDir.path)
        } catch (e: Throwable) {
            throw ErrorMapping.map(e)
        }
    }

    /**
     * One Rust call under the engine's own lock. This is not [MlsGate]: callers take the gate
     * around "engine call + database write", and a second take here would deadlock.
     * NonCancellable: Rust has already saved its state change when the call returns, so the
     * result must never be dropped because the caller was cancelled. Callers should make
     * "process + database write" non-cancellable too.
     */
    private suspend fun <T> call(body: (Engine) -> T): T =
        withContext(Dispatchers.IO + NonCancellable) {
            engineLock.withLock {
                try {
                    body(engine)
                } catch (e: Throwable) {
                    throw ErrorMapping.map(e)
                }
            }
        }

    override suspend fun makeKeyPackages(count: Int): List<ByteArray> {
        if (count < 0) throw MlsException.Malformed
        return call { it.keyPackages(count.toUInt()) }
    }

    override suspend fun createGroup(): GroupId = GroupId(call { it.createGroup() })

    override suspend fun add(keyPackages: List<ByteArray>, to: GroupId): CommitBundle =
        bundle(call { it.add(to.raw, keyPackages) })

    override suspend fun remove(members: List<String>, from: GroupId): CommitBundle =
        bundle(call { it.remove(from.raw, members) })

    override suspend fun commitAccepted(group: GroupId) = call { it.commitAccepted(group.raw) }

    override suspend fun commitRejected(group: GroupId) = call { it.commitRejected(group.raw) }

    override suspend fun encrypt(plaintext: ByteArray, group: GroupId): Encrypted {
        val result = call { it.encrypt(group.raw, plaintext) }
        return Encrypted(result.ciphertext, result.epoch.toLong())
    }

    override suspend fun process(message: ByteArray): Incoming = when (val result = call { it.process(message) }) {
        is Processed.Application ->
            Incoming.Application(GroupId(result.groupId), result.sender, result.plaintext, result.epoch.toLong())
        is Processed.Commit -> Incoming.Commit(GroupId(result.groupId), result.newEpoch.toLong(), result.removedMe)
        is Processed.Joined -> Incoming.Joined(GroupId(result.groupId), result.epoch.toLong())
    }

    override suspend fun processApplication(message: ByteArray): Incoming.Application =
        when (val result = call { it.processApplication(message) }) {
            is Processed.Application ->
                Incoming.Application(GroupId(result.groupId), result.sender, result.plaintext, result.epoch.toLong())
            // The Rust side returns only an application message here; anything else is a refusal.
            else -> throw MlsException.Malformed
        }

    override suspend fun epoch(group: GroupId): Long = call { it.epoch(group.raw) }.toLong()

    override suspend fun exportSecret(group: GroupId, label: String, length: Int): ByteArray {
        if (length < 0) throw MlsException.Malformed
        return call { it.exportSecret(group.raw, label, length.toUInt()) }
    }

    override suspend fun members(group: GroupId): List<String> = call { it.members(group.raw) }

    /**
     * Same fallback as the iPhone's `Membership.keys(of:)`: members with placeholder keys, sorted
     * by identity. Replace with the real export in MLS M7 and set [providesRealMemberKeys] true.
     */
    override suspend fun memberKeys(group: GroupId): List<MemberKey> =
        members(group)
            .map { MemberKey(it, "fake-sig:$it".toByteArray(Charsets.UTF_8)) }
            .sortedWith { x, y -> compareUtf8(x.identity, y.identity) }

    private fun compareUtf8(x: String, y: String): Int {
        val a = x.toByteArray(Charsets.UTF_8)
        val b = y.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    private fun bundle(ffi: org.example.app.mls.ffi.CommitBundle) =
        CommitBundle(ffi.commit, ffi.welcome, ffi.newEpoch.toLong())

    private companion object {
        /** Process-wide: two engines on one directory still take turns. */
        val engineLock = Mutex()
    }
}
