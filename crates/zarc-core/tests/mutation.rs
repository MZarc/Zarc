//! Adversarial tests: every invalid object must be rejected, and no invalid
//! object may ever produce a committed plaintext file.
//!
//! The tests deliberately do *not* assume that every mutation has to fail at
//! the same point: a modified length field is caught by the container parser,
//! a modified ciphertext byte by the secretstream authentication, a modified
//! header by the associated data. What matters is that the operation fails and
//! that nothing is published.

mod common;

use std::path::{Path, PathBuf};

use tempfile::TempDir;
use zarc_core::container::{Header, HEADER_LEN};
use zarc_core::error::Error;
use zarc_core::keywrap::VaultMasterKey;
use zarc_core::sodium::{Decryptor, Encryptor, SecretStreamKey, TAG_FINAL, TAG_MESSAGE};
use zarc_core::vault::Vault;

/// A vault, its master key and a stored object with several chunks.
struct Fixture {
    _temp: TempDir,
    vault: Vault,
    vmk: VaultMasterKey,
    stored: Vec<u8>,
    output: PathBuf,
}

impl Fixture {
    fn new() -> Self {
        let temp = TempDir::new().expect("temp dir");
        let vault = Vault::new(temp.path().join("vault"))
            .expect("vault")
            .with_chunk_size(1024)
            .expect("chunk size");
        let vmk = VaultMasterKey::generate().expect("vmk");

        let data = common::pseudo_random_bytes(42, 3 * 1024 + 100);
        let source = temp.path().join("input.bin");
        std::fs::write(&source, &data).expect("write source");
        let encrypted = vault.encrypt_file(&vmk, &source).expect("encrypt");
        let stored = std::fs::read(&encrypted.path).expect("read stored");

        let output = temp.path().join("output");
        std::fs::create_dir_all(&output).expect("output dir");

        Self {
            _temp: temp,
            vault,
            vmk,
            stored,
            output,
        }
    }

    /// Write `bytes` as an object and try to decrypt it.
    ///
    /// The file name always matches the object id inside the (possibly
    /// modified) header, so that the identity check does not mask the
    /// cryptographic checks. The function returns the decryption error.
    fn try_decrypt(&self, bytes: &[u8]) -> Error {
        let id = match Header::decode(bytes) {
            Ok(header) => header.object_id,
            Err(_) => {
                // The header is unusable: fall back to the original identity so
                // that the parser is the component under test.
                match Header::decode(&self.stored) {
                    Ok(header) => header.object_id,
                    Err(_) => unreachable!("the original object must parse"),
                }
            }
        };
        let path = self.vault.object_path(&id);
        std::fs::write(&path, bytes).expect("write mutated object");

        let destination = self.output.join("restored.bin");
        let result = self
            .vault
            .decrypt_file(&self.vmk, &id, &self.output, "restored.bin");

        // Nothing may be published, whatever the outcome was.
        assert!(
            !destination.exists(),
            "a failed decryption must not publish a plaintext file"
        );
        let leftovers: Vec<String> = common::file_names(&self.output);
        assert!(
            leftovers.is_empty(),
            "a failed decryption must not leave staging files behind: {leftovers:?}"
        );

        let _ = std::fs::remove_file(&path);
        match result {
            Ok(_) => panic!("the mutated object was accepted"),
            Err(error) => error,
        }
    }

    /// Decrypt the unmodified object and check that it still works.
    fn verify_original(&self) {
        let id = Header::decode(&self.stored).unwrap().object_id;
        let path = self.vault.object_path(&id);
        std::fs::write(&path, &self.stored).unwrap();
        let restored = self
            .vault
            .decrypt_file(&self.vmk, &id, &self.output, "restored.bin")
            .expect("the original object must decrypt");
        assert!(restored.exists());
        let _ = std::fs::remove_file(&path);
    }
}

/// Flip one bit of the byte at `offset`.
fn flip_bit(bytes: &mut [u8], offset: usize) {
    bytes[offset] ^= 0x01;
}

#[test]
fn a_modified_magic_is_rejected() {
    let fixture = Fixture::new();
    let mut mutated = fixture.stored.clone();
    flip_bit(&mut mutated, 0);
    assert!(matches!(fixture.try_decrypt(&mutated), Error::Format(_)));
}

#[test]
fn a_modified_version_is_rejected() {
    let fixture = Fixture::new();
    let mut mutated = fixture.stored.clone();
    mutated[4] = 2;
    assert!(matches!(
        fixture.try_decrypt(&mutated),
        Error::UnsupportedVersion(2)
    ));
}

