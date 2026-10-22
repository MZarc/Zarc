//! Safe Rust wrappers around the vendored libsodium C API.
//!
//! Every function in this module is safe to call: pointer/length handling,
//! output-buffer sizing and error translation are all done here, so the rest
//! of Zarc never touches raw pointers.

pub mod ffi;

use std::ffi::CStr;
use std::sync::OnceLock;

use zeroize::{Zeroize, ZeroizeOnDrop};

use crate::error::{Error, Result};

pub use ffi::{
    AEAD_MAC_BYTES, AEAD_NONCE_BYTES, ARGON2ID_SALT_BYTES, HEADER_BYTES, KEY_BYTES, STREAM_ABYTES,
};

/// Message tag: an ordinary message.
pub const TAG_MESSAGE: u8 = 0x00;
/// Message tag: end of a logical group of messages.
pub const TAG_PUSH: u8 = 0x01;
/// Message tag: rekey the stream after this message.
pub const TAG_REKEY: u8 = 0x02;
/// Message tag: final message of the stream.
pub const TAG_FINAL: u8 = 0x03;

static INIT_RESULT: OnceLock<bool> = OnceLock::new();

/// # Errors
/// #
/// # Returns [`crate::Error`] when the operation cannot be completed; see
/// # the error taxonomy in [`crate::error`] for the individual failure modes.
/// Initialise libsodium exactly once per process.
///
/// libsodium's `sodium_init()` is idempotent but not thread-safe with respect
/// to *first* use, so it is called behind a `OnceLock`.
pub fn ensure_init() -> Result<()> {
    let ok = *INIT_RESULT.get_or_init(|| {
        // SAFETY: `sodium_init` takes no arguments and only initialises global
        // state inside libsodium. It is safe to call from any thread as long as
        // it is not called concurrently, which the `OnceLock` guarantees.
        let rc = unsafe { ffi::sodium_init() };
        rc == 0 || rc == 1
    });
    if ok {
        Ok(())
    } else {
        Err(Error::Crypto("libsodium initialisation failed".to_string()))
    }
}

/// # Errors
/// #
/// # Returns [`crate::Error`] when the operation cannot be completed; see
/// # the error taxonomy in [`crate::error`] for the individual failure modes.
/// Version string of the linked libsodium build.
pub fn version() -> Result<&'static str> {
    ensure_init()?;
    // SAFETY: `sodium_version_string` returns a pointer to a static, NUL
    // terminated string that lives for the whole process lifetime.
    let ptr = unsafe { ffi::sodium_version_string() };
    if ptr.is_null() {
        return Err(Error::Crypto("libsodium version string is NULL".into()));
    }
    // SAFETY: the pointer is non-NULL and points at a NUL terminated string.
    let version = unsafe { CStr::from_ptr(ptr) };
    version
        .to_str()
        .map_err(|e| Error::Crypto(format!("invalid libsodium version: {e}")))
}

/// Convert a Rust length into the `unsigned long long` that libsodium uses.
///
/// A slice length always fits into 64 bits, so this cannot fail.
fn len_u64(len: usize) -> std::ffi::c_ulonglong {
    u64::try_from(len).expect("a slice length always fits into 64 bits")
}

/// # Errors
/// #
/// # Returns [`crate::Error`] when the operation cannot be completed; see
/// # the error taxonomy in [`crate::error`] for the individual failure modes.
/// Fill `out` with cryptographically secure random bytes.
pub fn random_bytes(out: &mut [u8]) -> Result<()> {
    ensure_init()?;
    // SAFETY: `out` is a valid, exclusively borrowed slice of `out.len()` bytes
    // and libsodium writes exactly that many bytes.
    unsafe { ffi::randombytes_buf(out.as_mut_ptr(), out.len()) };
    Ok(())
}

