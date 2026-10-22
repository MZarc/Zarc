//! Key lifecycle tests.
//!
//! These cover the parts of the key hierarchy that live in the Rust core. The
//! Android Keystore half of the hierarchy (wrapping and unwrapping the vault
//! master key, key invalidation, StrongBox) is covered by the on-device tests
//! in `android/app/src/androidTest`.

mod common;

use std::path::Path;

use tempfile::TempDir;
use zarc_core::container::{Header, HEADER_LEN};
use zarc_core::error::Error;
use zarc_core::keywrap::{unwrap_data_key, wrap_data_key, VaultMasterKey};
use zarc_core::sodium;
use zarc_core::vault::Vault;

#[test]
fn key_generation_produces_valid_key_material() {
    let first = VaultMasterKey::generate().unwrap();
    let second = VaultMasterKey::generate().unwrap();
    assert_eq!(first.as_bytes().len(), 32);
    assert_ne!(first.as_bytes(), second.as_bytes());
    assert!(first.as_bytes().iter().any(|byte| *byte != 0));

    let mut random = [0u8; 32];
    sodium::random_bytes(&mut random).unwrap();
    assert!(random.iter().any(|byte| *byte != 0));
}

#[test]
fn the_vault_master_key_must_have_the_expected_length() {
    assert!(VaultMasterKey::from_slice(&[0u8; 31]).is_err());
    assert!(VaultMasterKey::from_slice(&[0u8; 33]).is_err());
    assert!(VaultMasterKey::from_slice(&[7u8; 32]).is_ok());
}

#[test]
fn different_objects_receive_independent_data_keys() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    let first = temp.path().join("a.bin");
    let second = temp.path().join("b.bin");
    std::fs::write(&first, b"first payload").unwrap();
    std::fs::write(&second, b"second payload").unwrap();

    let a = vault.encrypt_file(&vmk, &first).unwrap();
    let b = vault.encrypt_file(&vmk, &second).unwrap();

    let header_a = Header::decode(&std::fs::read(&a.path).unwrap()).unwrap();
    let header_b = Header::decode(&std::fs::read(&b.path).unwrap()).unwrap();

    let dek_a = unwrap_data_key(
        &vmk,
        &zarc_core::keywrap::WrappedDataKey {
            nonce: header_a.envelope_nonce,
            ciphertext: header_a.wrapped_dek.to_vec(),
            mac: header_a.wrapped_dek_mac,
        },
        &header_a.envelope_aad(),
    )
    .unwrap();
    let dek_b = unwrap_data_key(
        &vmk,
        &zarc_core::keywrap::WrappedDataKey {
            nonce: header_b.envelope_nonce,
            ciphertext: header_b.wrapped_dek.to_vec(),
            mac: header_b.wrapped_dek_mac,
        },
        &header_b.envelope_aad(),
    )
    .unwrap();

    assert_ne!(
        dek_a.as_bytes(),
        dek_b.as_bytes(),
        "each object must use an independent data key"
    );
    assert_ne!(header_a.envelope_nonce, header_b.envelope_nonce);
}

#[test]
fn the_vault_master_key_survives_a_process_restart() {
    let temp = TempDir::new().unwrap();
    let vmk_bytes = [0x5au8; 32];

    let source = temp.path().join("input.bin");
    std::fs::write(&source, b"persistent payload").unwrap();

    // "First process": create the vault and encrypt.
    let object_id = {
        let vault = Vault::new(temp.path().join("vault")).unwrap();
        let vmk = VaultMasterKey::from_bytes(vmk_bytes);
        vault.encrypt_file(&vmk, &source).unwrap().id
    };

    // "Second process": a fresh vault object and a fresh key instance that is
    // reconstructed from the unwrapped key material.
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::from_bytes(vmk_bytes);
    let restored = vault
        .decrypt_file(&vmk, &object_id, &temp.path().join("out"), "restored.bin")
        .expect("the object must still decrypt after a restart");
    assert_eq!(std::fs::read(restored).unwrap(), b"persistent payload");
}

