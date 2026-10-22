//! Minimal hexadecimal helpers.
//!
//! Zarc only needs lowercase hex encoding for object identifiers and test
//! output, so a tiny dependency-free implementation is used instead of pulling
//! in a crate.
//!
//! This module contains no `unsafe` code.
//!
//! The only `unsafe` in the workspace is the libsodium FFI layer in
//! [`crate::sodium::ffi`].

#![forbid(unsafe_code)]

use crate::error::{Error, Result};

const HEX_DIGITS: &[u8; 16] = b"0123456789abcdef";

/// Encode bytes as lowercase hexadecimal.
#[must_use]
pub fn encode(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(HEX_DIGITS[(byte >> 4) as usize] as char);
        out.push(HEX_DIGITS[(byte & 0x0f) as usize] as char);
    }
    out
}

/// # Errors
/// #
/// # Returns [`crate::Error`] when the operation cannot be completed; see
/// # the error taxonomy in [`crate::error`] for the individual failure modes.
/// Decode a lowercase or uppercase hexadecimal string.
pub fn decode(text: &str) -> Result<Vec<u8>> {
    if text.len() % 2 != 0 {
        return Err(Error::Format(
            "hexadecimal string must have an even length".into(),
        ));
    }
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len() / 2);
    for pair in bytes.chunks_exact(2) {
        let hi = hex_value(pair[0])?;
        let lo = hex_value(pair[1])?;
        out.push((hi << 4) | lo);
    }
    Ok(out)
}

fn hex_value(byte: u8) -> Result<u8> {
    match byte {
        b'0'..=b'9' => Ok(byte - b'0'),
        b'a'..=b'f' => Ok(byte - b'a' + 10),
        b'A'..=b'F' => Ok(byte - b'A' + 10),
        _ => Err(Error::Format(format!(
            "invalid hexadecimal character {:?}",
            byte as char
        ))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips() {
        let data: Vec<u8> = (0u8..=255).collect();
        let encoded = encode(&data);
        assert_eq!(encoded.len(), 512);
        assert_eq!(decode(&encoded).unwrap(), data);
    }

    #[test]
    fn rejects_odd_length() {
        assert!(decode("abc").is_err());
    }

    #[test]
    fn rejects_invalid_characters() {
        assert!(decode("zz").is_err());
    }
}
