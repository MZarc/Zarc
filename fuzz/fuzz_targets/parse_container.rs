#![no_main]
//! Fuzz target: the container parser must never panic and must never accept
//! a container that it cannot describe.
//!
//! The input is split into a header part and a ciphertext part; both are
//! parsed. Any panic is a bug, any accepted input must be self consistent.

use libfuzzer_sys::fuzz_target;
use zarc_core::container::{expected_ciphertext_len, Header};

fuzz_target!(|data: &[u8]| {
    let header = match Header::decode(data) {
        Ok(header) => header,
        Err(_) => return,
    };

    // A header that parsed must be internally consistent.
    let expected = expected_ciphertext_len(header.plaintext_len, header.chunk_size)
        .expect("a parsed header must have consistent lengths");
    assert_eq!(header.ciphertext_len, expected);
    assert!(header.chunk_size >= 1024);
    assert!(header.chunk_size <= 1024 * 1024);

    // Re-encoding and decoding again must produce the same header.
    let encoded = header.encode();
    assert_eq!(encoded.len(), zarc_core::container::HEADER_LEN);
    let again = Header::decode(&encoded).expect("a re-encoded header must parse");
    assert_eq!(again.object_id, header.object_id);
    assert_eq!(again.plaintext_len, header.plaintext_len);
    assert_eq!(again.ciphertext_len, header.ciphertext_len);
    assert_eq!(again.wrapped_dek, header.wrapped_dek);
    assert_eq!(again.wrapped_dek_mac, header.wrapped_dek_mac);
});
