package org.example.app.mls

import java.util.Base64
import org.example.app.contracts.SecretNames
import org.example.app.contracts.SecretStore
import org.example.app.contracts.MlsException
import org.example.app.mls.ffi.SignatureKeypair
import org.example.app.mls.ffi.generateSignatureKeypair

/** Loads or creates this device's MLS signing key. Stored as `{"pub":b64,"sec":b64}`. */
object SigningKeys {
    private val lock = Any()
    private val shape = Regex("""^\{"pub":"([A-Za-z0-9+/=]+)","sec":"([A-Za-z0-9+/=]+)"\}$""")

    fun loadOrCreate(secrets: SecretStore): SignatureKeypair = synchronized(lock) {
        secrets.load(SecretNames.MLS_SIGNING)?.let { stored ->
            val match = shape.matchEntire(String(stored, Charsets.UTF_8))
                ?: throw MlsException.Storage("signing key is unreadable")
            val decoder = Base64.getDecoder()
            return SignatureKeypair(decoder.decode(match.groupValues[1]), decoder.decode(match.groupValues[2]))
        }
        val fresh = try {
            generateSignatureKeypair()
        } catch (e: Throwable) {
            throw ErrorMapping.map(e)
        }
        val encoder = Base64.getEncoder()
        val json = """{"pub":"${encoder.encodeToString(fresh.publicKey)}","sec":"${encoder.encodeToString(fresh.secretKey)}"}"""
        secrets.save(SecretNames.MLS_SIGNING, json.toByteArray(Charsets.UTF_8))
        fresh
    }
}
