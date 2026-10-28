//! The UniFFI engine: a thin, synchronous layer over mls-rs.
//!
//! No protocol or crypto logic lives here. Every call loads the group from
//! [`FileStorage`], acts, and (when it changed anything) writes it back, so the
//! notification extension may change the same files from another process
//! between calls. Nothing about a group is cached in memory.
//!
//! Storage errors: a mutating call that returns `FfiError::Storage` leaves the
//! state unknown (the durable write may have happened and a later step, such as
//! pruning old epochs, may have failed). Callers must treat the result as
//! "reload and look": the next call re-reads everything from disk.

use std::fmt;
use std::sync::{Arc, Mutex, MutexGuard};

use mls_rs::client_builder::{
    BaseConfig, ClientBuilder, IntoConfigOutput, WithCryptoProvider, WithGroupStateStorage,
    WithIdentityProvider, WithKeyPackageRepo, WithMlsRules,
};
use mls_rs::crypto::{SignaturePublicKey, SignatureSecretKey};
use mls_rs::error::MlsError;
use mls_rs::group::{CommitEffect, CommitOutput, ReceivedMessage};
use mls_rs::identity::basic::{BasicCredential, BasicIdentityProvider};
use mls_rs::identity::SigningIdentity;
use mls_rs::mls_rules::{CommitOptions, DefaultMlsRules};
use mls_rs::{
    CipherSuite, CipherSuiteProvider, Client, CryptoProvider, ExtensionList, Group, MlsMessage,
    WireFormat,
};
use mls_rs_core::group::GroupStateStorage;
use zeroize::Zeroizing;

use crate::error::FfiError;
use crate::storage::FileStorage;

type Provider = mls_rs_crypto_rustcrypto::RustCryptoProvider;

const CIPHER_SUITE: CipherSuite = CipherSuite::CURVE25519_AES128;
const GROUP_ID_LEN: usize = 32;

type Config<P> = WithMlsRules<
    DefaultMlsRules,
    WithCryptoProvider<
        P,
        WithIdentityProvider<
            BasicIdentityProvider,
            WithKeyPackageRepo<FileStorage, WithGroupStateStorage<FileStorage, BaseConfig>>,
        >,
    >,
>;
type EngineClient<P> = Client<IntoConfigOutput<Config<P>>>;
type EngineGroup<P> = Group<IntoConfigOutput<Config<P>>>;

fn provider() -> Provider {
    Provider::default()
}

// ---------------------------------------------------------------------------
// Records and enums. `Debug` is written by hand so that no key, ciphertext or
// plaintext can reach a log by accident: only lengths and public numbers show.

#[derive(uniffi::Record)]
pub struct SignatureKeypair {
    pub public_key: Vec<u8>,
    pub secret_key: Vec<u8>,
}

impl fmt::Debug for SignatureKeypair {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("SignatureKeypair").finish_non_exhaustive()
    }
}

#[derive(uniffi::Record)]
pub struct CommitBundle {
    pub commit: Vec<u8>,
    pub welcome: Option<Vec<u8>>,
    pub new_epoch: u64,
}

impl fmt::Debug for CommitBundle {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("CommitBundle")
            .field("commit_len", &self.commit.len())
            .field("has_welcome", &self.welcome.is_some())
            .field("new_epoch", &self.new_epoch)
            .finish()
    }
}

#[derive(uniffi::Record)]
pub struct Encrypted {
    pub ciphertext: Vec<u8>,
    pub epoch: u64,
}

impl fmt::Debug for Encrypted {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Encrypted")
            .field("ciphertext_len", &self.ciphertext.len())
            .field("epoch", &self.epoch)
            .finish()
    }
}

/// An application message decrypted by `decrypt_application`.
#[derive(uniffi::Record)]
pub struct ApplicationMessage {
    pub sender: String,
    pub plaintext: Vec<u8>,
    /// The epoch it was sent in.
    pub epoch: u64,
}

impl fmt::Debug for ApplicationMessage {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ApplicationMessage")
            .field("sender", &self.sender)
            .field("plaintext_len", &self.plaintext.len())
            .field("epoch", &self.epoch)
            .finish()
    }
}

/// A group member's identity and MLS signature public key (the leaf's).
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct MemberKey {
    pub identity: String,
    pub signature_key: Vec<u8>,
}

