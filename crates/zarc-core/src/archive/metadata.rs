// Format-aware lossless image metadata sanitization (JPEG, PNG, WebP)

pub fn sanitize_image_bytes(data: &[u8], filename: &str) -> Result<Vec<u8>, String> {
    let ext = filename.rsplit('.').next().unwrap_or("").to_lowercase();
    match ext.as_str() {
        "jpg" | "jpeg" => sanitize_jpeg(data),
        "png" => sanitize_png(data),
        "webp" => sanitize_webp(data),
        _ => {
            // Check magic bytes if extension is missing/ambiguous
            if data.starts_with(&[0xFF, 0xD8, 0xFF]) {
                sanitize_jpeg(data)
            } else if data.starts_with(b"\x89PNG\r\n\x1a\n") {
                sanitize_png(data)
            } else if data.len() >= 12 && &data[0..4] == b"RIFF" && &data[8..12] == b"WEBP" {
                sanitize_webp(data)
            } else {
                Err(format!("Unsupported image format for metadata sanitization: {}", filename))
            }
        }
    }
}

/// Losslessly sanitize JPEG by stripping APP1..APP15 metadata segments and COM comments,
/// while preserving SOF, DHT, DQT, SOS, compressed image entropy data, and EOI.
fn sanitize_jpeg(data: &[u8]) -> Result<Vec<u8>, String> {
    if data.len() < 4 || data[0] != 0xFF || data[1] != 0xD8 {
        return Err("Invalid JPEG magic".into());
    }

    let mut out = Vec::with_capacity(data.len());
    out.push(0xFF);
    out.push(0xD8); // SOI

    let mut pos = 2;
    while pos < data.len() {
        if data[pos] != 0xFF {
            return Err("Expected JPEG marker byte 0xFF".into());
        }
        // Skip fill bytes 0xFF
        while pos < data.len() && data[pos] == 0xFF {
            pos += 1;
        }
        if pos >= data.len() {
            break;
        }

        let marker = data[pos];
        pos += 1;

        // Standalone markers without length: RST0-7 (D0..D7), SOI (D8), EOI (D9), TEM (01)
        if marker == 0xD9 {
            // EOI
            out.push(0xFF);
            out.push(0xD9);
            break;
        }
        if (0xD0..=0xD7).contains(&marker) || marker == 0x01 {
            out.push(0xFF);
            out.push(marker);
            continue;
        }

        if pos + 2 > data.len() {
            return Err("Truncated JPEG segment length".into());
        }
        let length = u16::from_be_bytes([data[pos], data[pos + 1]]) as usize;
        if length < 2 || pos + length > data.len() {
            return Err("Invalid JPEG segment length".into());
        }

        // Check if marker is an EXIF/XMP/IPTC/Comment segment:
        // APP1 (E1) -> EXIF / XMP
        // APP2 (E2) -> ICC Profile / FlashPix
        // APP13 (ED) -> Photoshop / IPTC
        // COM (FE) -> Comment
        let is_metadata = matches!(marker, 0xE1 | 0xE2 | 0xED | 0xFE);

        if !is_metadata {
            out.push(0xFF);
            out.push(marker);
            out.extend_from_slice(&data[pos..pos + length]);
        }

        pos += length;

        // If marker was SOS (Start of Scan 0xDA), entropy-coded image data follows immediately until EOI
        if marker == 0xDA {
            // Read until next marker (0xFF followed by non-0x00 and non-RST)
            while pos < data.len() {
                if data[pos] == 0xFF {
                    if pos + 1 < data.len() {
                        let next = data[pos + 1];
                        if next == 0x00 {
                            // Byte stuffing, keep both
                            out.push(0xFF);
                            out.push(0x00);
                            pos += 2;
                            continue;
                        } else if (0xD0..=0xD7).contains(&next) {
                            // Restart marker
                            out.push(0xFF);
                            out.push(next);
                            pos += 2;
                            continue;
                        } else {
                            // Next marker reached (e.g. EOI 0xD9)
                            break;
                        }
                    } else {
                        break;
                    }
                } else {
                    out.push(data[pos]);
                    pos += 1;
                }
            }
        }
    }

    Ok(out)
}

