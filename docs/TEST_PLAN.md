# Test plan

This document lists the test cases, their expected outcome, the command that
runs them, and the limitations of the environment.

The actual results are in `docs/VALIDATION_REPORT.md`.

## 1. Test layers

| Layer | Where | What it proves |
|-------|-------|----------------|
| Rust unit tests | `crates/zarc-core/src/**` (`#[cfg(test)]` modules) | the primitives behave as documented |
| Rust integration tests | `crates/zarc-core/tests/*.rs` | the container format, the key hierarchy and the vault behave as specified against the *real* libsodium |
| Cross-implementation interop | `crates/zarc-core/tests/interop.rs` | the FFI layer speaks the documented libsodium wire format |
| Property based tests | `crates/zarc-core/tests/property.rs` | the parser and the vault hold their invariants for arbitrary input |
| Fuzzing | `fuzz/fuzz_targets/*.rs` | the parser, the decryptor and the key envelope do not panic and do not accept invalid data |
| Android instrumented tests | `android/app/src/androidTest/` | the *real* Android Keystore, the *real* storage and the *real* native library work on a device |
| On-device self test | `crates/zarc-core/src/selftest.rs`, triggered from the Security Lab | the same checks run inside the app on the device |

## 2. Round trip tests (section A of the brief)

File: `crates/zarc-core/tests/roundtrip.rs`

| # | Case | Expected outcome |
|---|------|------------------|
| A1 | Empty file | Round trips. The object is `152 + 17` bytes: a header and one final (empty) message |
| A2 | One byte file | Round trips byte for byte |
| A3 | Chunk size - 1, chunk size, chunk size + 1, 2x, 2x+1, 4x | Round trip; the object size is `152 + plaintext + 17 * chunk_count` |
| A4 | Multiple chunks (5 chunks + 17 bytes) | Round trips |
| A5 | Multi-megabyte input (3 MiB + 12345 bytes) | Round trips |
| A6 | Arbitrary binary data (all 256 byte values) | Round trips |
| A7 | Synthetic JPEG, PNG, WebP and MP4 fixtures | Round trip; the stored object contains neither the magic bytes nor the plaintext |
| A8 | The stored file name | 32 lowercase hex characters, no extension, equal to the object id |
| A9 | The original file after encryption | Unchanged and still present |
| A10 | The same input encrypted twice | Two different object ids and two different ciphertexts |
| A11 | Decryption with a different VMK | Fails with an authentication error; no output file |

The fixtures are **synthetic**: they carry the real magic bytes of the format
they imitate, but they are not photographs or videos. No personal media is
used anywhere in the test suite.

## 3. Ciphertext mutation tests (section B of the brief)

File: `crates/zarc-core/tests/mutation.rs`

| # | Mutation | Expected outcome |
|---|----------|------------------|
| B1 | Magic byte flipped | `Format` error |
| B2 | Format version set to 2 | `UnsupportedVersion` error |
| B3 | Header length field corrupted | `Format` error |
| B4 | Flags set to a non-zero value | `Format` error |
| B5 | Each of the three reserved fields set to non-zero | `Format` error |
| B6 | Unknown envelope algorithm id | `UnsupportedAlgorithm` error |
| B7 | Unknown stream algorithm id | `UnsupportedAlgorithm` error |
| B8 | Object id in the header modified | Authentication failure (the id is in the envelope associated data) |
| B9 | The file stored under the wrong object id | `Format` error (the file name must match the header) |
| B10 | Secretstream header modified | Authentication failure |
| B11 | Envelope nonce modified | Authentication failure |
| B12 | Plaintext length modified | Length mismatch (the parser cross-checks the lengths) |
| B13 | Ciphertext length modified | Length mismatch |
| B14 | Plaintext length set to `u64::MAX` | Format or length mismatch (overflow / sanity bound) |
| B15 | Chunk size set to 0 | Format error |
| B16 | Wrapped data key modified | Authentication failure |
| B17 | Wrapped data key tag modified | Authentication failure |
| B18 | A ciphertext byte flipped | Authentication failure |
| B19 | The final authentication tag flipped | Authentication failure |
| B20 | Truncation at 9 different offsets (0, 1, 151, 152, 153, header+1024, last byte, last 17 bytes, last 18 bytes) | Format, authentication or length mismatch error |
| B21 | Extra bytes appended | Format error (trailing data) |
| B22 | A chunk removed | Rejected (the stream no longer authenticates) |
| B23 | A chunk duplicated | Rejected |
| B24 | Two chunks swapped (the length stays valid) | Authentication failure - the rejection comes from the secretstream itself |
| B25 | The secretstream used directly: reordered, duplicated, removed and truncated messages | Each is rejected; the complete stream still decrypts |
| B26 | The secretstream used directly with a wrong key | Authentication failure |
| B27 | The original object after all the mutations | Still decrypts |
| B28 | The vault directory after the mutation suite | Contains no leftovers |