#[derive(uniffi::Enum, PartialEq)]
pub enum Processed {
    Application {
        group_id: Vec<u8>,
        sender: String,
        plaintext: Vec<u8>,
        epoch: u64,
    },
    Commit {
        group_id: Vec<u8>,
        new_epoch: u64,
        removed_me: bool,
    },
    Joined {
        group_id: Vec<u8>,
        epoch: u64,
    },
}

impl Processed {
    fn group_id(&self) -> &[u8] {
        match self {
            Processed::Application { group_id, .. }
            | Processed::Commit { group_id, .. }
            | Processed::Joined { group_id, .. } => group_id,
        }
    }
}

/// The result of [`EngineCore::stage_process`]: what `process` would return,
/// plus what is needed to apply it later. Holds no key material and nothing is
/// persisted for it. `confirm` re-runs the processing against the stored state
/// and applies it only if the state is the one this was staged against.
#[derive(uniffi::Object)]
pub struct StagedProcess {
    message: Vec<u8>,
    /// The suite's hash of the group's stored state at staging time (`None`
    /// when there was none). This is a conservative extra check, not what makes
    /// confirm safe: safety comes from `confirm` re-processing the message from
    /// fresh storage under the lock, so it can never write stale ratchet state.
    /// The hash only turns "something changed since staging" into `Stale`
    /// (it covers the state file only, so an A to B to A change passes).
    base: Option<Vec<u8>>,
    processed: Processed,
    /// Set by `discard` and by a successful `confirm`.
    closed: Mutex<bool>,
}

#[uniffi::export]
impl StagedProcess {
    /// Drops this staged message (same as `Engine::discard`). Nothing was persisted.
    pub fn discard(&self) {
        self.close();
    }

    /// What `process` would return for this message.
    pub fn incoming(&self) -> Processed {
        match &self.processed {
            Processed::Application {
                group_id,
                sender,
                plaintext,
                epoch,
            } => Processed::Application {
                group_id: group_id.clone(),
                sender: sender.clone(),
                plaintext: plaintext.clone(),
                epoch: *epoch,
            },
            Processed::Commit {
                group_id,
                new_epoch,
                removed_me,
            } => Processed::Commit {
                group_id: group_id.clone(),
                new_epoch: *new_epoch,
                removed_me: *removed_me,
            },
            Processed::Joined { group_id, epoch } => Processed::Joined {
                group_id: group_id.clone(),
                epoch: *epoch,
            },
        }
    }
}

impl StagedProcess {
    fn close(&self) {
        *self.closed.lock().unwrap_or_else(|p| p.into_inner()) = true;
    }
    fn is_closed(&self) -> bool {
        *self.closed.lock().unwrap_or_else(|p| p.into_inner())
    }
}

impl fmt::Debug for StagedProcess {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("StagedProcess")
            .field("incoming", &self.processed)
            .finish_non_exhaustive()
    }
}

/// A message processed in memory only: nothing of it is in storage yet.
struct Prepared<P: CryptoProvider + Clone> {
    group: EngineGroup<P>,
    processed: Processed,
    base: Option<Vec<u8>>,
    is_welcome: bool,
}

