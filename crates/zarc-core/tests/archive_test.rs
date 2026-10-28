use tempfile::tempdir;
use zarc_core::archive::*;

fn create_dummy_jpeg() -> Vec<u8> {
    let mut data = Vec::new();
    // SOI
    data.extend_from_slice(&[0xFF, 0xD8]);
    // APP1 with EXIF: length 12 (2 length bytes + 10 payload bytes)
    data.extend_from_slice(&[0xFF, 0xE1, 0x00, 0x0C]);
    data.extend_from_slice(b"Exif\0\01234");
    // DQT
    data.extend_from_slice(&[0xFF, 0xDB, 0x00, 0x04, 0x01, 0x02]);
    // SOF0
    data.extend_from_slice(&[0xFF, 0xC0, 0x00, 0x0B, 0x08, 0x00, 0x64, 0x00, 0x64, 0x01, 0x01, 0x11, 0x00]);
    // SOS
    data.extend_from_slice(&[0xFF, 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00]);
    // Entropy image bytes
    data.extend_from_slice(&[0x12, 0x34, 0x56, 0x78]);
    // EOI
    data.extend_from_slice(&[0xFF, 0xD9]);
    data
}

fn create_dummy_png() -> Vec<u8> {
    let mut data = Vec::new();
    data.extend_from_slice(b"\x89PNG\r\n\x1a\n");
    // IHDR (length 13)
    data.extend_from_slice(&13u32.to_be_bytes());
    data.extend_from_slice(b"IHDR");
    data.extend_from_slice(&[0; 13]);
    data.extend_from_slice(&[0; 4]); // CRC
    // tEXt metadata chunk (length 10)
    data.extend_from_slice(&10u32.to_be_bytes());
    data.extend_from_slice(b"tEXt");
    data.extend_from_slice(b"Author=XYZ");
    data.extend_from_slice(&[0; 4]); // CRC
    // IDAT (length 4)
    data.extend_from_slice(&4u32.to_be_bytes());
    data.extend_from_slice(b"IDAT");
    data.extend_from_slice(&[0x78, 0x9C, 0x03, 0x00]);
    data.extend_from_slice(&[0; 4]); // CRC
    // IEND (length 0)
    data.extend_from_slice(&0u32.to_be_bytes());
    data.extend_from_slice(b"IEND");
    data.extend_from_slice(&[0; 4]); // CRC
    data
}

#[test]
fn test_metadata_sanitization() {
    let jpeg = create_dummy_jpeg();
    assert!(jpeg.windows(4).any(|w| w == b"Exif"));
    let sanitized_jpeg = sanitize_image_bytes(&jpeg, "test.jpg").expect("sanitize jpeg");
    assert!(!sanitized_jpeg.windows(4).any(|w| w == b"Exif"));
    assert_eq!(&sanitized_jpeg[0..2], &[0xFF, 0xD8]);
    assert_eq!(&sanitized_jpeg[sanitized_jpeg.len() - 2..], &[0xFF, 0xD9]);

    let png = create_dummy_png();
    assert!(png.windows(4).any(|w| w == b"tEXt"));
    let sanitized_png = sanitize_image_bytes(&png, "test.png").expect("sanitize png");
    assert!(!sanitized_png.windows(4).any(|w| w == b"tEXt"));
    assert!(sanitized_png.windows(4).any(|w| w == b"IDAT"));
    assert!(sanitized_png.windows(4).any(|w| w == b"IEND"));
}