**In every case the test also asserts that no plaintext file was published
and that no staging file was left behind.** The tests do not assume that
every mutation has to fail at the same point: a modified length is caught by
the parser, a modified ciphertext by the AEAD.

## 4. Key lifecycle tests (section C of the brief)

File: `crates/zarc-core/tests/keylifecycle.rs` and
`android/app/src/androidTest/java/com/zarc/vault/ZarcInstrumentedTest.kt`

| # | Case | Expected outcome |
|---|------|------------------|
| C1 | Key generation | 32 bytes, not all zero, two generations differ |
| C2 | A VMK of the wrong length | Rejected |
| C3 | Two objects | Independent data keys, independent envelope nonces |
| C4 | The same input encrypted twice | Different ciphertext |
| C5 | A new `VaultRepository` after a "restart" | The same VMK, the object still decrypts (device test) |
| C6 | A corrupted wrapped VMK | `KeystoreFailure`, the operation fails (device test) |
| C7 | A missing Keystore alias with a wrapped VMK present | The vault reports itself as unrecoverable and fails closed (device test) |
| C8 | The Keystore key and the wrapped VMK deleted | The vault starts over; the old objects can no longer be decrypted (device test) |
| C9 | A wrong VMK, a wrong DEK, a modified envelope, a modified nonce, a modified tag, wrong associated data | Every case is an authentication failure |
| C10 | A missing object | `NotFound` error |
| C11 | The unwrapped VMK in every file of the app's data directory | Never found |
| C12 | The Rust core | Contains no `println!`, `eprintln!` or `dbg!` (a source level check) |
| C13 | The container header | Contains no file name, no extension, no MIME type, no payload |

The "official known answer tests" of the cryptographic library could not be
run in this environment: libsodium's own test suite needs `cmocka` and the
autoconf build system, neither of which is available here. Instead, the
interop test in `tests/interop.rs` checks the implementation against an
*independent* pure Rust implementation of the same construction
(`crypto_secretstream` from the RustCrypto `nacl-compat` project), in both
directions. See `docs/VALIDATION_REPORT.md` for the exact status.

## 4b. Recovery envelope and key rotation

File: `crates/zarc-core/tests/recovery_rotation.rs` and
`android/app/src/androidTest/java/com/zarc/vault/RecoveryInstrumentedTest.kt`

| # | Case | Expected outcome |
|---|------|------------------|
| R1 | The envelope round trip | The recovered key is the vault master key |
| R2 | A wrong passphrase | An authentication failure; the vault is untouched |
| R3 | A passphrase that is too short | Rejected before any key derivation |
| R4 | A tampered envelope | Rejected |
| R5 | A tampered witness | The recovered key is rejected |
| R6 | The witness accepts only the right key | A different key is rejected |
| R7 | The master key rotation | Every object is re-wrapped; the **content region stays byte for byte identical**; the old key no longer opens the objects |
| R8 | The rotation is idempotent | A second run re-wraps nothing |
| R9 | A rotation with the wrong old key | It fails and no object changes |
| R10 | The object re-encryption | The plaintext is unchanged, the content changes, the identity is kept |
| R11 | A failed re-encryption | The object is untouched |
| R12 | The vault directory after a rotation | Only the object files, no leftovers |
| R13 | The envelope and the witness on the device | They contain neither the master key nor the passphrase |
| R14 | The Keystore key is destroyed | The vault opens with the passphrase, and the Keystore binding can be restored afterwards |
| R15 | The Keystore key is destroyed without a recovery envelope | The vault reports that only the passphrase can open it; it does not silently start over |

## 5. Crash and transaction tests (section D of the brief)

File: `crates/zarc-core/tests/transactional.rs`

| # | Case | Expected outcome |
|---|------|------------------|
| D1 | Encryption of a directory / a missing file | Fails; the vault directory stays empty |
| D2 | After a successful encryption | Exactly one file, named after the object, with the expected size |
| D3 | A leftover `.zarc-tmp-*` file (simulated process kill) | Not listed as an object; removable with `purge_stale_temporaries()`; the vault still works |
| D4 | The **last** chunk of an object corrupted | Authentication failure; no destination file, no staging file |
| D5 | A failed decryption with an existing destination file | The existing file is not overwritten |
| D6 | An unwritable destination (a file where a directory is expected) | The operation fails; the file is untouched |
| D7 | An unwritable vault directory | `Vault::new` fails |
| D8 | The source grows after the encryption | The stored object still describes the original content |
| D9 | Decrypting the same object twice | Two independent files with identical content |
| D10 | A destination name containing `../`, a path separator, `.` or `..` | Rejected with `InvalidArgument` |
| D11 | The vault directory contents | Only 32 character hex object names |