impl fmt::Debug for Processed {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Processed::Application {
                sender,
                plaintext,
                epoch,
                ..
            } => f
                .debug_struct("Application")
                .field("sender", sender)
                .field("plaintext_len", &plaintext.len())
                .field("epoch", epoch)
                .finish(),
            Processed::Commit {
                new_epoch,
                removed_me,
                ..
            } => f
                .debug_struct("Commit")
                .field("new_epoch", new_epoch)
                .field("removed_me", removed_me)
                .finish(),
            Processed::Joined { epoch, .. } => {
                f.debug_struct("Joined").field("epoch", epoch).finish()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Error mapping (plan Design table).

/// Maps an error that came out of our own `FileStorage` (wrapped by mls-rs in
/// an `AnyError`) back to a `FfiError`.
fn storage_any(e: &mls_rs::error::AnyError) -> FfiError {
    match e.inner_dyn_error().downcast_ref::<FfiError>() {
        Some(FfiError::Storage(m)) => FfiError::Storage(m.clone()),
        _ => FfiError::Storage(e.to_string()),
    }
}

fn map_mls(e: MlsError) -> FfiError {
    match e {
        MlsError::SerializationError(_) => FfiError::Malformed,
        MlsError::GroupNotFound => FfiError::UnknownGroup,
        MlsError::MemberNotFound | MlsError::WelcomeKeyPackageNotFound => FfiError::NotMember,
        MlsError::CantProcessMessageFromSelf | MlsError::KeyMissing(_) => FfiError::Duplicate,
        // Epoch ordering is decided in `process` before this point; an
        // InvalidEpoch that gets here comes from the epoch repository, i.e. storage.
        MlsError::InvalidEpoch => {
            FfiError::Storage("epoch records out of step with group state".into())
        }
        MlsError::ExistingPendingCommit => FfiError::PendingCommit,
        MlsError::GroupStorageError(ref a) | MlsError::KeyPackageRepoError(ref a) => storage_any(a),
        MlsError::CryptoProviderError(ref a) => FfiError::Crypto(a.to_string()),
        MlsError::InvalidSignature
        | MlsError::InvalidConfirmationTag
        | MlsError::InvalidMembershipTag => FfiError::Crypto(e.to_string()),
        other => FfiError::Internal(other.to_string()),
    }
}

impl From<MlsError> for FfiError {
    fn from(e: MlsError) -> Self {
        map_mls(e)
    }
}

// ---------------------------------------------------------------------------

#[uniffi::export]
pub fn generate_signature_keypair() -> Result<SignatureKeypair, FfiError> {
    let suite = provider()
        .cipher_suite_provider(CIPHER_SUITE)
        .ok_or_else(|| FfiError::Crypto("cipher suite unavailable".into()))?;
    let (secret, public) = suite
        .signature_key_generate()
        .map_err(|e| FfiError::Crypto(format!("{e:?}")))?;
    Ok(SignatureKeypair {
        public_key: public.to_vec(),
        secret_key: secret.as_bytes().to_vec(),
    })
}

/// The engine, generic over the crypto provider. `Engine` (the UniFFI object)
/// wraps the RustCrypto provider (all targets); the generic form is kept so tests
/// can inject another provider.
#[doc(hidden)]
pub struct EngineCore<P: CryptoProvider + Clone> {
    client: EngineClient<P>,
    storage: FileStorage,
    crypto: P,
    /// Serializes calls within this process (load, act, write must not
    /// interleave). Cross-process exclusion is the Swift side's `flock`.
    gate: Mutex<()>,
}

impl<P: CryptoProvider + Clone> EngineCore<P> {
    fn lock(&self) -> MutexGuard<'_, ()> {
        // A panic in an earlier call cannot leave in-memory state behind (there
        // is none), so a poisoned gate is safe to reuse.
        self.gate.lock().unwrap_or_else(|p| p.into_inner())
    }

    /// Loads the group fresh from storage.
    /// Runs the repair step first (see [`Engine::repair_epochs`]).
    fn load(&self, group_id: &[u8]) -> Result<EngineGroup<P>, FfiError> {
        let group = self.client.load_group(group_id).map_err(map_mls)?;
        self.repair_epochs(group_id, group.current_epoch())?;
        Ok(group)
    }

    /// mls-rs inserts the epoch being left with `id == max stored id + 1`, and
    /// refuses anything else. Stored epoch records must therefore be exactly
    /// the newest ones below the current epoch. They can drift from the group
    /// state after a crash or failed write between the epoch records and the
    /// state, or when a Welcome replaces the state of a group we were removed
    /// from. Repair, on every load and after a join:
    ///   1. delete records with id >= current epoch (never valid history);
    ///   2. if the newest remaining id is not current - 1, delete them all
    ///      (a gap cannot be used, and the old keys are for a stale history).
    fn repair_epochs(&self, group_id: &[u8], current: u64) -> Result<(), FfiError> {
        let mut ids = self.storage.stored_epochs(group_id)?;
        while ids.last().is_some_and(|&id| id >= current) {
            self.storage.delete_epoch(group_id, ids.pop().unwrap())?;
        }
        if ids.last().is_some_and(|&id| id + 1 != current) {
            for id in ids {
                self.storage.delete_epoch(group_id, id)?;
            }
        }
        Ok(())
    }

    fn bundle(group: &EngineGroup<P>, output: CommitOutput) -> Result<CommitBundle, FfiError> {
        let commit = output.commit_message.to_bytes().map_err(map_mls)?;
        let welcome = output
            .welcome_messages
            .first()
            .map(|w| w.to_bytes().map_err(map_mls))
            .transpose()?;
        Ok(CommitBundle {
            commit,
            welcome,
            // The commit is pending: it takes effect on `commit_accepted`.
            new_epoch: group.current_epoch() + 1,
        })
    }
}

fn identity_string(identity: &SigningIdentity) -> Result<String, FfiError> {
    let basic = identity
        .credential
        .as_basic()
        .ok_or_else(|| FfiError::Malformed)?;
    String::from_utf8(basic.identifier.clone()).map_err(|_| FfiError::Malformed)
}

impl<P: CryptoProvider + Clone> EngineCore<P> {
    pub fn new(
        identity: String,
        keypair: SignatureKeypair,
        storage_dir: String,
        crypto: P,
    ) -> Result<Arc<Self>, FfiError> {
        let storage = FileStorage::new(storage_dir)?;
        let credential = BasicCredential::new(identity.into_bytes()).into_credential();
        let signing_identity =
            SigningIdentity::new(credential, SignaturePublicKey::new(keypair.public_key));
        let secret = SignatureSecretKey::new(keypair.secret_key);

        let commit_options = CommitOptions::default()
            .with_ratchet_tree_extension(true)
            .with_single_welcome_message(true);
        let client = ClientBuilder::new()
            .group_state_storage(storage.clone())
            .key_package_repo(storage.clone())
            .identity_provider(BasicIdentityProvider::new())
            .crypto_provider(crypto.clone())
            .mls_rules(DefaultMlsRules::new().with_commit_options(commit_options))
            .signing_identity(signing_identity, secret, CIPHER_SUITE)
            .build();
        Ok(Arc::new(Self {
            storage,
            client,
            crypto,
            gate: Mutex::new(()),
        }))
    }

