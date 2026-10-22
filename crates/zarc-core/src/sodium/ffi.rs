//! Raw FFI declarations for the small subset of the libsodium C API that Zarc uses.
//!
//! This module is the *only* place in the workspace that contains `unsafe`
//! foreign function calls. Every declaration below mirrors the upstream
//! `libsodium-1.0.22` public headers:
//!
//! * `src/libsodium/include/sodium/crypto_secretstream_xchacha20poly1305.h`
//! * `src/libsodium/include/sodium/crypto_aead_xchacha20poly1305.h`
//! * `src/libsodium/include/sodium/randombytes.h`
//! * `src/libsodium/include/sodium/core.h`
//!
//! The ABI of these symbols is stable across libsodium 1.x releases.
//!
//! # Safety
//!
//! Callers must respect the documented libsodium contracts:
//!
//! * key buffers must be exactly [`KEY_BYTES`] long,
//! * the secretstream state must be initialised (`init_push` / `init_pull`)
//!   before `push` / `pull` is called,
//! * `push` output buffers must hold `mlen + 17` bytes and `pull` output
//!   buffers must hold `clen - 17` bytes,
//! * `nsec` must be `NULL` (libsodium's XChaCha20-Poly1305 has no separate
//!   nonce/secret input).

#![allow(non_camel_case_types)]

use core::ffi::{c_char, c_int, c_ulonglong, c_void};

/// Size of a secretstream key in bytes (256-bit).
pub const KEY_BYTES: usize = 32;

/// Size of a secretstream header in bytes.
pub const HEADER_BYTES: usize = 24;

/// Size of an XChaCha20-Poly1305-IETF nonce in bytes.
pub const AEAD_NONCE_BYTES: usize = 24;

/// Size of an XChaCha20-Poly1305-IETF authentication tag in bytes.
pub const AEAD_MAC_BYTES: usize = 16;

/// Size of the Argon2id salt in bytes.
pub const ARGON2ID_SALT_BYTES: usize = 16;

/// The Argon2id13 algorithm identifier (libsodium 1.0.22).
pub const ARGON2ID_ALG: i32 = 2;

/// Size of the secretstream per-message overhead: 1 tag byte + 16 MAC bytes.
pub const STREAM_ABYTES: usize = 17;

/// Opaque secretstream state.
///
/// Layout mirrors the upstream `crypto_secretstream_xchacha20poly1305_state` from the
/// upstream header: `k[32]`, `nonce[12]`, `pad[8]`.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct crypto_secretstream_xchacha20poly1305_state {
    /// Per-stream `ChaCha20` key.
    pub k: [u8; KEY_BYTES],
    /// Per-stream nonce.
    pub nonce: [u8; 12],
    /// Padding, present so the struct size matches upstream exactly.
    pub pad: [u8; 8],
}

extern "C" {
    /// Initialise libsodium. Returns 0 on success, 1 if already initialised.
    pub fn sodium_init() -> c_int;

    /// Version string of the linked libsodium build, e.g. "1.0.22".
    pub fn sodium_version_string() -> *const c_char;

    /// Fill `buf` with cryptographically secure random bytes.
    pub fn randombytes_buf(buf: *mut u8, size: usize);

    /// Overwrite `len` bytes at `buf` with zeros in a way the compiler may not
    /// optimise away.
    pub fn sodium_memzero(buf: *mut c_void, len: usize);

    /// Generate a random secretstream key.
    pub fn crypto_secretstream_xchacha20poly1305_keygen(k: *mut u8);

    /// Initialise an encryption state and produce the stream header.
    pub fn crypto_secretstream_xchacha20poly1305_init_push(
        state: *mut crypto_secretstream_xchacha20poly1305_state,
        header: *mut u8,
        k: *const u8,
    ) -> c_int;

    /// Initialise a decryption state from a stream header.
    pub fn crypto_secretstream_xchacha20poly1305_init_pull(
        state: *mut crypto_secretstream_xchacha20poly1305_state,
        header: *const u8,
        k: *const u8,
    ) -> c_int;

    /// Encrypt one message and append its authentication tag.
    pub fn crypto_secretstream_xchacha20poly1305_push(
        state: *mut crypto_secretstream_xchacha20poly1305_state,
        c: *mut u8,
        clen_p: *mut c_ulonglong,
        m: *const u8,
        mlen: c_ulonglong,
        ad: *const u8,
        adlen: c_ulonglong,
        tag: u8,
    ) -> c_int;

    /// Decrypt and authenticate one message.
    pub fn crypto_secretstream_xchacha20poly1305_pull(
        state: *mut crypto_secretstream_xchacha20poly1305_state,
        m: *mut u8,
        mlen_p: *mut c_ulonglong,
        tag_p: *mut u8,
        c: *const u8,
        clen: c_ulonglong,
        ad: *const u8,
        adlen: c_ulonglong,
    ) -> c_int;

    /// Generate a random XChaCha20-Poly1305-IETF key.
    pub fn crypto_aead_xchacha20poly1305_ietf_keygen(k: *mut u8);

    /// Authenticated encryption with detached tag.
    pub fn crypto_aead_xchacha20poly1305_ietf_encrypt_detached(
        c: *mut u8,
        mac: *mut u8,
        maclen_p: *mut c_ulonglong,
        m: *const u8,
        mlen: c_ulonglong,
        ad: *const u8,
        adlen: c_ulonglong,
        nsec: *const u8,
        npub: *const u8,
        k: *const u8,
    ) -> c_int;

    /// Authenticated decryption with detached tag.
    pub fn crypto_aead_xchacha20poly1305_ietf_decrypt_detached(
        m: *mut u8,
        nsec: *mut u8,
        c: *const u8,
        clen: c_ulonglong,
        mac: *const u8,
        ad: *const u8,
        adlen: c_ulonglong,
        npub: *const u8,
        k: *const u8,
    ) -> c_int;

    /// HKDF-SHA256 extract step.
    pub fn crypto_kdf_hkdf_sha256_extract(
        prk: *mut u8,
        salt: *const u8,
        salt_len: usize,
        ikm: *const u8,
        ikm_len: usize,
    ) -> c_int;

    /// HKDF-SHA256 expand step.
    pub fn crypto_kdf_hkdf_sha256_expand(
        out: *mut u8,
        out_len: usize,
        ctx: *const u8,
        ctx_len: usize,
        prk: *const u8,
    ) -> c_int;

    /// Derive a key from a passphrase with Argon2id.
    ///
    /// The signature is the one of libsodium 1.0.22, which takes the
    /// algorithm identifier as the last argument.
    pub fn crypto_pwhash_argon2id(
        out: *mut u8,
        outlen: c_ulonglong,
        passwd: *const u8,
        passwdlen: c_ulonglong,
        salt: *const u8,
        opslimit: c_ulonglong,
        memlimit: usize,
        alg: c_int,
    ) -> c_int;
}
