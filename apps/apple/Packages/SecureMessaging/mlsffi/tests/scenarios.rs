//! End-to-end scenarios through the public engine API, on real files.

use std::ops::Deref;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

use mlsffi::engine::{generate_signature_keypair, Engine, Processed, SignatureKeypair};
use mlsffi::error::FfiError;

/// A fresh directory under the system temp dir, removed on drop.
struct TempDir(PathBuf);

impl TempDir {
    fn new() -> Self {
        static COUNTER: AtomicU64 = AtomicU64::new(0);
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let n = COUNTER.fetch_add(1, Ordering::SeqCst);
        let dir =
            std::env::temp_dir().join(format!("mlsffi-scn-{}-{nanos:x}-{n}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        TempDir(dir)
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

/// A device: an engine plus what is needed to rebuild it on the same directory.
struct Dev {
    engine: Arc<Engine>,
    dir: TempDir,
    identity: String,
    public: Vec<u8>,
    secret: Vec<u8>,
}

impl Deref for Dev {
    type Target = Engine;
    fn deref(&self) -> &Engine {
        &self.engine
    }
}

fn build(identity: &str, public: &[u8], secret: &[u8], dir: &TempDir) -> Arc<Engine> {
    let keypair = SignatureKeypair {
        public_key: public.to_vec(),
        secret_key: secret.to_vec(),
    };
    Engine::new(
        identity.to_string(),
        keypair,
        dir.0.to_string_lossy().into_owned(),
    )
    .unwrap()
}

fn dev(identity: &str) -> Dev {
    let kp = generate_signature_keypair().unwrap();
    let dir = TempDir::new();
    let engine = build(identity, &kp.public_key, &kp.secret_key, &dir);
    Dev {
        engine,
        dir,
        identity: identity.to_string(),
        public: kp.public_key,
        secret: kp.secret_key,
    }
}

impl Dev {
    /// Drops the engine and builds a new one on the same directory.
    fn restart(&mut self) {
        // Replace with a throwaway first so the old engine is really dropped.
        let old = std::mem::replace(
            &mut self.engine,
            build("x/x", &self.public, &self.secret, &TempDir::new()),
        );
        drop(old);
        self.engine = build(&self.identity, &self.public, &self.secret, &self.dir);
    }

    fn path(&self, rel: &str) -> PathBuf {
        self.dir.0.join(rel)
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// alice (acct-a/dev-1) creates a group and adds the given devices in one
/// commit, which she accepts; each joins from the Welcome. Returns the group id.
fn group_of(alice: &Dev, others: &[&Dev]) -> Vec<u8> {
    let g = alice.create_group().unwrap();
    let kps: Vec<Vec<u8>> = others
        .iter()
        .map(|d| d.key_packages(1).unwrap().remove(0))
        .collect();
    let bundle = alice.add(g.clone(), kps).unwrap();
    alice.commit_accepted(g.clone()).unwrap();
    let welcome = bundle.welcome.unwrap();
    for d in others {
        match d.process(welcome.clone()).unwrap() {
            Processed::Joined { group_id, epoch } => {
                assert_eq!(group_id, g);
                assert_eq!(epoch, 1);
            }
            other => panic!("expected Joined, got {other:?}"),
        }
    }
    g
}

fn say(from: &Dev, to: &Dev, g: &[u8], text: &[u8]) {
    let enc = from.encrypt(g.to_vec(), text.to_vec()).unwrap();
    match to.process(enc.ciphertext).unwrap() {
        Processed::Application {
            sender,
            plaintext,
            epoch,
            ..
        } => {
            assert_eq!(sender, from.identity);
            assert_eq!(plaintext, text);
            assert_eq!(epoch, enc.epoch);
        }
        other => panic!("expected Application, got {other:?}"),
    }
}

#[test]
fn two_devices_exchange_messages() {
    let (alice, bob) = (dev("acct-a/dev-1"), dev("acct-b/dev-1"));
    let kp = bob.key_packages(1).unwrap().remove(0);
    let g = alice.create_group().unwrap();
    assert_eq!(g.len(), 32);
    assert_eq!(alice.epoch(g.clone()).unwrap(), 0);

    let bundle = alice.add(g.clone(), vec![kp]).unwrap();
    assert!(bundle.welcome.is_some());
    assert_eq!(bundle.new_epoch, 1);
    assert_eq!(alice.epoch(g.clone()).unwrap(), 0);
    alice.commit_accepted(g.clone()).unwrap();
    assert_eq!(alice.epoch(g.clone()).unwrap(), 1);

    match bob.process(bundle.welcome.unwrap()).unwrap() {
        Processed::Joined { group_id, epoch } => {
            assert_eq!(group_id, g);
            assert_eq!(epoch, 1);
        }
        other => panic!("expected Joined, got {other:?}"),
    }

    let enc = alice.encrypt(g.clone(), b"hi".to_vec()).unwrap();
    assert_eq!(enc.epoch, 1);
    match bob.process(enc.ciphertext).unwrap() {
        Processed::Application {
            group_id,
            sender,
            plaintext,
            epoch,
        } => {
            assert_eq!(group_id, g);
            assert_eq!(sender, "acct-a/dev-1");
            assert_eq!(plaintext, b"hi");
            assert_eq!(epoch, 1);
        }
        other => panic!("expected Application, got {other:?}"),
    }
    say(&bob, &alice, &g, b"hello back");
}

#[test]
fn members_lists_identities() {
    let (alice, bob) = (dev("acct-a/dev-1"), dev("acct-b/dev-1"));
    let g = group_of(&alice, &[&bob]);
    for d in [&alice, &bob] {
        let mut members = d.members(g.clone()).unwrap();
        members.sort();
        assert_eq!(members, vec!["acct-a/dev-1", "acct-b/dev-1"]);
    }
    assert!(matches!(
        alice.members(vec![9; 32]),
        Err(FfiError::UnknownGroup)
    ));
}

#[test]
fn three_devices_add_and_remove() {
    let alice = dev("acct-a/dev-1");
    let (bob, alice2) = (dev("acct-b/dev-1"), dev("acct-a/dev-2"));
    let g = group_of(&alice, &[&bob, &alice2]);
    assert_eq!(alice.epoch(g.clone()).unwrap(), 1);

    let removal = alice
        .remove(g.clone(), vec!["acct-a/dev-2".to_string()])
        .unwrap();
    assert_eq!(removal.new_epoch, 2);
    alice.commit_accepted(g.clone()).unwrap();
    assert!(removal.welcome.is_none());

    match bob.process(removal.commit.clone()).unwrap() {
        Processed::Commit {
            new_epoch,
            removed_me,
            ..
        } => {
            assert_eq!(new_epoch, 2);
            assert!(!removed_me);
        }
        other => panic!("expected Commit, got {other:?}"),
    }
    match alice2.process(removal.commit).unwrap() {
        Processed::Commit { removed_me, .. } => assert!(removed_me),
        other => panic!("expected Commit, got {other:?}"),
    }

    let enc = bob.encrypt(g.clone(), b"after removal".to_vec()).unwrap();
    assert_eq!(enc.epoch, 2);
    assert!(alice2.process(enc.ciphertext.clone()).is_err());
    assert!(matches!(
        alice.process(enc.ciphertext).unwrap(),
        Processed::Application { .. }
    ));
}

#[test]
fn remove_unknown_member_is_not_member() {
    let (alice, bob) = (dev("acct-a/dev-1"), dev("acct-b/dev-1"));
    let g = group_of(&alice, &[&bob]);
    let r = alice.remove(g.clone(), vec!["acct-z/dev-9".to_string()]);
    assert!(matches!(r, Err(FfiError::NotMember)), "got {r:?}");
    assert!(!alice.has_pending_commit(g).unwrap());
}

#[test]
fn commit_rejected_discards_pending() {
    let (alice, bob) = (dev("acct-a/dev-1"), dev("acct-b/dev-1"));
    let g = alice.create_group().unwrap();
    alice
        .add(g.clone(), vec![bob.key_packages(1).unwrap().remove(0)])
        .unwrap();
    assert!(alice.has_pending_commit(g.clone()).unwrap());
    alice.commit_rejected(g.clone()).unwrap();
    assert_eq!(alice.epoch(g.clone()).unwrap(), 0);
    assert!(!alice.has_pending_commit(g.clone()).unwrap());
    alice
        .add(g, vec![bob.key_packages(1).unwrap().remove(0)])
        .unwrap();
}

#[test]
fn commit_rejected_without_pending_is_ok() {
    let alice = dev("acct-a/dev-1");
    let g = alice.create_group().unwrap();
    alice.commit_rejected(g.clone()).unwrap();
    assert_eq!(alice.epoch(g).unwrap(), 0);
}

#[test]
fn pending_commit_blocks_new_commit() {
    let (alice, bob, carol) = (
        dev("acct-a/dev-1"),
        dev("acct-b/dev-1"),
        dev("acct-c/dev-1"),
    );
    let g = alice.create_group().unwrap();
    alice
        .add(g.clone(), vec![bob.key_packages(1).unwrap().remove(0)])
        .unwrap();
    let second = alice.add(g, vec![carol.key_packages(1).unwrap().remove(0)]);
    assert!(
        matches!(second, Err(FfiError::PendingCommit)),
        "got {second:?}"
    );
}

#[test]
fn pending_commit_survives_restart() {
    let (mut alice, bob) = (dev("acct-a/dev-1"), dev("acct-b/dev-1"));
    let g = alice.create_group().unwrap();
    alice
        .add(g.clone(), vec![bob.key_packages(1).unwrap().remove(0)])
        .unwrap();
    alice.restart();
    assert!(alice.has_pending_commit(g.clone()).unwrap());
    alice.commit_accepted(g.clone()).unwrap();
    assert_eq!(alice.epoch(g).unwrap(), 1);
}

#[test]
fn commit_accepted_without_pending_is_a_noop() {
    let alice = dev("acct-a/dev-1");
    let g = alice.create_group().unwrap();
    alice.commit_accepted(g.clone()).unwrap();
    assert_eq!(alice.epoch(g).unwrap(), 0);
}

#[test]
fn own_commit_echoed_back_applies_the_pending_commit() {
    // Server design 7.2: an accepted commit is delivered to its sender too.
    let (alice, bob, carol) = (
        dev("acct-a/dev-1"),
        dev("acct-b/dev-1"),
        dev("acct-c/dev-1"),
    );
    let g = group_of(&alice, &[&bob]);
    let bundle = alice
        .add(g.clone(), vec![carol.key_packages(1).unwrap().remove(0)])
        .unwrap();
    assert!(alice.has_pending_commit(g.clone()).unwrap());
    assert_eq!(alice.epoch(g.clone()).unwrap(), 1);

    match alice.process(bundle.commit.clone()).unwrap() {
        Processed::Commit {
            new_epoch,
            removed_me,
            ..
        } => {
            assert_eq!(new_epoch, 2);
            assert!(!removed_me);
        }
        other => panic!("expected Commit, got {other:?}"),
    }
    assert_eq!(alice.epoch(g.clone()).unwrap(), 2);
    assert!(!alice.has_pending_commit(g.clone()).unwrap());

    // The 200 may arrive after the echo: nothing is left to accept.
    alice.commit_accepted(g.clone()).unwrap();
    assert_eq!(alice.epoch(g.clone()).unwrap(), 2);

    // Everyone converges and keeps talking.
    assert!(matches!(
        bob.process(bundle.commit).unwrap(),
        Processed::Commit { new_epoch: 2, .. }
    ));
    carol.process(bundle.welcome.unwrap()).unwrap();
    say(&alice, &bob, &g, b"epoch two");
    say(&carol, &alice, &g, b"from carol");
}

#[test]
fn commit_race_both_orders() {
    for order_a in [true, false] {
        let (alice, bob) = (dev("acct-a/dev-1"), dev("acct-b/dev-1"));
        let (carol, dave) = (dev("acct-c/dev-1"), dev("acct-d/dev-1"));
        let g = group_of(&alice, &[&bob]);

        let alice_commit = alice
            .add(g.clone(), vec![carol.key_packages(1).unwrap().remove(0)])
            .unwrap();
        let bob_commit = bob
            .add(g.clone(), vec![dave.key_packages(1).unwrap().remove(0)])
            .unwrap();
        assert!(alice.has_pending_commit(g.clone()).unwrap());
        // The server accepts bob's commit; alice's is stale.
        let _ = alice_commit;
        bob.commit_accepted(g.clone()).unwrap();

        if order_a {
            alice.commit_rejected(g.clone()).unwrap();
            match alice.process(bob_commit.commit.clone()).unwrap() {
                Processed::Commit { new_epoch, .. } => assert_eq!(new_epoch, 2),
                other => panic!("expected Commit, got {other:?}"),
            }
        } else {
            // Processing the winning commit discards alice's pending one itself.
            match alice.process(bob_commit.commit.clone()).unwrap() {
                Processed::Commit { new_epoch, .. } => assert_eq!(new_epoch, 2),
                other => panic!("expected Commit, got {other:?}"),
            }
            assert!(!alice.has_pending_commit(g.clone()).unwrap());
            alice.commit_rejected(g.clone()).unwrap();
        }
        assert_eq!(alice.epoch(g.clone()).unwrap(), 2);
        assert!(!alice.has_pending_commit(g.clone()).unwrap());
        say(&alice, &bob, &g, b"alice at two");
        say(&bob, &alice, &g, b"bob at two");
    }
}

#[test]
fn duplicate_ciphertext_rejected() {
    let (alice, bob) = (dev("acct-a/dev-1"), dev("acct-b/dev-1"));
    let g = group_of(&alice, &[&bob]);
    let first = alice.encrypt(g.clone(), b"one".to_vec()).unwrap();
    assert!(matches!(
        bob.process(first.ciphertext.clone()),
        Ok(Processed::Application { .. })
    ));
    let again = bob.process(first.ciphertext);
    assert!(matches!(again, Err(FfiError::Duplicate)), "got {again:?}");
    say(&alice, &bob, &g, b"two");
}

#[test]
fn own_ciphertext_is_duplicate() {
    let (alice, bob) = (dev("acct-a/dev-1"), dev("acct-b/dev-1"));
    let g = group_of(&alice, &[&bob]);
    let enc = alice.encrypt(g.clone(), b"mine".to_vec()).unwrap();
    let r = alice.process(enc.ciphertext);
    assert!(matches!(r, Err(FfiError::Duplicate)), "got {r:?}");
}

#[test]
fn welcome_after_restart() {
    let (alice, mut bob) = (dev("acct-a/dev-1"), dev("acct-b/dev-1"));
    let kp = bob.key_packages(1).unwrap().remove(0);
    assert_eq!(
        std::fs::read_dir(bob.path("keypackages")).unwrap().count(),
        1
    );
    let g = alice.create_group().unwrap();
    let bundle = alice.add(g.clone(), vec![kp]).unwrap();
    alice.commit_accepted(g.clone()).unwrap();

    bob.restart();
    assert!(matches!(
        bob.process(bundle.welcome.unwrap()).unwrap(),
        Processed::Joined { epoch: 1, .. }
    ));
    assert_eq!(
        std::fs::read_dir(bob.path("keypackages")).unwrap().count(),
        0
    );
    say(&alice, &bob, &g, b"welcome home");
}

#[test]
fn replayed_welcome_is_rejected_cleanly() {
    let (alice, bob) = (dev("acct-a/dev-1"), dev("acct-b/dev-1"));
    let g = alice.create_group().unwrap();
    let bundle = alice
        .add(g.clone(), vec![bob.key_packages(1).unwrap().remove(0)])
        .unwrap();
    alice.commit_accepted(g.clone()).unwrap();
    let welcome = bundle.welcome.unwrap();
    bob.process(welcome.clone()).unwrap();
    let again = bob.process(welcome);
    assert!(matches!(again, Err(FfiError::NotMember)), "got {again:?}");
    say(&alice, &bob, &g, b"still fine");
}

#[test]
fn export_secret_matches_across_members() {
    let (alice, bob, carol) = (
        dev("acct-a/dev-1"),
        dev("acct-b/dev-1"),
        dev("acct-c/dev-1"),
    );
    let g = group_of(&alice, &[&bob]);
    let a1 = alice
        .export_secret(g.clone(), "call-media".into(), 32)
        .unwrap();
    let b1 = bob
        .export_secret(g.clone(), "call-media".into(), 32)
        .unwrap();
    assert_eq!(a1.len(), 32);
    assert_eq!(a1, b1);
    assert_ne!(
        a1,
        alice
            .export_secret(g.clone(), "other-label".into(), 32)
            .unwrap()
    );

    let bundle = alice
        .add(g.clone(), vec![carol.key_packages(1).unwrap().remove(0)])
        .unwrap();
    alice.commit_accepted(g.clone()).unwrap();
    bob.process(bundle.commit).unwrap();
    let a2 = alice
        .export_secret(g.clone(), "call-media".into(), 32)
        .unwrap();
    let b2 = bob
        .export_secret(g.clone(), "call-media".into(), 32)
        .unwrap();
    assert_eq!(a2, b2);
    assert_ne!(a1, a2);
}

#[test]
fn unknown_group_and_garbage_errors() {
    let alice = dev("acct-a/dev-1");
    for r in [
        alice.epoch(vec![1; 32]).map(|_| ()),
        alice.encrypt(vec![1; 32], vec![]).map(|_| ()),
    ] {
        assert!(matches!(r, Err(FfiError::UnknownGroup)), "got {r:?}");
    }
    assert!(matches!(
        alice.process(vec![0xff; 10]),
        Err(FfiError::Malformed)
    ));
}

#[test]
fn state_is_reread_not_cached() {
    // Two engines on one directory (as the app and the NSE would be): each sees
    // what the other did.
    let (alice, bob) = (dev("acct-a/dev-1"), dev("acct-b/dev-1"));
    let g = alice.create_group().unwrap();
    let twin = build(&alice.identity, &alice.public, &alice.secret, &alice.dir);
    assert_eq!(twin.epoch(g.clone()).unwrap(), 0);
    alice
        .add(g.clone(), vec![bob.key_packages(1).unwrap().remove(0)])
        .unwrap();
    assert!(twin.has_pending_commit(g.clone()).unwrap());
    twin.commit_accepted(g.clone()).unwrap();
    assert_eq!(alice.epoch(g).unwrap(), 1);
}

#[test]
fn malformed_inputs_never_panic() {
    let (alice, bob) = (dev("acct-a/dev-1"), dev("acct-b/dev-1"));
    let g = group_of(&alice, &[&bob]);
    let real = alice
        .encrypt(g.clone(), b"real message".to_vec())
        .unwrap()
        .ciphertext;

    let mut inputs: Vec<Vec<u8>> = (0..real.len()).map(|n| real[..n].to_vec()).collect();
    let mut x: u64 = 0x9e37_79b9_7f4a_7c15;
    let mut next = move || {
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        x
    };
    for _ in 0..2000 {
        let len = (next() % 601) as usize;
        inputs.push((0..len).map(|_| next() as u8).collect());
    }
    assert!(inputs.len() >= 2000);

    for input in inputs {
        let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| bob.process(input)));
        match r {
            Ok(result) => assert!(result.is_err()),
            Err(_) => panic!("process panicked"),
        }
    }
    // Rejected input changed nothing: the real message still decrypts.
    assert!(matches!(
        bob.process(real),
        Ok(Processed::Application { .. })
    ));
}

/// The epoch record is written before the group state. If the process dies in
/// between, the stored max epoch id is one ahead of the stored state, and
/// mls-rs would refuse every later commit (`InvalidEpoch`). The engine repairs
/// this on load: epoch records at or above the current epoch are deleted.
#[test]
fn crash_between_epoch_insert_and_state_write_recovers() {
    let (alice, bob, carol) = (
        dev("acct-a/dev-1"),
        dev("acct-b/dev-1"),
        dev("acct-c/dev-1"),
    );
    let g = group_of(&alice, &[&bob]);
    let state_path = bob.path(&format!("groups/{}/state", hex(&g)));
    let stray_epoch = bob.path(&format!("groups/{}/epoch-1", hex(&g)));
    let state_at_1 = std::fs::read(&state_path).unwrap();

    let bundle = alice
        .add(g.clone(), vec![carol.key_packages(1).unwrap().remove(0)])
        .unwrap();
    alice.commit_accepted(g.clone()).unwrap();
    bob.process(bundle.commit.clone()).unwrap();
    assert_eq!(bob.epoch(g.clone()).unwrap(), 2);

    // Simulate the crash: epoch-1 is on disk, the state is still epoch 1.
    std::fs::write(&state_path, &state_at_1).unwrap();
    assert!(stray_epoch.exists());

    // The same commit processes normally, and the group carries on.
    assert!(matches!(
        bob.process(bundle.commit).unwrap(),
        Processed::Commit { new_epoch: 2, .. }
    ));
    say(&alice, &bob, &g, b"recovered");
    // Bob's own next commit works too (it inserts the next epoch record).
    let next = bob
        .add(
            g.clone(),
            vec![dev("acct-d/dev-1").key_packages(1).unwrap().remove(0)],
        )
        .unwrap();
    bob.commit_accepted(g.clone()).unwrap();
    assert!(matches!(
        alice.process(next.commit).unwrap(),
        Processed::Commit { new_epoch: 3, .. }
    ));
}

/// A device that was removed and later re-added gets a Welcome that replaces
/// its state; epoch records from the old membership must not break later commits.
#[test]
fn removed_then_readded_device_keeps_working() {
    let (alice, bob, carol) = (
        dev("acct-a/dev-1"),
        dev("acct-b/dev-1"),
        dev("acct-c/dev-1"),
    );
    let g = group_of(&alice, &[&bob]);
    let add_carol = alice
        .add(g.clone(), vec![carol.key_packages(1).unwrap().remove(0)])
        .unwrap();
    alice.commit_accepted(g.clone()).unwrap();
    bob.process(add_carol.commit).unwrap();
    carol.process(add_carol.welcome.unwrap()).unwrap();

    let removal = alice
        .remove(g.clone(), vec!["acct-b/dev-1".into()])
        .unwrap();
    alice.commit_accepted(g.clone()).unwrap();
    carol.process(removal.commit.clone()).unwrap();
    assert!(matches!(
        bob.process(removal.commit).unwrap(),
        Processed::Commit {
            removed_me: true,
            ..
        }
    ));

    let readd = alice
        .add(g.clone(), vec![bob.key_packages(1).unwrap().remove(0)])
        .unwrap();
    alice.commit_accepted(g.clone()).unwrap();
    carol.process(readd.commit).unwrap();
    assert!(matches!(
        bob.process(readd.welcome.unwrap()).unwrap(),
        Processed::Joined { epoch: 4, .. }
    ));
    say(&alice, &bob, &g, b"welcome back");

    // Bob commits, then receives a commit: both touch the epoch records.
    let bob_commit = bob
        .add(
            g.clone(),
            vec![dev("acct-d/dev-1").key_packages(1).unwrap().remove(0)],
        )
        .unwrap();
    bob.commit_accepted(g.clone()).unwrap();
    assert!(matches!(
        alice.process(bob_commit.commit.clone()).unwrap(),
        Processed::Commit { new_epoch: 5, .. }
    ));
    carol.process(bob_commit.commit).unwrap();
    let alice_commit = alice
        .add(
            g.clone(),
            vec![dev("acct-e/dev-1").key_packages(1).unwrap().remove(0)],
        )
        .unwrap();
    alice.commit_accepted(g.clone()).unwrap();
    assert!(matches!(
        bob.process(alice_commit.commit).unwrap(),
        Processed::Commit { new_epoch: 6, .. }
    ));
    say(&bob, &alice, &g, b"still in sync");
}

#[test]
fn future_commit_is_an_epoch_gap_and_catching_up_works() {
    let (alice, bob, carol, dave) = (
        dev("acct-a/dev-1"),
        dev("acct-b/dev-1"),
        dev("acct-c/dev-1"),
        dev("acct-d/dev-1"),
    );
    let g = group_of(&alice, &[&bob]);
    let c1 = alice
        .add(g.clone(), vec![carol.key_packages(1).unwrap().remove(0)])
        .unwrap();
    alice.commit_accepted(g.clone()).unwrap();
    let c2 = alice
        .add(g.clone(), vec![dave.key_packages(1).unwrap().remove(0)])
        .unwrap();
    alice.commit_accepted(g.clone()).unwrap();

    let gap = bob.process(c2.commit.clone());
    assert!(matches!(gap, Err(FfiError::EpochGap)), "got {gap:?}");
    assert_eq!(bob.epoch(g.clone()).unwrap(), 1);
    bob.process(c1.commit).unwrap();
    bob.process(c2.commit).unwrap();
    assert_eq!(bob.epoch(g.clone()).unwrap(), 3);
    say(&alice, &bob, &g, b"caught up");
}

#[test]
fn old_commit_and_own_echo_after_accepted_are_duplicates() {
    let (alice, bob, carol) = (
        dev("acct-a/dev-1"),
        dev("acct-b/dev-1"),
        dev("acct-c/dev-1"),
    );
    let g = group_of(&alice, &[&bob]);
    let c = alice
        .add(g.clone(), vec![carol.key_packages(1).unwrap().remove(0)])
        .unwrap();
    alice.commit_accepted(g.clone()).unwrap();
    // The echo of our own commit arrives after commit_accepted.
    let echo = alice.process(c.commit.clone());
    assert!(matches!(echo, Err(FfiError::Duplicate)), "got {echo:?}");
    assert_eq!(alice.epoch(g.clone()).unwrap(), 2);

    // Bob applies the commit, then gets it delivered again.
    bob.process(c.commit.clone()).unwrap();
    let again = bob.process(c.commit);
    assert!(matches!(again, Err(FfiError::Duplicate)), "got {again:?}");
    assert_eq!(bob.epoch(g.clone()).unwrap(), 2);
    say(&alice, &bob, &g, b"fine");
}

#[test]
fn late_application_message_reports_the_epoch_it_was_sent_in() {
    let (alice, bob, carol) = (
        dev("acct-a/dev-1"),
        dev("acct-b/dev-1"),
        dev("acct-c/dev-1"),
    );
    let g = group_of(&alice, &[&bob]);
    let late = alice.encrypt(g.clone(), b"late".to_vec()).unwrap();
    assert_eq!(late.epoch, 1);
    let c = alice
        .add(g.clone(), vec![carol.key_packages(1).unwrap().remove(0)])
        .unwrap();
    alice.commit_accepted(g.clone()).unwrap();
    bob.process(c.commit).unwrap();
    assert_eq!(bob.epoch(g.clone()).unwrap(), 2);
    match bob.process(late.ciphertext).unwrap() {
        Processed::Application {
            plaintext, epoch, ..
        } => {
            assert_eq!(plaintext, b"late");
            assert_eq!(epoch, 1);
        }
        other => panic!("expected Application, got {other:?}"),
    }
}

/// A write error after the durable state write (here: pruning an old epoch
/// fails) is reported as `Storage`, but the new state is already on disk. The
/// engine keeps nothing in memory, so a reload shows the truth.
#[test]
fn storage_error_after_durable_write_means_reload() {
    let alice = dev("acct-a/dev-1");
    let g = alice.create_group().unwrap();
    let group_dir = alice.path(&format!("groups/{}", hex(&g)));
    for round in 0..4u64 {
        if round == 3 {
            // epoch-0 is the oldest of four records after the next commit and
            // will be pruned: make that fail by turning it into a directory.
            std::fs::remove_file(group_dir.join("epoch-0")).unwrap();
            std::fs::create_dir(group_dir.join("epoch-0")).unwrap();
        }
        let joiner = dev(&format!("acct-j{round}/dev-1"));
        alice
            .add(g.clone(), vec![joiner.key_packages(1).unwrap().remove(0)])
            .unwrap();
        let result = alice.commit_accepted(g.clone());
        if round < 3 {
            result.unwrap();
        } else {
            assert!(
                matches!(result, Err(FfiError::Storage(_))),
                "got {result:?}"
            );
        }
    }
    // The error said "unknown"; the reload shows the commit did land.
    assert_eq!(alice.epoch(g.clone()).unwrap(), 4);
    assert!(!alice.has_pending_commit(g).unwrap());
}
