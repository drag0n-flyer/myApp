package org.example.app.mls

import org.example.app.contracts.Hpke
import org.example.app.contracts.HpkeException
import org.example.app.contracts.X25519KeyPair
import org.example.app.mls.ffi.FfiException
import org.example.app.mls.ffi.hpkeGenerateKeypair
import org.example.app.mls.ffi.hpkeOpen
import org.example.app.mls.ffi.hpkeSeal

/**
 * HPKE through the Rust library (mlsffi). It only passes data; all cryptography is in Rust.
 * The functions are stateless, so this does not use [MlsGate] or the engine's lock.
 * A Rust error (bad input, wrong key or info, low-order key) becomes [HpkeException].
 * Anything else, such as a library that fails to load, is not hidden as bad data.
 */
class RustHpke : Hpke {
    override fun seal(recipientPublicKey: ByteArray, info: String, plaintext: ByteArray): ByteArray =
        call { hpkeSeal(recipientPublicKey, info.toByteArray(Charsets.UTF_8), plaintext) }

    override fun open(keyPair: X25519KeyPair, info: String, sealed: ByteArray): ByteArray =
        call { hpkeOpen(keyPair.privateKey, keyPair.publicKey, info.toByteArray(Charsets.UTF_8), sealed) }

    override fun generateKeyPair(): X25519KeyPair =
        call { hpkeGenerateKeypair() }.let { X25519KeyPair(it.privateKey, it.publicKey) }

    private inline fun <T> call(block: () -> T): T =
        try {
            block()
        } catch (_: FfiException) {
            throw HpkeException()
        }
}