#[test]
fn a_modified_header_length_is_rejected() {
    let fixture = Fixture::new();
    let mut mutated = fixture.stored.clone();
    mutated[5] = 0x99;
    assert!(matches!(fixture.try_decrypt(&mutated), Error::Format(_)));
}

#[test]
fn non_zero_flags_are_rejected() {
    let fixture = Fixture::new();
    let mut mutated = fixture.stored.clone();
    mutated[7] = 0x01;
    assert!(matches!(fixture.try_decrypt(&mutated), Error::Format(_)));
}

#[test]
fn non_zero_reserved_bytes_are_rejected() {
    let fixture = Fixture::new();
    for offset in [8usize, 30, 100] {
        let mut mutated = fixture.stored.clone();
        mutated[offset] = 0x80;
        assert!(
            matches!(fixture.try_decrypt(&mutated), Error::Format(_)),
            "reserved byte at {offset} was accepted"
        );
    }
}

#[test]
fn an_unknown_envelope_algorithm_is_rejected() {
    let fixture = Fixture::new();
    let mut mutated = fixture.stored.clone();
    mutated[28] = 9;
    assert!(matches!(
        fixture.try_decrypt(&mutated),
        Error::UnsupportedAlgorithm(9)
    ));
}

#[test]
fn an_unknown_stream_algorithm_is_rejected() {
    let fixture = Fixture::new();
    let mut mutated = fixture.stored.clone();
    mutated[29] = 9;
    assert!(matches!(
        fixture.try_decrypt(&mutated),
        Error::UnsupportedAlgorithm(9)
    ));
}

#[test]
fn a_modified_object_id_is_rejected() {
    let fixture = Fixture::new();
    let mut mutated = fixture.stored.clone();
    mutated[12] ^= 0xff;
    // The object id is part of the envelope associated data, so the key
    // envelope no longer authenticates.
    assert!(matches!(
        fixture.try_decrypt(&mutated),
        Error::Authentication(_)
    ));
}

#[test]
fn a_file_whose_name_does_not_match_its_object_id_is_rejected() {
    let fixture = Fixture::new();
    let id = Header::decode(&fixture.stored).unwrap().object_id;
    let wrong = zarc_core::container::ObjectId::from_hex(&"0".repeat(32)).unwrap();
    assert_ne!(id, wrong);

    // The content is valid but it is stored under the wrong identity.
    let path = fixture.vault.object_path(&wrong);
    std::fs::write(&path, &fixture.stored).unwrap();
    let error = fixture
        .vault
        .decrypt_file(&fixture.vmk, &wrong, &fixture.output, "restored.bin")
        .expect_err("a mismatching file name must be rejected");
    assert!(matches!(error, Error::Format(_)));
    assert!(!fixture.output.join("restored.bin").exists());
    let _ = std::fs::remove_file(&path);
}

#[test]
fn a_modified_stream_header_is_rejected() {
    let fixture = Fixture::new();
    let mut mutated = fixture.stored.clone();
    flip_bit(&mut mutated, 32);
    assert!(matches!(
        fixture.try_decrypt(&mutated),
        Error::Authentication(_)
    ));
}

#[test]
fn a_modified_envelope_nonce_is_rejected() {
    let fixture = Fixture::new();
    let mut mutated = fixture.stored.clone();
    flip_bit(&mut mutated, 56);
    assert!(matches!(
        fixture.try_decrypt(&mutated),
        Error::Authentication(_)
    ));
}

#[test]
fn a_modified_plaintext_length_is_rejected() {
    let fixture = Fixture::new();
    let mut mutated = fixture.stored.clone();
    mutated[80] ^= 0x01;
    // The declared lengths are cross-checked, so this is caught by the parser.
    assert!(matches!(
        fixture.try_decrypt(&mutated),
        Error::LengthMismatch { .. }
    ));
}

#[test]
fn a_modified_ciphertext_length_is_rejected() {
    let fixture = Fixture::new();
    let mut mutated = fixture.stored.clone();
    mutated[88] ^= 0x01;
    assert!(matches!(
        fixture.try_decrypt(&mutated),
        Error::LengthMismatch { .. }
    ));
}

#[test]
fn an_absurd_plaintext_length_is_rejected() {
    let fixture = Fixture::new();
    let mut mutated = fixture.stored.clone();
    mutated[80..88].copy_from_slice(&u64::MAX.to_le_bytes());
    // Either the sanity bound or the overflow check rejects it; both are
    // explicit format errors.
    assert!(matches!(
        fixture.try_decrypt(&mutated),
        Error::Format(_) | Error::LengthMismatch { .. }
    ));
}

