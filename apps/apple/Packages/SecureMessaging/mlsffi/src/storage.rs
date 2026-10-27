//! File-backed mls-rs storage.
//!
//! Layout under the root passed to [`FileStorage::new`]:
//!   groups/<hex group id>/state
//!   groups/<hex group id>/epoch-<n>
//!   keypackages/<hex id>
//!
//! Nothing is cached: every call reads the files, because another process
//! (the notification extension) may change them between calls. Each write goes
//! to `<name>.tmp`, is fsynced, then renamed over the final name.

use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};

use mls_rs_core::group::{EpochRecord, GroupState, GroupStateStorage};
use mls_rs_core::key_package::{KeyPackageData, KeyPackageStorage};
use mls_rs_core::mls_rs_codec::{MlsDecode, MlsEncode};
use zeroize::Zeroizing;

use crate::error::FfiError;

/// Newest epochs kept per group; same default as mls-rs's in-memory storage.
const EPOCH_RETENTION: u64 = 3;

const EPOCH_PREFIX: &str = "epoch-";

#[derive(Clone, Debug)]
pub struct FileStorage {
    root: PathBuf,
}

/// Lower-case hex. Group and key IDs are untrusted bytes; as hex they can only
/// ever be a single safe path component.
fn hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push(DIGITS[(b >> 4) as usize] as char);
        s.push(DIGITS[(b & 0xf) as usize] as char);
    }
    s
}

fn storage_err(context: &str, e: impl std::fmt::Display) -> FfiError {
    FfiError::Storage(format!("{context}: {e}"))
}

fn sync_dir(dir: &Path) -> io::Result<()> {
    fs::File::open(dir)?.sync_all()
}

/// Write `data` to `<path>.tmp`, fsync, rename over `path`, fsync the directory.
fn atomic_write(path: &Path, data: &[u8]) -> io::Result<()> {
    let mut tmp_name = path.file_name().expect("file name").to_os_string();
    tmp_name.push(".tmp");
    let tmp = path.with_file_name(tmp_name);
    {
        let mut f = fs::File::create(&tmp)?;
        f.write_all(data)?;
        f.sync_all()?;
    }
    fs::rename(&tmp, path)?;
    if let Some(dir) = path.parent() {
        sync_dir(dir)?;
    }
    Ok(())
}

fn read_optional(path: &Path) -> io::Result<Option<Vec<u8>>> {
    match fs::read(path) {
        Ok(bytes) => Ok(Some(bytes)),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(e),
    }
}

fn remove_optional(path: &Path) -> io::Result<()> {
    match fs::remove_file(path) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => Err(e),
        _ => Ok(()),
    }
}

impl FileStorage {
    /// Opens (creating if needed) the storage at `root`.
    pub fn new(root: impl Into<PathBuf>) -> Result<Self, FfiError> {
        let root = root.into();
        for sub in ["groups", "keypackages"] {
            fs::create_dir_all(root.join(sub)).map_err(|e| storage_err("create directory", e))?;
        }
        Ok(Self { root })
    }

    fn group_dir(&self, group_id: &[u8]) -> PathBuf {
        self.root.join("groups").join(hex(group_id))
    }

    fn epoch_path(&self, group_id: &[u8], epoch_id: u64) -> PathBuf {
        self.group_dir(group_id).join(format!("{EPOCH_PREFIX}{epoch_id}"))
    }

    fn key_package_path(&self, id: &[u8]) -> PathBuf {
        self.root.join("keypackages").join(hex(id))
    }

    /// Epoch numbers stored for a group, ascending. Anything that is not
    /// exactly `epoch-<n>` (such as a leftover `.tmp`) is ignored.
    fn epoch_ids(&self, group_id: &[u8]) -> io::Result<Vec<u64>> {
        let entries = match fs::read_dir(self.group_dir(group_id)) {
            Ok(entries) => entries,
            Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(vec![]),
            Err(e) => return Err(e),
        };
        let mut ids = vec![];
        for entry in entries {
            let name = entry?.file_name();
            let Some(digits) = name.to_str().and_then(|n| n.strip_prefix(EPOCH_PREFIX)) else {
                continue;
            };
            if let Ok(id) = digits.parse::<u64>() {
                if id.to_string() == digits {
                    ids.push(id);
                }
            }
        }
        ids.sort_unstable();
        Ok(ids)
    }
}

