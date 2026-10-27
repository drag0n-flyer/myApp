# MLS Client Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Two lanes can run in parallel; see "Lanes".

**Goal:** Give the iPhone and Mac apps one Swift API, `MLSEngine`, for MLS (RFC 9420). It is backed by mls-rs through a small Rust FFI crate built into an XCFramework, keeps its state in the app group so the Notification Service Extension can decrypt, and comes with a prototype NSE that decrypts a padded push.

**Architecture:**
- A thin Rust crate, `mlsffi`, wraps `mls-rs` with UniFFI (sync mode). It exposes only the operations `MLSEngine` needs, and stores group state and KeyPackage secrets as files in a directory the app chooses.
- A script builds it into `MLSFFI.xcframework`: iOS device, iOS simulator, and macOS universal.
- The Swift package `SecureMessaging` wraps the generated bindings in the `MLSEngine` contract from the [roadmap](README.md). It adds a cross-process file lock, the Keychain-held signing key, `FakeMLSEngine`, and push-payload unpadding.
- A separate prototype project proves the NSE path end to end.

**Tech Stack:**
- Rust 1.82+
- `mls-rs`, `mls-rs-core` and `mls-rs-crypto-rustcrypto` (the crypto provider on every target; `mls-rs-crypto-cryptokit` was dropped by the owner decision of 2026-10-28), pinned to git rev `323bd84f52e410c9ecb0b1ccd84a5a9f7f191b5a`
- UniFFI 0.31
- Swift 6 and SwiftPM, XcodeGen, GitHub Actions `macos-26`

**Spec:** [GOAL.md](../../../GOAL.md) §5 (Encryption protocol) and §10 (NSE testing), the [roadmap](README.md) ("`SecureMessaging`" contract), and [server design](../specs/2026-10-27-server-design.md) §7.2 (commit and epoch rules) and §7.4 (push padding). There is no separate design document; "Design (decisions)" below is the spec for what those leave open.

## Global Constraints

- Swift package minimums: iOS 17, macOS 14. Swift 6 language mode with strict concurrency for `SecureMessaging`. The generated bindings target `MLSFFIBindings` builds in Swift 5 mode, because UniFFI output is not Swift 6 clean.
- Dependencies: only the mls-rs crates named above at rev `323bd84f52e410c9ecb0b1ccd84a5a9f7f191b5a`, plus `uniffi = "0.31"`. Nothing else, not even `thiserror` or `tempfile`; write those few lines by hand. Commit `Cargo.lock`.
- Cipher suite: only `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519` (RFC 9420 §17.1, value `0x0001`; `CipherSuite::CURVE25519_AES128` in mls-rs).
- Credential: `BasicCredential` whose identity is the UTF-8 bytes of `"<accountID>/<deviceID>"` (roadmap, Identifiers).
- The public Swift API matches the roadmap's `SecureMessaging` block exactly. Additions are allowed; renames are not.
- **Owner review:** every file in this plan is cryptographic code (AGENTS.md, GOAL.md §16). Nothing merges to `main` without the owner's approval.
- Never log plaintext, ciphertext, keys, or KeyPackages. Group IDs may appear in debug logs as hex.
- Commits: plain messages, no `Co-Authored-By` or AI trailers. Coding agents run on Sonnet 5.5.
- Branch: `feat/mls-core` from `main`. This plan touches no file from plans 1 or 2.

## Review Focus