    pub fn key_packages(&self, count: u32) -> Result<Vec<Vec<u8>>, FfiError> {
        let _g = self.lock();
        (0..count)
            .map(|_| {
                self.client
                    .generate_key_package_message(ExtensionList::new(), ExtensionList::new(), None)
                    .and_then(|m| m.to_bytes())
                    .map_err(map_mls)
            })
            .collect()
    }

    pub fn create_group(&self) -> Result<Vec<u8>, FfiError> {
        let _g = self.lock();
        let suite = self
            .crypto
            .cipher_suite_provider(CIPHER_SUITE)
            .ok_or_else(|| FfiError::Crypto("cipher suite unavailable".into()))?;
        let group_id = suite
            .random_bytes_vec(GROUP_ID_LEN)
            .map_err(|e| FfiError::Crypto(format!("{e:?}")))?;
        let mut group = self
            .client
            .create_group_with_id(
                group_id.clone(),
                ExtensionList::new(),
                ExtensionList::new(),
                None,
            )
            .map_err(map_mls)?;
        group.write_to_storage().map_err(map_mls)?;
        Ok(group_id)
    }

    pub fn add(
        &self,
        group_id: Vec<u8>,
        key_packages: Vec<Vec<u8>>,
    ) -> Result<CommitBundle, FfiError> {
        let _g = self.lock();
        let mut group = self.load(&group_id)?;
        let mut builder = group.commit_builder();
        for kp in &key_packages {
            let msg = MlsMessage::from_bytes(kp).map_err(|_| FfiError::Malformed)?;
            builder = builder.add_member(msg).map_err(map_mls)?;
        }
        let output = builder.build().map_err(map_mls)?;
        group.write_to_storage().map_err(map_mls)?;
        Self::bundle(&group, output)
    }

    pub fn remove(
        &self,
        group_id: Vec<u8>,
        identities: Vec<String>,
    ) -> Result<CommitBundle, FfiError> {
        let _g = self.lock();
        let mut group = self.load(&group_id)?;
        // Same order as `add` and the fake engine: a pending commit wins over member lookup.
        if group.has_pending_commit() {
            return Err(FfiError::PendingCommit);
        }
        let mut indexes = Vec::with_capacity(identities.len());
        for identity in &identities {
            indexes.push(
                group
                    .member_with_identity(identity.as_bytes())
                    .map_err(map_mls)?
                    .index,
            );
        }
        let mut builder = group.commit_builder();
        for index in indexes {
            builder = builder.remove_member(index).map_err(map_mls)?;
        }
        let output = builder.build().map_err(map_mls)?;
        group.write_to_storage().map_err(map_mls)?;
        Self::bundle(&group, output)
    }

