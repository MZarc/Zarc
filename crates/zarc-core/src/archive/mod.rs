//! Secure photo container format (`.zarc` / polyglot `.jpg`) module.

#![allow(missing_docs)]
#![allow(clippy::all)]

pub mod crypto;
pub mod extract;
pub mod format;
pub mod manifest;
pub mod metadata;
pub mod pack;

pub use extract::{
    extract_all_to_dir, extract_object_bytes, extract_object_to_file, read_outer_cover_jpeg,
    unlock_archive, UnlockedArchive,
};
pub use format::{
    ArchiveFooter, ArchiveHeader, ARCHIVE_MAGIC, CIPHER_XCHACHA20_POLY1305, DEFAULT_CHUNK_SIZE,
    FLAG_POLYGLOT_JPEG, FLAG_STANDALONE, FOOTER_LEN, FOOTER_MAGIC, HEADER_LEN, KDF_ARGON2ID,
};
pub use manifest::{ArchiveManifest, ManifestObject};
pub use metadata::sanitize_image_bytes;
pub use pack::{create_archive, ArchiveInputFile};
