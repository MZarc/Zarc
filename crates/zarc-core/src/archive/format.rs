// Format, constants, and data structures for the custom `.zarc` container format.

pub const ARCHIVE_MAGIC: &[u8; 4] = b"ZARC";
pub const FOOTER_MAGIC: &[u8; 4] = b"CRAZ";
pub const ARCHIVE_VERSION_V1: u16 = 1;

pub const KDF_ARGON2ID: u16 = 1;
pub const CIPHER_XCHACHA20_POLY1305: u16 = 1;

pub const FLAG_STANDALONE: u16 = 0x0000;
pub const FLAG_POLYGLOT_JPEG: u16 = 0x0001;

pub const HEADER_LEN: usize = 128;
pub const FOOTER_LEN: usize = 32;
pub const DEFAULT_CHUNK_SIZE: usize = 64 * 1024; // 64 KiB
pub const AEAD_TAG_LEN: usize = 16;
pub const AEAD_NONCE_LEN: usize = 24;
pub const SALT_LEN: usize = 16;
pub const KEY_LEN: usize = 32;
pub const CONTAINER_ID_LEN: usize = 16;
pub const WRAPPED_DEK_LEN: usize = KEY_LEN + AEAD_TAG_LEN; // 48 bytes

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ArchiveHeader {
    pub version: u16,
    pub flags: u16,
    pub container_id: [u8; CONTAINER_ID_LEN],
    pub kdf_id: u16,
    pub cipher_id: u16,
    pub ops_limit: u32,
    pub mem_limit_kib: u32,
    pub salt: [u8; SALT_LEN],
    pub wrapped_dek_nonce: [u8; AEAD_NONCE_LEN],
    pub wrapped_dek: [u8; WRAPPED_DEK_LEN],
}

impl ArchiveHeader {
    pub fn to_bytes(&self) -> [u8; HEADER_LEN] {
        let mut buf = [0u8; HEADER_LEN];
        buf[0..4].copy_from_slice(ARCHIVE_MAGIC);
        buf[4..6].copy_from_slice(&self.version.to_le_bytes());
        buf[6..8].copy_from_slice(&self.flags.to_le_bytes());
        buf[8..24].copy_from_slice(&self.container_id);
        buf[24..26].copy_from_slice(&self.kdf_id.to_le_bytes());
        buf[26..28].copy_from_slice(&self.cipher_id.to_le_bytes());
        buf[28..32].copy_from_slice(&self.ops_limit.to_le_bytes());
        buf[32..36].copy_from_slice(&self.mem_limit_kib.to_le_bytes());
        buf[36..52].copy_from_slice(&self.salt);
        buf[52..76].copy_from_slice(&self.wrapped_dek_nonce);
        buf[76..124].copy_from_slice(&self.wrapped_dek);
        // bytes 124..128 reserved 0
        buf
    }

