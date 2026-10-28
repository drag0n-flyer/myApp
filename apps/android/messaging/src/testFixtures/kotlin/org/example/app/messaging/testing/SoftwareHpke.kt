package org.example.app.messaging.testing

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.example.app.contracts.Hpke
import org.example.app.contracts.HpkeException
import org.example.app.contracts.X25519KeyPair

/**
 * RFC 9180 base mode with DHKEM(X25519, HKDF-SHA256), HKDF-SHA256 and ChaCha20-Poly1305, on the JDK's own providers.
 * For JVM tests only: production uses `:mls`'s `RustHpke`. The shared vectors prove it matches the Rust library.
 */
class SoftwareHpke : Hpke {
    private val kemSuite = "KEM".toByteArray() + i2osp(0x0020, 2)
    private val hpkeSuite = "HPKE".toByteArray() + i2osp(0x0020, 2) + i2osp(0x0001, 2) + i2osp(0x0003, 2)

    override fun generateKeyPair(): X25519KeyPair {
        val pair = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        return X25519KeyPair(pair.private.encoded.takeLast(32).toByteArray(), pair.public.encoded.takeLast(32).toByteArray())
    }

    override fun seal(recipientPublicKey: ByteArray, info: String, plaintext: ByteArray): ByteArray {
        if (recipientPublicKey.size != 32) throw HpkeException()
        val ephemeral = generateKeyPair()
        val dh = dh(ephemeral.privateKey, recipientPublicKey)
        val shared = sharedSecret(dh, ephemeral.publicKey + recipientPublicKey)
        val (key, nonce) = keySchedule(shared, info.toByteArray())
        return ephemeral.publicKey + aead(Cipher.ENCRYPT_MODE, key, nonce, plaintext)
    }

    override fun open(keyPair: X25519KeyPair, info: String, sealed: ByteArray): ByteArray {
        if (sealed.size < 32 + 16) throw HpkeException()
        val enc = sealed.copyOfRange(0, 32)
        val shared = sharedSecret(dh(keyPair.privateKey, enc), enc + keyPair.publicKey)
        val (key, nonce) = keySchedule(shared, info.toByteArray())
        return try {
            aead(Cipher.DECRYPT_MODE, key, nonce, sealed.copyOfRange(32, sealed.size))
        } catch (_: Exception) {
            throw HpkeException()
        }
    }

    private fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val factory = KeyFactory.getInstance("X25519")
        val sk = factory.generatePrivate(PKCS8EncodedKeySpec(PKCS8_PREFIX + privateKey))
        val pk = factory.generatePublic(X509EncodedKeySpec(X509_PREFIX + publicKey))
        return KeyAgreement.getInstance("X25519").run { init(sk); doPhase(pk, true); generateSecret() }
    }

    private fun sharedSecret(dh: ByteArray, kemContext: ByteArray): ByteArray {
        val prk = labeledExtract(kemSuite, ByteArray(0), "eae_prk", dh)
        return labeledExpand(kemSuite, prk, "shared_secret", kemContext, 32)
    }

    private fun keySchedule(shared: ByteArray, info: ByteArray): Pair<ByteArray, ByteArray> {
        val pskIdHash = labeledExtract(hpkeSuite, ByteArray(0), "psk_id_hash", ByteArray(0))
        val infoHash = labeledExtract(hpkeSuite, ByteArray(0), "info_hash", info)
        val context = byteArrayOf(0) + pskIdHash + infoHash
        val secret = labeledExtract(hpkeSuite, shared, "secret", ByteArray(0))
        return labeledExpand(hpkeSuite, secret, "key", context, 32) to labeledExpand(hpkeSuite, secret, "base_nonce", context, 12)
    }

    private fun aead(mode: Int, key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("ChaCha20-Poly1305").run {
            init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            doFinal(data)
        }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256")); doFinal(data) }

    private fun labeledExtract(suite: ByteArray, salt: ByteArray, label: String, ikm: ByteArray) =
        hmac(salt, "HPKE-v1".toByteArray() + suite + label.toByteArray() + ikm)

    private fun labeledExpand(suite: ByteArray, prk: ByteArray, label: String, info: ByteArray, length: Int): ByteArray {
        val labeled = i2osp(length, 2) + "HPKE-v1".toByteArray() + suite + label.toByteArray() + info
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            t = hmac(prk, t + labeled + byteArrayOf(counter.toByte()))
            out.write(t)
            counter += 1
        }
        return out.toByteArray().copyOf(length)
    }

    private companion object {
        val PKCS8_PREFIX = hex("302e020100300506032b656e04220420")
        val X509_PREFIX = hex("302a300506032b656e032100")
        fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        fun i2osp(v: Int, n: Int) = ByteArray(n) { (v shr (8 * (n - 1 - it))).toByte() }
    }
}
