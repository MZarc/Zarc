#![no_main]
//! Fuzz target: the key envelope must never unwrap a key for data that was
//! not produced by the matching wrapping operation.

use libfuzzer_sys::fuzz_target;
use zarc_core::keywrap::{unwrap_data_key, wrap_data_key, DataEncryptionKey, VaultMasterKey};

fuzz_target!(|data: &[u8]| {
    let vmk = VaultMasterKey::from_bytes([0x24; 32]);
    let dek = DataEncryptionKey::from_bytes([0x36; 32]);
    let nonce = [0x48; 24];

    let wrapped = match wrap_data_key(&vmk, &dek, &nonce, data) {
        Ok(value) => value,
        Err(_) => return,
    };

    let mut tampered = wrapped.clone();
    if !tampered.ciphertext.is_empty() {
        // Flip a bit that the fuzzer controls.
        let index = data.first().copied().unwrap_or(0) as usize % tampered.ciphertext.len();
        tampered.ciphertext[index] ^= 0x01;
    }

    match unwrap_data_key(&vmk, &tampered, data) {
        Ok(key) => {
            // Unwrapping tampered material must never succeed.
            panic!("a tampered key envelope was unwrapped");
        }
        Err(_) => {}
    }

    // The untouched envelope must unwrap to the original key.
    let key = unwrap_data_key(&vmk, &wrapped, data).expect("the original envelope must unwrap");
    assert_eq!(key.as_bytes(), dek.as_bytes());
});