/// Losslessly sanitize PNG by removing eXIf, tEXt, zTXt, iTXt, tIME, and metadata chunks
/// while preserving IHDR, PLTE, IDAT, and IEND.
fn sanitize_png(data: &[u8]) -> Result<Vec<u8>, String> {
    const PNG_SIG: &[u8; 8] = b"\x89PNG\r\n\x1a\n";
    if data.len() < 8 || &data[0..8] != PNG_SIG {
        return Err("Invalid PNG signature".into());
    }

    let mut out = Vec::with_capacity(data.len());
    out.extend_from_slice(PNG_SIG);

    let mut pos = 8;
    while pos + 12 <= data.len() {
        let length = u32::from_be_bytes(data[pos..pos + 4].try_into().unwrap()) as usize;
        let chunk_type = &data[pos + 4..pos + 8];
        let total_chunk_len = 12 + length;

        if pos + total_chunk_len > data.len() {
            return Err("PNG chunk length exceeds data size".into());
        }

        // Strippable metadata chunks in PNG:
        // eXIf (Exif), tEXt (plain text), zTXt (compressed text), iTXt (international text),
        // tIME (last modification time)
        let is_metadata = matches!(chunk_type, b"eXIf" | b"tEXt" | b"zTXt" | b"iTXt" | b"tIME");

        if !is_metadata {
            out.extend_from_slice(&data[pos..pos + total_chunk_len]);
        }

        if chunk_type == b"IEND" {
            break;
        }

        pos += total_chunk_len;
    }

    Ok(out)
}

/// Losslessly sanitize WebP by stripping EXIF and XMP chunks from Extended WebP (VP8X)
/// or passing simple VP8/VP8L RIFF data intact.
fn sanitize_webp(data: &[u8]) -> Result<Vec<u8>, String> {
    if data.len() < 12 || &data[0..4] != b"RIFF" || &data[8..12] != b"WEBP" {
        return Err("Invalid WebP RIFF header".into());
    }

    let mut pos = 12;
    let mut chunks = Vec::new();
    let mut has_vp8x = false;
    let mut vp8x_flags_pos = 0;

    while pos + 8 <= data.len() {
        let fourcc = &data[pos..pos + 4];
        let length = u32::from_le_bytes(data[pos + 4..pos + 8].try_into().unwrap()) as usize;
        let padded_length = (length + 1) & !1;

        if pos + 8 + length > data.len() {
            return Err("Truncated WebP chunk".into());
        }

        if fourcc == b"VP8X" && length >= 10 {
            has_vp8x = true;
            vp8x_flags_pos = chunks.len();
        }

        // Skip EXIF and XMP metadata chunks
        if fourcc != b"EXIF" && fourcc != b"XMP " {
            chunks.push((fourcc.to_vec(), data[pos + 8..pos + 8 + length].to_vec()));
        }

        pos += 8 + padded_length;
    }

    // Reconstruct WebP RIFF file
    let mut payload = Vec::new();
    for (i, (fourcc, body)) in chunks.iter().enumerate() {
        payload.extend_from_slice(fourcc);
        payload.extend_from_slice(&(body.len() as u32).to_le_bytes());
        let mut body_bytes = body.clone();
        if has_vp8x && i == vp8x_flags_pos && body_bytes.len() >= 4 {
            // Clear EXIF flag (bit 3) and XMP flag (bit 2) in VP8X flags
            body_bytes[0] &= !(0x08 | 0x04);
        }
        payload.extend_from_slice(&body_bytes);
        if body.len() % 2 != 0 {
            payload.push(0); // padding byte
        }
    }

    let mut out = Vec::with_capacity(12 + payload.len());
    out.extend_from_slice(b"RIFF");
    let riff_size = (4 + payload.len()) as u32;
    out.extend_from_slice(&riff_size.to_le_bytes());
    out.extend_from_slice(b"WEBP");
    out.extend_from_slice(&payload);

    Ok(out)
}
