//! The Zarc opaque encrypted-object container (format version 1).
//!
//! The container is a small, strictly validated, versioned binary header
//! followed by an authenticated secretstream. It intentionally contains *no*
//! descriptive metadata: no file name, no MIME type, no media dimensions, no
//! EXIF, no timestamps and no album information. The only plaintext fields are
//! the ones that are structurally required to decrypt the object.
//!
//! The full specification, including the byte-level layout and the leakage
//! analysis, lives in `docs/FILE_FORMAT.md`.
//!
//! # Layout
//!
//! ```text
//! offset  size  field
//!      0     4  magic "ZARC"
//!      4     1  format_version (1)
//!      5     2  header_length (152)
//!      7     1  flags (0)
//!      8     4  reserved (0)
//!     12    16  object_id (random; also the stored file name, hex encoded)
//!     28     1  envelope_algo (1 = XChaCha20-Poly1305-IETF)
//!     29     1  stream_algo (1 = XChaCha20-Poly1305 secretstream)
//!     30     2  reserved (0)
//!     32    24  secretstream_header
//!     56    24  envelope_nonce
//!     80     8  plaintext_length
//!     88     8  ciphertext_length
//!     96     4  chunk_size
//!    100     4  reserved (0)
//!    104    32  wrapped_dek_ciphertext
//!    136    16  wrapped_dek_mac
//!    152       end of header, ciphertext follows
//! ```
//!
//! This module contains no `unsafe` code.
//!
//! The only `unsafe` in the workspace is the libsodium FFI layer in
//! [`crate::sodium::ffi`].

#![forbid(unsafe_code)]

use zeroize::Zeroize;

use crate::error::{Error, Result};
use crate::hex;
use crate::sodium;

/// Magic marker at the start of every container.
///
/// It identifies the file as belonging to the Zarc vault format. It does not
/// reveal anything about the original media type.
pub const MAGIC: [u8; 4] = *b"ZARC";

/// Container format version implemented by this build.
pub const FORMAT_VERSION: u8 = 1;

/// Fixed size of the container header in bytes.
pub const HEADER_LEN: usize = 152;

/// The header length as it is stored inside the header itself.
const HEADER_LEN_U16: u16 = 152;

/// Compile time proof that the two constants agree.
const _: () = assert!(HEADER_LEN_U16 as usize == HEADER_LEN);

/// Default plaintext chunk size (64 KiB).
///
/// A chunk is one secretstream message. The size bounds the amount of
/// plaintext held in memory at any time and the amount of data that must be
/// re-processed when a chunk fails authentication.
pub const DEFAULT_CHUNK_SIZE: u32 = 64 * 1024;

/// Smallest accepted chunk size.
pub const MIN_CHUNK_SIZE: u32 = 1024;

/// Largest accepted chunk size.
pub const MAX_CHUNK_SIZE: u32 = 1024 * 1024;

/// Sanity bound for the plaintext size of a single object (256 TiB).
///
/// The implementation never allocates based on this value - data is always
/// streamed - but an absurd declared length is treated as malformed.
pub const MAX_OBJECT_BYTES: u64 = 1u64 << 48;

/// Envelope algorithm: XChaCha20-Poly1305-IETF.
pub const ENVELOPE_ALGO_XCHACHA20POLY1305: u8 = 1;

/// Stream algorithm: XChaCha20-Poly1305 secretstream.
pub const STREAM_ALGO_SECRETSTREAM_XCHACHA20POLY1305: u8 = 1;

/// Associated-data prefix for the secretstream chunks.
pub const STREAM_AAD_PREFIX: &[u8] = b"zarc.stream.v1";

/// Associated-data prefix for the wrapped data key.
pub const ENVELOPE_AAD_PREFIX: &[u8] = b"zarc.dek.v1";

/// Offset of the wrapped data key ciphertext inside the header.
const OFFSET_WRAPPED_DEK: usize = 104;

/// Number of header bytes covered by the envelope associated data.
///
/// The wrapped data key itself is excluded (it is the AEAD *input*), but every
/// other header byte - including the object id, the stream header and the
/// declared lengths - is bound into the envelope authentication.
const ENVELOPE_AAD_END: usize = OFFSET_WRAPPED_DEK;

/// Length of the wrapped data key ciphertext (a 256-bit key).
pub const WRAPPED_DEK_LEN: usize = 32;

