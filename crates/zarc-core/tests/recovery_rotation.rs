//! Tests for the recovery envelope and the key rotation operations.

mod common;

use tempfile::TempDir;
use zarc_core::keywrap::VaultMasterKey;
use zarc_core::recovery::{RecoveryEnvelope, RecoveryWitness};
use zarc_core::vault::Vault;

const PASSPHRASE: &[u8] = b"correct horse battery staple";

/// Encrypt `count` objects and return their identifiers.
fn fill(vault: &Vault, vmk: &VaultMasterKey, count: usize) -> Vec<String> {
    let mut ids = Vec::new();
    for index in 0..count {
        let source = vault.objects_dir().join(format!("input-{index}.bin"));
        std::fs::write(&source, common::pseudo_random_bytes(index as u64, 3000)).unwrap();
        ids.push(vault.encrypt_file(vmk, &source).unwrap().id.to_hex());
        let _ = std::fs::remove_file(&source);
    }
    ids
}

/// The plaintext of an object, decrypted into memory.
fn plaintext_of(vault: &Vault, vmk: &VaultMasterKey, id: &str, work: &TempDir) -> Vec<u8> {
    let object = zarc_core::container::ObjectId::from_hex(id).unwrap();
    let path = vault
        .decrypt_file(vmk, &object, work.path(), "restored.bin")
        .unwrap();
    std::fs::read(path).unwrap()
}

#[test]
fn the_recovery_envelope_returns_the_same_master_key() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();
    let ids = fill(&vault, &vmk, 2);

    let envelope = RecoveryEnvelope::create(&vmk, PASSPHRASE).unwrap();
    let witness = RecoveryWitness::create(&vmk).unwrap();

    // The device is lost: only the envelope and the passphrase are left.
    let recovered = envelope.open(PASSPHRASE).unwrap();
    witness.verify(&recovered).unwrap();

    for id in &ids {
        let object = zarc_core::container::ObjectId::from_hex(id).unwrap();
        vault
            .decrypt_file(&recovered, &object, &temp.path().join("out"), "r.bin")
            .expect("the recovered key must open the object");
    }
}

#[test]
fn a_wrong_passphrase_does_not_open_the_vault() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();
    let ids = fill(&vault, &vmk, 1);

    let envelope = RecoveryEnvelope::create(&vmk, PASSPHRASE).unwrap();
    assert!(envelope.open(b"wrong horse battery staple").is_err());

    // The vault is untouched: the original key still works.
    let object = zarc_core::container::ObjectId::from_hex(&ids[0]).unwrap();
    assert!(vault
        .decrypt_file(&vmk, &object, &temp.path().join("out"), "r.bin")
        .is_ok());
}

#[test]
fn a_tampered_witness_rejects_the_recovered_key() {
    let _temp = TempDir::new().unwrap();
    let vmk = VaultMasterKey::generate().unwrap();
    let other = VaultMasterKey::generate().unwrap();
    let envelope = RecoveryEnvelope::create(&vmk, PASSPHRASE).unwrap();
    let witness = RecoveryWitness::create(&vmk).unwrap();

    let recovered = envelope.open(PASSPHRASE).unwrap();
    assert!(witness.verify(&recovered).is_ok());
    // A key that is not the vault master key must not pass the witness.
    assert!(witness.verify(&other).is_err());
}

#[test]
fn rotating_the_master_key_keeps_the_content_and_changes_the_envelope() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();
    let new_vmk = VaultMasterKey::generate().unwrap();
    let ids = fill(&vault, &vmk, 3);

    let before: Vec<Vec<u8>> = ids
        .iter()
        .map(|id| {
            std::fs::read(vault.object_path(&zarc_core::container::ObjectId::from_hex(id).unwrap()))
                .unwrap()
        })
        .collect();

    let rotated = vault.rotate_master_key(&vmk, &new_vmk).unwrap();
    assert_eq!(rotated, 3);

    let after: Vec<Vec<u8>> = ids
        .iter()
        .map(|id| {
            std::fs::read(vault.object_path(&zarc_core::container::ObjectId::from_hex(id).unwrap()))
                .unwrap()
        })
        .collect();

    for (old, new) in before.iter().zip(after.iter()) {
        // The header changed (new nonce, new wrapped key) ...
        assert_ne!(old, new);
        // ... but the encrypted content is untouched: the same data key, the
        // same stream.
        assert_eq!(&old[152..], &new[152..]);
    }

    // The new key opens the objects, the old one does not.
    for id in &ids {
        let object = zarc_core::container::ObjectId::from_hex(id).unwrap();
        assert!(vault
            .decrypt_file(&new_vmk, &object, &temp.path().join("out"), "r.bin")
            .is_ok());
        assert!(vault
            .decrypt_file(&vmk, &object, &temp.path().join("out"), "r.bin")
            .is_err());
    }
}

