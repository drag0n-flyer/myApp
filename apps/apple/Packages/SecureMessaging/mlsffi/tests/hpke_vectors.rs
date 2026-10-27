//! HPKE exports against the protocol test vectors (Go and Swift open the same files).

use mlsffi::error::FfiError;
use mlsffi::hpke::{hpke_generate_keypair, hpke_open, hpke_seal};
use serde_json::Value;

fn b64url(s: &str) -> Vec<u8> {
    let val = |c: u8| match c {
        b'A'..=b'Z' => c - b'A',
        b'a'..=b'z' => c - b'a' + 26,
        b'0'..=b'9' => c - b'0' + 52,
        b'-' => 62,
        b'_' => 63,
        _ => panic!("bad base64url byte"),
    };
    let (mut out, mut acc, mut bits) = (Vec::new(), 0u32, 0);
    for c in s.bytes() {
        acc = (acc << 6) | val(c) as u32;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
            acc &= (1 << bits) - 1;
        }
    }
    out
}

fn hex(s: &str) -> Vec<u8> {
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}

fn load(path: &str) -> Value {
    let full = format!("{}/../../../../../{path}", env!("CARGO_MANIFEST_DIR"));
    serde_json::from_str(&std::fs::read_to_string(full).unwrap()).unwrap()
}

/// Opens every positive case and rejects every negative case of a vector file.
fn check_vector_file(path: &str, expected_info: &str) {
    let v = load(path);
    assert_eq!(v["info"], expected_info);
    let private = hex(v["recipientPrivateKeyHex"].as_str().unwrap());
    let public = b64url(v["recipientPublicKeyB64"].as_str().unwrap());
    let info = expected_info.as_bytes().to_vec();
    let cases = v["cases"].as_array().unwrap();
    assert!(!cases.is_empty());
    for c in cases {
        let sealed = b64url(c["sealedB64"].as_str().unwrap());
        let pt = hpke_open(private.clone(), public.clone(), info.clone(), sealed).unwrap();
        assert_eq!(pt, c["plaintextJSON"].as_str().unwrap().as_bytes());
    }
    for n in v["negative"].as_array().unwrap() {
        let sealed = b64url(n["sealedB64"].as_str().unwrap());
        let info = n["info"]
            .as_str()
            .unwrap_or(expected_info)
            .as_bytes()
            .to_vec();
        let r = hpke_open(private.clone(), public.clone(), info, sealed);
        assert!(r.is_err(), "negative case opened: {}", n["name"]);
    }
}

#[test]
fn opens_escrow_vector() {
    check_vector_file("docs/protocol/test-vectors/escrow.json", "escrow-v1");
}

#[test]
fn opens_sealed_handle_vector() {
    check_vector_file(
        "docs/protocol/test-vectors/sealed-handle.json",
        "push-handle-v1",
    );
}

#[test]
fn opens_push_handle_v1() {
    let v = load("docs/protocol/vectors/push-handle-v1.json");
    let pt = hpke_open(
        hex(v["privateKey"].as_str().unwrap()),
        hex(v["publicKey"].as_str().unwrap()),
        b"push-handle-v1".to_vec(),
        b64url(v["sealed"].as_str().unwrap()),
    )
    .unwrap();
    assert_eq!(pt, v["plaintext"].as_str().unwrap().as_bytes());
}

#[test]
fn seal_open_round_trip() {
    let kp = hpke_generate_keypair().unwrap();
    assert_eq!(kp.private_key.len(), 32);
    assert_eq!(kp.public_key.len(), 32);
    let sealed = hpke_seal(
        kp.public_key.clone(),
        b"link-v1".to_vec(),
        b"snapshot".to_vec(),
    )
    .unwrap();
    let pt = hpke_open(kp.private_key, kp.public_key, b"link-v1".to_vec(), sealed).unwrap();
    assert_eq!(pt, b"snapshot");
}

#[test]
fn wrong_info_fails() {
    let v = load("docs/protocol/test-vectors/escrow.json");
    let sealed = b64url(v["cases"][0]["sealedB64"].as_str().unwrap());
    let r = hpke_open(
        hex(v["recipientPrivateKeyHex"].as_str().unwrap()),
        b64url(v["recipientPublicKeyB64"].as_str().unwrap()),
        b"link-v1".to_vec(),
        sealed,
    );
    assert!(matches!(r, Err(FfiError::Crypto(_))), "{r:?}");
}

#[test]
fn sealed_length_is_32_plus_pt_plus_16() {
    let kp = hpke_generate_keypair().unwrap();
    for len in [1usize, 100] {
        let s = hpke_seal(kp.public_key.clone(), b"escrow-v1".to_vec(), vec![7; len]).unwrap();
        assert_eq!(s.len(), 32 + len + 16);
    }
}

#[test]
fn short_input_is_malformed() {
    let kp = hpke_generate_keypair().unwrap();
    for n in [0usize, 31] {
        let r = hpke_open(
            kp.private_key.clone(),
            kp.public_key.clone(),
            b"x".to_vec(),
            vec![0; n],
        );
        assert!(matches!(r, Err(FfiError::Malformed)), "{r:?}");
    }
}
