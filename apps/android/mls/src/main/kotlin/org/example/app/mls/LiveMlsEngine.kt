package org.example.app.mls

import java.io.File
import kotlinx.coroutines.Dispatchers
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
    private val engine: Engine

    init {
        try {
            if (!storageDir.isDirectory && !storageDir.mkdirs()) throw MlsException.Storage("cannot create storage directory")
            val keypair = SigningKeys.loadOrCreate(secrets)
            engine = Engine(identity, keypair, storageDir.path)
        } catch (e: Throwable) {
            throw ErrorMapping.map(e)
        }
    }

    private suspend fun <T> call(body: (Engine) -> T): T = MlsGate.withLock {
        withContext(Dispatchers.IO) {
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

    override suspend fun epoch(group: GroupId): Long = call { it.epoch(group.raw) }.toLong()

    override suspend fun exportSecret(group: GroupId, label: String, length: Int): ByteArray {
        if (length < 0) throw MlsException.Malformed
        return call { it.exportSecret(group.raw, label, length.toUInt()) }
    }

    override suspend fun members(group: GroupId): List<String> = call { it.members(group.raw) }

    /** Needs the mlsffi member-key export from MLS M7, which is not on main yet. */
    override suspend fun memberKeys(group: GroupId): List<MemberKey> =
        throw MlsException.Internal("memberKeys needs mlsffi M7")

    private fun bundle(ffi: org.example.app.mls.ffi.CommitBundle) =
        CommitBundle(ffi.commit, ffi.welcome, ffi.newEpoch.toLong())
}
