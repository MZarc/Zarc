use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

use zeroize::Zeroizing;

use crate::archive::crypto::*;
use crate::archive::format::*;
use crate::archive::manifest::*;

#[derive(Clone)]
pub struct UnlockedArchive {
    pub archive_path: PathBuf,
    pub header: ArchiveHeader,
    pub footer: ArchiveFooter,
    pub manifest: ArchiveManifest,
    pub dek: Zeroizing<[u8; KEY_LEN]>,
}

/// Read the unencrypted outer cover JPEG from a polyglot container without requiring a passphrase.
/// Returns `Ok(None)` if the container is a standalone `.zarc` without an outer JPEG carrier.
pub fn read_outer_cover_jpeg(archive_path: &Path) -> Result<Option<Vec<u8>>, String> {
    let mut file = File::open(archive_path)
        .map_err(|e| format!("Failed to open file '{}': {}", archive_path.display(), e))?;

    let file_len = file
        .metadata()
        .map_err(|e| format!("Failed to read metadata: {}", e))?
        .len();

    if file_len < (HEADER_LEN + FOOTER_LEN) as u64 {
        return Ok(None);
    }

    file.seek(SeekFrom::End(-(FOOTER_LEN as i64)))
        .map_err(|e| format!("Failed to seek to footer: {}", e))?;

    let mut footer_buf = [0u8; FOOTER_LEN];
    file.read_exact(&mut footer_buf)
        .map_err(|e| format!("Failed to read footer: {}", e))?;

    let footer = ArchiveFooter::parse(&footer_buf, file_len)?;

    if footer.zarc_offset == 0 {
        return Ok(None);
    }

    if footer.zarc_offset > file_len {
        return Err("Malformed container: zarc_offset exceeds file size".into());
    }

    file.seek(SeekFrom::Start(0))
        .map_err(|e| format!("Failed to seek to cover JPEG: {}", e))?;

    let mut cover_bytes = vec![0u8; footer.zarc_offset as usize];
    file.read_exact(&mut cover_bytes)
        .map_err(|e| format!("Failed to read cover JPEG bytes: {}", e))?;

    if cover_bytes.len() < 4
        || cover_bytes[0..2] != [0xFF, 0xD8]
        || cover_bytes[cover_bytes.len() - 2..] != [0xFF, 0xD9]
    {
        return Err("Outer cover bytes do not form a valid JPEG (missing SOI/EOI)".into());
    }

    Ok(Some(cover_bytes))
}