**Low storage conditions** could not be simulated reliably: the platform
does not offer a portable way to fill the device's storage from a test. The
related code path (a write that fails) is covered by D6, and the atomic
rename means a partially written file is never published.

**Process termination** is simulated by creating the files that a killed
process would leave behind (D3) rather than by killing the process itself,
because the test harness has to stay alive to make the assertion.

## 6. Property based and fuzzing tests (section E of the brief)

File: `crates/zarc-core/tests/property.rs`, `fuzz/fuzz_targets/`

| # | Property | Expected outcome |
|---|----------|------------------|
| E1 | The parser never panics, for any byte string | No panic |
| E2 | A valid header round trips through the parser | The re-encoded header is identical |
| E3 | Arbitrary input survives a full vault round trip | The restored bytes are identical |
| E4 | A single byte mutation of a valid object | Either rejected, or the plaintext is *identical* - never different |
| E5 | The chunk plan is consistent | The chunk lengths sum to the plaintext length; the ciphertext length matches |
| E6 | Inconsistent lengths are rejected | The parser refuses the object |
| E7 | The header never contains descriptive data | The magic and the length are the only fixed content |
| E8 | Fuzz: the container parser | No panic, no inconsistent header |
| E9 | Fuzz: the secretstream decryptor | No panic; accepted data is exactly the pushed data |
| E10 | Fuzz: the key envelope | A tampered envelope is never unwrapped |

The fuzz targets never use real photographs and never use a production key:
they use fixed test keys.

## 7. Android integration tests (section F of the brief)

File: `android/app/src/androidTest/java/com/zarc/vault/ZarcInstrumentedTest.kt`

| # | Case | Expected outcome |
|---|------|------------------|
| F1 | The Keystore key is created | The platform reports a protection level; without `KeyInfo` the level is `UNAVAILABLE` and no hardware backing is claimed |
| F2 | The VMK survives a process restart | The same VMK is unwrapped and the object decrypts |
| F3 | A corrupted wrapped VMK | `KeystoreFailure` |
| F4 | A missing Keystore alias | Fails closed |
| F5 | Losing the Keystore key | The vault is unrecoverable; the old objects cannot be decrypted |
| F6 | Encrypt and decrypt on the device | The SHA-256 digests are identical |
| F7 | The stored objects | Opaque names, no extension, inside the app private directory |
| F8 | The plaintext in the vault | Never found |
| F9 | The unwrapped VMK on disk | Never found in `filesDir`, `noBackupFilesDir` or `cacheDir` |
| F10 | The backup configuration | The vault and the wrapped key are inside `noBackupFilesDir` |
| F11 | Two encryptions of the same input | Different objects and different ciphertext |
| F12 | A wrong key | Rejected with a `ZarcException`; no output file |
| F13 | The crypto self test on the device | `RESULT: PASS` and `libsodium 1.0.22` |

**StrongBox** could not be exercised: the test device is an emulator without
a StrongBox. The code requests StrongBox when the platform advertises it and
falls back explicitly, and the report distinguishes the two cases. This is
recorded as *not run* in the validation report.

**A real, hardware backed device** was not available in this environment. The
protection level that is reported comes from the platform; on the emulator it
is `UNAVAILABLE`, which is the honest answer for that platform.

## 8. Storage leakage audit (section G of the brief)

See `docs/VALIDATION_REPORT.md` for the executed audit. The audit:

1. encrypts a fixture with a unique marker,
2. searches the vault directory, the app's data directories, the cache and the
   logs for the marker, for the original file name, for the media magic bytes
   and for the unwrapped VMK,
3. inspects the merged manifest for the backup configuration,
4. repeats the inspection with elevated privileges (an emulator shell, i.e.
   `adb shell` as the shell user) where that is possible.

The audit is a finite search over finite locations. It is **not** a proof
that no plaintext exists anywhere on the device.

## 9. How to run everything

```powershell
# Rust: format, lint, unit and integration tests
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace

# Fuzzing (needs the nightly toolchain and cargo-fuzz)
rustup toolchain install nightly --profile minimal
cargo +nightly install cargo-fuzz --locked
cd fuzz
$env:PATH = "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Tools\MSVC\14.50.35717\bin\HostX64\x64;" + $env:PATH
cargo +nightly fuzz run parse_container -- -max_total_time=120
cargo +nightly fuzz run decrypt_stream -- -max_total_time=120
cargo +nightly fuzz run wrap_key -- -max_total_time=120

# Dependency and license audit
python tools/audit-deps.py

# Android: native library, APK, on-device tests
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build-android.ps1
cd android
gradlew.bat assembleDebug
gradlew.bat connectedDebugAndroidTest
```