#[test]
fn a_corrupt_wrapped_key_envelope_is_rejected() {
    let vmk = VaultMasterKey::generate().unwrap();
    let dek = zarc_core::keywrap::DataEncryptionKey::generate().unwrap();
    let nonce = [0x11u8; 24];
    let aad = b"zarc.dek.v1|test";

    let mut wrapped = wrap_data_key(&vmk, &dek, &nonce, aad).unwrap();

    // A modified ciphertext.
    let mut broken = wrapped.clone();
    broken.ciphertext[0] ^= 0x01;
    assert!(matches!(
        unwrap_data_key(&vmk, &broken, aad),
        Err(Error::Authentication(_))
    ));

    // A modified tag.
    let mut broken = wrapped.clone();
    broken.mac[0] ^= 0x01;
    assert!(matches!(
        unwrap_data_key(&vmk, &broken, aad),
        Err(Error::Authentication(_))
    ));

    // A modified nonce.
    let mut broken = wrapped.clone();
    broken.nonce[0] ^= 0x01;
    assert!(matches!(
        unwrap_data_key(&vmk, &broken, aad),
        Err(Error::Authentication(_))
    ));

    // A wrong master key.
    let other = VaultMasterKey::generate().unwrap();
    assert!(matches!(
        unwrap_data_key(&other, &wrapped, aad),
        Err(Error::Authentication(_))
    ));

    // Wrong associated data.
    assert!(matches!(
        unwrap_data_key(&vmk, &wrapped, b"other"),
        Err(Error::Authentication(_))
    ));

    wrapped.ciphertext.truncate(31);
    assert!(matches!(
        unwrap_data_key(&vmk, &wrapped, aad),
        Err(Error::Format(_))
    ));
}

#[test]
fn a_missing_object_fails_closed() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();
    let missing = zarc_core::container::ObjectId::from_hex(&"a".repeat(32)).unwrap();

    let error = vault
        .decrypt_file(&vmk, &missing, &temp.path().join("out"), "x.bin")
        .expect_err("a missing object must not decrypt");
    assert!(matches!(error, Error::NotFound(_)));
}

#[test]
fn the_unwrapped_vault_master_key_is_never_written_to_disk() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();
    let key_bytes = *vmk.as_bytes();

    let source = temp.path().join("input.bin");
    std::fs::write(&source, common::pseudo_random_bytes(3, 5000)).unwrap();
    vault.encrypt_file(&vmk, &source).unwrap();

    // Scan every file in the vault directory (and the whole temp tree) for the
    // raw key bytes.
    for entry in walk(temp.path()) {
        let bytes = std::fs::read(&entry).unwrap_or_default();
        assert!(
            !contains(&bytes, &key_bytes),
            "the vault master key was found in {}",
            entry.display()
        );
    }
}

#[test]
fn the_rust_core_does_not_log_anything() {
    let sources = Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
    let offenders = find_logging_calls(&sources);
    assert!(
        offenders.is_empty(),
        "the vault core must not log (found {:?})",
        offenders
    );
}

/// Recursively collect the files below `dir`.
fn walk(dir: &Path) -> Vec<std::path::PathBuf> {
    let mut files = Vec::new();
    let Ok(entries) = std::fs::read_dir(dir) else {
        return files;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            files.extend(walk(&path));
        } else {
            files.push(path);
        }
    }
    files
}

/// Whether `haystack` contains `needle`.
fn contains(haystack: &[u8], needle: &[u8]) -> bool {
    if needle.is_empty() || needle.len() > haystack.len() {
        return false;
    }
    haystack
        .windows(needle.len())
        .any(|window| window == needle)
}

/// Find calls that would print to the console in the library sources.
fn find_logging_calls(dir: &Path) -> Vec<String> {
    let mut offenders = Vec::new();
    for path in walk(dir) {
        if path.extension().and_then(|e| e.to_str()) != Some("rs") {
            continue;
        }
        let Ok(content) = std::fs::read_to_string(&path) else {
            continue;
        };
        for (number, line) in content.lines().enumerate() {
            let trimmed = line.trim_start();
            if trimmed.starts_with("println!")
                || trimmed.starts_with("eprintln!")
                || trimmed.starts_with("dbg!")
                || trimmed.contains("log::")
                || trimmed.contains("tracing::")
            {
                offenders.push(format!("{}:{}: {}", path.display(), number + 1, trimmed));
            }
        }
    }
    offenders
}

/// The header of an object must never contain the plaintext.
#[test]
fn the_header_carries_no_descriptive_metadata() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    let source = temp.path().join("secret-photo-name.jpg");
    std::fs::write(&source, b"payload").unwrap();
    let encrypted = vault.encrypt_file(&vmk, &source).unwrap();

    let bytes = std::fs::read(&encrypted.path).unwrap();
    let header = &bytes[..HEADER_LEN];

    // The original file name must not appear anywhere in the object.
    assert!(!contains(&bytes, b"secret-photo-name"));
    assert!(!contains(&bytes, b".jpg"));
    assert!(!contains(&bytes, b"image/jpeg"));
    // The header must not contain the payload either.
    assert!(!contains(header, b"payload"));
}