/// Unlock a `.zarc` or polyglot `.jpg` archive by verifying password, unwrapping DEK, and decrypting manifest.
pub fn unlock_archive(archive_path: &Path, passphrase: &[u8]) -> Result<UnlockedArchive, String> {
    let mut file = File::open(archive_path)
        .map_err(|e| format!("Failed to open archive file '{}': {}", archive_path.display(), e))?;

    let file_len = file
        .metadata()
        .map_err(|e| format!("Failed to read archive file metadata: {}", e))?
        .len();

    if file_len < (HEADER_LEN + FOOTER_LEN) as u64 {
        return Err("File too small to be a valid .zarc or polyglot JPEG archive".into());
    }

    // 1. Read and parse footer first (O(1) seek from end of file)
    file.seek(SeekFrom::End(-(FOOTER_LEN as i64)))
        .map_err(|e| format!("Failed to seek to footer: {}", e))?;
    let mut footer_buf = [0u8; FOOTER_LEN];
    file.read_exact(&mut footer_buf)
        .map_err(|e| format!("Failed to read footer: {}", e))?;
    let footer = ArchiveFooter::parse(&footer_buf, file_len)?;

    // 2. Validate locator and read ArchiveHeader
    let is_polyglot = (footer.flags & FLAG_POLYGLOT_JPEG != 0) || (footer.zarc_offset > 0);
    if is_polyglot {
        // Deterministic locator verification:
        // Byte 0..2 must be JPEG SOI (0xFF 0xD8)
        file.seek(SeekFrom::Start(0))
            .map_err(|e| format!("Failed to seek to start of JPEG: {}", e))?;
        let mut soi = [0u8; 2];
        file.read_exact(&mut soi)
            .map_err(|e| format!("Failed to read JPEG SOI: {}", e))?;
        if soi != [0xFF, 0xD8] {
            return Err("Polyglot container missing JPEG SOI marker at offset 0".into());
        }

        // Byte (zarc_offset - 2)..zarc_offset must be JPEG EOI (0xFF 0xD9)
        file.seek(SeekFrom::Start(footer.zarc_offset - 2))
            .map_err(|e| format!("Failed to seek to JPEG EOI: {}", e))?;
        let mut eoi = [0u8; 2];
        file.read_exact(&mut eoi)
            .map_err(|e| format!("Failed to read JPEG EOI: {}", e))?;
        if eoi != [0xFF, 0xD9] {
            return Err("Polyglot container missing JPEG EOI marker before ZARC payload".into());
        }

        // Seek directly to zarc_offset
        file.seek(SeekFrom::Start(footer.zarc_offset))
            .map_err(|e| format!("Failed to seek to ZARC header at offset {}: {}", footer.zarc_offset, e))?;
    } else {
        file.seek(SeekFrom::Start(0))
            .map_err(|e| format!("Failed to seek to start of header: {}", e))?;
    }

    let mut header_buf = [0u8; HEADER_LEN];
    file.read_exact(&mut header_buf)
        .map_err(|e| format!("Failed to read archive header: {}", e))?;
    let header = ArchiveHeader::parse(&header_buf)?;

    // 3. Derive KEK via Argon2id
    let kek = derive_argon2id_key(
        passphrase,
        &header.salt,
        header.ops_limit,
        header.mem_limit_kib,
    )?;

    // 4. Unwrap DEK
    let mut dek_aad = Vec::with_capacity(16 + CONTAINER_ID_LEN);
    dek_aad.extend_from_slice(b"ZARC_DEK_V1_____");
    dek_aad.extend_from_slice(&header.container_id);

    let dek = unwrap_dek(
        &kek,
        &header.wrapped_dek_nonce,
        &header.wrapped_dek,
        &dek_aad,
    )?;

    // 5. Derive subkeys
    let subkeys = derive_subkeys(&dek, &header.container_id)?;

    // 6. Read and decrypt manifest
    file.seek(SeekFrom::Start(footer.manifest_offset))
        .map_err(|e| format!("Failed to seek to manifest: {}", e))?;

    if footer.manifest_len < AEAD_NONCE_LEN as u64 + AEAD_TAG_LEN as u64 {
        return Err("Invalid manifest length in footer".into());
    }

    let mut manifest_disk = vec![0u8; footer.manifest_len as usize];
    file.read_exact(&mut manifest_disk)
        .map_err(|e| format!("Failed to read manifest payload: {}", e))?;

    let mut manifest_nonce = [0u8; AEAD_NONCE_LEN];
    manifest_nonce.copy_from_slice(&manifest_disk[0..AEAD_NONCE_LEN]);
    let manifest_encrypted = &manifest_disk[AEAD_NONCE_LEN..];

    let mut manifest_aad = Vec::with_capacity(16 + 12);
    manifest_aad.extend_from_slice(&header.container_id);
    manifest_aad.extend_from_slice(b"MANIFEST_V1_");

    let manifest_json = aead_decrypt(
        &subkeys.manifest_key,
        &manifest_nonce,
        manifest_encrypted,
        &manifest_aad,
    )?;

    let manifest = ArchiveManifest::from_json_bytes(&manifest_json)?;

    Ok(UnlockedArchive {
        archive_path: archive_path.to_path_buf(),
        header,
        footer,
        manifest,
        dek,
    })
}