/// A random, opaque object identifier.
///
/// The identifier is stored in the container header, is bound into the key
/// envelope authentication and is used verbatim as the stored file name, so a
/// container cannot be moved to a different object identity without failing.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct ObjectId([u8; 16]);

impl ObjectId {
    /// Wrap raw identifier bytes.
    #[must_use]
    pub fn from_bytes(bytes: [u8; 16]) -> Self {
        Self(bytes)
    }

    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Generate a fresh random identifier.
    pub fn random() -> Result<Self> {
        let mut bytes = [0u8; 16];
        sodium::random_bytes(&mut bytes)?;
        Ok(Self(bytes))
    }

    /// Borrow the raw identifier bytes.
    #[must_use]
    pub fn as_bytes(&self) -> &[u8; 16] {
        &self.0
    }

    /// Lowercase hexadecimal representation (32 characters).
    #[must_use]
    pub fn to_hex(&self) -> String {
        hex::encode(&self.0)
    }

    /// The stored file name of the object: the hex identifier, no extension.
    #[must_use]
    pub fn file_name(&self) -> String {
        self.to_hex()
    }

    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Parse a hexadecimal object identifier.
    pub fn from_hex(text: &str) -> Result<Self> {
        if text.len() != 32 {
            return Err(Error::Format(format!(
                "object id must be 32 hexadecimal characters, got {}",
                text.len()
            )));
        }
        let bytes = hex::decode(text)?;
        let mut id = [0u8; 16];
        id.copy_from_slice(&bytes);
        Ok(Self(id))
    }
}

/// The parsed container header.
#[derive(Clone, Debug)]
pub struct Header {
    /// Container format version.
    pub version: u8,
    /// Opaque object identifier.
    pub object_id: ObjectId,
    /// Algorithm used to wrap the data key.
    pub envelope_algo: u8,
    /// Algorithm used for the content stream.
    pub stream_algo: u8,
    /// libsodium secretstream header.
    pub secretstream_header: [u8; 24],
    /// Nonce used to wrap the data key.
    pub envelope_nonce: [u8; 24],
    /// Length of the original plaintext in bytes.
    pub plaintext_len: u64,
    /// Length of the ciphertext region in bytes.
    pub ciphertext_len: u64,
    /// Plaintext chunk size used for the content stream.
    pub chunk_size: u32,
    /// Wrapped (encrypted) data key.
    pub wrapped_dek: [u8; WRAPPED_DEK_LEN],
    /// Authentication tag of the wrapped data key.
    pub wrapped_dek_mac: [u8; 16],
}

