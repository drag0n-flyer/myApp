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
use zeroize::Zeroizing;

use crate::error::FfiError;
use crate::storage::FileStorage;

#[cfg(target_vendor = "apple")]
type Provider = mls_rs_crypto_cryptokit::CryptoKitProvider;
#[cfg(not(target_vendor = "apple"))]
type Provider = mls_rs_crypto_rustcrypto::RustCryptoProvider;

const CIPHER_SUITE: CipherSuite = CipherSuite::CURVE25519_AES128;
const GROUP_ID_LEN: usize = 32;

type Config = WithMlsRules<
    DefaultMlsRules,
    WithCryptoProvider<
        Provider,
        WithIdentityProvider<
            BasicIdentityProvider,
            WithKeyPackageRepo<FileStorage, WithGroupStateStorage<FileStorage, BaseConfig>>,
        >,
    >,
>;
type EngineClient = Client<IntoConfigOutput<Config>>;
type EngineGroup = Group<IntoConfigOutput<Config>>;

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

#[derive(uniffi::Enum)]
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

#[derive(uniffi::Object)]
pub struct Engine {
    client: EngineClient,
    storage: FileStorage,
    /// Serializes calls within this process (load, act, write must not
    /// interleave). Cross-process exclusion is the Swift side's `flock`.
    gate: Mutex<()>,
}

impl Engine {
    fn lock(&self) -> MutexGuard<'_, ()> {
        // A panic in an earlier call cannot leave in-memory state behind (there
        // is none), so a poisoned gate is safe to reuse.
        self.gate.lock().unwrap_or_else(|p| p.into_inner())
    }

    /// Loads the group fresh from storage.
    /// Runs the repair step first (see [`Engine::repair_epochs`]).
    fn load(&self, group_id: &[u8]) -> Result<EngineGroup, FfiError> {
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

    fn bundle(group: &EngineGroup, output: CommitOutput) -> Result<CommitBundle, FfiError> {
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

#[uniffi::export]
impl Engine {
    #[uniffi::constructor]
    pub fn new(
        identity: String,
        keypair: SignatureKeypair,
        storage_dir: String,
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
            .crypto_provider(provider())
            .mls_rules(DefaultMlsRules::new().with_commit_options(commit_options))
            .signing_identity(signing_identity, secret, CIPHER_SUITE)
            .build();
        Ok(Arc::new(Self {
            storage,
            client,
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
        let suite = provider()
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

    pub fn process(&self, message: Vec<u8>) -> Result<Processed, FfiError> {
        let _g = self.lock();
        let message = MlsMessage::from_bytes(&message).map_err(|_| FfiError::Malformed)?;
        match message.wire_format() {
            WireFormat::Welcome => {
                let (mut group, _info) = self
                    .client
                    .join_group(None, &message, None)
                    .map_err(map_mls)?;
                // A Welcome replaces any earlier state for this group (for
                // example after we were removed and then re-added): old epoch
                // records belong to the old history. Delete them all first.
                for id in self.storage.stored_epochs(group.group_id())? {
                    self.storage.delete_epoch(group.group_id(), id)?;
                }
                // Also deletes the KeyPackage this Welcome consumed.
                group.write_to_storage().map_err(map_mls)?;
                Ok(Processed::Joined {
                    group_id: group.group_id().to_vec(),
                    epoch: group.current_epoch(),
                })
            }
            WireFormat::PrivateMessage | WireFormat::PublicMessage => {
                let group_id = message.group_id().ok_or(FfiError::Malformed)?.to_vec();
                let mut group = self.load(&group_id)?;
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
                    ReceivedMessage::Commit(description) => Processed::Commit {
                        group_id: group_id.clone(),
                        new_epoch: group.current_epoch(),
                        removed_me: matches!(description.effect, CommitEffect::Removed { .. }),
                    },
                    // Standalone proposals, GroupInfo, KeyPackage: never sent by our clients.
                    _ => return Err(FfiError::Malformed),
                };
                group.write_to_storage().map_err(map_mls)?;
                Ok(processed)
            }
            _ => Err(FfiError::Malformed),
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