/// # Errors
/// #
/// # Returns [`crate::Error`] when the operation cannot be completed; see
/// # the error taxonomy in [`crate::error`] for the individual failure modes.
/// Generate a random 256-bit key.
pub fn random_key() -> Result<[u8; KEY_BYTES]> {
    let mut key = [0u8; KEY_BYTES];
    random_bytes(&mut key)?;
    Ok(key)
}

/// Overwrite a buffer with zeros.
pub fn memzero(buf: &mut [u8]) {
    // SAFETY: `buf` is valid for `buf.len()` bytes; libsodium only writes zeros.
    unsafe {
        ffi::sodium_memzero(
            std::ptr::from_mut(buf).cast::<std::ffi::c_void>(),
            buf.len(),
        );
    };
}

/// A secretstream key. Zeroized when dropped.
#[derive(Clone, Zeroize, ZeroizeOnDrop)]
pub struct SecretStreamKey([u8; KEY_BYTES]);

impl SecretStreamKey {
    /// Create a key from raw bytes.
    #[must_use]
    pub fn from_bytes(bytes: &[u8; KEY_BYTES]) -> Self {
        Self(*bytes)
    }

    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Generate a fresh random key.
    pub fn generate() -> Result<Self> {
        Ok(Self(random_key()?))
    }

    /// Borrow the raw key bytes.
    #[must_use]
    pub fn as_bytes(&self) -> &[u8; KEY_BYTES] {
        &self.0
    }
}

/// A secretstream header. It is not secret, but it must be stored unmodified.
#[derive(Clone, Copy, PartialEq, Eq)]
pub struct StreamHeader([u8; HEADER_BYTES]);

impl StreamHeader {
    /// Wrap raw header bytes.
    #[must_use]
    pub fn from_bytes(bytes: [u8; HEADER_BYTES]) -> Self {
        Self(bytes)
    }

    /// Borrow the raw header bytes.
    #[must_use]
    pub fn as_bytes(&self) -> &[u8; HEADER_BYTES] {
        &self.0
    }
}

/// Encrypting half of a secretstream.
pub struct Encryptor {
    state: ffi::crypto_secretstream_xchacha20poly1305_state,
    finalized: bool,
}

impl Encryptor {
    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Initialise an encryption stream, returning the encryptor and header.
    pub fn init(key: &SecretStreamKey) -> Result<(Self, StreamHeader)> {
        ensure_init()?;
        let mut state = ffi::crypto_secretstream_xchacha20poly1305_state {
            k: [0u8; KEY_BYTES],
            nonce: [0u8; 12],
            pad: [0u8; 8],
        };
        let mut header = [0u8; HEADER_BYTES];
        // SAFETY: `state` and `header` are valid, correctly sized and
        // exclusively owned by this function; `key` holds KEY_BYTES bytes.
        let rc = unsafe {
            ffi::crypto_secretstream_xchacha20poly1305_init_push(
                std::ptr::from_mut(&mut state),
                header.as_mut_ptr(),
                key.as_bytes().as_ptr(),
            )
        };
        if rc != 0 {
            memzero(&mut state.k);
            return Err(Error::Crypto("secretstream init_push failed".into()));
        }
        Ok((
            Self {
                state,
                finalized: false,
            },
            StreamHeader(header),
        ))
    }

    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Encrypt one message, returning `message + 17` bytes of ciphertext.
    pub fn push(&mut self, message: &[u8], ad: &[u8], tag: u8) -> Result<Vec<u8>> {
        if self.finalized {
            return Err(Error::Crypto("secretstream already finalized".into()));
        }
        if !matches!(tag, TAG_MESSAGE | TAG_PUSH | TAG_REKEY | TAG_FINAL) {
            return Err(Error::Crypto(format!("invalid secretstream tag {tag}")));
        }
        let mut out = vec![0u8; message.len() + STREAM_ABYTES];
        let mut out_len: std::ffi::c_ulonglong = 0;
        // SAFETY: `out` has `message.len() + STREAM_ABYTES` bytes of capacity,
        // which is exactly what libsodium writes; `message` and `ad` are valid
        // slices for the lengths passed.
        let rc = unsafe {
            ffi::crypto_secretstream_xchacha20poly1305_push(
                std::ptr::from_mut(&mut self.state),
                out.as_mut_ptr(),
                std::ptr::from_mut(&mut out_len),
                message.as_ptr(),
                len_u64(message.len()),
                ad.as_ptr(),
                len_u64(ad.len()),
                tag,
            )
        };
        if rc != 0 {
            out.zeroize();
            return Err(Error::Crypto("secretstream push failed".into()));
        }
        let written = usize::try_from(out_len)
            .map_err(|_| Error::Crypto("libsodium reported an invalid length".into()))?;
        out.truncate(written);
        if tag == TAG_FINAL {
            self.finalized = true;
        }
        Ok(out)
    }
}

