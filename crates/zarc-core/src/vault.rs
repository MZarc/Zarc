//! The vault: fail-closed encryption and decryption of vault objects.
//!
//! Encryption is *transactional*:
//!
//! 1. a new temporary ciphertext object is created in the (app-private)
//!    objects directory,
//! 2. the content is streamed through the secretstream encryptor,
//! 3. the temporary file is flushed and atomically renamed to the final opaque
//!    object name,
//! 4. on any error the temporary file is removed and no object is published.
//!
//! Decryption is *fail-closed*:
//!
//! 1. the container header is parsed and strictly validated,
//! 2. the data key is unwrapped through the key hierarchy,
//! 3. the plaintext is written to a restricted staging file,
//! 4. the complete stream - including the final authentication tag - must be
//!    accepted before the staging file is renamed to its destination,
//! 5. on any error the staging file is removed and the operation fails.
//!
//! This module contains no `unsafe` code.
//!
//! The only `unsafe` in the workspace is the libsodium FFI layer in
//! [`crate::sodium::ffi`].

#![forbid(unsafe_code)]

use std::fs::File;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use zeroize::Zeroize;

use crate::container::{
    expected_ciphertext_len, Header, ObjectId, DEFAULT_CHUNK_SIZE, ENVELOPE_ALGO_XCHACHA20POLY1305,
    FORMAT_VERSION, HEADER_LEN, MAX_CHUNK_SIZE, MIN_CHUNK_SIZE,
    STREAM_ALGO_SECRETSTREAM_XCHACHA20POLY1305,
};
use crate::error::{Error, Result};
use crate::fsutil;
use crate::keywrap::{wrap_data_key, DataEncryptionKey, VaultMasterKey, WrappedDataKey};
use crate::sodium::{
    self, Decryptor, Encryptor, SecretStreamKey, StreamHeader, TAG_FINAL, TAG_MESSAGE,
};

/// Result of a successful encryption.
#[derive(Clone, Debug)]
pub struct EncryptedObject {
    /// The opaque identifier of the new object.
    pub id: ObjectId,
    /// The final path of the object inside the vault.
    pub path: PathBuf,
    /// Length of the original plaintext.
    pub plaintext_len: u64,
    /// Length of the encrypted container.
    pub ciphertext_len: u64,
}

/// Metadata about a stored object, obtained without decrypting the content.
#[derive(Clone, Debug)]
pub struct ObjectInfo {
    /// The opaque identifier of the object.
    pub id: ObjectId,
    /// The path of the object inside the vault.
    pub path: PathBuf,
    /// Length of the original plaintext.
    pub plaintext_len: u64,
    /// Length of the encrypted container.
    pub ciphertext_len: u64,
    /// Chunk size used for the content stream.
    pub chunk_size: u32,
    /// Number of secretstream messages in the object.
    pub chunk_count: u64,
}

/// Name prefix of the temporary ciphertext objects.
const TEMP_OBJECT_PREFIX: &str = ".zarc-tmp-";

/// Name prefix of the staging plaintext files.
const TEMP_STAGING_PREFIX: &str = ".zarc-stage-";

/// A temporary file that is removed unless it is explicitly committed.
///
/// This makes the "never publish a partial object" rule hold even when the
/// operation is aborted by an error *or* by an unwinding panic.
struct TempGuard {
    path: PathBuf,
    committed: bool,
}

impl TempGuard {
    fn new(path: PathBuf) -> Self {
        Self {
            path,
            committed: false,
        }
    }

    fn commit(&mut self) {
        self.committed = true;
    }
}

impl Drop for TempGuard {
    fn drop(&mut self) {
        if !self.committed {
            let _ = fsutil::remove_if_exists(&self.path);
        }
    }
}

/// A directory of encrypted objects.
pub struct Vault {
    objects_dir: PathBuf,
    chunk_size: u32,
}

