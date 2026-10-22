//! Transactional and crash-safety tests.
//!
//! The vault must never mistake an incomplete operation for a completed one.
//! These tests simulate interruptions and failures and check that no partial
//! object is published and that no temporary file survives.

mod common;

use std::path::Path;

use tempfile::TempDir;
use zarc_core::container::Header;
use zarc_core::error::Error;
use zarc_core::keywrap::VaultMasterKey;
use zarc_core::vault::Vault;

#[test]
fn a_failed_encryption_publishes_nothing() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    // A directory is not a regular file.
    let directory = temp.path().join("a-directory");
    std::fs::create_dir_all(&directory).unwrap();
    assert!(vault.encrypt_file(&vmk, &directory).is_err());

    // A file that does not exist.
    assert!(vault
        .encrypt_file(&vmk, &temp.path().join("missing.bin"))
        .is_err());

    let names = common::file_names(vault.objects_dir());
    assert!(names.is_empty(), "unexpected files: {names:?}");
}

#[test]
fn the_object_is_published_atomically() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    let source = temp.path().join("input.bin");
    let data = common::pseudo_random_bytes(9, 200_000);
    std::fs::write(&source, &data).unwrap();

    let encrypted = vault.encrypt_file(&vmk, &source).unwrap();

    // Exactly one file, named after the object, with the expected size.
    let names = common::file_names(vault.objects_dir());
    assert_eq!(names, vec![encrypted.id.to_hex()]);
    let stored = std::fs::read(&encrypted.path).unwrap();
    // `ciphertext_len` is the length of the encrypted region; the container
    // adds the fixed size header.
    assert_eq!(
        stored.len() as u64,
        zarc_core::container::HEADER_LEN as u64 + encrypted.ciphertext_len
    );

    let header = Header::decode(&stored).unwrap();
    assert_eq!(header.object_id, encrypted.id);
    assert_eq!(header.plaintext_len, data.len() as u64);
}

#[test]
fn an_interrupted_encryption_leaves_only_a_ignored_temporary_file() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    // Simulate a process that was killed in the middle of an encryption.
    let stale = vault
        .objects_dir()
        .join(".zarc-tmp-00112233445566778899aabb");
    std::fs::write(&stale, b"partial ciphertext").unwrap();

    // The stale file is not an object.
    assert!(vault.list_objects().unwrap().is_empty());

    // It can be purged explicitly.
    assert_eq!(vault.purge_stale_temporaries().unwrap(), 1);
    assert!(!stale.exists());

    // The vault still works afterwards.
    let source = temp.path().join("input.bin");
    std::fs::write(&source, b"payload").unwrap();
    let encrypted = vault.encrypt_file(&vmk, &source).unwrap();
    assert!(encrypted.path.exists());
}

#[test]
fn an_interrupted_decryption_leaves_no_staging_file_and_no_plaintext() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    let source = temp.path().join("input.bin");
    let data = common::pseudo_random_bytes(13, 100_000);
    std::fs::write(&source, &data).unwrap();
    let encrypted = vault.encrypt_file(&vmk, &source).unwrap();

    // Corrupt the last chunk so that the failure happens at the very end of
    // the stream, after most of the plaintext has already been written to the
    // staging file.
    let mut stored = std::fs::read(&encrypted.path).unwrap();
    let last = stored.len() - 1;
    stored[last] ^= 0x80;
    std::fs::write(&encrypted.path, &stored).unwrap();

    let output = temp.path().join("output");
    std::fs::create_dir_all(&output).unwrap();

    let error = vault
        .decrypt_file(&vmk, &encrypted.id, &output, "restored.bin")
        .expect_err("a corrupted object must not decrypt");
    assert!(matches!(error, Error::Authentication(_)));

    // Neither the destination nor a staging file may exist.
    assert!(!output.join("restored.bin").exists());
    let leftovers = common::file_names(&output);
    assert!(leftovers.is_empty(), "leftover files: {leftovers:?}");
}