impl GroupStateStorage for FileStorage {
    type Error = FfiError;

    fn state(&self, group_id: &[u8]) -> Result<Option<Zeroizing<Vec<u8>>>, Self::Error> {
        read_optional(&self.group_dir(group_id).join("state"))
            .map(|o| o.map(Into::into))
            .map_err(|e| storage_err("read group state", e))
    }

    fn epoch(
        &self,
        group_id: &[u8],
        epoch_id: u64,
    ) -> Result<Option<Zeroizing<Vec<u8>>>, Self::Error> {
        read_optional(&self.epoch_path(group_id, epoch_id))
            .map(|o| o.map(Into::into))
            .map_err(|e| storage_err("read epoch", e))
    }

    fn write(
        &mut self,
        state: GroupState,
        epoch_inserts: Vec<EpochRecord>,
        epoch_updates: Vec<EpochRecord>,
    ) -> Result<(), Self::Error> {
        let err = |e| storage_err("write group state", e);
        let dir = self.group_dir(&state.id);
        fs::create_dir_all(&dir).map_err(err)?;

        // Epochs first, then the state that refers to them, then prune: a crash
        // part-way leaves an older, consistent state plus at most extra epochs.
        for record in &epoch_inserts {
            atomic_write(&self.epoch_path(&state.id, record.id), &record.data).map_err(err)?;
        }
        // Like mls-rs's in-memory storage, an update replaces an existing epoch only.
        for record in &epoch_updates {
            let path = self.epoch_path(&state.id, record.id);
            // `try_exists` is false only for NotFound; any other stat error is returned.
            if path.try_exists().map_err(err)? {
                atomic_write(&path, &record.data).map_err(err)?;
            }
        }
        atomic_write(&dir.join("state"), &state.data).map_err(err)?;

        let ids = self.epoch_ids(&state.id).map_err(err)?;
        let excess = ids.len().saturating_sub(EPOCH_RETENTION as usize);
        for id in &ids[..excess] {
            remove_optional(&self.epoch_path(&state.id, *id)).map_err(err)?;
        }
        Ok(())
    }

    fn max_epoch_id(&self, group_id: &[u8]) -> Result<Option<u64>, Self::Error> {
        self.epoch_ids(group_id)
            .map(|ids| ids.last().copied())
            .map_err(|e| storage_err("list epochs", e))
    }
}

impl KeyPackageStorage for FileStorage {
    type Error = FfiError;

    fn delete(&mut self, id: &[u8]) -> Result<(), Self::Error> {
        remove_optional(&self.key_package_path(id)).map_err(|e| storage_err("delete key package", e))
    }

    fn insert(&mut self, id: Vec<u8>, pkg: KeyPackageData) -> Result<(), Self::Error> {
        let bytes = Zeroizing::new(
            pkg.mls_encode_to_vec()
                .map_err(|e| FfiError::Internal(format!("encode key package: {e}")))?,
        );
        atomic_write(&self.key_package_path(&id), &bytes)
            .map_err(|e| storage_err("write key package", e))
    }