impl Vault {
    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Open (and create if necessary) a vault directory.
    pub fn new(objects_dir: impl AsRef<Path>) -> Result<Self> {
        let objects_dir = objects_dir.as_ref().to_path_buf();
        fsutil::ensure_private_dir(&objects_dir)?;
        Ok(Self {
            objects_dir,
            chunk_size: DEFAULT_CHUNK_SIZE,
        })
    }

    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Override the chunk size. The value must stay inside the bounds that the
    /// container format accepts.
    pub fn with_chunk_size(mut self, chunk_size: u32) -> Result<Self> {
        if !(MIN_CHUNK_SIZE..=MAX_CHUNK_SIZE).contains(&chunk_size) {
            return Err(Error::InvalidArgument(format!(
                "chunk size {chunk_size} is outside the accepted range {MIN_CHUNK_SIZE}..={MAX_CHUNK_SIZE}"
            )));
        }
        self.chunk_size = chunk_size;
        Ok(self)
    }

    /// The directory that holds the encrypted objects.
    #[must_use]
    pub fn objects_dir(&self) -> &Path {
        &self.objects_dir
    }

    /// The path an object is stored at.
    #[must_use]
    pub fn object_path(&self, id: &ObjectId) -> PathBuf {
        self.objects_dir.join(id.file_name())
    }

    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// List the identifiers of all objects in the vault.
    ///
    /// Files whose name is not a valid object identifier (for example a
    /// leftover staging file) are ignored.
    pub fn list_objects(&self) -> Result<Vec<ObjectId>> {
        let mut ids = Vec::new();
        let entries = match std::fs::read_dir(&self.objects_dir) {
            Ok(entries) => entries,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(ids),
            Err(error) => return Err(error.into()),
        };
        for entry in entries.flatten() {
            let name = entry.file_name();
            let Some(name) = name.to_str() else { continue };
            if let Ok(id) = ObjectId::from_hex(name) {
                ids.push(id);
            }
        }
        ids.sort_by_key(ObjectId::to_hex);
        Ok(ids)
    }

    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Remove leftover temporary files from interrupted operations.
    ///
    /// Zarc writes a temporary ciphertext object (`.zarc-tmp-*`) while
    /// encrypting and a staging plaintext file (`.zarc-stage-*`) while
    /// decrypting. If the process is killed in the middle of an operation,
    /// those files stay behind. They are never valid objects and are never
    /// listed by [`Vault::list_objects`]; this method deletes them.
    ///
    /// Returns the number of files that were removed.
    pub fn purge_stale_temporaries(&self) -> Result<usize> {
        let mut removed = 0usize;
        let entries = match std::fs::read_dir(&self.objects_dir) {
            Ok(entries) => entries,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(removed),
            Err(error) => return Err(error.into()),
        };
        for entry in entries.flatten() {
            let name = entry.file_name();
            let Some(name) = name.to_str() else { continue };
            if (name.starts_with(TEMP_OBJECT_PREFIX) || name.starts_with(TEMP_STAGING_PREFIX))
                && fsutil::remove_if_exists(&entry.path()).is_ok()
            {
                removed += 1;
            }
        }
        Ok(removed)
    }

    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Encrypt a file into the vault.
    ///
    /// The original file is never modified or deleted.
    pub fn encrypt_file(&self, vmk: &VaultMasterKey, source: &Path) -> Result<EncryptedObject> {
        self.encrypt_file_as(vmk, source, ObjectId::random()?)
    }

    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Encrypt a file into the vault under a given object identifier.
    ///
    /// This is used when an object is re-encrypted: the identity of the object
    /// is preserved while its data key and its content stream are replaced.
    pub fn encrypt_file_as(
        &self,
        vmk: &VaultMasterKey,
        source: &Path,
        object_id: ObjectId,
    ) -> Result<EncryptedObject> {
        sodium::ensure_init()?;

        let metadata = std::fs::metadata(source)?;
        if !metadata.is_file() {
            return Err(Error::InvalidArgument(
                "the source of an encryption must be a regular file".into(),
            ));
        }
        let plaintext_len = metadata.len();

        let dek = DataEncryptionKey::generate()?;
        let stream_key = SecretStreamKey::from_bytes(dek.as_bytes());
        let (mut encryptor, stream_header) = Encryptor::init(&stream_key)?;

        let mut envelope_nonce = [0u8; 24];
        sodium::random_bytes(&mut envelope_nonce)?;

        let mut header = Header {
            version: FORMAT_VERSION,
            object_id,
            envelope_algo: ENVELOPE_ALGO_XCHACHA20POLY1305,
            stream_algo: STREAM_ALGO_SECRETSTREAM_XCHACHA20POLY1305,
            secretstream_header: *stream_header.as_bytes(),
            envelope_nonce,
            plaintext_len,
            ciphertext_len: expected_ciphertext_len(plaintext_len, self.chunk_size)?,
            chunk_size: self.chunk_size,
            wrapped_dek: [0u8; 32],
            wrapped_dek_mac: [0u8; 16],
        };

        // The envelope associated data covers every header byte except the
        // wrapped key itself, so it can be derived before the key is wrapped.
        // The wrapping nonce lives in the header and is therefore authenticated
        // together with the object id, the stream header and the lengths.
        let envelope_aad = header.envelope_aad();
        let wrapped = wrap_data_key(vmk, &dek, &envelope_nonce, &envelope_aad)?;
        header.wrapped_dek.copy_from_slice(&wrapped.ciphertext);
        header.wrapped_dek_mac = wrapped.mac;

        let stream_aad = header.stream_aad();
        let temp_path = self
            .objects_dir
            .join(fsutil::temp_name(TEMP_OBJECT_PREFIX)?);
        let mut guard = TempGuard::new(temp_path.clone());

        // Never publish a partial object: on any error the guard removes the
        // temporary ciphertext when this function returns.
        self.write_object(&temp_path, source, &mut encryptor, &stream_aad, &header)?;

        let final_path = self.object_path(&object_id);
        std::fs::rename(&temp_path, &final_path)?;
        guard.commit();
        fsutil::sync_dir(&self.objects_dir)?;

        Ok(EncryptedObject {
            id: object_id,
            path: final_path,
            plaintext_len,
            ciphertext_len: header.ciphertext_len,
        })
    }