impl Header {
    /// Serialise the header into its fixed 152-byte representation.
    #[must_use]
    pub fn encode(&self) -> [u8; HEADER_LEN] {
        let mut out = [0u8; HEADER_LEN];
        out[0..4].copy_from_slice(&MAGIC);
        out[4] = self.version;
        out[5..7].copy_from_slice(&HEADER_LEN_U16.to_le_bytes());
        // out[7] flags = 0
        // out[8..12] reserved = 0
        out[12..28].copy_from_slice(self.object_id.as_bytes());
        out[28] = self.envelope_algo;
        out[29] = self.stream_algo;
        // out[30..32] reserved = 0
        out[32..56].copy_from_slice(&self.secretstream_header);
        out[56..80].copy_from_slice(&self.envelope_nonce);
        out[80..88].copy_from_slice(&self.plaintext_len.to_le_bytes());
        out[88..96].copy_from_slice(&self.ciphertext_len.to_le_bytes());
        out[96..100].copy_from_slice(&self.chunk_size.to_le_bytes());
        // out[100..104] reserved = 0
        out[104..136].copy_from_slice(&self.wrapped_dek);
        out[136..152].copy_from_slice(&self.wrapped_dek_mac);
        out
    }

    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Parse and strictly validate a container header.
    ///
    /// Every field that influences decryption is validated here; unknown
    /// versions, unknown algorithms, non-zero reserved fields, implausible
    /// chunk sizes and inconsistent lengths are rejected instead of being
    /// "best effort" interpreted.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() < HEADER_LEN {
            return Err(Error::Format(format!(
                "header truncated: {} bytes, expected {HEADER_LEN}",
                bytes.len()
            )));
        }
        let bytes = &bytes[..HEADER_LEN];

        if bytes[0..4] != MAGIC {
            return Err(Error::Format("bad container magic".into()));
        }
        let version = bytes[4];
        if version != FORMAT_VERSION {
            return Err(Error::UnsupportedVersion(version));
        }
        let header_len = u16::from_le_bytes([bytes[5], bytes[6]]);
        if header_len as usize != HEADER_LEN {
            return Err(Error::Format(format!(
                "unexpected header length {header_len}"
            )));
        }
        if bytes[7] != 0 {
            return Err(Error::Format(format!(
                "unsupported flags 0x{:02x}",
                bytes[7]
            )));
        }
        if bytes[8..12].iter().any(|b| *b != 0) {
            return Err(Error::Format("reserved header bytes must be zero".into()));
        }
        if bytes[30..32].iter().any(|b| *b != 0) {
            return Err(Error::Format("reserved header bytes must be zero".into()));
        }
        if bytes[100..104].iter().any(|b| *b != 0) {
            return Err(Error::Format("reserved header bytes must be zero".into()));
        }

        let envelope_algo = bytes[28];
        if envelope_algo != ENVELOPE_ALGO_XCHACHA20POLY1305 {
            return Err(Error::UnsupportedAlgorithm(envelope_algo));
        }
        let stream_algo = bytes[29];
        if stream_algo != STREAM_ALGO_SECRETSTREAM_XCHACHA20POLY1305 {
            return Err(Error::UnsupportedAlgorithm(stream_algo));
        }

        let mut object_id = [0u8; 16];
        object_id.copy_from_slice(&bytes[12..28]);
        let mut secretstream_header = [0u8; 24];
        secretstream_header.copy_from_slice(&bytes[32..56]);
        let mut envelope_nonce = [0u8; 24];
        envelope_nonce.copy_from_slice(&bytes[56..80]);
        let plaintext_len = u64::from_le_bytes([
            bytes[80], bytes[81], bytes[82], bytes[83], bytes[84], bytes[85], bytes[86], bytes[87],
        ]);
        let ciphertext_len = u64::from_le_bytes([
            bytes[88], bytes[89], bytes[90], bytes[91], bytes[92], bytes[93], bytes[94], bytes[95],
        ]);
        let chunk_size = u32::from_le_bytes([bytes[96], bytes[97], bytes[98], bytes[99]]);
        let mut wrapped_dek = [0u8; WRAPPED_DEK_LEN];
        wrapped_dek.copy_from_slice(&bytes[104..136]);
        let mut wrapped_dek_mac = [0u8; 16];
        wrapped_dek_mac.copy_from_slice(&bytes[136..152]);

        if !(MIN_CHUNK_SIZE..=MAX_CHUNK_SIZE).contains(&chunk_size) {
            return Err(Error::Format(format!(
                "implausible chunk size {chunk_size}"
            )));
        }
        if plaintext_len > MAX_OBJECT_BYTES {
            return Err(Error::Format(format!(
                "implausible plaintext length {plaintext_len}"
            )));
        }
        let expected = expected_ciphertext_len(plaintext_len, chunk_size)?;
        if ciphertext_len != expected {
            return Err(Error::LengthMismatch {
                expected,
                actual: ciphertext_len,
            });
        }

        Ok(Self {
            version,
            object_id: ObjectId::from_bytes(object_id),
            envelope_algo,
            stream_algo,
            secretstream_header,
            envelope_nonce,
            plaintext_len,
            ciphertext_len,
            chunk_size,
            wrapped_dek,
            wrapped_dek_mac,
        })
    }

    /// Associated data for the content stream.
    ///
    /// It covers the *entire* header, so any modification of a header field -
    /// including the object id, the lengths and the wrapped key - invalidates
    /// the authentication of every chunk.
    #[must_use]
    pub fn stream_aad(&self) -> Vec<u8> {
        let encoded = self.encode();
        let mut aad = Vec::with_capacity(STREAM_AAD_PREFIX.len() + 80);
        aad.extend_from_slice(STREAM_AAD_PREFIX);
        // header[0..56]: magic, version, object id, algorithm ids, the
        // secretstream header.
        aad.extend_from_slice(&encoded[..56]);
        // header[80..104]: the plaintext length, the ciphertext length and the
        // chunk size.
        aad.extend_from_slice(&encoded[80..104]);
        aad
    }

    /// Associated data for the wrapped data key.
    ///
    /// It covers every header byte except the wrapped key itself, which binds
    /// the key envelope to this exact object, stream and length set.
    #[must_use]
    pub fn envelope_aad(&self) -> Vec<u8> {
        let encoded = self.encode();
        let mut aad = Vec::with_capacity(ENVELOPE_AAD_PREFIX.len() + ENVELOPE_AAD_END);
        aad.extend_from_slice(ENVELOPE_AAD_PREFIX);
        aad.extend_from_slice(&encoded[..ENVELOPE_AAD_END]);
        aad
    }

    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Number of secretstream messages in this object.
    pub fn chunk_count(&self) -> Result<u64> {
        chunk_count(self.plaintext_len, self.chunk_size)
    }

    /// Plaintext length of chunk `index` (0-based).
    #[must_use]
    pub fn chunk_plain_len(&self, index: u64) -> u64 {
        ChunkPlan::new(self.plaintext_len, self.chunk_size).chunk_plain_len(index)
    }

    /// The chunk layout of this object.
    #[must_use]
    pub fn chunk_plan(&self) -> ChunkPlan {
        ChunkPlan::new(self.plaintext_len, self.chunk_size)
    }
}