#[test]
fn a_modified_chunk_size_is_rejected() {
    let fixture = Fixture::new();
    let mut mutated = fixture.stored.clone();
    mutated[96..100].copy_from_slice(&0u32.to_le_bytes());
    assert!(matches!(fixture.try_decrypt(&mutated), Error::Format(_)));
}

#[test]
fn a_modified_wrapped_key_is_rejected() {
    let fixture = Fixture::new();
    let mut mutated = fixture.stored.clone();
    flip_bit(&mut mutated, 104);
    assert!(matches!(
        fixture.try_decrypt(&mutated),
        Error::Authentication(_)
    ));
}

#[test]
fn a_modified_wrapped_key_tag_is_rejected() {
    let fixture = Fixture::new();
    let mut mutated = fixture.stored.clone();
    flip_bit(&mut mutated, 136);
    assert!(matches!(
        fixture.try_decrypt(&mutated),
        Error::Authentication(_)
    ));
}

#[test]
fn a_modified_ciphertext_byte_is_rejected() {
    let fixture = Fixture::new();
    let mut mutated = fixture.stored.clone();
    flip_bit(&mut mutated, HEADER_LEN);
    assert!(matches!(
        fixture.try_decrypt(&mutated),
        Error::Authentication(_)
    ));
}

#[test]
fn a_modified_final_tag_is_rejected() {
    let fixture = Fixture::new();
    let mut mutated = fixture.stored.clone();
    let last = mutated.len() - 1;
    flip_bit(&mut mutated, last);
    assert!(matches!(
        fixture.try_decrypt(&mutated),
        Error::Authentication(_)
    ));
}

#[test]
fn truncation_at_many_offsets_is_rejected() {
    let fixture = Fixture::new();
    let offsets = [
        0usize,
        1,
        HEADER_LEN - 1,
        HEADER_LEN,
        HEADER_LEN + 1,
        HEADER_LEN + 1024,
        fixture.stored.len() - 1,
        fixture.stored.len() - 17,
        fixture.stored.len() - 18,
    ];
    for offset in offsets {
        let mutated = fixture.stored[..offset].to_vec();
        let error = fixture.try_decrypt(&mutated);
        assert!(
            matches!(
                error,
                Error::Format(_) | Error::Authentication(_) | Error::LengthMismatch { .. }
            ),
            "truncation at {offset} produced an unexpected error: {error}"
        );
    }
}

#[test]
fn appended_data_is_rejected() {
    let fixture = Fixture::new();
    let mut mutated = fixture.stored.clone();
    mutated.extend_from_slice(b"trailing garbage");
    let error = fixture.try_decrypt(&mutated);
    assert!(
        matches!(error, Error::Format(_)),
        "unexpected error: {error}"
    );
}

#[test]
fn a_removed_chunk_is_rejected() {
    let fixture = Fixture::new();
    let header = Header::decode(&fixture.stored).unwrap();
    let chunk_size = header.chunk_size as usize;
    let chunk_len = chunk_size + 17;

    // Remove the second chunk.
    let mut mutated = fixture.stored[..HEADER_LEN + chunk_len].to_vec();
    mutated.extend_from_slice(&fixture.stored[HEADER_LEN + 2 * chunk_len..]);
    let error = fixture.try_decrypt(&mutated);
    assert!(
        matches!(
            error,
            Error::Authentication(_) | Error::LengthMismatch { .. } | Error::Format(_)
        ),
        "unexpected error: {error}"
    );
}

#[test]
fn a_duplicated_chunk_is_rejected() {
    let fixture = Fixture::new();
    let header = Header::decode(&fixture.stored).unwrap();
    let chunk_len = header.chunk_size as usize + 17;

    let mut mutated = fixture.stored[..HEADER_LEN + chunk_len].to_vec();
    mutated.extend_from_slice(&fixture.stored[HEADER_LEN..HEADER_LEN + chunk_len]);
    mutated.extend_from_slice(&fixture.stored[HEADER_LEN + chunk_len..]);
    let error = fixture.try_decrypt(&mutated);
    assert!(
        matches!(
            error,
            Error::Authentication(_) | Error::LengthMismatch { .. } | Error::Format(_)
        ),
        "unexpected error: {error}"
    );
}

