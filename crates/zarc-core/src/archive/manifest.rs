use serde::{Deserialize, Serialize};

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

        let mut prev_end: u64 = crate::archive::format::HEADER_LEN as u64;
        for (i, obj) in self.objects.iter().enumerate() {
            if obj.id.trim().is_empty() {
                return Err(format!("Object {} has empty id", i));
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
}