impl Drop for Encryptor {
    fn drop(&mut self) {
        // SAFETY: `self.state` is a valid, exclusively owned struct.
        unsafe {
            ffi::sodium_memzero(
                std::ptr::from_mut(&mut self.state).cast::<std::ffi::c_void>(),
                std::mem::size_of::<ffi::crypto_secretstream_xchacha20poly1305_state>(),
            );
        }
    }
}

/// Decrypting half of a secretstream.
pub struct Decryptor {
    state: ffi::crypto_secretstream_xchacha20poly1305_state,
    finalized: bool,
}

impl Decryptor {
    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Initialise a decryption stream from a stored header.
    pub fn init(header: &StreamHeader, key: &SecretStreamKey) -> Result<Self> {
        ensure_init()?;
        let mut state = ffi::crypto_secretstream_xchacha20poly1305_state {
            k: [0u8; KEY_BYTES],
            nonce: [0u8; 12],
            pad: [0u8; 8],
        };
        // SAFETY: `state` is valid and exclusively owned; `header` and `key`
        // point at correctly sized buffers.
        let rc = unsafe {
            ffi::crypto_secretstream_xchacha20poly1305_init_pull(
                std::ptr::from_mut(&mut state),
                header.as_bytes().as_ptr(),
                key.as_bytes().as_ptr(),
            )
        };
        if rc != 0 {
            memzero(&mut state.k);
            return Err(Error::Crypto("secretstream init_pull failed".into()));
        }
        Ok(Self {
            state,
            finalized: false,
        })
    }

    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Decrypt and authenticate one message.
    ///
    /// Returns the plaintext and the message tag. An authentication failure is
    /// reported as [`Error::Authentication`] and never as an empty success.
    pub fn pull(&mut self, ciphertext: &[u8], ad: &[u8]) -> Result<(Vec<u8>, u8)> {
        if self.finalized {
            return Err(Error::Crypto("secretstream already finalized".into()));
        }
        if ciphertext.len() < STREAM_ABYTES {
            return Err(Error::Format(format!(
                "secretstream chunk too short: {} bytes",
                ciphertext.len()
            )));
        }
        let plain_len = ciphertext.len() - STREAM_ABYTES;
        let mut out = vec![0u8; plain_len];
        let mut out_len: std::ffi::c_ulonglong = 0;
        let mut tag: u8 = 0;
        // SAFETY: `out` has exactly `ciphertext.len() - STREAM_ABYTES` bytes of
        // capacity, which is the maximum libsodium can write; all pointers are
        // valid for the lengths passed.
        let rc = unsafe {
            ffi::crypto_secretstream_xchacha20poly1305_pull(
                std::ptr::from_mut(&mut self.state),
                out.as_mut_ptr(),
                std::ptr::from_mut(&mut out_len),
                std::ptr::from_mut(&mut tag),
                ciphertext.as_ptr(),
                len_u64(ciphertext.len()),
                ad.as_ptr(),
                len_u64(ad.len()),
            )
        };
        if rc != 0 {
            out.zeroize();
            return Err(Error::Authentication(
                "secretstream message authentication failed".into(),
            ));
        }
        let written = usize::try_from(out_len)
            .map_err(|_| Error::Crypto("libsodium reported an invalid length".into()))?;
        out.truncate(written);
        if tag == TAG_FINAL {
            self.finalized = true;
        }
        Ok((out, tag))
    }
}

