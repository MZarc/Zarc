//! # Zarc vault core
//!
//! The Rust heart of the Zarc private gallery vault. It provides:
//!
//! * [`sodium`] - a safe wrapper around the vendored, signature-verified
//!   upstream libsodium build (XChaCha20-Poly1305 secretstream and AEAD).
//! * [`container`] - the versioned, opaque encrypted-object container format.
//! * [`keywrap`] - the vault master key / data encryption key hierarchy.
//! * [`vault`] - fail-closed, transactional encryption and decryption of
//!   vault objects.
//!
//! The core is deliberately free of Android specific code: platform facilities
//! (in particular the Android Keystore) live in the thin Kotlin adapter, which
//! hands the core an unwrapped vault master key for the duration of a single
//! operation.
//!
//! ## Security properties
//!
//! * Every object gets an independent random 256-bit data key.
//! * Object content is encrypted with libsodium's
//!   `crypto_secretstream_xchacha20poly1305` construction, so truncation,
//!   reordering, duplication and modification of the ciphertext are detected.
//! * The container header is bound into the authentication of every chunk, so
//!   no header field can be tampered with.
//! * Incomplete operations never publish an object: the temporary ciphertext
//!   or the staging plaintext is removed and the operation fails.
//!
//! See `docs/THREAT_MODEL.md`, `docs/CRYPTOGRAPHY_DECISION.md`,
//! `docs/KEY_MANAGEMENT.md` and `docs/FILE_FORMAT.md`.

#![deny(missing_docs)]
#![deny(unsafe_op_in_unsafe_fn)]
#![warn(clippy::all)]
#![warn(clippy::pedantic)]
#![allow(clippy::module_name_repetitions)]

pub mod container;
pub mod error;
pub mod fsutil;
pub mod hex;
pub mod keywrap;
pub mod recovery;
pub mod selftest;
pub mod sodium;
pub mod vault;

pub use error::{Error, Result};
pub use vault::{EncryptedObject, ObjectInfo, Vault};