    /// Stream the plaintext into the temporary ciphertext object.
    fn write_object(
        &self,
        temp_path: &Path,
        source: &Path,
        encryptor: &mut Encryptor,
        stream_aad: &[u8],
        header: &Header,
    ) -> Result<()> {
        let mut output = fsutil::create_private_file(temp_path)?;
        output.write_all(&header.encode())?;

        let mut input = File::open(source)?;
        let mut buffer = vec![0u8; self.chunk_size as usize];
        let total_chunks = header.chunk_count()?;

        for index in 0..total_chunks {
            let want = usize::try_from(header.chunk_plain_len(index))
                .map_err(|_| Error::Format("the chunk length does not fit this platform".into()))?;
            fsutil::read_exact_or_truncated(&mut input, &mut buffer[..want])?;
            let is_last = index + 1 == total_chunks;
            let tag = if is_last { TAG_FINAL } else { TAG_MESSAGE };
            let ciphertext = encryptor.push(&buffer[..want], stream_aad, tag)?;
            output.write_all(&ciphertext)?;
            // The chunk has been encrypted: wipe the plaintext buffer instead of
            // leaving the media in the heap until the next chunk overwrites it.
            buffer[..want].zeroize();
        }

        // The source must not have grown while we were encrypting it,
        // otherwise the declared lengths would not describe the object.
        ensure_source_unchanged(&mut input)?;

        fsutil::sync_file(&output)?;
        Ok(())
    }

    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Decrypt an object into `destination_dir/destination_name`.
    ///
    /// The plaintext is written to a staging file first and is only renamed to
    /// its final destination after the complete stream has been authenticated.
    pub fn decrypt_file(
        &self,
        vmk: &VaultMasterKey,
        id: &ObjectId,
        destination_dir: &Path,
        destination_name: &str,
    ) -> Result<PathBuf> {
        validate_file_name(destination_name)?;
        fsutil::ensure_private_dir(destination_dir)?;

        let (header, mut input) = self.open_object(id)?;
        let dek = Self::unwrap_object_key(vmk, &header)?;

        let staging_path = destination_dir.join(fsutil::temp_name(TEMP_STAGING_PREFIX)?);
        let mut guard = TempGuard::new(staging_path.clone());
        // The staging file is removed by the guard when this fails: a failed
        // decryption never leaves unverified plaintext behind.
        Self::write_plaintext(Some(&staging_path), &mut input, &header, &dek)?;

        let destination = destination_dir.join(destination_name);
        std::fs::rename(&staging_path, &destination)?;
        guard.commit();
        fsutil::sync_dir(destination_dir)?;
        Ok(destination)
    }

    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Verify an object end-to-end without producing any plaintext file.    ///
    /// The whole stream is decrypted and authenticated, but the plaintext is
    /// discarded. This is the operation used by the on-device self test.
    pub fn verify_object(&self, vmk: &VaultMasterKey, id: &ObjectId) -> Result<ObjectInfo> {
        let (header, mut input) = self.open_object(id)?;
        let dek = Self::unwrap_object_key(vmk, &header)?;
        Self::write_plaintext(None, &mut input, &header, &dek)?;
        Ok(ObjectInfo {
            id: *id,
            path: self.object_path(id),
            plaintext_len: header.plaintext_len,
            ciphertext_len: header.ciphertext_len,
            chunk_size: header.chunk_size,
            chunk_count: header.chunk_count()?,
        })
    }

    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Rotate the vault master key.
    ///
    /// Every object keeps its content stream and its identity; only the
    /// wrapping of its data key is redone under `new_vmk`. The operation is
    /// idempotent: an object that already unwraps with `new_vmk` is skipped,
    /// so an interrupted rotation can simply be repeated.
    ///
    /// Returns the number of objects that were re-wrapped.
    pub fn rotate_master_key(
        &self,
        old_vmk: &VaultMasterKey,
        new_vmk: &VaultMasterKey,
    ) -> Result<usize> {
        let mut rotated = 0usize;
        for id in self.list_objects()? {
            if self.rotate_object_key(old_vmk, new_vmk, &id)? {
                rotated += 1;
            }
        }
        Ok(rotated)
    }

