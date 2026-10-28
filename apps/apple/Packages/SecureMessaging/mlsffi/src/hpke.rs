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

/// X25519 public keys of small order, as raw 32-byte values: u = 0, 1, p-1 and
/// the two points of order 8, each with its non-canonical encodings (top bit
/// set, or u + p when that fits in 255 bits). This is the published list from
/// RFC 7748 section 7 (the same list libsodium blocks), compared as bytes.
///
/// RFC 9180 section 7.1.4 requires DHKEM(X25519) to abort when the DH output is
/// all zero. mls-rs-crypto-rustcrypto does not check this (ec.rs, `ecdh_x25519`,
/// lines 211-215, returns the raw result), so a recipient key on this list would
/// give a shared secret anyone can compute. Go's crypto/hpke refuses these keys;
/// the Go vector files list them and the tests pin the same answer here.
const LOW_ORDER_X25519: [[u8; 32]; 14] = {
    // First, middle and last byte of the keys that are otherwise all 0x00 or all 0xff.
    const fn rep(first: u8, mid: u8, last: u8) -> [u8; 32] {
        let mut a = [mid; 32];
        a[0] = first;
        a[31] = last;
        a
    }
    [
        rep(0x00, 0x00, 0x00),
        rep(0x00, 0x00, 0x80),
        rep(0x01, 0x00, 0x00),
        rep(0x01, 0x00, 0x80),
        rep(0xec, 0xff, 0x7f),
        rep(0xec, 0xff, 0xff),
        rep(0xed, 0xff, 0x7f),
        rep(0xed, 0xff, 0xff),
        rep(0xee, 0xff, 0x7f),
        rep(0xee, 0xff, 0xff),
        ORDER8_A,
        ORDER8_A_HIGH,
        ORDER8_B,
        ORDER8_B_HIGH,
    ]
};

const ORDER8_A: [u8; 32] = [
    0xe0, 0xeb, 0x7a, 0x7c, 0x3b, 0x41, 0xb8, 0xae, 0x16, 0x56, 0xe3, 0xfa, 0xf1, 0x9f, 0xc4, 0x6a,
    0xda, 0x09, 0x8d, 0xeb, 0x9c, 0x32, 0xb1, 0xfd, 0x86, 0x62, 0x05, 0x16, 0x5f, 0x49, 0xb8, 0x00,
];
const ORDER8_A_HIGH: [u8; 32] = with_top_bit(ORDER8_A);
const ORDER8_B: [u8; 32] = [
    0x5f, 0x9c, 0x95, 0xbc, 0xa3, 0x50, 0x8c, 0x24, 0xb1, 0xd0, 0xb1, 0x55, 0x9c, 0x83, 0xef, 0x5b,
    0x04, 0x44, 0x5c, 0xc4, 0x58, 0x1c, 0x8e, 0x86, 0xd8, 0x22, 0x4e, 0xdd, 0xd0, 0x9f, 0x11, 0x57,
];
const ORDER8_B_HIGH: [u8; 32] = with_top_bit(ORDER8_B);

const fn with_top_bit(mut k: [u8; 32]) -> [u8; 32] {
    k[31] |= 0x80;
    k
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

/// Returns `enc ‖ ct`. The plaintext must not be empty (mls-rs refuses it). A
/// recipient key of small order is `Malformed`.
#[uniffi::export]
pub fn hpke_seal(
    recipient_public_key: Vec<u8>,
    info: Vec<u8>,
    plaintext: Vec<u8>,
) -> Result<Vec<u8>, FfiError> {
    if LOW_ORDER_X25519
        .iter()
        .any(|k| k[..] == recipient_public_key[..])
    {
        return Err(FfiError::Malformed);
    }
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

/// `sealed` is `enc ‖ ct`. `public_key` must be the public half of `private_key`.
/// Ciphertext of 16 bytes or fewer (empty plaintext) is refused by mls-rs. Input shorter than the encapsulated key is `Malformed`.
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