/// Decrypt an object directly into in-memory bytes (useful for image previews / thumbnails).
pub fn extract_object_bytes(
    unlocked: &UnlockedArchive,
    object_id: &str,
) -> Result<Vec<u8>, String> {
    let obj_index = unlocked
        .manifest
        .objects
        .iter()
        .position(|o| o.id == object_id)
        .ok_or_else(|| format!("Object id '{}' not found in archive manifest", object_id))?;

    let obj = &unlocked.manifest.objects[obj_index];
    let subkeys = derive_subkeys(&unlocked.dek, &unlocked.header.container_id)?;

    let mut file = File::open(&unlocked.archive_path)
        .map_err(|e| format!("Failed to open archive: {}", e))?;

    file.seek(SeekFrom::Start(obj.offset))
        .map_err(|e| format!("Failed to seek to object offset: {}", e))?;

    let mut output = Vec::with_capacity(obj.sanitized_size as usize);

    for chunk_idx in 0..obj.total_chunks {
        let is_last = (chunk_idx == obj.total_chunks - 1) as u8;

        let expected_pt_len = if chunk_idx == obj.total_chunks - 1 {
            let prev = (chunk_idx as u64) * (obj.chunk_size as u64);
            (obj.sanitized_size.saturating_sub(prev)) as usize
        } else {
            obj.chunk_size as usize
        };

        let chunk_disk_len = AEAD_NONCE_LEN + expected_pt_len + AEAD_TAG_LEN;
        let mut chunk_buf = vec![0u8; chunk_disk_len];
        file.read_exact(&mut chunk_buf)
            .map_err(|e| format!("Failed to read chunk {}: {}", chunk_idx, e))?;

        let mut nonce = [0u8; AEAD_NONCE_LEN];
        nonce.copy_from_slice(&chunk_buf[0..AEAD_NONCE_LEN]);
        let ciphertext = &chunk_buf[AEAD_NONCE_LEN..];

        let mut aad = Vec::with_capacity(16 + 4 + 4 + 1);
        aad.extend_from_slice(&unlocked.header.container_id);
        aad.extend_from_slice(&(obj_index as u32).to_le_bytes());
        aad.extend_from_slice(&chunk_idx.to_le_bytes());
        aad.push(is_last);

        let pt = aead_decrypt(&subkeys.content_key, &nonce, ciphertext, &aad)?;
        output.extend_from_slice(&pt);
    }

    Ok(output)
}

/// Decrypt an object and extract it safely to an output file.
pub fn extract_object_to_file(
    unlocked: &UnlockedArchive,
    object_id: &str,
    output_path: &Path,
) -> Result<(), String> {
    let bytes = extract_object_bytes(unlocked, object_id)?;

    let staging_path = output_path.with_extension(format!("tmp_ext_{}", std::process::id()));
    let mut out_file = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&staging_path)
        .map_err(|e| format!("Failed to create extract output file: {}", e))?;

    out_file
        .write_all(&bytes)
        .map_err(|e| format!("Failed to write extracted file: {}", e))?;
    out_file
        .sync_all()
        .map_err(|e| format!("Failed to sync extracted file: {}", e))?;
    drop(out_file);

    std::fs::rename(&staging_path, output_path)
        .map_err(|e| format!("Failed to finalize extracted file: {}", e))?;

    Ok(())
}

/// Extract all photos from the unlocked archive into the target directory.
pub fn extract_all_to_dir<F>(
    unlocked: &UnlockedArchive,
    output_dir: &Path,
    mut progress_callback: Option<F>,
) -> Result<Vec<PathBuf>, String>
where
    F: FnMut(f32, &str),
{
    if !output_dir.exists() {
        std::fs::create_dir_all(output_dir)
            .map_err(|e| format!("Failed to create output directory: {}", e))?;
    }

    let total = unlocked.manifest.objects.len();
    let mut extracted_paths = Vec::with_capacity(total);

    for (i, obj) in unlocked.manifest.objects.iter().enumerate() {
        let progress = (i as f32) / (total as f32);
        if let Some(cb) = &mut progress_callback {
            cb(progress, &format!("Extracting {}...", obj.original_name));
        }

        let clean_filename = Path::new(&obj.original_name)
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("extracted_photo.jpg");

        // Resolve collision if file already exists
        let mut target_path = output_dir.join(clean_filename);
        let mut counter = 1;
        while target_path.exists() {
            let stem = Path::new(clean_filename)
                .file_stem()
                .and_then(|s| s.to_str())
                .unwrap_or("photo");
            let ext = Path::new(clean_filename)
                .extension()
                .and_then(|e| e.to_str())
                .unwrap_or("");
            let new_name = if ext.is_empty() {
                format!("{}_{}", stem, counter)
            } else {
                format!("{}_{}.{}", stem, counter, ext)
            };
            target_path = output_dir.join(new_name);
            counter += 1;
        }

        extract_object_to_file(unlocked, &obj.id, &target_path)?;
        extracted_paths.push(target_path);
    }

    if let Some(cb) = &mut progress_callback {
        cb(1.0, "All photos extracted successfully!");
    }

    Ok(extracted_paths)
}