#[test]
fn reordered_chunks_are_rejected() {
    let fixture = Fixture::new();
    let header = Header::decode(&fixture.stored).unwrap();
    let chunk_len = header.chunk_size as usize + 17;

    // Swap the first two chunks. The total length stays valid, so the
    // rejection has to come from the secretstream itself.
    let mut mutated = fixture.stored.clone();
    let a = fixture.stored[HEADER_LEN..HEADER_LEN + chunk_len].to_vec();
    let b = fixture.stored[HEADER_LEN + chunk_len..HEADER_LEN + 2 * chunk_len].to_vec();
    mutated[HEADER_LEN..HEADER_LEN + chunk_len].copy_from_slice(&b);
    mutated[HEADER_LEN + chunk_len..HEADER_LEN + 2 * chunk_len].copy_from_slice(&a);

    let error = fixture.try_decrypt(&mutated);
    assert!(
        matches!(error, Error::Authentication(_)),
        "unexpected error: {error}"
    );
}

#[test]
fn the_stream_itself_detects_reordering_duplication_and_truncation() {
    let key = SecretStreamKey::generate().unwrap();
    let (mut encryptor, header) = Encryptor::init(&key).unwrap();
    let ad = b"aad";
    let c1 = encryptor.push(b"first chunk", ad, TAG_MESSAGE).unwrap();
    let c2 = encryptor.push(b"second chunk", ad, TAG_MESSAGE).unwrap();
    let c3 = encryptor.push(b"third chunk", ad, TAG_FINAL).unwrap();

    // Reordered: the very first pull already fails, because every message is
    // authenticated with a key that depends on its position in the stream.
    let mut decryptor = Decryptor::init(&header, &key).unwrap();
    assert!(decryptor.pull(&c2, ad).is_err());

    // Duplicated.
    let mut decryptor = Decryptor::init(&header, &key).unwrap();
    assert!(decryptor.pull(&c1, ad).is_ok());
    assert!(decryptor.pull(&c1, ad).is_err());

    // Removed.
    let mut decryptor = Decryptor::init(&header, &key).unwrap();
    assert!(decryptor.pull(&c1, ad).is_ok());
    assert!(decryptor.pull(&c3, ad).is_err());

    // Truncated.
    let mut decryptor = Decryptor::init(&header, &key).unwrap();
    assert!(decryptor.pull(&c1, ad).is_ok());
    assert!(decryptor.pull(&c2, ad).is_ok());
    assert!(decryptor.pull(&c3[..c3.len() - 1], ad).is_err());

    // The complete stream still decrypts.
    let mut decryptor = Decryptor::init(&header, &key).unwrap();
    assert_eq!(decryptor.pull(&c1, ad).unwrap().0, b"first chunk");
    assert_eq!(decryptor.pull(&c2, ad).unwrap().0, b"second chunk");
    let (plain, tag) = decryptor.pull(&c3, ad).unwrap();
    assert_eq!(plain, b"third chunk");
    assert_eq!(tag, TAG_FINAL);
}

#[test]
fn a_wrong_data_key_cannot_decrypt_the_stream() {
    let key = SecretStreamKey::generate().unwrap();
    let other = SecretStreamKey::generate().unwrap();
    let (mut encryptor, header) = Encryptor::init(&key).unwrap();
    let ciphertext = encryptor.push(b"payload", b"aad", TAG_FINAL).unwrap();

    let mut decryptor = Decryptor::init(&header, &other).unwrap();
    assert!(matches!(
        decryptor.pull(&ciphertext, b"aad"),
        Err(Error::Authentication(_))
    ));
}

#[test]
fn the_original_object_still_decrypts_after_all_the_mutations() {
    let fixture = Fixture::new();
    let mut mutated = fixture.stored.clone();
    flip_bit(&mut mutated, HEADER_LEN + 10);
    let _ = fixture.try_decrypt(&mutated);
    fixture.verify_original();
}

/// The mutation suite must not leave any file behind in the vault directory.
#[test]
fn the_vault_directory_is_clean_after_the_mutation_suite() {
    let fixture = Fixture::new();
    let mut mutated = fixture.stored.clone();
    flip_bit(&mut mutated, HEADER_LEN);
    let _ = fixture.try_decrypt(&mutated);
    let names = common::file_names(fixture.vault.objects_dir());
    assert!(names.is_empty(), "leftover files: {names:?}");
}

/// Helper that keeps the unused import warning away while documenting intent.
#[allow(dead_code)]
fn is_file(path: &Path) -> bool {
    path.is_file()
}
