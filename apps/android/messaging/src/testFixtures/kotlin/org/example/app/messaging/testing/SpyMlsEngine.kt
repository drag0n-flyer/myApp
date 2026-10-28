package org.example.app.messaging.testing

import kotlinx.coroutines.CompletableDeferred
import org.example.app.contracts.CommitBundle
import org.example.app.contracts.Encrypted
import org.example.app.contracts.GroupId
import org.example.app.contracts.Incoming
import org.example.app.contracts.MemberKey
import org.example.app.contracts.MlsEngine

/**
 * Wraps an engine: counts `process` calls, can fail or hold one, and records every state-changing call made
 * while [lockHeld] says the outer lock is free (it must be empty).
 */
class SpyMlsEngine(private val inner: MlsEngine, private val lockHeld: () -> Boolean) : MlsEngine {
    val unlockedCalls = mutableListOf<String>()
    var processCalls = 0
        private set
    /** Throws its result instead of processing, when not null. */
    var failProcess: ((ByteArray) -> Throwable?)? = null
    /** Throws its result instead of reading the members, when not null. */
    var failMembers: ((GroupId) -> Throwable?)? = null
    /** When set, the next `process` call waits for it (after running). */
    var holdProcess: CompletableDeferred<Unit>? = null
    /** Completed when a held `process` call has run. */
    var processRan: CompletableDeferred<Unit>? = null

    private fun check(name: String) { if (!lockHeld()) unlockedCalls += name }

    override suspend fun makeKeyPackages(count: Int) = inner.makeKeyPackages(count)
    override suspend fun createGroup(): GroupId { check("createGroup"); return inner.createGroup() }
    override suspend fun add(keyPackages: List<ByteArray>, to: GroupId): CommitBundle { check("add"); return inner.add(keyPackages, to) }
    override suspend fun remove(members: List<String>, from: GroupId): CommitBundle { check("remove"); return inner.remove(members, from) }
    override suspend fun commitAccepted(group: GroupId) { check("commitAccepted"); inner.commitAccepted(group) }
    override suspend fun commitRejected(group: GroupId) { check("commitRejected"); inner.commitRejected(group) }
    override suspend fun encrypt(plaintext: ByteArray, group: GroupId): Encrypted { check("encrypt"); return inner.encrypt(plaintext, group) }
    override suspend fun process(message: ByteArray): Incoming {
        check("process")
        processCalls += 1
        failProcess?.invoke(message)?.let { throw it }
        val result = inner.process(message)
        holdProcess?.let { gate ->
            holdProcess = null
            processRan?.complete(Unit)
            gate.await()
        }
        return result
    }
    /** Counted in [processApplicationCalls], not in [processCalls]. */
    var processApplicationCalls = 0
        private set
    override suspend fun processApplication(message: ByteArray): Incoming.Application {
        check("processApplication")
        processApplicationCalls += 1
        failProcess?.invoke(message)?.let { throw it }
        return inner.processApplication(message)
    }
    override suspend fun epoch(group: GroupId) = inner.epoch(group)
    override suspend fun exportSecret(group: GroupId, label: String, length: Int) = inner.exportSecret(group, label, length)
    override suspend fun members(group: GroupId): List<String> {
        failMembers?.invoke(group)?.let { throw it }
        return inner.members(group)
    }
    override suspend fun memberKeys(group: GroupId): List<MemberKey> {
        failMembers?.invoke(group)?.let { throw it }
        return inner.memberKeys(group)
    }
}
