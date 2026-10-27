//! HPKE (RFC 9180, base mode) for the non-MLS uses: escrow copy, push handle
//! and device-link snapshot. Passes data to the mls-rs RustCrypto provider and
//! has no cryptography of its own. Suite `CURVE25519_CHACHA`: DHKEM(X25519,
//! HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305. AAD is empty.
//! Output layout is `enc ‖ ct` (32-byte encapsulated key, then ciphertext and tag).

use mls_rs_core::crypto::{
    CipherSuite, CipherSuiteProvider, CryptoProvider, HpkeCiphertext, HpkePublicKey, HpkeSecretKey,
};
use mls_rs_core::error::IntoAnyError;
use mls_rs_crypto_rustcrypto::RustCryptoProvider;

use crate::error::FfiError;

const SUITE: CipherSuite = CipherSuite::CURVE25519_CHACHA;
/// Length of the X25519 encapsulated key at the front of a sealed value.
const ENC_LEN: usize = 32;

#[derive(uniffi::Record)]
pub struct HpkeKeyPair {
    pub private_key: Vec<u8>,
    pub public_key: Vec<u8>,
}

fn provider() -> Result<impl CipherSuiteProvider, FfiError> {
    RustCryptoProvider::new()
        .cipher_suite_provider(SUITE)
        .ok_or_else(|| FfiError::Internal("HPKE suite unavailable".into()))
}

fn crypto<E: IntoAnyError>(e: E) -> FfiError {
    FfiError::Crypto(e.into_any_error().to_string())
}

#[uniffi::export]
pub fn hpke_generate_keypair() -> Result<HpkeKeyPair, FfiError> {
    let (sk, pk) = provider()?.kem_generate().map_err(crypto)?;
    Ok(HpkeKeyPair {
        private_key: sk.as_ref().to_vec(),
        public_key: pk.as_ref().to_vec(),
    })
}

/// Returns `enc ‖ ct`.
#[uniffi::export]
pub fn hpke_seal(
    recipient_public_key: Vec<u8>,
    info: Vec<u8>,
    plaintext: Vec<u8>,
) -> Result<Vec<u8>, FfiError> {
    let ct = provider()?
        .hpke_seal(
            &HpkePublicKey::from(recipient_public_key),
            &info,
            None,
            &plaintext,
        )
        .map_err(crypto)?;
    let mut out = ct.kem_output;
    out.extend_from_slice(&ct.ciphertext);
    Ok(out)
}

/// `sealed` is `enc ‖ ct`. Input shorter than the encapsulated key is `Malformed`.
#[uniffi::export]
pub fn hpke_open(
    private_key: Vec<u8>,
    public_key: Vec<u8>,
    info: Vec<u8>,
    sealed: Vec<u8>,
) -> Result<Vec<u8>, FfiError> {
    if sealed.len() < ENC_LEN {
        return Err(FfiError::Malformed);
    }
    let (enc, ct) = sealed.split_at(ENC_LEN);
    let ciphertext = HpkeCiphertext {
        kem_output: enc.to_vec(),
        ciphertext: ct.to_vec(),
    };
    let pt = provider()?
        .hpke_open(
            &ciphertext,
            &HpkeSecretKey::from(private_key),
            &HpkePublicKey::from(public_key),
            &info,
            None,
        )
        .map_err(crypto)?;
    Ok(pt.to_vec())
}