1. **A commit's `200` is lost**, through an app crash or a network drop, after the server accepted it. The pending commit must survive a restart and resolve by `commitAccepted` or `commitRejected`, never by being silently dropped. Pinned by M2 `pending_commit_survives_restart`. See also "Contract notes" (server echo).
2. **The app and the NSE decrypt for the same group at the same moment.** Both would advance the secret tree from the same stored state; one write would be lost, and a message key could be reused or lost. Every engine operation runs under one cross-process lock and reloads state from disk inside it. Pinned by M4 `CrossProcessLockTests` and M6 `testAppAndNSEProcessesShareState`.
3. **The same ciphertext arrives twice** (push plus inbox sync), **or a commit races another device's commit.** The result must be a typed error or a clean state, never a crash or a corrupted group. Pinned by M2 `duplicate_ciphertext_rejected` and `commit_race_both_orders`.
4. **The app restarts between publishing KeyPackages and receiving the Welcome.** The KeyPackage private keys must survive (mls-rs keeps them in memory by default). Pinned by M2 `welcome_after_restart`.
5. **A lock is held while iOS suspends the app.** That kills the app with `0xdead10cc` ([Apple: exception types](https://developer.apple.com/documentation/xcode/understanding-the-exception-types-in-a-crash-report)). The lock is held only for one engine call and released on every path, errors included. Pinned by M4 `testLockReleasedAfterThrow` and M5 `testLockFreeAfterEveryOperation`.

## Design (decisions)

- **Why not the official `mls-rs-uniffi` crate?** At the pinned rev it has four gaps:
  - It hard-wires the OpenSSL crypto provider, which would mean building OpenSSL for iOS.
  - It exposes no `export_secret`, epoch getter, or pending-commit apply/clear.
  - Its messages are opaque objects with no byte serialization.
  - It keeps KeyPackage secrets in memory.

  Source: `mls-rs-uniffi/src/lib.rs` at the pinned rev, where `Client::new` uses `OpensslCryptoProvider` and the `Group` methods are `write_to_storage`, `export_tree`, `commit`, `add_members`, `remove_members`, `encrypt_application_message` and `process_incoming_message`. On crates.io, `mls-rs-uniffi` is 0.1.0 and the git-only crates are not published. That is why we pin git rev `323bd84…`.
- **`mlsffi`** is a thin UniFFI crate of a few hundred lines that calls `mls-rs` directly. It has no protocol logic of its own. Where it can, it mirrors `mls-rs-uniffi`, so the additions can be offered upstream.
- **Crypto provider.** `mls-rs-crypto-rustcrypto` on all targets (iPhone, Mac, Linux), cipher suite `CURVE25519_AES128`. Owner decision 2026-10-28: the CryptoKit crate's Swift bridge needs macOS 26 (the plan supports macOS 14), its `build.rs` breaks on Xcode 26.1 and later, one crypto path is easier to review, and it avoids a possible duplicate-symbol clash with WebRTC. There is no `cfg(target_vendor)` provider choice.
- **Storage.** Files under the directory passed to `makeMLSEngine(identity:storage:)`:
  - `groups/<hex group id>/state`
  - `groups/<hex group id>/epoch-<n>`
  - `keypackages/<hex id>`

  Each write goes to `*.tmp`, then fsync, then rename. The newest **3** epochs are kept, the same default as mls-rs's `DEFAULT_EPOCH_RETENTION_LIMIT` in `storage_provider/in_memory/group_state_storage.rs`. The pending commit lives inside the group state snapshot (mls-rs `PendingCommitSnapshot`), so it persists.
- **No in-memory group cache.** Every call loads the group from disk, acts, and writes it back. That is what makes the cross-process lock sufficient.
- **Cross-process lock.** `flock(LOCK_EX)` on `<storage>/mls.lock`. It is retried every 10 ms for up to 5 s, then throws `MLSError.busy`, and it is held for exactly one engine call. `flock` works across processes sharing an app group container, and on both Linux and Darwin.
- **Data protection.** The storage directory is created with `FileProtectionType.completeUntilFirstUserAuthentication`, so the NSE can read it while the phone is locked (GOAL.md §5). New files inherit the directory's class. The signing key is a Keychain generic-password item with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` in the shared access group. Its service is `org.example.app.mls.signing` and its account is the identity. The resolved access group is read from the Info.plist key `MLSKeychainAccessGroup`.
- **Group IDs** are 32 random bytes, generated client-side. The server's conversation ID is separate, and plan 7 maps one to the other.
- **Pending commits.** Starting an `add` or `remove` while a commit is pending throws `MLSError.pendingCommit`. Processing someone else's commit for a group with a pending commit first clears ours, because that commit lost the race. A later `commitRejected` is then a no-op.
- **Proposals** are never sent by our clients. Receiving a standalone proposal throws `MLSError.malformed`.
- **Errors.** Rust's `FfiError` maps one-to-one to Swift's `MLSError`:

  | `FfiError` | `MLSError` | mls-rs source |
  |---|---|---|
  | `Malformed` | `.malformed` | decode errors |
  | `UnknownGroup` | `.unknownGroup` | `GroupNotFound` |
  | `NotMember` | `.notMember` | |
  | `Duplicate` | `.duplicate` | `CantProcessMessageFromSelf`, `KeyMissing`; an older-epoch message mls-rs rejects |
  | `EpochGap` | `.epochGap` | message epoch newer than the stored group (a commit was missed; resync) |
  | `PendingCommit` | `.pendingCommit` | `ExistingPendingCommit` |
  | `Storage(String)` | `.storage` | |
  | `Crypto(String)` | `.crypto` | |
  | `Internal(String)` | `.internalError` | |

  `.busy` comes only from Swift (the lock timeout).
- **Push padding** (server spec §7.4) is a 2-byte big-endian length, then the payload, then zeros up to 256, 512, 1024, 2048 or 3072 bytes. A length of 0 means "fetch the inbox". Non-zero padding bytes, or a length longer than the data, are `malformed`.

### Decisions I made — owner to confirm

1. **Our own thin FFI crate (`mlsffi`) instead of `mls-rs-uniffi`.** GOAL.md §3 says libraries should be consumed through their official bindings and "no shared core". The official binding lacks the functions we need and forces OpenSSL. The crate holds no protocol logic, and its additions go upstream.
2. **CryptoKit as the crypto provider on Apple platforms.** Superseded by the owner decision of 2026-10-28: RustCrypto on all targets. (The original reasoning, Apple's crypto and a smaller binary, lost to the macOS 26 requirement, the Xcode 26.1 build break and the single review path.)
3. **Pinning to git rev `323bd84…`** (mls-rs 0.57.0, unreleased) instead of crates.io. the pinned crates are not all on crates.io. Re-pin to a release tag when one includes it.
4. **Three epochs of past keys are kept.** A message more than two epochs late cannot be decrypted. This trades forward secrecy for tolerance of late messages.
5. **The NSE may process every message kind**: application messages, commits and Welcomes. The lock makes this safe. The alternative is to have the NSE show "New message" for anything that isn't an application message.
6. **A Keychain signing key per device, never synced** (`ThisDeviceOnly`). Losing the device means a new identity, and the other side must re-verify. This matches GOAL.md's no-escrow stance.

### Contract notes (for the roadmap and plan 2)

- **Server change recommended:** deliver each accepted commit to its **sender** device too (server spec §7.2 currently says "except the sender"). If the `200` is lost, the sender processes its own commit from the inbox. mls-rs applies a pending commit when it sees its own commit message (`group/mod.rs`, a commit-hash match leads to `apply_pending_commit`). That makes recovery automatic. Without the change, a client that lost the `200` cannot tell "accepted" from "lost the race" without a new endpoint. Until then, plan 7 must treat a `stale_epoch` that follows a timeout as "unknown" and resync. This plan's engine supports both paths.
- The roadmap's dependency table lists `mls-rs-uniffi`. That should read: `mls-rs` + `mls-rs-crypto-rustcrypto` + `uniffi` (Decision 1).
- `MLSError` is new public API. The roadmap does not list it; this is an addition, not a rename.

## Lanes

| Lane | Tasks | Runs on | Can start |
|---|---|---|---|
| R (Rust) | M1 → M2 → M3 | Linux for M1–M2, macOS CI for M3 | now |
| S (Swift) | M4, then M5 → M6 | Linux for M4, macOS CI for M5–M6 | M4 now; M5 after M3 |

Each lane uses its own worktree off `feat/mls-core` (`server/`-style instructions in the [Go server plan](2026-10-27-go-server.md#parallel-execution)). Merge R before S's M5.

## File structure

```
apps/apple/Packages/SecureMessaging/
  Package.swift                        M4 (Apple-only targets behind #if)
  mlsffi/                              Rust crate (lane R)
    Cargo.toml  Cargo.lock
    src/lib.rs        UniFFI exports: Engine, generate_signature_keypair, records, FfiError
    src/storage.rs    FileStorage: GroupStateStorage + KeyPackageStorage
    src/engine.rs     Engine operations over mls-rs
    src/error.rs      FfiError + mapping from MlsError
    src/bin/uniffi-bindgen.rs
    tests/scenarios.rs
  scripts/build-mlsffi.sh              M3: XCFramework + Swift bindings
  Artifacts/MLSFFI.xcframework         built, git-ignored
  Sources/MLSFFIBindings/mlsffi.swift  generated by M3, committed
  Sources/SecureMessaging/
    MLSEngine.swift   GroupID, CommitBundle, Incoming, MLSEngine, MLSError
    FakeMLSEngine.swift
    PushPayload.swift
    CrossProcessLock.swift
    SigningKeyStore.swift   (M5)
    LiveMLSEngine.swift     (M5) + makeMLSEngine
  Sources/mls-nse-sim/main.swift       M6 helper process (Apple only)
  Tests/SecureMessagingTests/…
apps/apple/Prototypes/NSEPrototype/    M6 XcodeGen project: host app + NSE
.github/workflows/mls.yml              M3 (extended in M4–M6)
```

---

## Lane R — Rust

### Task M1: Crate and file storage

**Files:**
- Create: `mlsffi/Cargo.toml`, `mlsffi/src/lib.rs` (module declarations only for now), `mlsffi/src/storage.rs`, `mlsffi/src/error.rs`
- Modify: `.gitignore`, adding `apps/apple/Packages/SecureMessaging/mlsffi/target/` and `apps/apple/Packages/SecureMessaging/Artifacts/`
- Test: the `#[cfg(test)] mod tests` in `storage.rs`

**Interfaces — Produces:**
- `pub struct FileStorage { root: PathBuf }`, with:
  - `FileStorage::new(root: impl Into<PathBuf>) -> Result<Self, FfiError>`, which creates `groups/` and `keypackages/`
  - `impl mls_rs_core::group::GroupStateStorage`
  - `impl mls_rs_core::key_package::KeyPackageStorage`
  - `Clone` (the same root)
- `const EPOCH_RETENTION: u64 = 3`
- `pub enum FfiError { Malformed, UnknownGroup, NotMember, Duplicate, EpochGap, PendingCommit, Storage(String), Crypto(String), Internal(String) }`, as a `#[derive(uniffi::Error)]`, with a hand-written `Display` and `std::error::Error`

`Cargo.toml` contents:
- `[lib] crate-type = ["lib", "staticlib", "cdylib"]`, `name = "mlsffi"`
- `mls-rs`, `mls-rs-core` and `mls-rs-crypto-rustcrypto` as `{ git = "https://github.com/awslabs/mls-rs", rev = "323bd84f52e410c9ecb0b1ccd84a5a9f7f191b5a" }`
- No CryptoKit crate (owner decision 2026-10-28); `mls-rs-crypto-rustcrypto` is unconditional
- `uniffi = { version = "0.31", features = ["cli"] }`
- `[[bin]] name = "uniffi-bindgen"`

- [ ] **Step 1: Write the failing tests** in `storage.rs`, each in a fresh directory under `std::env::temp_dir()` with a random suffix:
  - `group_state_round_trip`: `write(g, state, inserts=[e1, e2], updates=[])` → `state(g) == state`, `epoch(g, 1) == e1.data`, `max_epoch_id(g) == Some(2)`.
  - `keeps_three_newest_epochs`: write epochs 1 through 5 across three calls → epochs 1 and 2 are `None`; 3, 4 and 5 are present.
  - `epoch_updates_replace`: an update to epoch 3 → `epoch(g, 3)` returns the new bytes.
  - `writes_leave_no_temp_files`: after a write, no `*.tmp` remains under `root`. A stray `groups/<hex>/state.tmp` written by hand is ignored by `state()`.
  - `survives_reopen`: a second `FileStorage::new(same root)` reads the same state and KeyPackage.
  - `key_package_insert_get_delete`: get after delete → `None`.
  - `hostile_group_ids_stay_inside_root`: group IDs `b"../x"` and `b"a/b"` are stored as hex directory names under `root/groups`, and nothing is created outside `root`.
- [ ] **Step 2:** `cd apps/apple/Packages/SecureMessaging/mlsffi && cargo test storage` → FAIL (does not compile: `FileStorage` is missing).
- [ ] **Step 3: Implement `storage.rs` and `error.rs`.** Hex-encode IDs with a 6-line helper.
- [ ] **Step 4:** `cargo test storage` → 7 passed.
- [ ] **Step 5: Commit.** `git add -A apps/apple/Packages/SecureMessaging/mlsffi .gitignore && git commit -m "feat(mls): mlsffi crate with file-backed MLS storage"`

### Task M2: Engine operations

**Files:**
- Create: `mlsffi/src/engine.rs`
- Modify: `mlsffi/src/lib.rs` (UniFFI exports)
- Test: `mlsffi/tests/scenarios.rs`

**Interfaces — Produces** (the UniFFI surface, sync; `Vec<u8>` is `Data` in Swift):
```rust
#[derive(uniffi::Record)] pub struct SignatureKeypair { pub public_key: Vec<u8>, pub secret_key: Vec<u8> }
#[derive(uniffi::Record)] pub struct CommitBundle { pub commit: Vec<u8>, pub welcome: Option<Vec<u8>>, pub new_epoch: u64 }
#[derive(uniffi::Record)] pub struct Encrypted { pub ciphertext: Vec<u8>, pub epoch: u64 }
#[derive(uniffi::Enum)] pub enum Processed {
    Application { group_id: Vec<u8>, sender: String, plaintext: Vec<u8>, epoch: u64 },
    Commit { group_id: Vec<u8>, new_epoch: u64, removed_me: bool },
    Joined { group_id: Vec<u8>, epoch: u64 },
}
#[uniffi::export] pub fn generate_signature_keypair() -> Result<SignatureKeypair, FfiError>;
#[derive(uniffi::Object)] pub struct Engine { /* client config + FileStorage */ }
#[uniffi::export] impl Engine {
    #[uniffi::constructor] pub fn new(identity: String, keypair: SignatureKeypair, storage_dir: String) -> Result<Arc<Self>, FfiError>;
    pub fn key_packages(&self, count: u32) -> Result<Vec<Vec<u8>>, FfiError>;
    pub fn create_group(&self) -> Result<Vec<u8>, FfiError>;          // 32 random bytes; epoch 0
    pub fn add(&self, group_id: Vec<u8>, key_packages: Vec<Vec<u8>>) -> Result<CommitBundle, FfiError>;
    pub fn remove(&self, group_id: Vec<u8>, identities: Vec<String>) -> Result<CommitBundle, FfiError>;
    pub fn commit_accepted(&self, group_id: Vec<u8>) -> Result<(), FfiError>;  // apply_pending_commit; no-op if none
    pub fn commit_rejected(&self, group_id: Vec<u8>) -> Result<(), FfiError>;  // clear_pending_commit; no-op if none
    pub fn has_pending_commit(&self, group_id: Vec<u8>) -> Result<bool, FfiError>;
    pub fn encrypt(&self, group_id: Vec<u8>, plaintext: Vec<u8>) -> Result<Encrypted, FfiError>;
    pub fn process(&self, message: Vec<u8>) -> Result<Processed, FfiError>;
    pub fn epoch(&self, group_id: Vec<u8>) -> Result<u64, FfiError>;
    pub fn export_secret(&self, group_id: Vec<u8>, label: String, length: u32) -> Result<Vec<u8>, FfiError>;
}
uniffi::setup_scaffolding!();
```

The client is built with these settings:
- `BasicIdentityProvider`
- `BasicCredential::new(identity.as_bytes())`
- the `FileStorage` as both `group_state_storage` and `key_package_repo`
- `CommitOptions::default().with_ratchet_tree_extension(true).with_single_welcome_message(true)`, matching `mls-rs-uniffi`'s defaults, so a Welcome carries the tree and needs no separate tree fetch
- the default `PaddingMode::StepFunction`

`process`:
- A Welcome goes through `join_group(None, msg)` and returns `Joined`.
- A private or public message goes through `load_group(msg.group_id())`. The engine does not clear a pending commit itself: mls-rs applies it when the message is our own commit echoed back, and drops it when another member's commit is processed (clearing first would break the echo). It compares `message.epoch()` with the group's current epoch first: newer gives `EpochGap`; an older message that mls-rs rejects as `InvalidEpoch`/`EpochNotFound` gives `Duplicate`. Then `process_incoming_message` and `write_to_storage`; `Application.epoch` is the epoch the message was sent in. After every load and after `join_group`, a repair step deletes epoch records with id >= the current epoch, deletes all of them if the newest left is not current-1, and on a Welcome deletes all existing epoch records for the group first.
- Anything else is `Malformed`.

Every mutating method ends with `write_to_storage()`.

- [ ] **Step 1: Write the failing tests** (`tests/scenarios.rs`). A helper, `dev(identity) -> Engine`, gives each engine a fresh temp directory. Plaintexts are byte strings.
  - `two_devices_exchange_messages`:
    1. `bob.key_packages(1)`; `alice.create_group()`; `alice.add(g, [kp])` → `welcome.is_some()`, `new_epoch == 1`; `alice.epoch(g) == 0` until `commit_accepted`, then 1
    2. `bob.process(welcome)` → `Joined { group_id: g, epoch: 1 }`
    3. `alice.encrypt(g, b"hi")` → `epoch == 1`, and `bob.process(ct)` → `Application { sender: "acct-a/dev-1", plaintext: b"hi", epoch: 1 }`
    4. the reverse direction also works
  - `three_devices_add_and_remove`:
    1. alice adds `bob/1` and `alice/2` in one commit → epoch 1, and both join
    2. `alice.remove(g, ["acct-a/dev-2"])` → epoch 2
    3. `bob.process(commit)` → `Commit { new_epoch: 2, removed_me: false }`
    4. `alice2.process(commit)` → `Commit { removed_me: true }`
    5. bob's epoch-2 message → `alice2.process` is `Err(_)`, and `alice.process` is `Application`
  - `commit_rejected_discards_pending`: `add` → `has_pending_commit == true` → `commit_rejected` → `epoch == 0`, `has_pending_commit == false`; a second `add` succeeds.
  - `pending_commit_blocks_new_commit`: `add` then `add` → `Err(FfiError::PendingCommit)`.
  - `pending_commit_survives_restart`: `add`, drop the engine, build a new `Engine` on the same directory → `has_pending_commit == true` → `commit_accepted` → `epoch == 1`.
  - `commit_race_both_orders`, with a group of alice and bob at epoch 1:
    1. alice adds carol's KeyPackage and bob adds dave's, both from epoch 1. The server accepts bob's.
    2. Order A: `bob.commit_accepted`, then `alice.commit_rejected`, then `alice.process(bob_commit)` → epoch 2.
    3. Order B, in a fresh run: `alice.process(bob_commit)` first (this clears alice's pending commit) → epoch 2; then `alice.commit_rejected` → `Ok(())`.
    4. In both orders, alice and bob then exchange a message at epoch 2.
  - `duplicate_ciphertext_rejected`: `bob.process(ct)` → OK; `bob.process(ct)` again → `Err(FfiError::Duplicate)`; the next new message still decrypts.
  - `welcome_after_restart`: bob makes a KeyPackage, his engine is dropped, and a new engine on the same directory processes the Welcome → `Joined`; the `keypackages/` directory is then empty.
  - `export_secret_matches_across_members`: alice and bob, with label `"call-media"` and length 32 → equal bytes; after the next epoch they differ from the previous value.
  - `malformed_inputs_never_panic`: 2000 inputs (random bytes of length 0–600, plus every prefix of a real ciphertext) → each `process` returns `Err`, with no panic. Use `std::panic::catch_unwind` and assert no unwind.
- [ ] **Step 2:** `cargo test --test scenarios` → FAIL (`Engine` is missing).
- [ ] **Step 3: Implement `engine.rs` and the exports in `lib.rs`.** Map `MlsError` to `FfiError` per the Design table. For the `Duplicate` mapping, confirm the exact variant mls-rs returns by running `duplicate_ciphertext_rejected`, and record it in the ledger as a `Ruling:` line.
- [ ] **Step 4:** `cargo test` → all M1 and M2 tests pass (7 + 10).
- [ ] **Step 5: Commit.** `git commit -am "feat(mls): mlsffi engine — groups, commits, pending-commit recovery, encrypt/process, exporter"`

### Task M3: XCFramework, Swift bindings, macOS CI

**Files:**
- Create: `apps/apple/Packages/SecureMessaging/scripts/build-mlsffi.sh`
- Create: `mlsffi/src/bin/uniffi-bindgen.rs`, containing `fn main() { uniffi::uniffi_bindgen_main() }`
- Create: `.github/workflows/mls.yml`
- Generated and committed: `Sources/MLSFFIBindings/mlsffi.swift`

**Interfaces — Produces:**
- `Artifacts/MLSFFI.xcframework`, with slices `ios-arm64`, `ios-arm64-simulator` and `macos-arm64_x86_64`. Each slice holds `libmlsffi.a` plus `Headers/mlsffiFFI.h` and `Headers/module.modulemap`, which declares module `mlsffiFFI`.
- The Swift file declaring `Engine`, `SignatureKeypair`, `CommitBundle`, `Encrypted`, `Processed`, `FfiError` and `generateSignatureKeypair()`.

`build-mlsffi.sh`:
1. Run `rustup target add aarch64-apple-ios aarch64-apple-ios-sim aarch64-apple-darwin x86_64-apple-darwin`.
2. Run `cargo build --release --lib --target <each>`, with `IPHONEOS_DEPLOYMENT_TARGET=17.0` and `MACOSX_DEPLOYMENT_TARGET=14.0`.
3. `lipo` the two macOS `.a` files into one.
4. Run `cargo run --bin uniffi-bindgen -- generate --library target/aarch64-apple-darwin/release/libmlsffi.dylib --language swift --out-dir build/bindings`.
5. Copy `mlsffi.swift` to `Sources/MLSFFIBindings/`.
6. Rename the generated modulemap to `module.modulemap`.
7. Run `xcodebuild -create-xcframework` with three `-library … -headers build/bindings` pairs.
8. Zip the result and print its SHA-256 (for a later release asset).

`mls.yml` has these jobs, all triggered by paths under `apps/apple/Packages/SecureMessaging/**` or the workflow itself:

| Job | Runs on | Steps |
|---|---|---|
| `rust-linux` | `ubuntu-latest` | `cargo test` in `mlsffi` |
| `apple` | `macos-26` | `build-mlsffi.sh`, `cargo test` (RustCrypto provider), a minimum-OS check (`vtool`: iOS 17.0, macOS 14.0), the binding-drift check, and upload of the XCFramework as artifact `MLSFFI-xcframework` |
| `swift-linux` | `swift:6.0` container | added in M4 |

The upstream provider suite is `git clone https://github.com/awslabs/mls-rs && git -C mls-rs checkout 323bd84f… && cargo test --manifest-path mls-rs/mls-rs-crypto-cryptokit/Cargo.toml`. It runs `mls-rs-core`'s crypto-provider test suite against CryptoKit on that runner, which covers the RFC 9420 crypto test vectors through `mls-rs-core`'s `test_suite` feature. The binding-drift check is `git diff --exit-code Sources/MLSFFIBindings`.

- [ ] **Step 1: (superseded 2026-10-28) no interop test.** With one provider on all targets there is no cross-provider test; the scenario suite covers the engine.
- [ ] **Step 2: Push, then check with `gh run watch`.** Expected: the `apple` job FAILS, because `build-mlsffi.sh` and the interop constructor don't exist yet. The workflow file is committed in this step so CI runs.
- [ ] **Step 3: Implement.**
  - Write `build-mlsffi.sh`.
  - Add `engine::with_provider<P: CryptoProvider>`.
  - Write `mls.yml`.
  - Add to the script an assertion that the XCFramework has exactly 3 slices.
- [ ] **Step 4: Push and check.** Expected: `rust-linux` and `apple` are green; the upload holds `MLSFFI.xcframework` with exactly the 3 slices (`plutil -p Info.plist` in the script prints `AvailableLibraries` identifiers); the drift check passes.
- [ ] **Step 5: Commit.** `git commit -m "build(mls): MLSFFI XCFramework, Swift bindings, CI"`

**Note:** the former duplicate-symbol risk (CryptoKit bridge C symbols vs WebRTC) is gone with the RustCrypto switch (owner decision 2026-10-28).

---

## Lane S — Swift

### Task M4: Package, contract types, fake engine, lock, push payload (Linux-runnable)

**Files:**
- Create: `Package.swift`
- Create: `Sources/SecureMessaging/MLSEngine.swift`, `FakeMLSEngine.swift`, `CrossProcessLock.swift`, `PushPayload.swift`
- Test: `Tests/SecureMessagingTests/FakeMLSEngineTests.swift`, `CrossProcessLockTests.swift`, `PushPayloadTests.swift`
- Modify: `.github/workflows/mls.yml`, adding the `swift-linux` job: `swift test` in the package

**Interfaces — Produces:**
- The roadmap's `GroupID`, `CommitBundle`, `Incoming`, `MLSEngine` and `FakeMLSEngine`, exactly as listed there.
- `public enum MLSError: Error, Equatable, Sendable { case malformed, unknownGroup, notMember, duplicate, pendingCommit, busy, storage(String), crypto(String), internalError(String) }`
- `public final class FakeMLSEngine: MLSEngine, @unchecked Sendable`, with `public init(identity: String)` and state behind an `NSLock`. It is plaintext pass-through and **not secure** (say so in its doc comment). Its messages are JSON with `"fake"` set to `"kp"`, `"commit"`, `"welcome"` or `"app"`, plus `group` (hex), `epoch`, `members` (identities), `sender` and `body` (base64). Its rules:
  - It applies the same epoch and pending-commit rules as the real engine.
  - `process` of an `app` message from an epoch above its own, or for a group it doesn't know, throws.
  - Seeing the same `app` message twice throws `.duplicate`.
  - `exportSecret` is deterministic per (group, epoch, label): the UTF-8 of `"\(hex):\(epoch):\(label)"`, repeated and cut to `length`.
- `public struct CrossProcessLock: Sendable`, with:
  - `init(url: URL, timeout: Duration = .seconds(5), retry: Duration = .milliseconds(10))`
  - `func withLock<T>(_ body: () throws -> T) throws -> T`, which takes `open(O_CREAT|O_RDWR)` + `flock(LOCK_EX|LOCK_NB)` per call and closes the descriptor in `defer`
- `public enum PushPayload { public static func unpad(_ data: Data) throws -> Data? }`, which returns `nil` for length 0
- `Package.swift`:
  - platforms `.iOS(.v17)` and `.macOS(.v14)`
  - targets `SecureMessaging` and `SecureMessagingTests`
  - under `#if os(macOS) || os(iOS)` in the manifest (`#if !os(Linux)` is acceptable): the binary target `MLSFFI` (`path: "Artifacts/MLSFFI.xcframework"`), the target `MLSFFIBindings` (`swiftSettings: [.swiftLanguageMode(.v5)]`, depends on `MLSFFI`), the dependency of `SecureMessaging` on `MLSFFIBindings`, and the executable target `mls-nse-sim` (M6)
  - real-engine sources guarded with `#if canImport(MLSFFIBindings)`

- [ ] **Step 1: Write the failing tests.**
  - `FakeMLSEngineTests`:
    - `testTwoDevicesExchange`: the same flow as M2's `two_devices_exchange_messages`, through the `MLSEngine` protocol.
    - `testPendingCommitRules`: `pendingCommit` on a second add; after `commitRejected`, the epoch is unchanged.
    - `testRemoveMarksRemovedMe`.
    - `testDuplicateThrows`.
    - `testExportSecretAgreesAcrossMembers`.
  - `CrossProcessLockTests`:
    - `testSecondHolderWaitsThenFails`: thread 1 holds the lock for 300 ms; a lock with a 100 ms timeout throws `MLSError.busy`; one with a 1 s timeout succeeds after thread 1 releases.
    - `testLockReleasedAfterThrow`: a body that throws → an immediate `withLock` succeeds.
    - `testLockFileCreated`: the file exists after the first use.
  - `PushPayloadTests`:
    - a 256-byte buffer with length `0x0000` → `nil`
    - length 5, `"hello"` and 249 zero bytes → `"hello"`
    - length 300 in a 256-byte buffer → throws `.malformed`
    - a non-zero padding byte → `.malformed`
    - fewer than 2 bytes → `.malformed`
    - the buffer sizes allowed are exactly 256, 512, 1024, 2048 and 3072; 300 bytes → `.malformed`
- [ ] **Step 2:** `cd apps/apple/Packages/SecureMessaging && swift test` → FAIL (types are missing).
- [ ] **Step 3: Implement.** Use `Glibc` or `Darwin` `flock` via `#if canImport(Glibc)`.
- [ ] **Step 4:** `swift test` → all pass on Linux.
- [ ] **Step 5: Commit.** `git commit -m "feat(mls): SecureMessaging contract, fake engine, cross-process lock, push unpadding"`

### Task M5: Live engine, signing key, storage protection (after M3)

**Files:**
- Create: `Sources/SecureMessaging/SigningKeyStore.swift`, `Sources/SecureMessaging/LiveMLSEngine.swift`
- Test: `Tests/SecureMessagingTests/LiveMLSEngineTests.swift` (`#if canImport(MLSFFIBindings)`)
- Modify: `.github/workflows/mls.yml`, adding to the `apple` job `swift test` on macOS and `xcodebuild test -scheme SecureMessaging -destination 'platform=iOS Simulator,name=iPhone 17'`. Use whatever simulator `macos-26` lists; record it in the ledger.

**Interfaces — Produces:**
- `public protocol SigningKeyStore: Sendable { func load(identity: String) throws -> (publicKey: Data, secretKey: Data)?; func save(identity: String, publicKey: Data, secretKey: Data) throws }`
- `public struct KeychainSigningKeyStore: SigningKeyStore`, with `init(accessGroup: String?)`. It stores a generic password with service `org.example.app.mls.signing`, account = identity, data = a 2-byte length + public + secret, and `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`.
- `public final class InMemorySigningKeyStore: SigningKeyStore` (for tests).
- `public actor LiveMLSEngine: MLSEngine`, with `public init(identity: String, storage: URL, keys: SigningKeyStore, lockTimeout: Duration = .seconds(5)) throws`. It:
  - creates `storage` with `.protectionKey: .completeUntilFirstUserAuthentication` (iOS only)
  - loads the key or generates it with `generateSignatureKeypair()` and saves it
  - builds the FFI `Engine` once
  - wraps every method in `CrossProcessLock(url: storage/mls.lock).withLock`
  - maps `FfiError` to `MLSError`
- `public func makeMLSEngine(identity: String, storage: URL) throws -> MLSEngine`, which uses `KeychainSigningKeyStore(accessGroup: Bundle.main.object(forInfoDictionaryKey: "MLSKeychainAccessGroup") as? String)`.

- [ ] **Step 1: Write the failing tests** (`InMemorySigningKeyStore`, temp directories).
  - `testTwoDevices`, `testThreeDevicesAddRemove`, `testCommitRaceBothOrders`: the M2 scenarios, through the Swift `MLSEngine` API. Asserted `Incoming` values match exactly, e.g. `.application(group: g, sender: "acct-a/dev-1", plaintext: Data("hi".utf8), epoch: 1)`.
  - `testSameIdentityKeepsKey`: two engines with the same identity and key store → the key store's `save` is called once.
  - `testLockFreeAfterEveryOperation`: after each of the 10 methods returns, or throws, a `CrossProcessLock` with a 0 ms timeout on the same file succeeds.
  - `testStorageProtectionClass`: iOS simulator only. `FileManager.attributesOfItem(atPath: storage).protectionKey == .completeUntilFirstUserAuthentication`. The simulator records the attribute but does not enforce it; enforcement is checked on a device in M6.
  - `testFakeAndLiveAgreeOnContract`: one table-driven script of operations runs against both `FakeMLSEngine` and `LiveMLSEngine`; the sequences of `Incoming` and error cases are equal.
- [ ] **Step 2: Push, then check with `gh run watch`.** Expected: FAIL (`LiveMLSEngine` is missing).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Push and check.** Expected: the `apple` job is green on both macOS `swift test` and iOS Simulator `xcodebuild test`.
- [ ] **Step 5: Commit.** `git commit -m "feat(mls): live MLS engine with keychain signing key and locked app-group storage"`

### Task M6: NSE prototype, cross-process test, memory budget

**Files:**
- Create: `Sources/mls-nse-sim/main.swift`. A helper executable: `mls-nse-sim <storage dir> <identity> <hex padded payload>`. It unpads, runs `LiveMLSEngine.process` with `InMemorySigningKeyStore` (the key is passed in env `MLS_TEST_KEY` as hex), and prints `OK <plaintext utf8>`, or `ERR <MLSError>`, then `RSS <ru_maxrss bytes>`.
- Test: `Tests/SecureMessagingTests/NSEProcessTests.swift` (macOS only)
- Create: `apps/apple/Prototypes/NSEPrototype/project.yml`
  - targets `NSEProtoHost` (iOS app, `org.example.app.nseproto`) and `NSEProtoService` (NSE, `org.example.app.nseproto.nse`)
  - both with app group `group.org.example.app.nseproto` and the local package `SecureMessaging`
- Create: `apps/apple/Prototypes/NSEPrototype/Host/HostApp.swift`, `Service/NotificationService.swift`
- Create: `apps/apple/Prototypes/NSEPrototype/run-sim-push.sh`
- Create: `apps/apple/Prototypes/NSEPrototype/DEVICE-CHECKLIST.md`

**Interfaces — Consumes:** `LiveMLSEngine`, `PushPayload.unpad` and `CrossProcessLock` from M4 and M5. The server padding format comes from server spec §7.4. The APNs alert shape is `{"aps":{"alert":{"title":"[App]","body":"New message"},"mutable-content":1},"d":"<b64>"}`, from the same section.

- [ ] **Step 1: Write the failing tests** (`NSEProcessTests`, macOS). Locate the helper binary with `Bundle(for:)` → `../mls-nse-sim`, built as a test dependency.
  - `testAppAndNSEProcessesShareState`:
    1. The test process, as "the app", sets up alice and bob in temp storage; bob's storage plays the shared container.
    2. Alice encrypts 3 messages.
    3. The test decrypts message 1 in-process, launches the helper for message 2 and decrypts message 3 in-process again, with every bob engine call under the lock.
    4. All three plaintexts are correct, and the in-process engine still decrypts message 3 after the helper advanced the state.
  - `testConcurrentAppAndNSEDoNotCorrupt`: alice sends 20 messages; bob's in-process loop and 4 helper launches race over disjoint halves → every message is either `OK` or `.duplicate`, never `crypto`/`internalError`, and bob's group still decrypts a new message.
  - `testHelperMemoryBudget`: the helper's `RSS` line is below **20 MB** (20 971 520 bytes). The NSE limit is about 24 MB (GOAL.md §10); this leaves headroom for the extension's own baseline.
  - `testPaddedPushEndToEnd`: the ciphertext is padded per §7.4 in the test (a 12-line `pad` helper mirroring the server); the helper prints `OK hello from MLS`.
- [ ] **Step 2: Push and check.** Expected: FAIL (no helper target).
- [ ] **Step 3: Implement the helper and the prototype.**
  - **Host app**, launched with `-prepare`: creates a sender engine in its temp directory and a receiver engine in the app group container, under the identity `proto/nse`. It sets up a group, encrypts `"hello from MLS"`, pads it, and writes `push.apns` (the alert shape above, plus `"Simulator Target Bundle":"org.example.app.nseproto"`) to the container.
  - **`NotificationService`**:
    1. reads `d`
    2. unpads it; `nil` means body `"New message"`
    3. runs `makeMLSEngine(identity:"proto/nse", storage:<container>/mls)` → `process` → sets `bestAttemptContent.body` to the plaintext
    4. writes `nse-result.txt` (`OK <text> mem=<os_proc_available_memory()>`) into the container
    5. on any error, falls back to `"New message"` and writes `ERR <error>`
  - **`run-sim-push.sh`**:
    1. `xcodegen`, then build for the simulator
    2. `xcrun simctl boot`, `install`, `launch … -prepare`, then sleep 3 s
    3. `xcrun simctl push booted org.example.app.nseproto <container>/push.apns`, then sleep 5 s
    4. read `nse-result.txt` from `xcrun simctl get_app_container booted org.example.app.nseproto group.org.example.app.nseproto` and check it starts with `OK hello from MLS`
- [ ] **Step 4: Push and check** (add a `nse-prototype` step to the `apple` job running `run-sim-push.sh`). Expected: the `NSEProcessTests` all pass, and `run-sim-push.sh` exits 0. If the simulator does not run the NSE for `simctl push`, record `Ruling: NSE simulator run not supported on macos-26 — prototype verified by NSEProcessTests + device checklist` and make that step non-blocking. Never fake a pass.
- [ ] **Step 5: Write `DEVICE-CHECKLIST.md`** for the owner. Each item is one line with a pass/fail box:
  - install on a physical iPhone; lock it; send `push.apns` with Xcode → Devices → *Send push*, or `xcrun simctl push` replaced by `curl` to APNs sandbox with the owner's key
  - the notification shows `hello from MLS` while the phone is **locked after first unlock**
  - `nse-result.txt` reports `mem=` above 0
  - repeat after a reboot without unlocking: a fallback `"New message"` is expected
  - Instruments → Allocations on the NSE: peak below 24 MB
- [ ] **Step 6: Commit.** `git commit -m "feat(mls): NSE prototype, cross-process state test, NSE memory budget"`

---

## Done when

- `cargo test` passes on Linux.
- `swift test` passes on Linux.
- The `apple` job is green: the XCFramework is built, the minimum-OS check passes, the macOS and iOS-simulator package tests pass, and the NSE prototype step has run or is ruled.
- The owner has reviewed every file and ticked `DEVICE-CHECKLIST.md`.
- The ledger lists every `Ruling:`.
- After this, the "Exit criteria" item "a working NSE + MLS push prototype" in GOAL.md Phase 0 is met for the client side. The real push path needs plan 2's lane D gateway.