    pub fn commit_accepted(&self, group_id: Vec<u8>) -> Result<(), FfiError> {
        let _g = self.lock();
        let mut group = self.load(&group_id)?;
        // Nothing pending: our own commit's echo was already processed (or it
        // was discarded). Idempotent, like `commit_rejected`.
        if !group.has_pending_commit() {
            return Ok(());
        }
        group.apply_pending_commit().map_err(map_mls)?;
        group.write_to_storage().map_err(map_mls)
    }

    pub fn commit_rejected(&self, group_id: Vec<u8>) -> Result<(), FfiError> {
        let _g = self.lock();
        let mut group = self.load(&group_id)?;
        if !group.has_pending_commit() {
            return Ok(());
        }
        group.clear_pending_commit();
        group.write_to_storage().map_err(map_mls)
    }

    pub fn has_pending_commit(&self, group_id: Vec<u8>) -> Result<bool, FfiError> {
        let _g = self.lock();
        Ok(self.load(&group_id)?.has_pending_commit())
    }

    pub fn encrypt(&self, group_id: Vec<u8>, plaintext: Vec<u8>) -> Result<Encrypted, FfiError> {
        let _g = self.lock();
        let plaintext = Zeroizing::new(plaintext);
        let mut group = self.load(&group_id)?;
        let message = group
            .encrypt_application_message(&plaintext, Vec::new())
            .map_err(map_mls)?;
        // Encrypting advances the sender ratchet: persist it.
        group.write_to_storage().map_err(map_mls)?;
        Ok(Encrypted {
            ciphertext: message.to_bytes().map_err(map_mls)?,
            epoch: group.current_epoch(),
        })
    }

    /// Hash of the group's stored state, or `None` when there is none. Used to
    /// detect that the state changed between `stage_process` and `confirm`.
    fn state_base(&self, group_id: &[u8]) -> Result<Option<Vec<u8>>, FfiError> {
        let Some(bytes) = self.storage.state(group_id)? else {
            return Ok(None);
        };
        let suite = self
            .crypto
            .cipher_suite_provider(CIPHER_SUITE)
            .ok_or_else(|| FfiError::Crypto("cipher suite unavailable".into()))?;
        suite
            .hash(&bytes)
            .map(Some)
            .map_err(|e| FfiError::Crypto(format!("{e:?}")))
    }

    /// Processes `message` in memory: reads storage, writes nothing that
    /// matters (the epoch repair in `load` only deletes records that were never
    /// valid history). The caller decides whether to [`Self::finish`].
    fn prepare(&self, message: &[u8]) -> Result<Prepared<P>, FfiError> {
        // Callers other than `decrypt_application` treat any content we do not
        // handle (standalone proposals and the like) as malformed.
        self.prepare_raw(message).map_err(|e| match e {
            FfiError::NotApplication => FfiError::Malformed,
            other => other,
        })
    }

