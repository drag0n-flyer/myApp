package org.example.app.messaging.sync

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.example.app.contracts.MemberKey

/**
 * Safety numbers, identical to the Swift `SafetyNumberCalc` (pinned by `safety-v1.json`):
 *
 *     side(acct) = SHA-256("safety-v1" || 0x00 || acct || 0x00 ||
 *                          per device of acct sorted by identity UTF-8 bytes: u16be(len id) || id || u16be(len key) || key)
 *
 * A device belongs to `acct` when its identity's part before the first `/` equals `acct` (byte comparison). The two
 * sides are ordered by account ID UTF-8 bytes; each gives 6 groups of 5 digits (5 bytes big-endian mod 100000).
 */
object SafetyNumber {
    const val VERSION = "safety-v1"

    /** @throws IllegalArgumentException when an identity or key is longer than 65 535 bytes (a u16 length would wrap). */
    fun compute(memberKeys: List<MemberKey>, accountA: String, accountB: String): List<String> {
        val (low, high) = if (compareBytes(accountA.toByteArray(), accountB.toByteArray()) < 0) accountA to accountB else accountB to accountA
        return groups(side(low, memberKeys)) + groups(side(high, memberKeys))
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    private fun accountOf(identity: String): ByteArray = identity.toByteArray().let { bytes ->
        val slash = bytes.indexOf('/'.code.toByte())
        if (slash < 0) bytes else bytes.copyOfRange(0, slash)
    }

    private fun side(account: String, members: List<MemberKey>): ByteArray {
        val acct = account.toByteArray()
        val mine = members
            .map { it.identity.toByteArray() to it.signatureKey }
            .filter { (id, _) -> accountOf(String(id)).contentEquals(acct) }
            .sortedWith { x, y -> compareBytes(x.first, y.first).let { if (it != 0) it else compareBytes(x.second, y.second) } }
        val out = ByteArrayOutputStream()
        out.write(VERSION.toByteArray()); out.write(0); out.write(acct); out.write(0)
        for ((id, key) in mine) {
            require(id.size <= 0xffff && key.size <= 0xffff) { "identity or key too long" }
            out.write(id.size shr 8); out.write(id.size and 0xff); out.write(id)
            out.write(key.size shr 8); out.write(key.size and 0xff); out.write(key)
        }
        return MessageDigest.getInstance("SHA-256").digest(out.toByteArray())
    }

    private fun groups(hash: ByteArray): List<String> = (0 until 6).map { i ->
        var value = 0L
        for (j in 5 * i until 5 * i + 5) value = (value shl 8) or (hash[j].toLong() and 0xff)
        (value % 100_000).toString().padStart(5, '0')
    }
}