#[test]
fn test_polyglot_jpeg_creation_and_extraction() {
    let dir = tempdir().unwrap();
    let img1_path = dir.path().join("photo1.jpg");
    let img2_path = dir.path().join("photo2.png");
    let disguised_archive_path = dir.path().join("Album.jpg");
    let extract_dir = dir.path().join("extracted");

    let jpeg_bytes = create_dummy_jpeg();
    let png_bytes = create_dummy_png();

    std::fs::write(&img1_path, &jpeg_bytes).unwrap();
    std::fs::write(&img2_path, &png_bytes).unwrap();

    let inputs = vec![
        ArchiveInputFile {
            path: img1_path.clone(),
            display_name: "sunset.jpg".into(),
            is_cover: true,
        },
        ArchiveInputFile {
            path: img2_path.clone(),
            display_name: "portrait.png".into(),
            is_cover: false,
        },
    ];

    let password = b"PolyglotSecret2026!";
    // Outer cover JPEG is unencrypted so standard galleries can render Album.jpg
    let outer_cover = create_dummy_jpeg();

    create_archive(
        &disguised_archive_path,
        Some(&outer_cover),
        &inputs,
        password,
        1,
        8 * 1024,
        None::<fn(f32, &str)>,
    )
    .expect("create_archive succeeded");

    assert!(disguised_archive_path.exists());

    // 1. Verify that Album.jpg can be read as a genuine unencrypted JPEG without any password
    let outer_cover_extracted =
        read_outer_cover_jpeg(&disguised_archive_path).expect("read_outer_cover_jpeg succeeded");
    assert!(outer_cover_extracted.is_some());
    let cover_data = outer_cover_extracted.unwrap();
    assert_eq!(&cover_data[0..2], &[0xFF, 0xD8], "Must begin with JPEG SOI");
    assert_eq!(&cover_data[cover_data.len() - 2..], &[0xFF, 0xD9], "Must end with JPEG EOI");
    assert!(!cover_data.windows(4).any(|w| w == b"Exif"), "Cover metadata must be stripped");

    // 2. Test unlocking Album.jpg with correct password via deterministic locator
    let unlocked = unlock_archive(&disguised_archive_path, password).expect("unlock succeeded");
    assert_eq!(unlocked.footer.flags, FLAG_POLYGLOT_JPEG);
    assert_eq!(unlocked.footer.zarc_offset, cover_data.len() as u64);
    assert_eq!(unlocked.manifest.objects.len(), 2);
    assert_eq!(unlocked.manifest.objects[0].original_name, "sunset.jpg");
    assert_eq!(unlocked.manifest.objects[1].original_name, "portrait.png");

    // 3. Test extracting individual photo in-memory
    let photo1_bytes = extract_object_bytes(&unlocked, "obj_0000").expect("extract photo1 bytes");
    let expected_sanitized_jpeg = sanitize_image_bytes(&jpeg_bytes, "sunset.jpg").unwrap();
    assert_eq!(photo1_bytes, expected_sanitized_jpeg);

    // 4. Test extracting all photos to directory
    let extracted_files = extract_all_to_dir(&unlocked, &extract_dir, None::<fn(f32, &str)>)
        .expect("extract_all succeeded");
    assert_eq!(extracted_files.len(), 2);
    assert!(extract_dir.join("sunset.jpg").exists());
    assert!(extract_dir.join("portrait.png").exists());

    // 5. Test unlocking with wrong password fails
    let wrong_result = unlock_archive(&disguised_archive_path, b"WrongPassword!");
    assert!(wrong_result.is_err());
    let err_msg = wrong_result.err().unwrap();
    assert!(err_msg.contains("Authentication failed"));
}

#[test]
fn test_standalone_and_polyglot_detection() {
    let dir = tempdir().unwrap();
    let img_path = dir.path().join("photo.jpg");
    let standalone_path = dir.path().join("standalone.zarc");
    let polyglot_path = dir.path().join("polyglot.jpg");

    let jpeg = create_dummy_jpeg();
    std::fs::write(&img_path, &jpeg).unwrap();

    let inputs = vec![ArchiveInputFile {
        path: img_path,
        display_name: "test.jpg".into(),
        is_cover: true,
    }];
    let pass = b"Pass123!";

    // Create standalone (.zarc)
    create_archive(&standalone_path, None, &inputs, pass, 1, 8 * 1024, None::<fn(f32, &str)>).unwrap();
    let unlocked_standalone = unlock_archive(&standalone_path, pass).unwrap();
    assert_eq!(unlocked_standalone.footer.zarc_offset, 0);
    assert_eq!(unlocked_standalone.footer.flags, FLAG_STANDALONE);
    assert_eq!(read_outer_cover_jpeg(&standalone_path).unwrap(), None);

    // Create polyglot (.jpg)
    create_archive(&polyglot_path, Some(&jpeg), &inputs, pass, 1, 8 * 1024, None::<fn(f32, &str)>).unwrap();
    let unlocked_polyglot = unlock_archive(&polyglot_path, pass).unwrap();
    assert!(unlocked_polyglot.footer.zarc_offset > 0);
    assert_eq!(unlocked_polyglot.footer.flags, FLAG_POLYGLOT_JPEG);
    assert!(read_outer_cover_jpeg(&polyglot_path).unwrap().is_some());
}

#[test]
fn test_archive_tamper_detection() {
    let dir = tempdir().unwrap();
    let img1_path = dir.path().join("photo1.jpg");
    let archive_path = dir.path().join("tamper_test.jpg");

    let jpeg_bytes = create_dummy_jpeg();
    std::fs::write(&img1_path, &jpeg_bytes).unwrap();

    let inputs = vec![ArchiveInputFile {
        path: img1_path,
        display_name: "test.jpg".into(),
        is_cover: true,
    }];

    let password = b"Pass1234!";
    create_archive(&archive_path, Some(&jpeg_bytes), &inputs, password, 1, 8 * 1024, None::<fn(f32, &str)>).unwrap();

    let mut data = std::fs::read(&archive_path).unwrap();

    // Tamper with footer magic
    let len = data.len();
    data[len - 6] = b'X';
    let tampered_footer = dir.path().join("tampered_footer.jpg");
    std::fs::write(&tampered_footer, &data).unwrap();
    assert!(unlock_archive(&tampered_footer, password).is_err());

    // Tamper with payload bytes
    let mut data2 = std::fs::read(&archive_path).unwrap();
    let mid = data2.len() / 2;
    data2[mid] ^= 0xFF;
    let tampered_payload = dir.path().join("tampered_payload.jpg");
    std::fs::write(&tampered_payload, &data2).unwrap();

    if let Ok(unlocked) = unlock_archive(&tampered_payload, password) {
        assert!(extract_object_bytes(&unlocked, "obj_0000").is_err());
    }
}