    /// Like `prepare`, but content that is neither application data nor a
    /// commit (a proposal, ...) is reported as `NotApplication`.
    fn prepare_raw(&self, message: &[u8]) -> Result<Prepared<P>, FfiError> {
        let message = MlsMessage::from_bytes(message).map_err(|_| FfiError::Malformed)?;
        match message.wire_format() {
            WireFormat::Welcome => {
                let (group, _info) = self
                    .client
                    .join_group(None, &message, None)
                    .map_err(map_mls)?;
                let group_id = group.group_id().to_vec();
                // A Welcome replaces any earlier state for this group; the base
                // is that earlier state, so a change to it makes a staged join stale.
                let base = self.state_base(&group_id)?;
                let processed = Processed::Joined {
                    group_id,
                    epoch: group.current_epoch(),
                };
                Ok(Prepared {
                    group,
                    processed,
                    base,
                    is_welcome: true,
                })
            }
            WireFormat::PrivateMessage | WireFormat::PublicMessage => {
                let group_id = message.group_id().ok_or(FfiError::Malformed)?.to_vec();
                let mut group = self.load(&group_id)?;
                let base = self.state_base(&group_id)?;
                let message_epoch = message.epoch().ok_or(FfiError::Malformed)?;
                if message_epoch > group.current_epoch() {
                    // A commit was missed; the caller must resync.
                    return Err(FfiError::EpochGap);
                }
                // A pending commit is deliberately not cleared here. mls-rs
                // applies it when the message is our own commit echoed back,
                // and drops it when it processes someone else's commit
                // (the race was lost). Clearing first would break the echo.
                let received = match group.process_incoming_message(message) {
                    Ok(received) => received,
                    // An older message that cannot be handled is one we have
                    // already moved past (e.g. a commit already applied).
                    Err(MlsError::InvalidEpoch | MlsError::EpochNotFound)
                        if message_epoch < group.current_epoch() =>
                    {
                        return Err(FfiError::Duplicate)
                    }
                    Err(e) => return Err(map_mls(e)),
                };
                let processed = match received {
                    ReceivedMessage::ApplicationMessage(app) => {
                        let sender = group
                            .member_at_index(app.sender_index)
                            .ok_or(FfiError::Malformed)?;
                        Processed::Application {
                            group_id: group_id.clone(),
                            sender: identity_string(&sender.signing_identity)?,
                            plaintext: app.data().to_vec(),
                            // The epoch it was sent in, not ours.
                            epoch: message_epoch,
                        }
                    }
                    ReceivedMessage::Commit(description) => {
                        let removed_me = matches!(description.effect, CommitEffect::Removed { .. });
                        Processed::Commit {
                            group_id: group_id.clone(),
                            // A removed device's stored group stays at the old epoch;
                            // report the epoch the commit creates either way.
                            new_epoch: if removed_me {
                                message_epoch + 1
                            } else {
                                group.current_epoch()
                            },
                            removed_me,
                        }
                    }
                    // Standalone proposals, GroupInfo, KeyPackage: never sent by our clients.
                    _ => return Err(FfiError::NotApplication),
                };
                Ok(Prepared {
                    group,
                    processed,
                    base,
                    is_welcome: false,
                })
            }
            _ => Err(FfiError::Malformed),
        }
    }

    /// Refuses a message whose clear-text group id is not `group_id`.
    fn check_group(message: &[u8], group_id: &[u8]) -> Result<(), FfiError> {
        let parsed = MlsMessage::from_bytes(message).map_err(|_| FfiError::Malformed)?;
        match parsed.group_id() {
            Some(id) if id != group_id => Err(FfiError::Malformed),
            _ => Ok(()),
        }
    }

    /// Persists a prepared message: the one and only durable write of processing.
    fn finish(&self, mut prepared: Prepared<P>) -> Result<Processed, FfiError> {
        if prepared.is_welcome {
            // A Welcome replaces any earlier state for this group (for
            // example after we were removed and then re-added): old epoch
            // records belong to the old history. Delete them all first.
            let group_id = prepared.group.group_id().to_vec();
            for id in self.storage.stored_epochs(&group_id)? {
                self.storage.delete_epoch(&group_id, id)?;
            }
        }
        // For a Welcome this also deletes the KeyPackage it consumed.
        prepared.group.write_to_storage().map_err(map_mls)?;
        Ok(prepared.processed)
    }

    pub fn process(&self, message: Vec<u8>) -> Result<Processed, FfiError> {
        let _g = self.lock();
        let prepared = self.prepare(&message)?;
        self.finish(prepared)
    }

    /// Decrypts or validates `message` for `group_id` and persists NOTHING. The
    /// result carries what `process` would return; `confirm` applies it.
    pub fn stage_process(
        &self,
        group_id: Vec<u8>,
        message: Vec<u8>,
    ) -> Result<Arc<StagedProcess>, FfiError> {
        let _g = self.lock();
        // Cheap check first, before any decryption (a Welcome has no group id
        // in the clear; it is checked after joining below).
        Self::check_group(&message, &group_id)?;
        let prepared = self.prepare(&message)?;
        // The caller names the group the message is for; refuse anything else.
        if prepared.processed.group_id() != group_id.as_slice() {
            return Err(FfiError::Malformed);
        }
        Ok(Arc::new(StagedProcess {
            message,
            base: prepared.base,
            processed: prepared.processed,
            closed: Mutex::new(false),
        }))
    }

