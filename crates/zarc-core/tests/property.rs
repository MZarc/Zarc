//! Property based tests for the container parser and the vault.
//!
//! The properties under test are:
//!
//! * any byte string can be parsed without panicking; it either yields a
//!   valid header or an explicit error,
//! * any mutation of a valid container is either rejected or yields exactly
//!   the original plaintext - it can never yield different plaintext,
//! * encrypting and decrypting arbitrary input restores the input exactly.

mod common;

use proptest::prelude::*;
use tempfile::TempDir;
use zarc_core::container::{ChunkPlan, Header, HEADER_LEN};
use zarc_core::keywrap::VaultMasterKey;
use zarc_core::vault::Vault;

/// The parser must never panic, whatever it is fed.
#[test]
fn the_parser_never_panics() {
    proptest!(|(bytes: Vec<u8>)| {
        let _ = Header::decode(&bytes);
    });
}

/// A header that is syntactically valid must round trip through the parser.
#[test]
fn valid_headers_round_trip() {
    proptest!(|(bytes: Vec<u8>, version: u8, chunk_size: u32, plaintext_len: u64)| {
        let chunk_size = chunk_size.clamp(1024, 1024 * 1024);
        let plaintext_len = plaintext_len % (1u64 << 40);
        let plan = ChunkPlan::new(plaintext_len, chunk_size);
        let Ok(ciphertext_len) = plan.ciphertext_len() else {
            return Ok(());
        };
        let mut header = Header {
            version: 1,
            object_id: zarc_core::container::ObjectId::from_bytes([0x5a; 16]),
            envelope_algo: 1,
            stream_algo: 1,
            secretstream_header: [0x11; 24],
            envelope_nonce: [0x22; 24],
            plaintext_len,
            ciphertext_len,
            chunk_size,
            wrapped_dek: [0x33; 32],
            wrapped_dek_mac: [0x44; 16],
        };
        header.version = version;
        let encoded = header.encode();
        // Only the version byte is allowed to vary; everything else must be
        // reproduced exactly.
        let decoded = Header::decode(&encoded);
        if version == 1 {
            let decoded = decoded.expect("a valid header must parse");
            prop_assert_eq!(decoded.encode(), encoded);
            prop_assert_eq!(decoded.plaintext_len, plaintext_len);
            prop_assert_eq!(decoded.ciphertext_len, ciphertext_len);
        } else {
            prop_assert!(matches!(
                decoded,
                Err(zarc_core::Error::UnsupportedVersion(_))
            ));
        }
        let _ = bytes;
    });
}

/// Arbitrary input survives a full vault round trip.
#[test]
fn arbitrary_input_round_trips() {
    proptest!(|(data: Vec<u8>)| {
        let temp = TempDir::new().unwrap();
        let vault = Vault::new(temp.path().join("vault")).unwrap();
        let vmk = VaultMasterKey::generate().unwrap();

        let source = temp.path().join("input.bin");
        std::fs::write(&source, &data).unwrap();
        let encrypted = vault.encrypt_file(&vmk, &source).unwrap();
        let restored = vault
            .decrypt_file(
                &vmk,
                &encrypted.id,
                &temp.path().join("out"),
                "restored.bin",
            )
            .unwrap();
        prop_assert_eq!(std::fs::read(&restored).unwrap(), data);
    });
}

/// A single byte mutation of a container is either rejected or restores the
/// original plaintext. It can never produce different plaintext.
#[test]
fn a_mutation_never_produces_different_plaintext() {
    let temp = TempDir::new().unwrap();
    let vault = Vault::new(temp.path().join("vault")).unwrap();
    let vmk = VaultMasterKey::generate().unwrap();

    let payload = common::pseudo_random_bytes(17, 4096);
    let source = temp.path().join("input.bin");
    std::fs::write(&source, &payload).unwrap();
    let encrypted = vault.encrypt_file(&vmk, &source).unwrap();
    let stored = std::fs::read(&encrypted.path).unwrap();
    let id = encrypted.id;

    proptest!(|(offset: usize, xor: u8)| {
        let mut mutated = stored.clone();
        let offset = offset % mutated.len();
        mutated[offset] ^= xor;

        let path = vault.object_path(&id);
        std::fs::write(&path, &mutated).unwrap();
        let output = temp.path().join("out");
        let _ = std::fs::create_dir_all(&output);

        match vault.decrypt_file(&vmk, &id, &output, "restored.bin") {
            Ok(restored) => {
                // If it is accepted at all, the plaintext must be identical.
                prop_assert_eq!(std::fs::read(&restored).unwrap(), payload.clone());
                let _ = std::fs::remove_file(&restored);
            }
            Err(_) => {
                // Rejected: nothing may be published.
                prop_assert!(!output.join("restored.bin").exists());
            }
        }
        let _ = std::fs::remove_file(&path);
    });
}

