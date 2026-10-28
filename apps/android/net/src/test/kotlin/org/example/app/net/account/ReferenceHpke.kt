package org.example.app.net.account

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.example.app.contracts.Hpke
import org.example.app.contracts.HpkeException
import org.example.app.contracts.X25519KeyPair

/**
 * Test-only RFC 9180 base mode, DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 / ChaCha20-Poly1305, empty AAD,
 * output `enc || ct`, written on the JDK so the shared link vector opens in a JVM test. The app uses mlsffi.
 */
class ReferenceHpke : Hpke {
    private val xdh = NamedParameterSpec.X25519
    private val rnd = SecureRandom()

    override fun generateKeyPair(): X25519KeyPair {
        val sk = ByteArray(32).also(rnd::nextBytes)
        return X25519KeyPair(sk, publicOf(sk))
    }

    override fun seal(recipientPublicKey: ByteArray, info: String, plaintext: ByteArray): ByteArray {
        val eph = generateKeyPair()
        val dh = dh(eph.privateKey, recipientPublicKey)
        val shared = sharedSecret(dh, eph.publicKey + recipientPublicKey)
        val (key, nonce) = schedule(shared, info.encodeToByteArray())
        return eph.publicKey + aead(Cipher.ENCRYPT_MODE, key, nonce, plaintext)
    }

    override fun open(keyPair: X25519KeyPair, info: String, sealed: ByteArray): ByteArray {
        if (sealed.size < 32 + 16) throw HpkeException()
        try {
            val enc = sealed.copyOfRange(0, 32)
            val dh = dh(keyPair.privateKey, enc)
            val shared = sharedSecret(dh, enc + keyPair.publicKey)
            val (key, nonce) = schedule(shared, info.encodeToByteArray())
            return aead(Cipher.DECRYPT_MODE, key, nonce, sealed.copyOfRange(32, sealed.size))
        } catch (e: java.security.GeneralSecurityException) {
            throw HpkeException()
        }
    }

    private fun publicOf(sk: ByteArray): ByteArray {
        val base = ByteArray(32).also { it[0] = 9 }
        return dh(sk, base)
    }

    private fun dh(sk: ByteArray, pk: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance("XDH")
        val priv = kf.generatePrivate(XECPrivateKeySpec(xdh, sk))
        val u = BigInteger(1, pk.reversedArray())
        val pub = kf.generatePublic(XECPublicKeySpec(xdh, u))
        val ka = KeyAgreement.getInstance("XDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        return ka.generateSecret()
    }

    private val kemSuite = "KEM".encodeToByteArray() + i2osp(0x0020, 2)
    private val hpkeSuite = "HPKE".encodeToByteArray() + i2osp(0x0020, 2) + i2osp(0x0001, 2) + i2osp(0x0003, 2)

    private fun sharedSecret(dh: ByteArray, kemContext: ByteArray): ByteArray {
        val prk = labeledExtract(kemSuite, ByteArray(0), "eae_prk", dh)
        return labeledExpand(kemSuite, prk, "shared_secret", kemContext, 32)
    }

    private fun schedule(shared: ByteArray, info: ByteArray): Pair<ByteArray, ByteArray> {
        val pskIdHash = labeledExtract(hpkeSuite, ByteArray(0), "psk_id_hash", ByteArray(0))
        val infoHash = labeledExtract(hpkeSuite, ByteArray(0), "info_hash", info)
        val context = byteArrayOf(0) + pskIdHash + infoHash
        val secret = labeledExtract(hpkeSuite, shared, "secret", ByteArray(0))
        return labeledExpand(hpkeSuite, secret, "key", context, 32) to labeledExpand(hpkeSuite, secret, "base_nonce", context, 12)
    }

    private fun aead(mode: Int, key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        c.init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        return c.doFinal(data)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val k = if (key.isEmpty()) ByteArray(32) else key
        return Mac.getInstance("HmacSHA256").also { it.init(SecretKeySpec(k, "HmacSHA256")) }.doFinal(data)
    }

    private fun labeledExtract(suite: ByteArray, salt: ByteArray, label: String, ikm: ByteArray) =
        hmac(salt, "HPKE-v1".encodeToByteArray() + suite + label.encodeToByteArray() + ikm)

    private fun labeledExpand(suite: ByteArray, prk: ByteArray, label: String, info: ByteArray, len: Int): ByteArray {
        val labeled = i2osp(len, 2) + "HPKE-v1".encodeToByteArray() + suite + label.encodeToByteArray() + info
        var t = ByteArray(0)
        var out = ByteArray(0)
        var i = 1
        while (out.size < len) {
            t = hmac(prk, t + labeled + byteArrayOf(i.toByte()))
            out += t
            i++
        }
        return out.copyOf(len)
    }

    private fun i2osp(v: Int, n: Int) = ByteArray(n) { (v shr (8 * (n - 1 - it))).toByte() }
}
