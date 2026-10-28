# FORMAT SPECIFICATION — ZARC v1 Container & Polyglot JPEG Architecture

## 1. Executive Summary

PROJECT ZARC specifies a high-security, privacy-preserving photo archive format. In addition to standalone `.zarc` archives, the format supports a **Polyglot JPEG + Custom Container** architecture (`Album.jpg`).

Under this architecture:
1. **Outer Appearance**: The container file is physically named `Album.jpg`. Any stock photo viewer, Android gallery, Google Photos backup, or desktop viewer decodes and displays the metadata-sanitised cover photo without error or warning.
2. **Inner Security**: Immediately following the JPEG End-Of-Image (`0xFF, 0xD9`) marker, the file contains an encrypted, authenticated binary container (`ZARC` specification) protecting embedded photos, private manifests, filenames, capture timestamps, and dimensions.
3. **No Blind Scanning**: The locator mechanism is $O(1)$ deterministic from the file footer. It does not scan for magic bytes across compressed entropy.
4. **Zero-Knowledge**: All private assets, filenames, and manifest structures are encrypted with **Argon2id** key derivation and **XChaCha20-Poly1305** authenticated streaming AEAD.
5. **Metadata Sanitization**: Embedded images and outer cover images are stripped of all EXIF, GPS coordinates, camera serial numbers, IPTC, and XMP metadata before archive construction.

---

## 2. Physical File Layout

### Polyglot JPEG Architecture (`Album.jpg`)

```text
+-------------------------------------------------------------------------+
|                  GENUINE SANITISED JPEG COVER (0 .. zarc_offset)         |
|  0x00: SOI (0xFF, 0xD8)                                                 |
|  SOF0 / DQT / DHT / SOS image data ... (EXIF/GPS/APPn stripped)          |
|  zarc_offset - 2: EOI (0xFF, 0xD9)                                      |
+=========================================================================+
|                  ZARC CONTAINER HEADER (112 Bytes)                      |
|  zarc_offset + 0x00:  Magic "ZARC" (4B, [0x5A, 0x41, 0x52, 0x43])        |
|  zarc_offset + 0x04:  Format Version (2B, uint16_le = 1)                 |
|  zarc_offset + 0x06:  Container Flags (2B, FLAG_POLYGLOT_JPEG = 1)      |
|  zarc_offset + 0x08:  Container ID (16B, random UUIDv4 bytes)            |
|  zarc_offset + 0x18:  KDF Identifier (2B, uint16_le = 1 [Argon2id])      |
|  zarc_offset + 0x1A:  Argon2id Iterations (4B, uint32_le = 3)            |
|  zarc_offset + 0x1E:  Argon2id Memory KiB (4B, uint32_le = 65536)        |
|  zarc_offset + 0x22:  Argon2id Salt (16B, cryptographically random)     |
|  zarc_offset + 0x32:  Wrapped DEK Nonce (12B)                            |
|  zarc_offset + 0x3E:  Wrapped DEK + Auth Tag (32B DEK + 16B Poly1305)   |
+-------------------------------------------------------------------------+
|                  ENCRYPTED CONTENT CHUNKS STREAM                        |
|  Photo 1 (64 KiB chunks + 16B Poly1305 MAC tag per chunk)                |
|  Photo 2 (64 KiB chunks + 16B Poly1305 MAC tag per chunk)                |
|  ...                                                                    |
|  Cover Photo (64 KiB chunks + 16B Poly1305 MAC tag per chunk)           |
+-------------------------------------------------------------------------+
|                  ENCRYPTED MANIFEST                                     |
|  Nonce (12B) | Ciphertext (UTF-8 JSON) | Auth Tag (16B Poly1305)        |
+-------------------------------------------------------------------------+
|                  CONTAINER FOOTER (Fixed 32 Bytes at EOF - 32)          |
|  EOF - 32:  Manifest Absolute File Offset (8B, uint64_le)               |
|  EOF - 24:  Manifest Ciphertext Length (8B, uint64_le)                   |
|  EOF - 16:  ZARC Container Start Offset (8B, uint64_le = zarc_offset)   |
|  EOF - 08:  Footer Magic "CRAZ" (4B, [0x43, 0x52, 0x41, 0x5A])           |
|  EOF - 04:  Version (2B, uint16_le = 1)                                  |
|  EOF - 02:  Flags (2B, FLAG_STANDALONE = 0, FLAG_POLYGLOT_JPEG = 1)     |
+-------------------------------------------------------------------------+
```

### Standalone ZARC Layout (`.zarc`)
In standalone mode (`FLAG_STANDALONE = 0`), `zarc_offset` is `0`. The container begins directly at byte `0` with the 112-byte header.

---

## 3. Deterministic Locator Specification

Standard steganography or naive appending tools search through file streams looking for magic numbers. In high-entropy encrypted payloads or compressed JPEG streams, coincidence bytes can cause false-positive matches, resource exhaustion, or parsing bugs.

ZARC implements **deterministic $O(1)$ boundary locator validation**:

