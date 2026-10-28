use std::fmt;

/// Errors the engine reports across the FFI boundary. Maps one-to-one to the
/// Swift `MLSError` (plan table); `.busy` exists only on the Swift side.
#[derive(Debug, uniffi::Error)]
pub enum FfiError {
    Malformed,
    UnknownGroup,
    NotMember,
    Duplicate,
    /// The message belongs to a later epoch than the stored group: a commit was missed.
    EpochGap,
    PendingCommit,
    /// A staged `process` can no longer be confirmed: the group's stored state
    /// changed since it was staged (or it was already confirmed or discarded).
    Stale,
    /// `decrypt_application` was given something that is not an application message.
    NotApplication,
    Storage(String),
    Crypto(String),
    Internal(String),
}

impl fmt::Display for FfiError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            FfiError::Malformed => write!(f, "malformed message"),
            FfiError::UnknownGroup => write!(f, "unknown group"),
            FfiError::NotMember => write!(f, "not a member of the group"),
            FfiError::Duplicate => write!(f, "duplicate message"),
            FfiError::EpochGap => write!(f, "message is from a later epoch than this device has"),
            FfiError::PendingCommit => write!(f, "a commit is already pending"),
            FfiError::Stale => write!(f, "staged message is stale"),
            FfiError::NotApplication => write!(f, "not an application message"),
            FfiError::Storage(m) => write!(f, "storage error: {m}"),
            FfiError::Crypto(m) => write!(f, "crypto error: {m}"),
            FfiError::Internal(m) => write!(f, "internal error: {m}"),
        }
    }
}

impl std::error::Error for FfiError {}

// Lets `FfiError` be the `Error` type of the mls-rs storage traits.
impl mls_rs_core::error::IntoAnyError for FfiError {
    fn into_dyn_error(self) -> Result<Box<dyn std::error::Error + Send + Sync>, Self> {
        Ok(Box::new(self))
    }
}
