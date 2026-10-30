use std::collections::HashSet;

use serde::{Deserialize, Serialize};

use crate::archive::format::{AEAD_NONCE_LEN, AEAD_TAG_LEN, HEADER_LEN};

/// Upper bound for an in-memory object extraction (previews, covers).
///
/// File extraction streams chunk by chunk and is not subject to this limit.
pub const MAX_IN_MEMORY_OBJECT_BYTES: u64 = 64 * 1024 * 1024;

/// Upper bound for the outer JPEG carrier of a polyglot archive.
pub const MAX_OUTER_COVER_BYTES: u64 = 64 * 1024 * 1024;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ManifestObject {
    pub id: String,
    pub original_name: String,
    pub mime_type: String,
    pub original_size: u64,
    pub sanitized_size: u64,
    pub offset: u64,
    pub length: u64,
    pub chunk_size: u32,
    pub total_chunks: u32,
    pub is_cover: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ArchiveManifest {
    pub version: u16,
    pub container_id: String,
    pub created_at_unix: u64,
    pub cover_object_id: Option<String>,
    pub objects: Vec<ManifestObject>,
}

impl ArchiveManifest {
    pub fn to_json_bytes(&self) -> Result<Vec<u8>, String> {
        serde_json::to_vec(self).map_err(|e| format!("Manifest serialization failed: {}", e))
    }

    pub fn from_json_bytes(bytes: &[u8]) -> Result<Self, String> {
        let manifest: ArchiveManifest =
            serde_json::from_slice(bytes).map_err(|e| format!("Invalid manifest JSON: {}", e))?;
        manifest.validate()?;
        Ok(manifest)
    }

    pub fn validate(&self) -> Result<(), String> {
        if self.version != 1 {
            return Err(format!("Unsupported manifest version: {}", self.version));
        }
        if self.objects.is_empty() {
            return Err("Manifest must contain at least one object".into());
        }

        let mut ids = HashSet::with_capacity(self.objects.len());
        let mut prev_end: u64 = HEADER_LEN as u64;
        for (i, obj) in self.objects.iter().enumerate() {
            if obj.id.trim().is_empty() {
                return Err(format!("Object {} has empty id", i));
            }
            if !ids.insert(obj.id.as_str()) {
                return Err(format!("Duplicate object id '{}' in manifest", obj.id));
            }
            // Strict path traversal defense: original_name must be a simple clean filename
            if obj.original_name.contains('/')
                || obj.original_name.contains('\\')
                || obj.original_name.contains("..")
                || obj.original_name.trim().is_empty()
            {
                return Err(format!(
                    "Unsafe or invalid filename '{}' in archive manifest (path traversal detected)",
                    obj.original_name
                ));
            }
            if obj.chunk_size < 1024 || obj.chunk_size > 1024 * 1024 {
                return Err(format!(
                    "Implausible chunk size {} for object '{}'",
                    obj.chunk_size, obj.id
                ));
            }
            // Sanitization only removes metadata; the result can never be larger
            // than the original input.
            if obj.sanitized_size > obj.original_size {
                return Err(format!(
                    "Sanitized size {} exceeds original size {} for object '{}'",
                    obj.sanitized_size, obj.original_size, obj.id
                ));
            }
            // The chunk count must match the plaintext size exactly, otherwise a
            // crafted manifest can drive an oversized allocation or an
            // unterminated chunk loop.
            let expected_chunks = if obj.sanitized_size == 0 {
                1
            } else {
                obj.sanitized_size
                    .checked_add(obj.chunk_size as u64 - 1)
                    .ok_or_else(|| format!("Object '{}' size overflow", obj.id))?
                    / obj.chunk_size as u64
            };
            if expected_chunks > u32::MAX as u64 {
                return Err(format!("Object '{}' has too many chunks", obj.id));
            }
            if obj.total_chunks as u64 != expected_chunks {
                return Err(format!(
                    "Object '{}' declares {} chunks but its size requires {}",
                    obj.id, obj.total_chunks, expected_chunks
                ));
            }
            // Every chunk is stored as nonce || ciphertext || tag.
            let expected_length = expected_chunks
                .checked_mul((AEAD_NONCE_LEN + AEAD_TAG_LEN) as u64)
                .and_then(|v| v.checked_add(obj.sanitized_size))
                .ok_or_else(|| format!("Object '{}' length overflow", obj.id))?;
            if obj.length != expected_length {
                return Err(format!(
                    "Object '{}' length {} does not match its chunk layout {}",
                    obj.id, obj.length, expected_length
                ));
            }
            if obj.offset < prev_end {
                return Err(format!(
                    "Overlapping or out-of-order object offset {} < {}",
                    obj.offset, prev_end
                ));
            }
            let obj_end = obj
                .offset
                .checked_add(obj.length)
                .ok_or_else(|| format!("Object '{}' offset + length overflow", obj.id))?;
            prev_end = obj_end;
        }

        if let Some(cover_id) = &self.cover_object_id {
            if !self.objects.iter().any(|o| &o.id == cover_id) {
                return Err(format!("Cover object id '{}' not found in manifest", cover_id));
            }
        }

        Ok(())
    }

    /// Cross-check object ranges against the authenticated payload boundaries.
    ///
    /// The manifest is decrypted before this check runs, so a malformed range
    /// can only be produced by an authenticated-but-hostile writer; it must
    /// still never let an object read into the manifest or footer.
    pub fn validate_payload_bounds(&self, payload_start: u64, payload_end: u64) -> Result<(), String> {
        if payload_start > payload_end {
            return Err("Invalid archive payload bounds".into());
        }
        for obj in &self.objects {
            let obj_end = obj
                .offset
                .checked_add(obj.length)
                .ok_or_else(|| format!("Object '{}' offset + length overflow", obj.id))?;
            if obj.offset < payload_start {
                return Err(format!(
                    "Object '{}' starts before the archive payload ({})",
                    obj.id, obj.offset
                ));
            }
            if obj_end > payload_end {
                return Err(format!(
                    "Object '{}' ends after the archive payload ({} > {})",
                    obj.id, obj_end, payload_end
                ));
            }
        }
        Ok(())
    }
}