#[test]
fn the_rotation_is_idempotent() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();
    let new_vmk = VaultMasterKey::generate().unwrap();
    fill(&vault, &vmk, 2);

    assert_eq!(vault.rotate_master_key(&vmk, &new_vmk).unwrap(), 2);
    // A second run finds nothing to do: the objects already use the new key.
    assert_eq!(vault.rotate_master_key(&vmk, &new_vmk).unwrap(), 0);
}

#[test]
fn a_rotation_with_the_wrong_old_key_changes_nothing() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();
    let wrong = VaultMasterKey::generate().unwrap();
    let new_vmk = VaultMasterKey::generate().unwrap();
    let ids = fill(&vault, &vmk, 2);

    let before = std::fs::read(
        vault.object_path(&zarc_core::container::ObjectId::from_hex(&ids[0]).unwrap()),
    )
    .unwrap();
    assert!(vault.rotate_master_key(&wrong, &new_vmk).is_err());
    let after = std::fs::read(
        vault.object_path(&zarc_core::container::ObjectId::from_hex(&ids[0]).unwrap()),
    )
    .unwrap();
    assert_eq!(before, after, "a failed rotation must not touch the object");
}

#[test]
fn reencrypting_an_object_replaces_the_content_but_keeps_the_identity() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();
    let ids = fill(&vault, &vmk, 1);
    let id = ids[0].clone();
    let object = zarc_core::container::ObjectId::from_hex(&id).unwrap();

    let before = std::fs::read(vault.object_path(&object)).unwrap();
    let plaintext_before = plaintext_of(&vault, &vmk, &id, &temp);

    let info = vault.reencrypt_object(&vmk, &object).unwrap();
    assert_eq!(info.id, object);
    assert_eq!(info.plaintext_len, plaintext_before.len() as u64);

    let after = std::fs::read(vault.object_path(&object)).unwrap();
    assert_ne!(before, after, "the content must be encrypted again");
    assert_ne!(&before[152..], &after[152..], "the content region changes");
    let plaintext_after = plaintext_of(&vault, &vmk, &id, &temp);
    assert_eq!(plaintext_before, plaintext_after);

    // The file name is unchanged: the identity of the object is preserved.
    assert_eq!(
        vault
            .object_path(&object)
            .file_name()
            .unwrap()
            .to_string_lossy(),
        id
    );
}

#[test]
fn a_failed_reencryption_leaves_the_object_intact() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();
    let ids = fill(&vault, &vmk, 1);
    let object = zarc_core::container::ObjectId::from_hex(&ids[0]).unwrap();

    let before = std::fs::read(vault.object_path(&object)).unwrap();
    // Corrupt the object so that the decryption fails.
    let mut corrupted = before.clone();
    let last = corrupted.len() - 1;
    corrupted[last] ^= 0x80;
    std::fs::write(vault.object_path(&object), &corrupted).unwrap();

    assert!(vault.reencrypt_object(&vmk, &object).is_err());
    let after = std::fs::read(vault.object_path(&object)).unwrap();
    assert_eq!(after, corrupted, "the failed object must not be replaced");
}

#[test]
fn the_vault_directory_stays_clean_after_a_rotation() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();
    let new_vmk = VaultMasterKey::generate().unwrap();
    fill(&vault, &vmk, 2);
    vault.rotate_master_key(&vmk, &new_vmk).unwrap();

    let names = common::file_names(vault.objects_dir());
    assert_eq!(names.len(), 2, "unexpected files: {names:?}");
    for name in names {
        assert_eq!(name.len(), 32);
    }
}