/// # Errors
/// #
/// # Returns [`crate::Error`] when the operation cannot be completed; see
/// # the error taxonomy in [`crate::error`] for the individual failure modes.
/// Number of secretstream messages needed for a plaintext of `plaintext_len`.
pub fn chunk_count(plaintext_len: u64, chunk_size: u32) -> Result<u64> {
    if chunk_size == 0 {
        return Err(Error::InvalidArgument("chunk size must not be zero".into()));
    }
    if plaintext_len == 0 {
        // An empty object is still a single (final) message so that the
        // "stream was completed" property can be authenticated.
        return Ok(1);
    }
    let chunk = u64::from(chunk_size);
    Ok(plaintext_len.div_ceil(chunk))
}

/// # Errors
/// #
/// # Returns [`crate::Error`] when the operation cannot be completed; see
/// # the error taxonomy in [`crate::error`] for the individual failure modes.
/// Ciphertext length implied by a plaintext length and chunk size.
///
/// `ciphertext = plaintext + 17 * chunk_count`; the arithmetic is checked so
/// that a hostile header cannot trigger an integer overflow.
pub fn expected_ciphertext_len(plaintext_len: u64, chunk_size: u32) -> Result<u64> {
    let chunks = chunk_count(plaintext_len, chunk_size)?;
    let overhead = chunks
        .checked_mul(sodium::STREAM_ABYTES as u64)
        .ok_or_else(|| Error::Format("ciphertext length overflow".into()))?;
    plaintext_len
        .checked_add(overhead)
        .ok_or_else(|| Error::Format("ciphertext length overflow".into()))
}

/// The chunk layout of an object.
///
/// The plan is a pure function of the plaintext length and the chunk size. It
/// is used by both the writer and the reader so that the two always agree on
/// where a chunk starts and ends.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ChunkPlan {
    plaintext_len: u64,
    chunk_size: u32,
}

impl ChunkPlan {
    /// Build a plan.
    #[must_use]
    pub fn new(plaintext_len: u64, chunk_size: u32) -> Self {
        Self {
            plaintext_len,
            chunk_size,
        }
    }

    /// Number of secretstream messages in the object.
    #[must_use]
    pub fn chunk_count(&self) -> u64 {
        chunk_count(self.plaintext_len, self.chunk_size).unwrap_or(0)
    }

    /// Plaintext length of the chunk at `index` (0-based).
    #[must_use]
    pub fn chunk_plain_len(&self, index: u64) -> u64 {
        let chunk = u64::from(self.chunk_size);
        let offset = index.saturating_mul(chunk);
        let remaining = self.plaintext_len.saturating_sub(offset);
        remaining.min(chunk)
    }

    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// The length of the encrypted region.
    ///
    /// The arithmetic is checked, so a hostile header can never trigger an
    /// integer overflow.
    pub fn ciphertext_len(&self) -> Result<u64> {
        expected_ciphertext_len(self.plaintext_len, self.chunk_size)
    }
}

/// A parsed container: header plus the raw ciphertext region.
pub struct Container {
    /// The validated header.
    pub header: Header,
    /// The ciphertext region (exactly `header.ciphertext_len` bytes).
    pub ciphertext: Vec<u8>,
}