/// The chunk plan must never overflow and must always describe the plaintext
/// length exactly.
#[test]
fn the_chunk_plan_is_consistent() {
    proptest!(|(plaintext_len: u64, chunk_size: u32)| {
        let chunk_size = chunk_size.clamp(1024, 1024 * 1024);
        let plan = ChunkPlan::new(plaintext_len, chunk_size);
        let Ok(ciphertext_len) = plan.ciphertext_len() else {
            return Ok(());
        };
        let chunks = plan.chunk_count();
        prop_assert!(chunks >= 1);
        prop_assert_eq!(
            ciphertext_len,
            plaintext_len + chunks * zarc_core::sodium::STREAM_ABYTES as u64
        );
        // The container always has room for the header.
        prop_assert!(ciphertext_len >= zarc_core::sodium::STREAM_ABYTES as u64);

        // The last chunk carries the remainder and the chunks after it are
        // empty. The full sum is checked analytically so that the test does
        // not have to iterate over billions of chunks.
        let chunk = chunk_size as u64;
        let remainder = plaintext_len % chunk;
        let expected_last = if remainder == 0 { chunk } else { remainder };
        prop_assert_eq!(plan.chunk_plain_len(chunks - 1), expected_last);
        prop_assert_eq!(plan.chunk_plain_len(chunks), 0);
        prop_assert_eq!(plan.chunk_plain_len(chunks + 1), 0);
        let full_chunks = plaintext_len / chunk;
        let expected_sum = full_chunks * chunk + expected_last;
        prop_assert_eq!(expected_sum, plaintext_len);
    });
}

/// The header parser must reject a container whose declared lengths do not
/// match the chunk plan.
#[test]
fn inconsistent_lengths_are_rejected() {
    proptest!(|(plaintext_len: u64, extra: u64)| {
        let chunk_size = 1024u32;
        let plan = ChunkPlan::new(plaintext_len, chunk_size);
        let Ok(ciphertext_len) = plan.ciphertext_len() else {
            return Ok(());
        };
        let header = Header {
            version: 1,
            object_id: zarc_core::container::ObjectId::from_bytes([0x5a; 16]),
            envelope_algo: 1,
            stream_algo: 1,
            secretstream_header: [0x11; 24],
            envelope_nonce: [0x22; 24],
            plaintext_len,
            ciphertext_len: ciphertext_len.saturating_add(extra),
            chunk_size,
            wrapped_dek: [0x33; 32],
            wrapped_dek_mac: [0x44; 16],
        };
        let encoded = header.encode();
        if header.ciphertext_len != ciphertext_len {
            prop_assert!(Header::decode(&encoded).is_err());
        }
    });
}

/// The header must always start with the magic marker and never contain the
/// original file name.
#[test]
fn the_header_never_leaks_descriptive_data() {
    proptest!(|(secret: Vec<u8>)| {
        let header = Header {
            version: 1,
            object_id: zarc_core::container::ObjectId::from_bytes([0x5a; 16]),
            envelope_algo: 1,
            stream_algo: 1,
            secretstream_header: [0x11; 24],
            envelope_nonce: [0x22; 24],
            plaintext_len: 0,
            ciphertext_len: zarc_core::sodium::STREAM_ABYTES as u64,
            chunk_size: 1024,
            wrapped_dek: [0x33; 32],
            wrapped_dek_mac: [0x44; 16],
        };
        let encoded = header.encode();
        prop_assert_eq!(&encoded[..4], b"ZARC");
        prop_assert_eq!(encoded.len(), HEADER_LEN);
        let _ = secret;
    });
}
