//! Small filesystem helpers used by the vault.
//!
//! All helpers create files with owner-only permissions where the platform
//! supports it and make renames durable where the platform allows it.
//!
//! This module contains no `unsafe` code.
//!
//! The only `unsafe` in the workspace is the libsodium FFI layer in
//! [`crate::sodium::ffi`].

#![forbid(unsafe_code)]

use std::fs::{File, OpenOptions};
use std::io::Read;
use std::path::Path;

use crate::error::{Error, Result};
use crate::hex;
use crate::sodium;

/// # Errors
/// #
/// # Returns [`crate::Error`] when the operation cannot be completed; see
/// # the error taxonomy in [`crate::error`] for the individual failure modes.
/// Create a directory (including parents) and restrict it to the owner.
pub fn ensure_private_dir(dir: &Path) -> Result<()> {
    std::fs::create_dir_all(dir)?;
    set_private_dir_permissions(dir)?;
    Ok(())
}

#[cfg(unix)]
fn set_private_dir_permissions(dir: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700))?;
    Ok(())
}

#[cfg(not(unix))]
#[allow(clippy::unnecessary_wraps)]
fn set_private_dir_permissions(_dir: &Path) -> Result<()> {
    // Windows has no POSIX mode bits: the protection comes from the fact that
    // the directories live inside the application private storage.
    Ok(())
}

/// # Errors
/// #
/// # Returns [`crate::Error`] when the operation cannot be completed; see
/// # the error taxonomy in [`crate::error`] for the individual failure modes.
/// Create a new file for writing, with owner-only permissions.
///
/// Fails if the file already exists, which prevents accidental overwrites and
/// symlink attacks on the staging path.
pub fn create_private_file(path: &Path) -> Result<File> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    Ok(options.open(path)?)
}

/// # Errors
/// #
/// # Returns [`crate::Error`] when the operation cannot be completed; see
/// # the error taxonomy in [`crate::error`] for the individual failure modes.
/// Flush a file's contents to stable storage.
pub fn sync_file(file: &File) -> Result<()> {
    file.sync_all()?;
    Ok(())
}

/// # Errors
/// #
/// # Returns [`crate::Error`] when the operation cannot be completed; see
/// # the error taxonomy in [`crate::error`] for the individual failure modes.
/// Flush a directory entry so that a rename survives a crash.
///
/// Only meaningful on Unix-like systems; on Windows the equivalent guarantee is
/// provided by the file system itself for `MoveFileEx` with write-through.
#[cfg(unix)]
pub fn sync_dir(dir: &Path) -> Result<()> {
    File::open(dir)?.sync_all()?;
    Ok(())
}

/// # Errors
/// #
/// # Returns [`crate::Error`] when the operation cannot be completed; see
/// # the error taxonomy in [`crate::error`] for the individual failure modes.
/// Flush a directory entry so that a rename survives a crash.
///
/// Only meaningful on Unix-like systems.
#[cfg(not(unix))]
pub fn sync_dir(_dir: &Path) -> Result<()> {
    // Windows has no directory handle to flush; `MoveFileEx` with
    // `MOVEFILE_WRITE_THROUGH` provides the equivalent durability.
    Ok(())
}

/// # Errors
/// #
/// # Returns [`crate::Error`] when the operation cannot be completed; see
/// # the error taxonomy in [`crate::error`] for the individual failure modes.
/// Generate a random temporary file name.
///
/// The name starts with a dot so that it is never picked up by a media scan,
/// and carries no extension so that it cannot be mistaken for media.
pub fn temp_name(prefix: &str) -> Result<String> {
    let mut bytes = [0u8; 12];
    sodium::random_bytes(&mut bytes)?;
    Ok(format!("{prefix}{}", hex::encode(&bytes)))
}

/// # Errors
/// #
/// # Returns [`crate::Error`] when the operation cannot be completed; see
/// # the error taxonomy in [`crate::error`] for the individual failure modes.
/// Delete a file, ignoring "file not found".
pub fn remove_if_exists(path: &Path) -> Result<()> {
    match std::fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error.into()),
    }
}

/// # Errors
/// #
/// # Returns [`crate::Error`] when the operation cannot be completed; see
/// # the error taxonomy in [`crate::error`] for the individual failure modes.
/// Read exactly `buf.len()` bytes, reporting an unexpected end of file as a
/// malformed container rather than as an opaque I/O error.
pub fn read_exact_or_truncated(file: &mut File, buf: &mut [u8]) -> Result<()> {
    match file.read_exact(buf) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::UnexpectedEof => Err(Error::Format(
            "unexpected end of file: the stored object is truncated".into(),
        )),
        Err(error) => Err(error.into()),
    }
}
