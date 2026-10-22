//! Cross-implementation interoperability test.
//!
//! The `crypto_secretstream` crate is an independent, pure Rust implementation
//! of libsodium's `crypto_secretstream_xchacha20poly1305` construction (from
//! the RustCrypto `nacl-compat` project). It uses a completely different code
//! path and different dependencies than the vendored C library that Zarc uses.
//!
//! If the two implementations interoperate in both directions, then our FFI
//! usage of libsodium is correct with respect to the documented wire format -
//! a mistake in the FFI layer (wrong argument order, wrong tag handling, wrong
//! nonce handling) could not survive this test.
//!
//! The crate is a *test-only* dependency; the production code never uses it.

use crypto_secretstream::{Header, Key, PullStream, PushStream, Tag};
use rand_core::OsRng;
use zarc_core::sodium::{Decryptor, Encryptor, SecretStreamKey, StreamHeader, TAG_FINAL};

/// A message that is long enough to span more than one chunk of the other
/// implementation's expectations.
const MESSAGE: &[u8] = b"zarc interoperability check: the quick brown fox jumps over the lazy dog";

#[test]
fn a_stream_from_zarc_is_read_by_the_independent_implementation() {
    let key = SecretStreamKey::generate().unwrap();
    let (mut encryptor, header) = Encryptor::init(&key).unwrap();
    let ciphertext = encryptor.push(MESSAGE, b"aad", TAG_FINAL).unwrap();

    // The independent implementation must accept our header and ciphertext.
    let their_key = Key::from(*key.as_bytes());
    let their_header = Header::from(*header.as_bytes());
    let mut pull = PullStream::init(their_header, &their_key);
    let mut buffer = ciphertext.clone();
    let tag = pull
        .pull(&mut buffer, b"aad")
        .expect("the stream must decrypt");
    assert_eq!(tag, Tag::Final);
    assert_eq!(buffer, MESSAGE);
}

#[test]
fn a_stream_from_the_independent_implementation_is_read_by_zarc() {
    let key_bytes = [0x42u8; 32];
    let their_key = Key::from(key_bytes);
    let (their_header, mut push) = PushStream::init(OsRng, &their_key);

    let mut buffer = MESSAGE.to_vec();
    push.push(&mut buffer, b"aad", Tag::Final)
        .expect("the independent implementation must encrypt");

    // Zarc must accept the header and the ciphertext.
    let key = SecretStreamKey::from_bytes(&key_bytes);
    let mut decryptor = Decryptor::init(&StreamHeader::from_bytes(*their_header.as_ref()), &key)
        .expect("Zarc must accept the header");
    let (plaintext, tag) = decryptor
        .pull(&buffer, b"aad")
        .expect("Zarc must decrypt the stream");
    assert_eq!(tag, TAG_FINAL);
    assert_eq!(plaintext, MESSAGE);
}

#[test]
fn multi_message_streams_interoperate() {
    let key = SecretStreamKey::generate().unwrap();
    let (mut encryptor, header) = Encryptor::init(&key).unwrap();
    let first = encryptor.push(b"first", b"aad", 0x00).unwrap();
    let second = encryptor.push(b"second", b"aad", 0x00).unwrap();
    let third = encryptor.push(b"third", b"aad", TAG_FINAL).unwrap();

    let their_key = Key::from(*key.as_bytes());
    let their_header = Header::from(*header.as_bytes());
    let mut pull = PullStream::init(their_header, &their_key);

    for (expected, chunk) in [
        (&b"first"[..], &first),
        (&b"second"[..], &second),
        (&b"third"[..], &third),
    ] {
        let mut buffer = chunk.clone();
        pull.pull(&mut buffer, b"aad").expect("must decrypt");
        assert_eq!(buffer, expected);
    }
}

#[test]
fn the_independent_implementation_rejects_a_wrong_associated_data() {
    let key = SecretStreamKey::generate().unwrap();
    let (mut encryptor, header) = Encryptor::init(&key).unwrap();
    let ciphertext = encryptor.push(MESSAGE, b"aad", TAG_FINAL).unwrap();

    let their_key = Key::from(*key.as_bytes());
    let their_header = Header::from(*header.as_bytes());
    let mut pull = PullStream::init(their_header, &their_key);
    let mut buffer = ciphertext.clone();
    assert!(
        pull.pull(&mut buffer, b"different aad").is_err(),
        "a wrong associated data must be rejected"
    );
}

#[test]
fn zarc_rejects_a_wrong_associated_data_from_the_independent_implementation() {
    let key_bytes = [0x24u8; 32];
    let their_key = Key::from(key_bytes);
    let (their_header, mut push) = PushStream::init(OsRng, &their_key);
    let mut buffer = MESSAGE.to_vec();
    push.push(&mut buffer, b"aad", Tag::Final).unwrap();

    let key = SecretStreamKey::from_bytes(&key_bytes);
    let mut decryptor =
        Decryptor::init(&StreamHeader::from_bytes(*their_header.as_ref()), &key).unwrap();
    assert!(decryptor.pull(&buffer, b"different aad").is_err());
}
