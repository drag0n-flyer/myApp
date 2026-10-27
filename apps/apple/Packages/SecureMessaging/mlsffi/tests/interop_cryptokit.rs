//! Cross-provider interop: one client on CryptoKit, one on RustCrypto, in the same group.
//! Apple only: CryptoKit is not available elsewhere.
#![cfg(target_vendor = "apple")]

use mls_rs::CryptoProvider;
use mls_rs_crypto_cryptokit::CryptoKitProvider;
use mls_rs_crypto_rustcrypto::RustCryptoProvider;
use mlsffi::engine::{
    generate_signature_keypair, with_provider, EngineCore, Processed, SignatureKeypair,
};
use std::sync::Arc;

fn temp_dir(tag: &str) -> String {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let dir = std::env::temp_dir().join(format!(
        "mlsffi-interop-{tag}-{}-{nanos:x}",
        std::process::id()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    dir.to_string_lossy().into_owned()
}

fn client<P: CryptoProvider + Clone>(identity: &str, tag: &str, crypto: P) -> Arc<EngineCore<P>> {
    let kp: SignatureKeypair = generate_signature_keypair().unwrap();
    with_provider(identity.to_string(), kp, temp_dir(tag), crypto).unwrap()
}

fn say<A: CryptoProvider + Clone, B: CryptoProvider + Clone>(
    from: &EngineCore<A>,
    from_id: &str,
    to: &EngineCore<B>,
    g: &[u8],
    text: &[u8],
) {
    let enc = from.encrypt(g.to_vec(), text.to_vec()).unwrap();
    match to.process(enc.ciphertext).unwrap() {
        Processed::Application {
            sender, plaintext, ..
        } => {
            assert_eq!(sender, from_id);
            assert_eq!(plaintext, text);
        }
        other => panic!("expected Application, got {other:?}"),
    }
}

#[test]
fn cryptokit_rustcrypto_interop() {
    let alice = client("acct-a/dev-1", "alice", CryptoKitProvider::default());
    let bob = client("acct-b/dev-1", "bob", RustCryptoProvider::default());

    let g = alice.create_group().unwrap();
    let kp = bob.key_packages(1).unwrap().remove(0);
    let bundle = alice.add(g.clone(), vec![kp]).unwrap();
    alice.commit_accepted(g.clone()).unwrap();
    match bob.process(bundle.welcome.unwrap()).unwrap() {
        Processed::Joined { group_id, epoch } => {
            assert_eq!(group_id, g);
            assert_eq!(epoch, 1);
        }
        other => panic!("expected Joined, got {other:?}"),
    }
    say(&alice, "acct-a/dev-1", &bob, &g, b"epoch 1 from alice");
    say(&bob, "acct-b/dev-1", &alice, &g, b"epoch 1 from bob");

    // Epoch 2: bob adds a fresh member (a CryptoKit one), alice processes the commit.
    let carol = client("acct-c/dev-1", "carol", CryptoKitProvider::default());
    let kp = carol.key_packages(1).unwrap().remove(0);
    let bundle = bob.add(g.clone(), vec![kp]).unwrap();
    bob.commit_accepted(g.clone()).unwrap();
    match alice.process(bundle.commit).unwrap() {
        Processed::Commit { new_epoch, .. } => assert_eq!(new_epoch, 2),
        other => panic!("expected Commit, got {other:?}"),
    }
    carol.process(bundle.welcome.unwrap()).unwrap();
    assert_eq!(alice.epoch(g.clone()).unwrap(), 2);
    say(&alice, "acct-a/dev-1", &bob, &g, b"epoch 2 from alice");
    say(&bob, "acct-b/dev-1", &alice, &g, b"epoch 2 from bob");
    say(&carol, "acct-c/dev-1", &bob, &g, b"epoch 2 from carol");
}