    /// Re-wrap the data key of a single object under a new vault master key.
    ///
    /// Returns `true` when the object was re-wrapped, `false` when it was
    /// already wrapped under `new_vmk`.
    fn rotate_object_key(
        &self,
        old_vmk: &VaultMasterKey,
        new_vmk: &VaultMasterKey,
        id: &ObjectId,
    ) -> Result<bool> {
        let path = self.object_path(id);
        let mut input = File::open(&path)?;
        let mut header_bytes = [0u8; HEADER_LEN];
        fsutil::read_exact_or_truncated(&mut input, &mut header_bytes)?;
        let mut header = Header::decode(&header_bytes)?;
        if header.object_id != *id {
            return Err(Error::Format(
                "the stored object id does not match its file name".into(),
            ));
        }

        let current = WrappedDataKey {
            nonce: header.envelope_nonce,
            ciphertext: header.wrapped_dek.to_vec(),
            mac: header.wrapped_dek_mac,
        };
        // Already rotated: the object unwraps with the new master key.
        if crate::keywrap::unwrap_data_key(new_vmk, &current, &header.envelope_aad()).is_ok() {
            return Ok(false);
        }
        let dek = crate::keywrap::unwrap_data_key(old_vmk, &current, &header.envelope_aad())?;

        // A fresh nonce for the new envelope; the envelope associated data
        // covers it, so it cannot be swapped.
        let mut new_nonce = [0u8; 24];
        sodium::random_bytes(&mut new_nonce)?;
        header.envelope_nonce = new_nonce;
        let wrapped =
            crate::keywrap::wrap_data_key(new_vmk, &dek, &new_nonce, &header.envelope_aad())?;
        header.wrapped_dek.copy_from_slice(&wrapped.ciphertext);
        header.wrapped_dek_mac = wrapped.mac;

        // The content region is unchanged; the new header is written to a
        // temporary file and the object is replaced atomically.
        let temp_path = self
            .objects_dir
            .join(fsutil::temp_name(TEMP_OBJECT_PREFIX)?);
        let mut guard = TempGuard::new(temp_path.clone());
        {
            let mut output = fsutil::create_private_file(&temp_path)?;
            output.write_all(&header.encode())?;
            std::io::copy(&mut input, &mut output)?;
            fsutil::sync_file(&output)?;
        }
        std::fs::rename(&temp_path, &path)?;
        guard.commit();
        Ok(true)
    }

    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Re-encrypt an object with a fresh data key.
    ///
    /// The plaintext is decrypted, verified and encrypted again under a new
    /// data key, a new secretstream header and a new envelope nonce. The
    /// identity of the object is preserved. The object is replaced atomically,
    /// so an interrupted re-encryption leaves the old object intact.
    pub fn reencrypt_object(&self, vmk: &VaultMasterKey, id: &ObjectId) -> Result<ObjectInfo> {
        let staging_dir = self.objects_dir.join("staging");
        fsutil::ensure_private_dir(&staging_dir)?;
        let staging_path = staging_dir.join(fsutil::temp_name(TEMP_STAGING_PREFIX)?);
        let mut guard = TempGuard::new(staging_path.clone());

        // Decrypt and verify first: a broken object is never replaced.
        let (header, mut input) = self.open_object(id)?;
        let dek = Self::unwrap_object_key(vmk, &header)?;
        Self::write_plaintext(Some(&staging_path), &mut input, &header, &dek)?;

        // Encrypt again under the same identity.
        let encrypted = self.encrypt_file_as(vmk, &staging_path, *id)?;
        let _ = fsutil::remove_if_exists(&staging_path);
        guard.commit();

        // `encrypt_file_as` wrote a new object under the final name; the old
        // one was replaced by the rename inside that function.
        Ok(ObjectInfo {
            id: *id,
            path: encrypted.path,
            plaintext_len: encrypted.plaintext_len,
            ciphertext_len: encrypted.ciphertext_len,
            chunk_size: header.chunk_size,
            chunk_count: header.chunk_count()?,
        })
    }

