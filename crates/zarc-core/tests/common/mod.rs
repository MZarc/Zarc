//! Shared helpers for the integration tests.
//!
//! The media "fixtures" are synthetic: they are generated deterministically and
//! carry the real magic bytes of the format they imitate, but they are not
//! photographs or videos. No personal media is ever used by the test suite.

#![allow(dead_code)]

use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};

/// Hex encoded SHA-256 digest of `data`.
pub fn sha256_hex(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    let digest = hasher.finalize();
    digest.iter().map(|byte| format!("{byte:02x}")).collect()
}

/// A deterministic pseudo random byte generator (SplitMix64).
///
/// The generator is *not* used for anything cryptographic; it only produces
/// reproducible test inputs.
pub fn pseudo_random_bytes(seed: u64, len: usize) -> Vec<u8> {
    let mut state = seed.wrapping_add(0x9E37_79B9_7F4A_7C15);
    let mut out = Vec::with_capacity(len);
    while out.len() < len {
        state = state.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^= z >> 31;
        out.extend_from_slice(&z.to_le_bytes());
    }
    out.truncate(len);
    out
}

/// A synthetic JPEG file: SOI, APP0/JFIF header, entropy data, EOI.
pub fn synthetic_jpeg(seed: u64, payload: usize) -> Vec<u8> {
    let mut out = Vec::with_capacity(payload + 32);
    out.extend_from_slice(&[0xFF, 0xD8]); // SOI
    out.extend_from_slice(&[0xFF, 0xE0, 0x00, 0x10]); // APP0
    out.extend_from_slice(b"JFIF\0");
    out.extend_from_slice(&[0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00]);
    out.extend_from_slice(&pseudo_random_bytes(seed, payload));
    out.extend_from_slice(&[0xFF, 0xD9]); // EOI
    out
}

/// A synthetic PNG file: signature, IHDR, IDAT, IEND.
pub fn synthetic_png(seed: u64, payload: usize) -> Vec<u8> {
    let mut out = Vec::with_capacity(payload + 64);
    out.extend_from_slice(&[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
    out.extend_from_slice(b"IHDR");
    out.extend_from_slice(&[0x00, 0x00, 0x00, 0x10]);
    out.extend_from_slice(&[0x00, 0x00, 0x00, 0x10, 0x08, 0x02, 0x00, 0x00, 0x00]);
    out.extend_from_slice(&[0x3A, 0x7E, 0x9B, 0x55]); // CRC placeholder
    out.extend_from_slice(b"IDAT");
    out.extend_from_slice(&pseudo_random_bytes(seed, payload));
    out.extend_from_slice(b"IEND");
    out.extend_from_slice(&[0xAE, 0x42, 0x60, 0x82]);
    out
}

/// A synthetic WebP file: RIFF container with a VP8 chunk.
pub fn synthetic_webp(seed: u64, payload: usize) -> Vec<u8> {
    let mut body = Vec::with_capacity(payload + 16);
    body.extend_from_slice(b"WEBP");
    body.extend_from_slice(b"VP8 ");
    body.extend_from_slice(&(payload as u32).to_le_bytes());
    body.extend_from_slice(&pseudo_random_bytes(seed, payload));
    let mut out = Vec::with_capacity(body.len() + 8);
    out.extend_from_slice(b"RIFF");
    out.extend_from_slice(&(body.len() as u32).to_le_bytes());
    out.extend_from_slice(&body);
    out
}

/// A synthetic MP4 file: `ftyp` box followed by an `mdat` box.
pub fn synthetic_mp4(seed: u64, payload: usize) -> Vec<u8> {
    let mut out = Vec::with_capacity(payload + 32);
    out.extend_from_slice(&(16u32).to_be_bytes());
    out.extend_from_slice(b"ftyp");
    out.extend_from_slice(b"isom");
    out.extend_from_slice(&[0x00, 0x00, 0x02, 0x00]);
    out.extend_from_slice(b"isomiso2");
    out.extend_from_slice(&(payload as u32 + 8).to_be_bytes());
    out.extend_from_slice(b"mdat");
    out.extend_from_slice(&pseudo_random_bytes(seed, payload));
    out
}

/// Write `data` into `dir/name` and return the path.
pub fn write_file(dir: &Path, name: &str, data: &[u8]) -> PathBuf {
    let path = dir.join(name);
    std::fs::write(&path, data).expect("cannot write the test file");
    path
}

/// Read a file into memory.
pub fn read_file(path: &Path) -> Vec<u8> {
    std::fs::read(path).expect("cannot read the test file")
}

/// The names of the files inside `dir`, sorted.
pub fn file_names(dir: &Path) -> Vec<String> {
    let mut names: Vec<String> = std::fs::read_dir(dir)
        .expect("cannot list the directory")
        .flatten()
        .map(|entry| entry.file_name().to_string_lossy().into_owned())
        .collect();
    names.sort();
    names
}