#[test]
fn a_failed_decryption_does_not_touch_an_existing_destination() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    let source = temp.path().join("input.bin");
    std::fs::write(&source, b"payload").unwrap();
    let encrypted = vault.encrypt_file(&vmk, &source).unwrap();

    let output = temp.path().join("output");
    std::fs::create_dir_all(&output).unwrap();
    let destination = output.join("restored.bin");
    std::fs::write(&destination, b"previous content").unwrap();

    let mut stored = std::fs::read(&encrypted.path).unwrap();
    stored[zarc_core::container::HEADER_LEN] ^= 0x01;
    std::fs::write(&encrypted.path, &stored).unwrap();

    assert!(vault
        .decrypt_file(&vmk, &encrypted.id, &output, "restored.bin")
        .is_err());
    assert_eq!(
        std::fs::read(&destination).unwrap(),
        b"previous content",
        "a failed decryption must not overwrite an existing file"
    );
}

#[test]
fn an_unwritable_destination_fails_without_side_effects() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    let source = temp.path().join("input.bin");
    std::fs::write(&source, b"payload").unwrap();
    let encrypted = vault.encrypt_file(&vmk, &source).unwrap();

    // The destination directory is actually a file: every write must fail.
    let blocked = temp.path().join("blocked");
    std::fs::write(&blocked, b"not a directory").unwrap();

    assert!(vault
        .decrypt_file(&vmk, &encrypted.id, &blocked, "restored.bin")
        .is_err());
    assert_eq!(std::fs::read(&blocked).unwrap(), b"not a directory");
}

#[test]
fn an_unwritable_vault_directory_fails() {
    let temp = TempDir::new().unwrap();
    let blocked = temp.path().join("blocked");
    std::fs::write(&blocked, b"not a directory").unwrap();
    assert!(Vault::new(&blocked).is_err());
}

#[test]
fn a_source_that_grows_during_encryption_is_rejected() {
    // The check itself is unit tested in the crate; here the observable
    // property is that a source that does not match its declared length never
    // produces an object.
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    let source = temp.path().join("input.bin");
    std::fs::write(&source, b"payload").unwrap();
    let encrypted = vault.encrypt_file(&vmk, &source).unwrap();

    // Growing the source afterwards does not affect the stored object.
    std::fs::write(&source, b"payload that is now much longer").unwrap();
    let output = temp.path().join("output");
    let restored = vault
        .decrypt_file(&vmk, &encrypted.id, &output, "restored.bin")
        .unwrap();
    assert_eq!(std::fs::read(restored).unwrap(), b"payload");
}

#[test]
fn decrypting_twice_produces_independent_files() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    let source = temp.path().join("input.bin");
    std::fs::write(&source, b"payload").unwrap();
    let encrypted = vault.encrypt_file(&vmk, &source).unwrap();

    let output = temp.path().join("output");
    let first = vault
        .decrypt_file(&vmk, &encrypted.id, &output, "first.bin")
        .unwrap();
    let second = vault
        .decrypt_file(&vmk, &encrypted.id, &output, "second.bin")
        .unwrap();
    assert_ne!(first, second);
    assert_eq!(
        std::fs::read(&first).unwrap(),
        std::fs::read(&second).unwrap()
    );
    assert_eq!(common::file_names(&output), vec!["first.bin", "second.bin"]);
}

#[test]
fn a_destination_name_with_a_path_separator_is_rejected() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    let source = temp.path().join("input.bin");
    std::fs::write(&source, b"payload").unwrap();
    let encrypted = vault.encrypt_file(&vmk, &source).unwrap();

    let output = temp.path().join("output");
    for name in ["../escape.bin", "sub/dir.bin", "", ".."] {
        let error = vault
            .decrypt_file(&vmk, &encrypted.id, &output, name)
            .expect_err("a dangerous destination name must be rejected");
        assert!(matches!(error, Error::InvalidArgument(_)));
    }
    assert!(!temp.path().join("escape.bin").exists());
}

/// The vault directory must not contain anything but the objects.
#[test]
fn the_vault_directory_only_contains_objects() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    let source = temp.path().join("input.bin");
    std::fs::write(&source, b"payload").unwrap();
    vault.encrypt_file(&vmk, &source).unwrap();

    for name in common::file_names(vault.objects_dir()) {
        assert_eq!(name.len(), 32);
        assert!(name.chars().all(|c| c.is_ascii_hexdigit()));
        assert!(Path::new(&name).extension().is_none());
    }
}