impl Drop for Decryptor {
    fn drop(&mut self) {
        // SAFETY: `self.state` is a valid, exclusively owned struct.
        unsafe {
            ffi::sodium_memzero(
                std::ptr::from_mut(&mut self.state).cast::<std::ffi::c_void>(),
                std::mem::size_of::<ffi::crypto_secretstream_xchacha20poly1305_state>(),
            );
        }
    }
}

/// # Errors
/// #
/// # Returns [`crate::Error`] when the operation cannot be completed; see
/// # the error taxonomy in [`crate::error`] for the individual failure modes.
/// Authenticated encryption with XChaCha20-Poly1305-IETF and a detached tag.
///
/// Returns `(ciphertext, mac)`.
pub fn aead_seal(
    key: &[u8; KEY_BYTES],
    nonce: &[u8; AEAD_NONCE_BYTES],
    ad: &[u8],
    plaintext: &[u8],
) -> Result<(Vec<u8>, Vec<u8>)> {
    ensure_init()?;
    let mut ciphertext = vec![0u8; plaintext.len()];
    let mut mac = vec![0u8; AEAD_MAC_BYTES];
    let mut mac_len: std::ffi::c_ulonglong = 0;
    // SAFETY: output buffers have the exact capacity libsodium writes to
    // (`plaintext.len()` and 16 bytes); `nsec` is NULL as required by
    // libsodium's XChaCha20-Poly1305 API.
    let rc = unsafe {
        ffi::crypto_aead_xchacha20poly1305_ietf_encrypt_detached(
            ciphertext.as_mut_ptr(),
            mac.as_mut_ptr(),
            std::ptr::from_mut(&mut mac_len),
            plaintext.as_ptr(),
            len_u64(plaintext.len()),
            ad.as_ptr(),
            len_u64(ad.len()),
            std::ptr::null(),
            nonce.as_ptr(),
            key.as_ptr(),
        )
    };
    if rc != 0 {
        ciphertext.zeroize();
        mac.zeroize();
        return Err(Error::Crypto("XChaCha20-Poly1305 encryption failed".into()));
    }
    let written = usize::try_from(mac_len)
        .map_err(|_| Error::Crypto("libsodium reported an invalid length".into()))?;
    mac.truncate(written);
    Ok((ciphertext, mac))
}

/// # Errors
/// #
/// # Returns [`crate::Error`] when the operation cannot be completed; see
/// # the error taxonomy in [`crate::error`] for the individual failure modes.
/// Authenticated decryption with XChaCha20-Poly1305-IETF and a detached tag.
///
/// A modified ciphertext, tag, nonce or associated data is rejected with
/// [`Error::Authentication`].
pub fn aead_open(
    key: &[u8; KEY_BYTES],
    nonce: &[u8; AEAD_NONCE_BYTES],
    ad: &[u8],
    ciphertext: &[u8],
    mac: &[u8],
) -> Result<Vec<u8>> {
    ensure_init()?;
    if mac.len() != AEAD_MAC_BYTES {
        return Err(Error::Format(format!(
            "AEAD tag must be {AEAD_MAC_BYTES} bytes, got {}",
            mac.len()
        )));
    }
    let mut plaintext = vec![0u8; ciphertext.len()];
    // SAFETY: `plaintext` has exactly `ciphertext.len()` bytes of capacity;
    // `nsec` is NULL as required by libsodium.
    let rc = unsafe {
        ffi::crypto_aead_xchacha20poly1305_ietf_decrypt_detached(
            plaintext.as_mut_ptr(),
            std::ptr::null_mut(),
            ciphertext.as_ptr(),
            len_u64(ciphertext.len()),
            mac.as_ptr(),
            ad.as_ptr(),
            len_u64(ad.len()),
            nonce.as_ptr(),
            key.as_ptr(),
        )
    };
    if rc != 0 {
        plaintext.zeroize();
        return Err(Error::Authentication(
            "XChaCha20-Poly1305 authentication failed".into(),
        ));
    }
    Ok(plaintext)
}