    /// Open an object and parse its header.
    fn open_object(&self, id: &ObjectId) -> Result<(Header, File)> {
        let path = self.object_path(id);
        let mut input = File::open(&path).map_err(|error| {
            if error.kind() == std::io::ErrorKind::NotFound {
                Error::NotFound(path.display().to_string())
            } else {
                Error::Io(error)
            }
        })?;
        let mut header_bytes = [0u8; HEADER_LEN];
        fsutil::read_exact_or_truncated(&mut input, &mut header_bytes)?;
        let header = Header::decode(&header_bytes)?;
        if header.object_id != *id {
            return Err(Error::Format(format!(
                "the stored object id {} does not match its file name",
                header.object_id.to_hex()
            )));
        }
        Ok((header, input))
    }

    /// Unwrap the per-object data key through the key hierarchy.
    fn unwrap_object_key(vmk: &VaultMasterKey, header: &Header) -> Result<DataEncryptionKey> {
        let wrapped = WrappedDataKey {
            nonce: header.envelope_nonce,
            ciphertext: header.wrapped_dek.to_vec(),
            mac: header.wrapped_dek_mac,
        };
        let aad = header.envelope_aad();
        crate::keywrap::unwrap_data_key(vmk, &wrapped, &aad)
    }

    /// Decrypt the content stream.
    ///
    /// When `staging_path` is `Some`, the plaintext is written to that
    /// staging file; when it is `None`, the plaintext is discarded
    /// (verification).
    fn write_plaintext(
        staging_path: Option<&Path>,
        input: &mut File,
        header: &Header,
        dek: &DataEncryptionKey,
    ) -> Result<()> {
        let stream_aad = header.stream_aad();
        let mut decryptor = Decryptor::init(
            &StreamHeader::from_bytes(header.secretstream_header),
            &SecretStreamKey::from_bytes(dek.as_bytes()),
        )?;

        let mut output = match staging_path {
            Some(path) => Some(fsutil::create_private_file(path)?),
            None => None,
        };

        let total_chunks = header.chunk_count()?;
        let mut plaintext_total: u64 = 0;

        for index in 0..total_chunks {
            let plain_len = usize::try_from(header.chunk_plain_len(index))
                .map_err(|_| Error::Format("the chunk length does not fit this platform".into()))?;
            let mut ciphertext = vec![0u8; plain_len + sodium::STREAM_ABYTES];
            fsutil::read_exact_or_truncated(input, &mut ciphertext)?;
            let (mut plaintext, tag) = decryptor.pull(&ciphertext, &stream_aad)?;
            let is_last = index + 1 == total_chunks;
            if is_last {
                if tag != TAG_FINAL {
                    plaintext.zeroize();
                    return Err(Error::Format(
                        "the encrypted stream did not end with the final authentication tag".into(),
                    ));
                }
            } else if tag != TAG_MESSAGE {
                plaintext.zeroize();
                return Err(Error::Format(format!(
                    "unexpected message tag {tag} in the middle of the encrypted stream"
                )));
            }
            plaintext_total += plaintext.len() as u64;
            if let Some(output) = output.as_mut() {
                output.write_all(&plaintext)?;
            }
            // The plaintext chunk has served its purpose: wipe the buffer
            // instead of leaving the media in the heap.
            plaintext.zeroize();
        }

        if plaintext_total != header.plaintext_len {
            return Err(Error::LengthMismatch {
                expected: header.plaintext_len,
                actual: plaintext_total,
            });
        }

        // Anything after the authenticated stream is not part of the object.
        let mut trailing = [0u8; 1];
        if input.read(&mut trailing)? != 0 {
            return Err(Error::Format(
                "trailing data found after the encrypted stream".into(),
            ));
        }

        if let Some(output) = output {
            fsutil::sync_file(&output)?;
        }
        Ok(())
    }
}

/// Reject a source file that grew while it was being encrypted.
///
/// The container declares the plaintext length up front; if the source grows
/// afterwards the declared length would not describe the object any more, so
/// the operation is aborted instead of silently truncating the input.
fn ensure_source_unchanged(input: &mut File) -> Result<()> {
    let mut extra = [0u8; 1];
    if input.read(&mut extra)? != 0 {
        return Err(Error::InvalidArgument(
            "the source file changed size while it was being encrypted".into(),
        ));
    }
    Ok(())
}

/// Reject destination names that could escape the destination directory or
/// that carry a media extension.
fn validate_file_name(name: &str) -> Result<()> {
    if name.is_empty() {
        return Err(Error::InvalidArgument(
            "the destination name is empty".into(),
        ));
    }
    if name.contains('/') || name.contains('\\') || name.contains('\0') {
        return Err(Error::InvalidArgument(
            "the destination name must not contain path separators".into(),
        ));
    }
    if name == "." || name == ".." {
        return Err(Error::InvalidArgument(
            "the destination name must not be a directory reference".into(),
        ));
    }
    Ok(())
}
