package org.example.app.contracts

// HPKE: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305, base mode, empty AAD, output enc || ct.

object HpkeInfo {
    const val ESCROW = "escrow-v1"
    const val PUSH_HANDLE = "push-handle-v1"
    const val LINK = "link-v1"
}

class X25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

interface Hpke {
    fun seal(recipientPublicKey: ByteArray, info: String, plaintext: ByteArray): ByteArray

    /** Throws [HpkeException]. */
    fun open(keyPair: X25519KeyPair, info: String, sealed: ByteArray): ByteArray

    fun generateKeyPair(): X25519KeyPair
}

class HpkeException : Exception()