    fn get(&self, id: &[u8]) -> Result<Option<KeyPackageData>, Self::Error> {
        let Some(bytes) = read_optional(&self.key_package_path(id))
            .map_err(|e| storage_err("read key package", e))?
        else {
            return Ok(None);
        };
        let bytes = Zeroizing::new(bytes);
        KeyPackageData::mls_decode(&mut &bytes[..])
            .map(Some)
            .map_err(|e| storage_err("decode key package", e))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use mls_rs_core::crypto::HpkeSecretKey;
    use mls_rs_core::group::{EpochRecord, GroupState, GroupStateStorage};
    use mls_rs_core::key_package::{KeyPackageData, KeyPackageStorage};
    use std::path::{Path, PathBuf};
    use std::sync::atomic::{AtomicU64, Ordering};
    use zeroize::Zeroizing;

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
            let dir = std::env::temp_dir().join(format!(
                "mlsffi-test-{}-{nanos:x}-{n}",
                std::process::id()
            ));
            std::fs::create_dir_all(&dir).unwrap();
            TempDir(dir)
        }
        fn root(&self) -> PathBuf {
            self.0.join("store")
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn rec(id: u64, data: &[u8]) -> EpochRecord {
        EpochRecord::new(id, Zeroizing::new(data.to_vec()))
    }

    fn gstate(id: &[u8], data: &[u8]) -> GroupState {
        GroupState {
            id: id.to_vec(),
            data: Zeroizing::new(data.to_vec()),
        }
    }

    fn files_under(dir: &Path, out: &mut Vec<PathBuf>) {
        for e in std::fs::read_dir(dir).unwrap() {
            let p = e.unwrap().path();
            if p.is_dir() {
                files_under(&p, out);
            } else {
                out.push(p);
            }
        }
    }

    fn key_package_data(tag: u8) -> KeyPackageData {
        KeyPackageData::new(
            vec![tag; 40],
            HpkeSecretKey::from(vec![tag + 1; 32]),
            HpkeSecretKey::from(vec![tag + 2; 32]),
            1_000_000,
        )
    }

    #[test]
    fn group_state_round_trip() {
        let t = TempDir::new();
        let mut s = FileStorage::new(t.root()).unwrap();
        let (e1, e2) = (rec(1, b"epoch-one"), rec(2, b"epoch-two"));
        s.write(gstate(b"g", b"state-bytes"), vec![e1.clone(), e2], vec![])
            .unwrap();
        assert_eq!(s.state(b"g").unwrap().unwrap().as_slice(), b"state-bytes");
        assert_eq!(s.epoch(b"g", 1).unwrap().unwrap().as_slice(), e1.data.as_slice());
        assert_eq!(s.max_epoch_id(b"g").unwrap(), Some(2));
        assert!(s.state(b"other").unwrap().is_none());
        assert!(s.epoch(b"g", 9).unwrap().is_none());
        assert_eq!(s.max_epoch_id(b"other").unwrap(), None);
    }

    #[test]
    fn keeps_three_newest_epochs() {
        let t = TempDir::new();
        let mut s = FileStorage::new(t.root()).unwrap();
        s.write(gstate(b"g", b"s1"), vec![rec(1, b"1"), rec(2, b"2")], vec![])
            .unwrap();
        s.write(gstate(b"g", b"s2"), vec![rec(3, b"3"), rec(4, b"4")], vec![])
            .unwrap();
        s.write(gstate(b"g", b"s3"), vec![rec(5, b"5")], vec![])
            .unwrap();
        assert!(s.epoch(b"g", 1).unwrap().is_none());
        assert!(s.epoch(b"g", 2).unwrap().is_none());
        for (id, data) in [(3u64, b"3"), (4, b"4"), (5, b"5")] {
            assert_eq!(s.epoch(b"g", id).unwrap().unwrap().as_slice(), data);
        }
        assert_eq!(s.max_epoch_id(b"g").unwrap(), Some(5));
    }

    #[test]
    fn epoch_updates_replace() {
        let t = TempDir::new();
        let mut s = FileStorage::new(t.root()).unwrap();
        s.write(
            gstate(b"g", b"s"),
            vec![rec(1, b"1"), rec(2, b"2"), rec(3, b"old")],
            vec![],
        )
        .unwrap();
        s.write(gstate(b"g", b"s"), vec![], vec![rec(3, b"new")])
            .unwrap();
        assert_eq!(s.epoch(b"g", 3).unwrap().unwrap().as_slice(), b"new");
        assert_eq!(s.epoch(b"g", 2).unwrap().unwrap().as_slice(), b"2");
    }

    #[test]
    fn writes_leave_no_temp_files() {
        let t = TempDir::new();
        let root = t.root();
        let mut s = FileStorage::new(&root).unwrap();
        s.write(gstate(b"g", b"s"), vec![rec(1, b"1")], vec![])
            .unwrap();
        s.insert(b"kp".to_vec(), key_package_data(1)).unwrap();
        let mut files = vec![];
        files_under(&root, &mut files);
        assert!(!files.is_empty());
        assert!(files.iter().all(|p| p.extension().map_or(true, |e| e != "tmp")));

        // A leftover temp file from an interrupted write is ignored.
        let dir = root.join("groups").join(hex(b"g"));
        std::fs::write(dir.join("state.tmp"), b"half-written").unwrap();
        assert_eq!(s.state(b"g").unwrap().unwrap().as_slice(), b"s");
        std::fs::write(dir.join("epoch-9.tmp"), b"half-written").unwrap();
        assert_eq!(s.max_epoch_id(b"g").unwrap(), Some(1));
    }

    #[test]
    fn survives_reopen() {
        let t = TempDir::new();
        let mut s = FileStorage::new(t.root()).unwrap();
        s.write(gstate(b"g", b"s"), vec![rec(1, b"1")], vec![])
            .unwrap();
        s.insert(b"kp".to_vec(), key_package_data(7)).unwrap();
        drop(s);

        let s2 = FileStorage::new(t.root()).unwrap();
        assert_eq!(s2.state(b"g").unwrap().unwrap().as_slice(), b"s");
        assert_eq!(s2.epoch(b"g", 1).unwrap().unwrap().as_slice(), b"1");
        assert_eq!(s2.get(b"kp").unwrap().unwrap(), key_package_data(7));
    }

    #[test]
    fn reads_see_writes_from_another_handle() {
        // The NSE changes storage from another process: nothing may be cached.
        let t = TempDir::new();
        let mut a = FileStorage::new(t.root()).unwrap();
        let b = a.clone();
        a.write(gstate(b"g", b"s1"), vec![rec(1, b"1")], vec![]).unwrap();
        assert_eq!(b.state(b"g").unwrap().unwrap().as_slice(), b"s1");
        a.write(gstate(b"g", b"s2"), vec![rec(2, b"2")], vec![]).unwrap();
        assert_eq!(b.state(b"g").unwrap().unwrap().as_slice(), b"s2");
        assert_eq!(b.max_epoch_id(b"g").unwrap(), Some(2));
    }

    #[test]
    fn key_package_insert_get_delete() {
        let t = TempDir::new();
        let mut s = FileStorage::new(t.root()).unwrap();
        assert!(s.get(b"kp").unwrap().is_none());
        s.insert(b"kp".to_vec(), key_package_data(3)).unwrap();
        assert_eq!(s.get(b"kp").unwrap().unwrap(), key_package_data(3));
        s.delete(b"kp").unwrap();
        assert!(s.get(b"kp").unwrap().is_none());
        // Deleting what is not there is not an error.
        s.delete(b"kp").unwrap();
    }

    #[test]
    fn hostile_group_ids_stay_inside_root() {
        let t = TempDir::new();
        let root = t.root();
        let mut s = FileStorage::new(&root).unwrap();
        for id in [&b"../x"[..], &b"a/b"[..]] {
            s.write(gstate(id, b"s"), vec![rec(1, b"1")], vec![]).unwrap();
            s.insert(id.to_vec(), key_package_data(1)).unwrap();
            assert_eq!(s.state(id).unwrap().unwrap().as_slice(), b"s");
        }
        let mut names: Vec<String> = std::fs::read_dir(root.join("groups"))
            .unwrap()
            .map(|e| e.unwrap().file_name().into_string().unwrap())
            .collect();
        names.sort();
        assert_eq!(names, vec!["2e2e2f78", "612f62"]);
        // Nothing was created outside root.
        let outside: Vec<_> = std::fs::read_dir(&t.0).unwrap().map(|e| e.unwrap().file_name()).collect();
        assert_eq!(outside, vec![std::ffi::OsString::from("store")]);
        let mut kps: Vec<String> = std::fs::read_dir(root.join("keypackages"))
            .unwrap()
            .map(|e| e.unwrap().file_name().into_string().unwrap())
            .collect();
        kps.sort();
        assert_eq!(kps, vec!["2e2e2f78", "612f62"]);
    }

    #[cfg(unix)]
    #[test]
    fn epoch_update_propagates_stat_errors() {
        // A self-referencing symlink makes stat fail with ELOOP (not NotFound),
        // and the state file stays writable, so only the epoch check can fail.
        // Unlike a permission-based setup this also works when run as root.
        let t = TempDir::new();
        let mut s = FileStorage::new(t.root()).unwrap();
        s.write(gstate(b"g", b"s"), vec![rec(2, b"2")], vec![]).unwrap();
        let dir = t.root().join("groups").join(hex(b"g"));
        std::os::unix::fs::symlink("epoch-1", dir.join("epoch-1")).unwrap();
        assert!(std::fs::metadata(dir.join("epoch-1")).is_err());

        let result = s.write(gstate(b"g", b"s2"), vec![], vec![rec(1, b"new")]);
        assert!(matches!(result, Err(FfiError::Storage(_))), "got {result:?}");
        // The failed write left the old state in place.
        assert_eq!(s.state(b"g").unwrap().unwrap().as_slice(), b"s");
    }
}
