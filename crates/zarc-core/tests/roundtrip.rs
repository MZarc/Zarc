//! Round-trip tests: encrypt a file, decrypt it, compare byte for byte.
//!
//! Hash equality demonstrates data fidelity. It is *not* evidence that the
//! cipher is strong; that is established by the construction itself and by the
//! adversarial tests in `mutation.rs`.

mod common;

use std::path::Path;

use common::{
    file_names, pseudo_random_bytes, read_file, sha256_hex, synthetic_jpeg, synthetic_mp4,
    synthetic_png, synthetic_webp, write_file,
};
use tempfile::TempDir;
use zarc_core::keywrap::VaultMasterKey;
use zarc_core::vault::Vault;

/// Encrypt `data`, decrypt it again and return the restored bytes.
fn round_trip(
    vault: &Vault,
    vmk: &VaultMasterKey,
    source_name: &str,
    data: &[u8],
) -> (Vec<u8>, Vec<u8>) {
    let work = vault
        .objects_dir()
        .parent()
        .expect("vault has a parent")
        .join("input");
    std::fs::create_dir_all(&work).expect("cannot create the input directory");
    let source = write_file(&work, source_name, data);

    let encrypted = vault
        .encrypt_file(vmk, &source)
        .expect("encryption must succeed");
    let stored = read_file(&encrypted.path);

    let output_dir = vault
        .objects_dir()
        .parent()
        .expect("vault has a parent")
        .join("output");
    let restored_path = vault
        .decrypt_file(vmk, &encrypted.id, &output_dir, "restored.bin")
        .expect("decryption must succeed");
    let restored = read_file(&restored_path);

    (stored, restored)
}

#[test]
fn empty_file_round_trips() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    let (stored, restored) = round_trip(&vault, &vmk, "empty.bin", b"");
    assert!(restored.is_empty());
    // header + one final (empty) secretstream message
    assert_eq!(stored.len(), zarc_core::container::HEADER_LEN + 17);
}

#[test]
fn single_byte_round_trips() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    let (_, restored) = round_trip(&vault, &vmk, "one.bin", b"\x00");
    assert_eq!(restored, b"\x00");
}

#[test]
fn sizes_around_the_chunk_boundary_round_trip() {
    let temp = TempDir::new().unwrap();
    // A small chunk size keeps the test fast while still exercising the
    // multi-chunk path.
    let vault = Vault::new(temp.path().join("vault"))
        .unwrap()
        .with_chunk_size(1024)
        .unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    for size in [1023usize, 1024, 1025, 2048, 2049, 4096] {
        let data = pseudo_random_bytes(size as u64, size);
        let (stored, restored) = round_trip(&vault, &vmk, "chunky.bin", &data);
        assert_eq!(sha256_hex(&restored), sha256_hex(&data), "size {size}");
        // One 17 byte overhead per chunk, plus the 152 byte header.
        let chunks = size.div_ceil(1024).max(1);
        assert_eq!(
            stored.len(),
            zarc_core::container::HEADER_LEN + size + chunks * 17,
            "size {size}"
        );
    }
}

#[test]
fn multiple_chunks_round_trip() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault"))
        .unwrap()
        .with_chunk_size(1024)
        .unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    let data = pseudo_random_bytes(7, 5 * 1024 + 17);
    let (_, restored) = round_trip(&vault, &vmk, "multi.bin", &data);
    assert_eq!(sha256_hex(&restored), sha256_hex(&data));
}

#[test]
fn multi_megabyte_input_round_trips() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    let data = pseudo_random_bytes(11, 3 * 1024 * 1024 + 12345);
    let (_, restored) = round_trip(&vault, &vmk, "big.bin", &data);
    assert_eq!(sha256_hex(&restored), sha256_hex(&data));
}

#[test]
fn arbitrary_binary_data_round_trips() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    let data: Vec<u8> = (0u8..=255).cycle().take(70_000).collect();
    let (_, restored) = round_trip(&vault, &vmk, "binary.bin", &data);
    assert_eq!(restored, data);
}

#[test]
fn media_fixtures_round_trip() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    let fixtures: Vec<(&str, Vec<u8>)> = vec![
        ("photo.jpg", synthetic_jpeg(1, 40_000)),
        ("image.png", synthetic_png(2, 25_000)),
        ("image.webp", synthetic_webp(3, 18_000)),
        ("clip.mp4", synthetic_mp4(4, 120_000)),
    ];

    for (name, data) in fixtures {
        let (stored, restored) = round_trip(&vault, &vmk, name, &data);
        assert_eq!(sha256_hex(&restored), sha256_hex(&data), "{name}");

        // The stored object must not start with the media magic bytes and must
        // not contain the plaintext anywhere.
        assert_ne!(&stored[..4], &data[..4], "{name}: magic bytes leaked");
        assert!(
            !contains(&stored, &data[..4096.min(data.len())]),
            "{name}: plaintext found in the stored object"
        );
    }
}

#[test]
fn stored_objects_use_opaque_names_without_extensions() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    let source = write_file(temp.path(), "holiday.jpg", &synthetic_jpeg(5, 1024));
    let encrypted = vault.encrypt_file(&vmk, &source).unwrap();

    let name = encrypted
        .path
        .file_name()
        .unwrap()
        .to_string_lossy()
        .into_owned();
    assert_eq!(name.len(), 32);
    assert!(name
        .chars()
        .all(|c| c.is_ascii_hexdigit() && !c.is_uppercase()));
    assert!(!name.contains('.'));
    assert_eq!(name, encrypted.id.to_hex());
    assert_eq!(file_names(vault.objects_dir()), vec![name]);
}

#[test]
fn the_original_file_is_never_modified_or_deleted() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    let data = synthetic_jpeg(6, 4096);
    let source = write_file(temp.path(), "keep.jpg", &data);
    vault.encrypt_file(&vmk, &source).unwrap();

    assert!(source.exists());
    assert_eq!(read_file(&source), data);
}

#[test]
fn encrypting_the_same_input_twice_yields_different_ciphertext() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    let data = synthetic_png(7, 8192);
    let source = write_file(temp.path(), "same.png", &data);

    let first = vault.encrypt_file(&vmk, &source).unwrap();
    let second = vault.encrypt_file(&vmk, &source).unwrap();

    assert_ne!(
        first.id, second.id,
        "each object must get its own identifier"
    );
    assert_ne!(
        read_file(&first.path),
        read_file(&second.path),
        "fresh keys and initialisation data must produce different ciphertext"
    );
}

#[test]
fn a_second_vault_master_key_cannot_open_the_object() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();
    let other = VaultMasterKey::generate().unwrap();

    let source = write_file(temp.path(), "secret.jpg", &synthetic_jpeg(8, 2048));
    let encrypted = vault.encrypt_file(&vmk, &source).unwrap();

    let output = temp.path().join("output");
    let error = vault
        .decrypt_file(&other, &encrypted.id, &output, "stolen.bin")
        .expect_err("a foreign key must not decrypt the object");
    assert!(
        matches!(error, zarc_core::Error::Authentication(_)),
        "unexpected error: {error}"
    );
    assert!(!output.join("stolen.bin").exists());
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

/// Helper so the test above can be reused with a `Path`.
#[allow(dead_code)]
fn exists(path: &Path) -> bool {
    path.exists()
}
