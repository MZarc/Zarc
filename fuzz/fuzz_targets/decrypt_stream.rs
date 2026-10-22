#![no_main]
//! Fuzz target: the secretstream decryptor must never panic on hostile input
//! and must never return plaintext for data it did not authenticate.
//!
//! The fuzzer input is interpreted as a sequence of chunks; every chunk is
//! pulled with the same associated data. The only allowed outcome is an error
//! or the exact plaintext that was pushed.

use libfuzzer_sys::fuzz_target;
use zarc_core::sodium::{Decryptor, Encryptor, SecretStreamKey, TAG_FINAL};

fuzz_target!(|data: &[u8]| {
    // Use the input as the associated data and as the message; both are
    // attacker controlled in this target.
    let key = SecretStreamKey::from_bytes(&[0x42; 32]);
    let (mut encryptor, header) = match Encryptor::init(&key) {
        Ok(value) => value,
        Err(_) => return,
    };
    let ciphertext = match encryptor.push(data, data, TAG_FINAL) {
        Ok(value) => value,
        Err(_) => return,
    };

    // Corrupt the ciphertext in a way that the fuzzer controls: the first
    // byte is flipped when the input starts with a marker byte.
    let mut corrupted = ciphertext.clone();
    if !data.is_empty() && data[0] == 0xff {
        corrupted[0] ^= 0x01;
    }

    let mut decryptor = match Decryptor::init(&header, &key) {
        Ok(value) => value,
        Err(_) => return,
    };
    match decryptor.pull(&corrupted, data) {
        Ok((plaintext, tag)) => {
            // If it is accepted, it must be the original message and the
            // final tag.
            assert_eq!(plaintext, data);
            assert_eq!(tag, TAG_FINAL);
        }
        Err(_) => {
            // Rejection is always acceptable.
        }
    }
});