    /// Persists a staged message, if the group's stored state is exactly what it
    /// was when staged; otherwise `Stale` and nothing is written. The message is
    /// processed again from the stored state (deterministic for equal state and
    /// message) and the outcome must equal the staged one.
    pub fn confirm(&self, staged: &StagedProcess) -> Result<(), FfiError> {
        let _g = self.lock();
        if staged.is_closed() {
            return Err(FfiError::Stale);
        }
        let prepared = match self.prepare(&staged.message) {
            Ok(p) => p,
            // Storage and crypto failures are real errors (retryable). Anything
            // else means the state moved on (the same message succeeded when staged).
            Err(e @ (FfiError::Storage(_) | FfiError::Crypto(_))) => return Err(e),
            Err(_) => return Err(FfiError::Stale),
        };
        if prepared.base != staged.base || prepared.processed != staged.processed {
            return Err(FfiError::Stale);
        }
        self.finish(prepared)?;
        staged.close();
        Ok(())
    }

    /// Drops a staged message. Nothing was persisted, so there is nothing to undo.
    pub fn discard(&self, staged: &StagedProcess) {
        staged.close();
    }

    /// Decrypts an application message and nothing else. Welcomes, public
    /// messages (which cannot carry application data) and anything that turns
    /// out to be a commit or proposal are refused with `NotApplication` before
    /// any state is written; the decrypted application message is then persisted
    /// like `process` does (its ratchet key is consumed).
    pub fn decrypt_application(
        &self,
        group_id: Vec<u8>,
        message: Vec<u8>,
    ) -> Result<ApplicationMessage, FfiError> {
        let _g = self.lock();
        let parsed = MlsMessage::from_bytes(&message).map_err(|_| FfiError::Malformed)?;
        if parsed.wire_format() != WireFormat::PrivateMessage {
            return Err(FfiError::NotApplication);
        }
        Self::check_group(&message, &group_id)?;
        // Content type is inside the encryption: stage in memory, inspect, then persist or drop.
        let prepared = self.prepare_raw(&message)?;
        if prepared.processed.group_id() != group_id.as_slice() {
            return Err(FfiError::Malformed);
        }
        if !matches!(prepared.processed, Processed::Application { .. }) {
            return Err(FfiError::NotApplication); // `prepared` dropped: nothing was written
        }
        match self.finish(prepared)? {
            Processed::Application {
                sender,
                plaintext,
                epoch,
                ..
            } => Ok(ApplicationMessage {
                sender,
                plaintext,
                epoch,
            }),
            _ => Err(FfiError::NotApplication),
        }
    }

    pub fn epoch(&self, group_id: Vec<u8>) -> Result<u64, FfiError> {
        let _g = self.lock();
        Ok(self.load(&group_id)?.current_epoch())
    }

    pub fn export_secret(
        &self,
        group_id: Vec<u8>,
        label: String,
        length: u32,
    ) -> Result<Vec<u8>, FfiError> {
        let _g = self.lock();
        let group = self.load(&group_id)?;
        let secret = group
            .export_secret(label.as_bytes(), &[], length as usize)
            .map_err(map_mls)?;
        Ok(secret.as_bytes().to_vec())
    }