    pub fn parse(buf: &[u8]) -> Result<Self, String> {
        if buf.len() < HEADER_LEN {
            return Err("Header buffer too small".into());
        }
        if &buf[0..4] != ARCHIVE_MAGIC {
            return Err("Invalid archive magic. Not a genuine .zarc container".into());
        }
        let version = u16::from_le_bytes(buf[4..6].try_into().unwrap());
        if version != ARCHIVE_VERSION_V1 {
            return Err(format!("Unsupported archive version {}", version));
        }
        let flags = u16::from_le_bytes(buf[6..8].try_into().unwrap());
        let mut container_id = [0u8; CONTAINER_ID_LEN];
        container_id.copy_from_slice(&buf[8..24]);
        let kdf_id = u16::from_le_bytes(buf[24..26].try_into().unwrap());
        if kdf_id != KDF_ARGON2ID {
            return Err(format!("Unsupported KDF id {}", kdf_id));
        }
        let cipher_id = u16::from_le_bytes(buf[26..28].try_into().unwrap());
        if cipher_id != CIPHER_XCHACHA20_POLY1305 {
            return Err(format!("Unsupported cipher id {}", cipher_id));
        }
        let ops_limit = u32::from_le_bytes(buf[28..32].try_into().unwrap());
        let mem_limit_kib = u32::from_le_bytes(buf[32..36].try_into().unwrap());
        // Enforce safe bounds on KDF to prevent memory exhaustion
        if !(1..=10).contains(&ops_limit) {
            return Err("KDF ops limit outside allowed bounds (1..=10)".into());
        }
        if !(8 * 1024..=256 * 1024).contains(&mem_limit_kib) {
            return Err("KDF mem limit outside allowed bounds (8MB..=256MB)".into());
        }
        let mut salt = [0u8; SALT_LEN];
        salt.copy_from_slice(&buf[36..52]);
        let mut wrapped_dek_nonce = [0u8; AEAD_NONCE_LEN];
        wrapped_dek_nonce.copy_from_slice(&buf[52..76]);
        let mut wrapped_dek = [0u8; WRAPPED_DEK_LEN];
        wrapped_dek.copy_from_slice(&buf[76..124]);

        Ok(Self {
            version,
            flags,
            container_id,
            kdf_id,
            cipher_id,
            ops_limit,
            mem_limit_kib,
            salt,
            wrapped_dek_nonce,
            wrapped_dek,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ArchiveFooter {
    pub manifest_offset: u64,
    pub manifest_len: u64,
    pub zarc_offset: u64,
    pub version: u16,
    pub flags: u16,
}

impl ArchiveFooter {
    pub fn to_bytes(&self) -> [u8; FOOTER_LEN] {
        let mut buf = [0u8; FOOTER_LEN];
        buf[0..8].copy_from_slice(&self.manifest_offset.to_le_bytes());
        buf[8..16].copy_from_slice(&self.manifest_len.to_le_bytes());
        buf[16..24].copy_from_slice(&self.zarc_offset.to_le_bytes());
        buf[24..28].copy_from_slice(FOOTER_MAGIC);
        buf[28..30].copy_from_slice(&self.version.to_le_bytes());
        buf[30..32].copy_from_slice(&self.flags.to_le_bytes());
        buf
    }

    pub fn parse(buf: &[u8], actual_file_len: u64) -> Result<Self, String> {
        if buf.len() < FOOTER_LEN {
            return Err("Footer buffer too small".into());
        }
        if &buf[24..28] != FOOTER_MAGIC {
            return Err("Invalid footer magic. Not a genuine or complete .zarc container".into());
        }
        let version = u16::from_le_bytes(buf[28..30].try_into().unwrap());
        if version != ARCHIVE_VERSION_V1 {
            return Err(format!("Unsupported footer version {}", version));
        }
        let manifest_offset = u64::from_le_bytes(buf[0..8].try_into().unwrap());
        let manifest_len = u64::from_le_bytes(buf[8..16].try_into().unwrap());
        let zarc_offset = u64::from_le_bytes(buf[16..24].try_into().unwrap());
        let flags = u16::from_le_bytes(buf[30..32].try_into().unwrap());

        let expected_total = manifest_offset
            .checked_add(manifest_len)
            .and_then(|v| v.checked_add(FOOTER_LEN as u64))
            .ok_or_else(|| "Manifest offset and length overflow".to_string())?;

        if expected_total != actual_file_len {
            return Err(format!(
                "Inconsistent container layout: manifest_offset ({}) + manifest_len ({}) + 32 != actual_file_len ({})",
                manifest_offset, manifest_len, actual_file_len
            ));
        }

        let is_polyglot = (flags & FLAG_POLYGLOT_JPEG != 0) || (zarc_offset > 0);
        if is_polyglot {
            if zarc_offset < 4 {
                return Err("Polyglot JPEG offset implausibly small for a valid JPEG".into());
            }
            if manifest_offset < zarc_offset + HEADER_LEN as u64 {
                return Err("Manifest offset precedes ZARC header in polyglot file".into());
            }
        } else {
            if zarc_offset != 0 {
                return Err("Non-zero zarc_offset in standalone container".into());
            }
            if manifest_offset < HEADER_LEN as u64 {
                return Err("Invalid manifest offset before header in standalone container".into());
            }
        }

        Ok(Self {
            manifest_offset,
            manifest_len,
            zarc_offset,
            version,
            flags,
        })
    }
}