1. **Footer Read**: Seek to `file_len - 32`. Read 32 bytes.
2. **Footer Magic Check**: Verify bytes `[24..28]` match `b"CRAZ"`.
3. **Boundary Invariants**:
   - `manifest_offset + manifest_len + 32 == file_len`
   - `zarc_offset + 112 <= manifest_offset`
4. **Mode Verification**:
   - If `flags & FLAG_POLYGLOT_JPEG != 0`:
     - Assert `zarc_offset >= 4` (must have room for SOI and EOI).
     - Assert bytes `[0..2]` are `[0xFF, 0xD8]` (valid JPEG SOI).
     - Assert bytes `[zarc_offset - 2 .. zarc_offset]` are `[0xFF, 0xD9]` (valid JPEG EOI).
   - If `flags == FLAG_STANDALONE`:
     - Assert `zarc_offset == 0`.
5. **Container Magic Check**: Seek to `zarc_offset` and verify `[0..4]` is `b"ZARC"`.

Any failure in these checks immediately rejects the file as invalid or tampered without reading inner chunks.

---

## 4. Cryptographic Key Hierarchy

All cryptographic operations are implemented in native Rust (`crates/zarc-core/src/archive/`):

1. **Key Encryption Key (KEK)**:
   $$\text{KEK} = \text{Argon2id}(\text{passphrase}, \text{salt}, \text{time\_cost}=3, \text{memory}=64\,\text{MiB}, \text{lanes}=4)$$
2. **Data Encryption Key (DEK)**:
   - Fresh 256-bit key from system CSPRNG (`getrandom`).
   - Wrapped under KEK using XChaCha20-Poly1305 with Associated Authenticated Data (AAD):
     $$\text{AAD} = \text{Header}[0..48]$$
3. **Domain-Separated Subkeys (HKDF-SHA256)**:
   - **Manifest Key**: $\text{HKDF}(\text{DEK}, \text{info} = \text{"zarc.archive.v1.manifest"} \parallel \text{ContainerID})$
   - **Object Key ($O_i$)**: $\text{HKDF}(\text{DEK}, \text{info} = \text{"zarc.archive.v1.object"} \parallel \text{ContainerID} \parallel \text{ObjectID})$
4. **Chunked Streaming Encryption**:
   - Files are encrypted in bounded 64 KiB chunks using `crypto_secretstream_xchacha20poly1305`.
   - Each chunk has an independent 16-byte Poly1305 MAC tag. The last chunk bears `TAG_FINAL`.
   - Reordering, dropping, or truncating chunks fails MAC verification instantly.

---

## 5. Metadata Sanitization

Before writing any image to the outer cover or inner encrypted payload:
- **JPEG**: Parser traverses marker segments. All `APP1` (EXIF/XMP), `APP2` (ICC/FlashPix), `APP13` (Photoshop/IPTC), and `COM` (comments) markers are stripped. Fundamental encoding tables (`SOF0`, `DQT`, `DHT`, `SOS`) are preserved intact.
- **PNG**: Ancillary chunks (`eXIf`, `tEXt`, `zTXt`, `iTXt`, `tIME`) are stripped. Critical chunks (`IHDR`, `PLTE`, `IDAT`, `IEND`) are preserved.
- **WebP**: `EXIF` and `XMP` RIFF sub-chunks are stripped.

---

## 6. Manifest Schema (JSON)

The manifest is an authenticated UTF-8 JSON object encrypted under the Manifest Key:

```json
{
  "version": 1,
  "created_at": 1730100000000,
  "archive_name": "Family_Vacation_2026",
  "cover_object_id": "9f8a2b3c-...",
  "entries": [
    {
      "id": "9f8a2b3c-...",
      "name": "beach_sunset.jpg",
      "mime": "image/jpeg",
      "sanitized_size": 3450210,
      "offset": 1048576,
      "encrypted_size": 3451080,
      "chunk_size": 65536,
      "chunk_count": 53,
      "is_cover": true
    }
  ]
}
```

---

## 7. Decoder Compatibility Verification

The JPEG specification (ISO/IEC 10918-1) designates marker `0xFF, 0xD9` (EOI) as the end of image stream. Standard compliant decoders parse until EOI and disregard trailing bytes.

| Target Platform / Application | Image Decoding Engine | Compatibility Status | Notes |
|---|---|---|---|
| **Android Stock Gallery** | Android Skia / libjpeg-turbo | **Full Support** | Decodes cover photo instantly; renders thumbnail & full screen |
| **Google Photos (Android)** | Google Skia ImageDecoder | **Full Support** | Backs up `Album.jpg` as standard photo; displays cover |
| **Android Files (AOSP/Pixel)** | Android `BitmapFactory` | **Full Support** | Generates system thumbnail from cover photo |
| **Windows Photos / Paint** | Windows Imaging Component (WIC) | **Full Support** | Opens cover cleanly without corruption alerts |
| **macOS Preview / Finder** | Apple ImageIO framework | **Full Support** | Renders QuickLook preview and full view of cover |
| **Web Browsers (Chrome / Firefox)** | Blink / Gecko JPEG Decoders | **Full Support** | Renders outer cover via `<img>` tag seamlessly |
| **Zarc Native Extractor** | Rust `zarc-core` | **Full Support** | Reads cover without password; decrypts payload upon password authentication |
