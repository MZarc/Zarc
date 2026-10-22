//! Explicit error types for the Zarc vault core.
//!
//! Authentication failures are always represented by [`Error::Authentication`]
//! and never by an empty success value, an empty file or a truncated result.
//!
//! This module contains no `unsafe` code.
//!
//! The only `unsafe` in the workspace is the libsodium FFI layer in
//! [`crate::sodium::ffi`].

#![forbid(unsafe_code)]

use thiserror::Error;

/// Result alias used throughout the crate.
pub type Result<T> = std::result::Result<T, Error>;

/// All failure modes of the vault core.
#[derive(Debug, Error)]
pub enum Error {
    /// An I/O operation failed.
    #[error("i/o error: {0}")]
    Io(#[from] std::io::Error),

    /// The cryptographic authentication of the data failed.
    ///
    /// This covers wrong keys, modified ciphertext, modified tags, modified
    /// headers and truncated streams. It is always a hard failure.
    #[error("authentication failure: {0}")]
    Authentication(String),

    /// The container is malformed: bad magic, bad lengths, trailing data, ...
    #[error("malformed container: {0}")]
    Format(String),

    /// The container uses a format version this build does not understand.
    #[error("unsupported container version {0}")]
    UnsupportedVersion(u8),

    /// The container requests an algorithm this build does not implement.
    #[error("unsupported algorithm id {0}")]
    UnsupportedAlgorithm(u8),

    /// A cryptographic primitive failed for a non-authentication reason.
    #[error("cryptographic failure: {0}")]
    Crypto(String),

    /// Key material was missing, of the wrong size, or unusable.
    #[error("invalid key material: {0}")]
    InvalidKey(String),

    /// A declared length did not match the number of bytes actually present.
    #[error("length mismatch: expected {expected} bytes, got {actual}")]
    LengthMismatch {
        /// The length the container declared.
        expected: u64,
        /// The length that was actually observed.
        actual: u64,
    },

    /// The requested object does not exist.
    #[error("object not found: {0}")]
    NotFound(String),

    /// A caller supplied an argument that cannot be accepted.
    #[error("invalid argument: {0}")]
    InvalidArgument(String),
}