impl Container {
    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Parse a complete container from a byte slice.
    ///
    /// The slice must contain the header followed by *exactly* the declared
    /// number of ciphertext bytes: trailing data is rejected.
    pub fn parse(bytes: &[u8]) -> Result<Self> {
        let header = Header::decode(bytes)?;
        let expected = usize::try_from(header.ciphertext_len).map_err(|_| {
            Error::Format("the ciphertext length does not fit this platform".into())
        })?;
        let available = bytes.len() - HEADER_LEN;
        if available != expected {
            return Err(Error::LengthMismatch {
                expected: header.ciphertext_len,
                actual: available as u64,
            });
        }
        Ok(Self {
            header,
            ciphertext: bytes[HEADER_LEN..].to_vec(),
        })
    }
}

impl Drop for Container {
    fn drop(&mut self) {
        self.ciphertext.zeroize();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_header() -> Header {
        Header {
            version: FORMAT_VERSION,
            object_id: ObjectId::from_bytes([7u8; 16]),
            envelope_algo: ENVELOPE_ALGO_XCHACHA20POLY1305,
            stream_algo: STREAM_ALGO_SECRETSTREAM_XCHACHA20POLY1305,
            secretstream_header: [3u8; 24],
            envelope_nonce: [5u8; 24],
            plaintext_len: 100,
            ciphertext_len: expected_ciphertext_len(100, DEFAULT_CHUNK_SIZE).unwrap(),
            chunk_size: DEFAULT_CHUNK_SIZE,
            wrapped_dek: [9u8; WRAPPED_DEK_LEN],
            wrapped_dek_mac: [11u8; 16],
        }
    }

    #[test]
    fn header_round_trips() {
        let header = sample_header();
        let encoded = header.encode();
        assert_eq!(encoded.len(), HEADER_LEN);
        let decoded = Header::decode(&encoded).unwrap();
        assert_eq!(decoded.object_id, header.object_id);
        assert_eq!(decoded.plaintext_len, header.plaintext_len);
        assert_eq!(decoded.ciphertext_len, header.ciphertext_len);
        assert_eq!(decoded.wrapped_dek, header.wrapped_dek);
        assert_eq!(decoded.wrapped_dek_mac, header.wrapped_dek_mac);
    }

    #[test]
    fn rejects_bad_magic() {
        let mut encoded = sample_header().encode();
        encoded[0] ^= 0xff;
        assert!(matches!(Header::decode(&encoded), Err(Error::Format(_))));
    }

    #[test]
    fn rejects_unknown_version() {
        let mut encoded = sample_header().encode();
        encoded[4] = 9;
        assert!(matches!(
            Header::decode(&encoded),
            Err(Error::UnsupportedVersion(9))
        ));
    }

    #[test]
    fn rejects_non_zero_reserved() {
        for offset in [8usize, 30, 100] {
            let mut encoded = sample_header().encode();
            encoded[offset] = 1;
            assert!(matches!(Header::decode(&encoded), Err(Error::Format(_))));
        }
    }

    #[test]
    fn rejects_inconsistent_lengths() {
        let mut encoded = sample_header().encode();
        encoded[88..96].copy_from_slice(&9999u64.to_le_bytes());
        assert!(matches!(
            Header::decode(&encoded),
            Err(Error::LengthMismatch { .. })
        ));
    }

    #[test]
    fn rejects_implausible_chunk_size() {
        let mut encoded = sample_header().encode();
        encoded[96..100].copy_from_slice(&0u32.to_le_bytes());
        assert!(matches!(Header::decode(&encoded), Err(Error::Format(_))));
    }

    #[test]
    fn rejects_truncated_header() {
        let encoded = sample_header().encode();
        assert!(matches!(
            Header::decode(&encoded[..10]),
            Err(Error::Format(_))
        ));
    }

    #[test]
    fn object_id_hex_round_trips() {
        let id = ObjectId::random().unwrap();
        let hex = id.to_hex();
        assert_eq!(hex.len(), 32);
        assert!(hex
            .chars()
            .all(|c| c.is_ascii_hexdigit() && !c.is_uppercase()));
        assert_eq!(ObjectId::from_hex(&hex).unwrap(), id);
        assert!(ObjectId::from_hex("abcd").is_err());
    }
}
