use std::fs::OpenOptions;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use zeroize::Zeroizing;

use crate::archive::crypto::*;
use crate::archive::format::*;
use crate::archive::manifest::*;
use crate::archive::metadata::sanitize_image_bytes;

#[derive(Debug, Clone)]
pub struct ArchiveInputFile {
    pub path: PathBuf,
    pub display_name: String,
    pub is_cover: bool,
}

/// Create a secure `.zarc` container, optionally wrapped in a polyglot JPEG carrier (`outer_cover_jpeg`).
pub fn create_archive<F>(
    output_path: &Path,
    outer_cover_jpeg: Option<&[u8]>,
    input_files: &[ArchiveInputFile],
    passphrase: &[u8],
    ops_limit: u32,
    mem_limit_kib: u32,
    mut progress_callback: Option<F>,
) -> Result<(), String>
where
    F: FnMut(f32, &str),
{
    if input_files.is_empty() {
        return Err("Cannot create an empty archive; at least one photo is required".into());
    }
    if passphrase.is_empty() {
        return Err("Passphrase cannot be empty".into());
    }

    if let Some(cb) = &mut progress_callback {
        cb(0.05, "Initializing cryptographic parameters...");
    }

    // 1. Generate random container ID, salt, and DEK
    let mut container_id = [0u8; CONTAINER_ID_LEN];
    fill_random_bytes(&mut container_id);

    let mut salt = [0u8; SALT_LEN];
    fill_random_bytes(&mut salt);

    let mut dek = Zeroizing::new([0u8; KEY_LEN]);
    fill_random_bytes(dek.as_mut());

    // 2. Derive KEK via Argon2id
    let kek = derive_argon2id_key(passphrase, &salt, ops_limit, mem_limit_kib)?;

    // 3. Wrap DEK
    let mut wrapped_dek_nonce = [0u8; AEAD_NONCE_LEN];
    fill_random_bytes(&mut wrapped_dek_nonce);

    let mut dek_aad = Vec::with_capacity(16 + CONTAINER_ID_LEN);
    dek_aad.extend_from_slice(b"ZARC_DEK_V1_____");
    dek_aad.extend_from_slice(&container_id);

    let wrapped_dek = wrap_dek(&kek, &wrapped_dek_nonce, &dek, &dek_aad)?;

    // 4. Derive content and manifest subkeys
    let subkeys = derive_subkeys(&dek, &container_id)?;

    // 5. Open staging file for atomic creation
    let staging_path = output_path.with_extension(format!("tmp_{}_{}", std::process::id(), fastrand()));
    let mut file = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&staging_path)
        .map_err(|e| format!("Failed to open output staging file: {}", e))?;

    let mut current_offset: u64 = 0;
    let zarc_offset: u64;
    let footer_flags: u16;

    // 6. If polyglot JPEG carrier is requested, sanitize and write outer cover JPEG
    if let Some(raw_cover) = outer_cover_jpeg {
        if let Some(cb) = &mut progress_callback {
            cb(0.08, "Sanitizing outer JPEG cover...");
        }

        let sanitized_cover = sanitize_image_bytes(raw_cover, "cover.jpg")
            .map_err(|e| format!("Outer JPEG cover sanitization failed: {}", e))?;

        if sanitized_cover.len() < 4
            || sanitized_cover[0..2] != [0xFF, 0xD8]
            || sanitized_cover[sanitized_cover.len() - 2..] != [0xFF, 0xD9]
        {
            return Err("Outer cover is not a valid JPEG (missing SOI/EOI)".into());
        }

        file.write_all(&sanitized_cover)
            .map_err(|e| format!("Failed to write outer JPEG cover: {}", e))?;

        zarc_offset = sanitized_cover.len() as u64;
        current_offset = zarc_offset;
        footer_flags = FLAG_POLYGLOT_JPEG;
    } else {
        zarc_offset = 0;
        footer_flags = FLAG_STANDALONE;
    }

    // 7. Construct public header
    let header = ArchiveHeader {
        version: ARCHIVE_VERSION_V1,
        flags: footer_flags,
        container_id,
        kdf_id: KDF_ARGON2ID,
        cipher_id: CIPHER_XCHACHA20_POLY1305,
        ops_limit,
        mem_limit_kib,
        salt,
        wrapped_dek_nonce,
        wrapped_dek,
    };

    // Write header (128 bytes)
    file.write_all(&header.to_bytes())
        .map_err(|e| format!("Failed to write header: {}", e))?;
    current_offset += HEADER_LEN as u64;

    let mut manifest_objects = Vec::with_capacity(input_files.len());
    let mut cover_object_id: Option<String> = None;
    let total_inputs = input_files.len();

    // 8. Process each photo
    for (i, input) in input_files.iter().enumerate() {
        let object_id = format!("obj_{:04}", i);
        if input.is_cover && cover_object_id.is_none() {
            cover_object_id = Some(object_id.clone());
        }

        let progress_fraction = 0.10 + (0.75 * (i as f32 / total_inputs as f32));
        if let Some(cb) = &mut progress_callback {
            cb(
                progress_fraction,
                &format!("Sanitizing and encrypting {}...", input.display_name),
            );
        }

        // Read source photo
        let raw_bytes = std::fs::read(&input.path)
            .map_err(|e| format!("Failed to read source photo '{}': {}", input.path.display(), e))?;

        // Lossless metadata sanitization
        let sanitized = sanitize_image_bytes(&raw_bytes, &input.display_name)
            .map_err(|e| format!("Failed to sanitize metadata for '{}': {}", input.display_name, e))?;

        let mime = detect_mime(&input.display_name, &sanitized);
        let sanitized_len = sanitized.len() as u64;
        let chunk_size = DEFAULT_CHUNK_SIZE;
        let total_chunks = if sanitized_len == 0 {
            1
        } else {
            ((sanitized_len + chunk_size as u64 - 1) / chunk_size as u64) as u32
        };

        let object_start_offset = current_offset;

        // Stream and encrypt chunks
        for chunk_idx in 0..total_chunks {
            let start = (chunk_idx as usize) * chunk_size;
            let end = ((start + chunk_size).min(sanitized.len())).max(start);
            let chunk_plaintext = if start < sanitized.len() {
                &sanitized[start..end]
            } else {
                &[]
            };

            let is_last = (chunk_idx == total_chunks - 1) as u8;

            let mut chunk_nonce = [0u8; AEAD_NONCE_LEN];
            fill_random_bytes(&mut chunk_nonce);

            // AAD binds container, object index, chunk index, and last-chunk flag
            let mut aad = Vec::with_capacity(16 + 4 + 4 + 1);
            aad.extend_from_slice(&container_id);
            aad.extend_from_slice(&(i as u32).to_le_bytes());
            aad.extend_from_slice(&chunk_idx.to_le_bytes());
            aad.push(is_last);

            let encrypted_chunk = aead_encrypt(&subkeys.content_key, &chunk_nonce, chunk_plaintext, &aad)?;

            file.write_all(&chunk_nonce)
                .map_err(|e| format!("Failed to write chunk nonce: {}", e))?;
            file.write_all(&encrypted_chunk)
                .map_err(|e| format!("Failed to write encrypted chunk: {}", e))?;

            current_offset += (AEAD_NONCE_LEN + encrypted_chunk.len()) as u64;
        }

        let object_total_length = current_offset - object_start_offset;

        // Clean filename (remove any directory components)
        let clean_name = Path::new(&input.display_name)
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("photo")
            .to_string();

        manifest_objects.push(ManifestObject {
            id: object_id,
            original_name: clean_name,
            mime_type: mime,
            original_size: raw_bytes.len() as u64,
            sanitized_size: sanitized_len,
            offset: object_start_offset,
            length: object_total_length,
            chunk_size: chunk_size as u32,
            total_chunks,
            is_cover: input.is_cover,
        });
    }

    if cover_object_id.is_none() && !manifest_objects.is_empty() {
        cover_object_id = Some(manifest_objects[0].id.clone());
        manifest_objects[0].is_cover = true;
    }

    if let Some(cb) = &mut progress_callback {
        cb(0.90, "Encrypting archive manifest...");
    }

    // 9. Serialize and encrypt manifest
    let now_unix = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);

    let manifest = ArchiveManifest {
        version: ARCHIVE_VERSION_V1,
        container_id: hex_string(&container_id),
        created_at_unix: now_unix,
        cover_object_id,
        objects: manifest_objects,
    };

    let manifest_json = manifest.to_json_bytes()?;

    let mut manifest_nonce = [0u8; AEAD_NONCE_LEN];
    fill_random_bytes(&mut manifest_nonce);

    let mut manifest_aad = Vec::with_capacity(16 + 12);
    manifest_aad.extend_from_slice(&container_id);
    manifest_aad.extend_from_slice(b"MANIFEST_V1_");

    let encrypted_manifest =
        aead_encrypt(&subkeys.manifest_key, &manifest_nonce, &manifest_json, &manifest_aad)?;

    let manifest_offset = current_offset;
    file.write_all(&manifest_nonce)
        .map_err(|e| format!("Failed to write manifest nonce: {}", e))?;
    file.write_all(&encrypted_manifest)
        .map_err(|e| format!("Failed to write encrypted manifest: {}", e))?;

    let manifest_disk_len = (AEAD_NONCE_LEN + encrypted_manifest.len()) as u64;

    // 10. Write footer
    let footer = ArchiveFooter {
        manifest_offset,
        manifest_len: manifest_disk_len,
        zarc_offset,
        version: ARCHIVE_VERSION_V1,
        flags: footer_flags,
    };

    file.write_all(&footer.to_bytes())
        .map_err(|e| format!("Failed to write footer: {}", e))?;

    file.sync_all()
        .map_err(|e| format!("Failed to flush archive to disk: {}", e))?;
    drop(file);

    // Atomically rename staging to final destination
    if let Err(e) = std::fs::rename(&staging_path, output_path) {
        let _ = std::fs::remove_file(&staging_path);
        return Err(format!("Failed to finalize archive file: {}", e));
    }

    if let Some(cb) = &mut progress_callback {
        cb(1.0, "Container build complete!");
    }

    Ok(())
}

fn fastrand() -> u32 {
    let mut b = [0u8; 4];
    fill_random_bytes(&mut b);
    u32::from_le_bytes(b)
}

fn detect_mime(filename: &str, data: &[u8]) -> String {
    let ext = filename.rsplit('.').next().unwrap_or("").to_lowercase();
    match ext.as_str() {
        "jpg" | "jpeg" => "image/jpeg".into(),
        "png" => "image/png".into(),
        "webp" => "image/webp".into(),
        _ => {
            if data.starts_with(&[0xFF, 0xD8, 0xFF]) {
                "image/jpeg".into()
            } else if data.starts_with(b"\x89PNG") {
                "image/png".into()
            } else if data.len() >= 12 && &data[0..4] == b"RIFF" && &data[8..12] == b"WEBP" {
                "image/webp".into()
            } else {
                "application/octet-stream".into()
            }
        }
    }
}

fn hex_string(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push_str(&format!("{:02x}", b));
    }
    s
}
