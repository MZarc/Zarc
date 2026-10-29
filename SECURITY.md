# Security Policy

ZARC is an open-source, zero-knowledge offline media vault designed to protect user privacy against unauthorized access, device theft, forensic inspection, and data leakage. We take the security of our cryptographic core and architecture seriously.

---

## Supported Versions

Security updates and vulnerability patches are actively maintained for the current release stream:

| Version | Supported | Status |
| :--- | :--- | :--- |
| **1.0.x** | :white_check_mark: | **Current Production Release** |
| < 1.0.0 | :x: | Deprecated / Development builds |

---

## Reporting a Vulnerability

If you discover a security vulnerability, weakness in the cryptographic implementation, container format bypass, or memory leakage, **please report it responsibly and privately**. Do not disclose vulnerabilities in public GitHub issues, discussions, or social channels until they have been reviewed and addressed.

### Disclosure Process:

1. **Email Contact**: Send all reports privately to **[meetzarc@gmail.com](mailto:meetzarc@gmail.com)** with the subject line `[SECURITY] ZARC Vulnerability Report`.
2. **Include in Your Report**:
   - A clear description of the issue and its potential security impact.
   - Exact steps, scripts, or reproducible test cases demonstrating the vulnerability.
   - The specific version (`v1.0.0`), commit hash, and Android device/OS version tested.
   - Any proposed remediation or patches, if available.
3. **Response Timeline**:
   - **Initial Acknowledgement**: Within **48 hours** of receipt.
   - **Assessment & Validation**: Within **5 business days**, detailing validity and severity rating.
   - **Patch & Advisory**: Coordinated public release of an update and security advisory once resolved.

---

## Security Model & Protections

### What ZARC Actively Protects Against:
- **Device Theft & Offline Forensic Extraction**: All media is stored as 100% ciphertext using authenticated **XChaCha20-Poly1305** streaming AEAD with per-object 256-bit keys. Extracting data via physical access, USB debugging (ADB), or chip-off dumping yields only encrypted UUID blobs.
- **Metadata & Catalog Exposure**: Filenames, capture dates, dimensions, video duration, and thumbnails are sealed inside an encrypted catalog (`index.bin`). No plaintext metadata exists on disk.
- **Hardware Isolation**: Vault Master Keys are enveloped using hardware-backed Android Keystore keys rooted in the device's **Trusted Execution Environment (TEE)** or **StrongBox Keymaster**, preventing key export even if filesystem read access is obtained.
- **Data Tampering & Truncation**: Poly1305 message authentication tags are verified per 64 KiB chunk. Any bit-flip, truncated block, or reordered stream causes an immediate authentication failure (fail-closed).
- **Steganographic Disguise Security**: Disguised polyglot archives use **Argon2id** (memory-hard KDF) with byte-level metadata stripping (EXIF, GPS, camera serials removed in native Rust) before authenticated packaging.
- **Network Exfiltration**: ZARC strictly omits `android.permission.INTERNET`. The application has zero network sockets and is incapable of transmitting data off-device.

### What is Outside the Threat Model:
- **Compromised Operating System / Root Exploits**: A device compromised by rootkit malware or kernel exploits can monitor system RAM or hook application processes during active viewing.
- **Malware Running Inside Active Memory**: Malware with root or debugger privileges that attaches to the ZARC process while unlocked.
- **Operating System Interceptions**: Plaintext screenshots or screen recordings captured by compromised OS software while an image is being displayed on screen.
- **External Copies**: Media that was left unencrypted in public camera rolls (`DCIM/`), cloud backups, or messaging cache directories prior to import into Zarc.

---

## Cryptographic Parameters & Primitives

All cryptographic primitives are implemented using audited, vendored **libsodium 1.0.22** and hardware-backed Android Keystore:

| Layer | Algorithm / Primitive | Purpose |
| :--- | :--- | :--- |
| **Object Cipher** | `crypto_secretstream_xchacha20poly1305` | Streaming AEAD with 64 KiB chunks & incremental Poly1305 MACs |
| **Key Wrapping** | `crypto_aead_xchacha20poly1305_ietf` | Authenticated envelope encryption for DEKs and index |
| **Hardware Keymaster** | AES-256-GCM | Master Keystore key bound to device hardware (TEE / StrongBox) |
| **Archive KDF** | **Argon2id** | Passphrase key derivation (64 MiB memory, 3 passes, 4 lanes) |
| **Subkey Derivation** | BLAKE2b Subkey KDF | Domain-separated derivation (`zarc.private.v1`) for Private Vault |
| **Memory Sanitization** | Rust `zeroize` & JVM Sweeps | Explicit zeroization of keys and plaintext buffers upon lock |

For detailed design rationale, see [`docs/CRYPTOGRAPHY_DECISION.md`](docs/CRYPTOGRAPHY_DECISION.md) and [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md).
