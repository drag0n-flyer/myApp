//! The profile's credential rule (docs/protocol/mls-profile.md): every member's
//! credential is `basic` and its identity is UTF-8 of at most 255 bytes, not
//! empty. A KeyPackage, Welcome or commit that would make anything else a
//! member is rejected, so `members` and `member_keys` can always read the group.
//!
//! The hostile peer is a plain mls-rs client (not the engine), because the
//! engine itself only takes a `String` identity.

use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

use mls_rs::client_builder::ClientBuilder;
use mls_rs::crypto::SignaturePublicKey;
use mls_rs::identity::basic::{BasicCredential, BasicIdentityProvider};
use mls_rs::identity::SigningIdentity;
use mls_rs::mls_rules::{CommitOptions, DefaultMlsRules};
use mls_rs::{CipherSuite, CipherSuiteProvider, CryptoProvider, ExtensionList, MlsMessage};
use mls_rs_crypto_rustcrypto::RustCryptoProvider;
use mlsffi::engine::{generate_signature_keypair, Engine, Processed};

const SUITE: CipherSuite = CipherSuite::CURVE25519_AES128;

struct TempDir(PathBuf);

impl TempDir {
    fn new() -> Self {
        static COUNTER: AtomicU64 = AtomicU64::new(0);
        let n = COUNTER.fetch_add(1, Ordering::SeqCst);
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let dir = std::env::temp_dir().join(format!(
            "mlsffi-id-{}-{nanos:x}-{n}",
            std::process::id()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        TempDir(dir)
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn engine(identity: &str) -> (Arc<Engine>, TempDir) {
    let dir = TempDir::new();
    let e = Engine::new(
        identity.to_string(),
        generate_signature_keypair().unwrap(),
        dir.0.to_string_lossy().into_owned(),
    )
    .unwrap();
    (e, dir)
}

/// A plain mls-rs client with any identity bytes, in memory.
fn raw_client(identity: &[u8]) -> mls_rs::Client<impl mls_rs::client_builder::MlsConfig> {
    let crypto = RustCryptoProvider::default();
    let (secret, public) = crypto
        .cipher_suite_provider(SUITE)
        .unwrap()
        .signature_key_generate()
        .unwrap();
    let credential = BasicCredential::new(identity.to_vec()).into_credential();
    let signing = SigningIdentity::new(credential, SignaturePublicKey::from(public.to_vec()));
    ClientBuilder::new()
        .identity_provider(BasicIdentityProvider::new())
        .crypto_provider(crypto)
        .mls_rules(DefaultMlsRules::new().with_commit_options(
            CommitOptions::default()
                .with_ratchet_tree_extension(true)
                .with_single_welcome_message(true),
        ))
        .signing_identity(signing, secret, SUITE)
        .build()
}

fn raw_key_package(identity: &[u8]) -> Vec<u8> {
    raw_client(identity)
        .generate_key_package_message(ExtensionList::new(), ExtensionList::new(), None)
        .unwrap()
        .to_bytes()
        .unwrap()
}

const BAD_UTF8: &[u8] = &[0x61, 0x2f, 0xff, 0xfe];

#[test]
fn add_rejects_key_packages_that_break_the_identity_rule() {
    let (alice, _d) = engine("acct-a/dev-1");
    let g = alice.create_group().unwrap();
    let long = "a".repeat(256);
    for bad in [BAD_UTF8, b"".as_slice(), long.as_bytes()] {
        assert!(
            alice.add(g.clone(), vec![raw_key_package(bad)]).is_err(),
            "accepted identity of {} bytes",
            bad.len()
        );
        assert!(!alice.has_pending_commit(g.clone()).unwrap());
    }
    // Control: a valid identity at the length limit is accepted.
    let edge = "b".repeat(255);
    alice.add(g.clone(), vec![raw_key_package(edge.as_bytes())]).unwrap();
    alice.commit_accepted(g.clone()).unwrap();
    assert_eq!(alice.members(g).unwrap().len(), 2);
}

/// A Welcome whose ratchet tree holds a member with a non-UTF-8 identity.
fn welcome_from(creator_identity: &[u8], bob: &Engine) -> Vec<u8> {
    let creator = raw_client(creator_identity);
    let mut group = creator
        .create_group(ExtensionList::new(), ExtensionList::new(), None)
        .unwrap();
    let kp = MlsMessage::from_bytes(&bob.key_packages(1).unwrap()[0]).unwrap();
    let out = group.commit_builder().add_member(kp).unwrap().build().unwrap();
    group.apply_pending_commit().unwrap();
    out.welcome_messages[0].to_bytes().unwrap()
}

#[test]
fn welcome_with_an_unreadable_member_is_rejected() {
    let (bob, _d) = engine("acct-b/dev-1");
    // Control: the same Welcome from a valid creator joins.
    let good = welcome_from(b"acct-c/dev-1", &bob);
    assert!(matches!(bob.process(good).unwrap(), Processed::Joined { .. }));

    let (bob, _d) = engine("acct-b/dev-1");
    let bad = welcome_from(BAD_UTF8, &bob);
    assert!(bob.process(bad).is_err());
}

#[test]
fn commit_adding_an_unreadable_member_is_rejected() {
    let (alice, _d) = engine("acct-a/dev-1");
    let g = alice.create_group().unwrap();
    let mallory = raw_client(b"acct-m/dev-1");
    let kp = mallory
        .generate_key_package_message(ExtensionList::new(), ExtensionList::new(), None)
        .unwrap()
        .to_bytes()
        .unwrap();
    let bundle = alice.add(g.clone(), vec![kp]).unwrap();
    alice.commit_accepted(g.clone()).unwrap();
    let welcome = MlsMessage::from_bytes(&bundle.welcome.unwrap()).unwrap();
    let (mut group, _) = mallory.join_group(None, &welcome, None).unwrap();

    let commit = |identity: &[u8], group: &mut mls_rs::Group<_>| {
        let kp = MlsMessage::from_bytes(&raw_key_package(identity)).unwrap();
        let out = group.commit_builder().add_member(kp).unwrap().build().unwrap();
        out.commit_message.to_bytes().unwrap()
    };

    let bad = commit(BAD_UTF8, &mut group);
    assert!(alice.process(bad).is_err());
    assert_eq!(alice.members(g.clone()).unwrap().len(), 2);

    // Control: the same commit with a valid identity is processed.
    group.clear_pending_commit();
    let good = commit(b"acct-n/dev-1", &mut group);
    assert!(matches!(alice.process(good).unwrap(), Processed::Commit { .. }));
    assert_eq!(alice.members(g).unwrap().len(), 3);
}