    /// Current members as "<accountID>/<deviceID>" (README contract: `members(of:)`).
    pub fn members(&self, group_id: Vec<u8>) -> Result<Vec<String>, FfiError> {
        let _g = self.lock();
        let group = self.load(&group_id)?;
        group
            .roster()
            .members_iter()
            .map(|m| identity_string(&m.signing_identity))
            .collect()
    }
}

impl<P: CryptoProvider + Clone> EngineCore<P> {
    /// Every member's identity and leaf signature public key, sorted by identity
    /// (UTF-8 byte order); includes this device.
    pub fn member_keys(&self, group_id: Vec<u8>) -> Result<Vec<MemberKey>, FfiError> {
        let _g = self.lock();
        let group = self.load(&group_id)?;
        let mut keys = group
            .roster()
            .members_iter()
            .map(|m| {
                Ok(MemberKey {
                    identity: identity_string(&m.signing_identity)?,
                    signature_key: m.signing_identity.signature_key.as_bytes().to_vec(),
                })
            })
            .collect::<Result<Vec<_>, FfiError>>()?;
        keys.sort_by(|a, b| a.identity.as_bytes().cmp(b.identity.as_bytes()));
        Ok(keys)
    }
}

/// Interop-test constructor: an engine on an explicit crypto provider.
#[doc(hidden)]
pub fn with_provider<P: CryptoProvider + Clone>(
    identity: String,
    keypair: SignatureKeypair,
    storage_dir: String,
    crypto: P,
) -> Result<Arc<EngineCore<P>>, FfiError> {
    EngineCore::new(identity, keypair, storage_dir, crypto)
}

#[derive(uniffi::Object)]
pub struct Engine {
    core: Arc<EngineCore<Provider>>,
}

#[uniffi::export]
impl Engine {
    #[uniffi::constructor]
    pub fn new(
        identity: String,
        keypair: SignatureKeypair,
        storage_dir: String,
    ) -> Result<Arc<Self>, FfiError> {
        let core = EngineCore::new(identity, keypair, storage_dir, provider())?;
        Ok(Arc::new(Self { core }))
    }

    pub fn key_packages(&self, count: u32) -> Result<Vec<Vec<u8>>, FfiError> {
        self.core.key_packages(count)
    }

    pub fn create_group(&self) -> Result<Vec<u8>, FfiError> {
        self.core.create_group()
    }

    pub fn add(
        &self,
        group_id: Vec<u8>,
        key_packages: Vec<Vec<u8>>,
    ) -> Result<CommitBundle, FfiError> {
        self.core.add(group_id, key_packages)
    }

    pub fn remove(
        &self,
        group_id: Vec<u8>,
        identities: Vec<String>,
    ) -> Result<CommitBundle, FfiError> {
        self.core.remove(group_id, identities)
    }

    pub fn commit_accepted(&self, group_id: Vec<u8>) -> Result<(), FfiError> {
        self.core.commit_accepted(group_id)
    }

    pub fn commit_rejected(&self, group_id: Vec<u8>) -> Result<(), FfiError> {
        self.core.commit_rejected(group_id)
    }

    pub fn has_pending_commit(&self, group_id: Vec<u8>) -> Result<bool, FfiError> {
        self.core.has_pending_commit(group_id)
    }

    pub fn encrypt(&self, group_id: Vec<u8>, plaintext: Vec<u8>) -> Result<Encrypted, FfiError> {
        self.core.encrypt(group_id, plaintext)
    }

    pub fn process(&self, message: Vec<u8>) -> Result<Processed, FfiError> {
        self.core.process(message)
    }

    pub fn epoch(&self, group_id: Vec<u8>) -> Result<u64, FfiError> {
        self.core.epoch(group_id)
    }

    pub fn export_secret(
        &self,
        group_id: Vec<u8>,
        label: String,
        length: u32,
    ) -> Result<Vec<u8>, FfiError> {
        self.core.export_secret(group_id, label, length)
    }

    /// Current members as "<accountID>/<deviceID>" (README contract: `members(of:)`).
    pub fn members(&self, group_id: Vec<u8>) -> Result<Vec<String>, FfiError> {
        self.core.members(group_id)
    }

    /// Every member's identity and leaf signature public key, sorted by identity.
    pub fn member_keys(&self, group_id: Vec<u8>) -> Result<Vec<MemberKey>, FfiError> {
        self.core.member_keys(group_id)
    }

    /// Decrypts and validates without persisting anything; see `confirm`.
    pub fn stage_process(
        &self,
        group_id: Vec<u8>,
        message: Vec<u8>,
    ) -> Result<Arc<StagedProcess>, FfiError> {
        self.core.stage_process(group_id, message)
    }

    /// Persists a staged message; `Stale` if the group's state changed since staging.
    pub fn confirm(&self, staged: Arc<StagedProcess>) -> Result<(), FfiError> {
        self.core.confirm(&staged)
    }

    pub fn discard(&self, staged: Arc<StagedProcess>) {
        self.core.discard(&staged)
    }

    /// Decrypts an application message; anything else is `NotApplication`.
    pub fn decrypt_application(
        &self,
        group_id: Vec<u8>,
        message: Vec<u8>,
    ) -> Result<ApplicationMessage, FfiError> {
        self.core.decrypt_application(group_id, message)
    }
}