/// # Errors
/// #
/// # Returns [`crate::Error`] when the operation cannot be completed; see
/// # the error taxonomy in [`crate::error`] for the individual failure modes.
/// Derive a 256-bit subkey with HKDF-SHA256.
///
/// `info` is the purpose label: two different labels always produce two
/// different keys.
pub fn derive_subkey_hkdf_sha256(key: &[u8; KEY_BYTES], info: &[u8]) -> Result<[u8; KEY_BYTES]> {
    ensure_init()?;
    let mut out = [0u8; KEY_BYTES];
    let mut prk = [0u8; KEY_BYTES];
    // RFC 5869: an absent salt is a string of HashLen zero bytes, which is
    // what libsodium does for a zero length salt.
    // SAFETY: `prk` has 32 bytes of capacity, which is the documented size of
    // the pseudorandom key; the key material is valid for the length passed.
    let rc = unsafe {
        ffi::crypto_kdf_hkdf_sha256_extract(
            prk.as_mut_ptr(),
            std::ptr::null(),
            0,
            key.as_ptr(),
            KEY_BYTES,
        )
    };
    if rc != 0 {
        prk.zeroize();
        out.zeroize();
        return Err(Error::Crypto("HKDF-SHA256 extract failed".into()));
    }
    // SAFETY: `out` has 32 bytes of capacity, which is the requested output
    // length; the info label is valid for the length passed and `prk` holds
    // the extracted pseudorandom key.
    let rc = unsafe {
        ffi::crypto_kdf_hkdf_sha256_expand(
            out.as_mut_ptr(),
            KEY_BYTES,
            info.as_ptr(),
            info.len(),
            prk.as_ptr(),
        )
    };
    prk.zeroize();
    if rc != 0 {
        out.zeroize();
        return Err(Error::Crypto("HKDF-SHA256 expand failed".into()));
    }
    Ok(out)
}

/// # Errors
/// #
/// # Returns [`crate::Error`] when the operation cannot be completed; see
/// # the error taxonomy in [`crate::error`] for the individual failure modes.
/// Derive a 256-bit key from a passphrase with Argon2id.
///
/// Argon2id is the password based key derivation function of the same library
/// that provides the content encryption. `opslimit` and `memlimit` are the
/// cost parameters; they are stored with the derived material so that a later
/// version can raise them and re-derive the key.
pub fn derive_key_argon2id(
    passphrase: &[u8],
    salt: &[u8; ARGON2ID_SALT_BYTES],
    opslimit: u64,
    memlimit: usize,
) -> Result<[u8; KEY_BYTES]> {
    ensure_init()?;
    let mut key = [0u8; KEY_BYTES];
    // SAFETY: `key` has KEY_BYTES bytes of capacity, which is the output
    // length that is requested; the passphrase and the salt are valid for the
    // lengths that are passed.
    let rc = unsafe {
        ffi::crypto_pwhash_argon2id(
            key.as_mut_ptr(),
            len_u64(KEY_BYTES),
            passphrase.as_ptr(),
            len_u64(passphrase.len()),
            salt.as_ptr(),
            opslimit,
            memlimit,
            ffi::ARGON2ID_ALG,
        )
    };
    if rc != 0 {
        key.zeroize();
        return Err(Error::Crypto("Argon2id key derivation failed".into()));
    }
    Ok(key)
}
